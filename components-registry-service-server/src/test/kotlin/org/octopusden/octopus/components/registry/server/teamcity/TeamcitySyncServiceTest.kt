package org.octopusden.octopus.components.registry.server.teamcity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentTeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.event.AuditEvent
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentTeamcityProjectRepository
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Example
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.repository.query.FluentQuery
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import java.util.UUID
import java.util.function.Function

/**
 * Unit tests for [TeamcitySyncService] against the v2 multi-row schema.
 *
 * All dependencies are hand-rolled in-memory stubs — no Spring context, no
 * Mockito, no DB. [TcProjectFetcher] is a SAM interface so a single lambda is
 * enough for most stubs. [StubTcProjectRepository] owns the per-component TC
 * row state, replacing the old `teamcityProjectId`/`teamcityProjectUrl` scalar
 * columns that lived on `ComponentEntity` in schema v1–v3.
 */
class TeamcitySyncServiceTest {
    private val alice = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val bob   = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val carol = UUID.fromString("33333333-3333-3333-3333-333333333333")

    /**
     * v2: `ComponentEntity` uses `componentKey` instead of `name`; the old
     * `teamcityProjectId` / `teamcityProjectUrl` scalar fields are gone —
     * TC association is kept in [StubTcProjectRepository].
     */
    private fun component(id: UUID, key: String) = ComponentEntity(id = id, componentKey = key)

    private fun stubFetcher(matchesByKey: Map<String, List<TcProject>>): TcProjectFetcher =
        TcProjectFetcher { componentsByName ->
            componentsByName.entries
                .mapNotNull { (key, uuid) ->
                    val projects = matchesByKey[key] ?: return@mapNotNull null
                    uuid to projects
                }
                .toMap()
        }

    private fun service(
        repo: ComponentRepository,
        tcRepo: StubTcProjectRepository,
        fetcher: TcProjectFetcher,
        publisher: ApplicationEventPublisher,
        user: CurrentUserResolver,
        properties: TeamcityProperties = configuredProperties(),
    ) = TeamcitySyncService(repo, tcRepo, fetcher, publisher, user, inlineTx(), properties)

    private fun configuredProperties() = TeamcityProperties(baseUrl = "https://teamcity.example.com")

    // ---------- Tests --------------------------------------------------------

    @Test
    @DisplayName("SYS-051 happy path: writes TC row; counts updated; NO audit event")
    fun happyPath() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject(
                        "Alpha_Build",
                        "https://teamcity.example.com/project/Alpha_Build",
                        hasCdReleaseBuild = false,
                    ),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(0, result.skippedNoMatch)
        assertEquals(0, result.skippedAmbiguous)
        assertTrue(result.errors.isEmpty())

        // v2: TC project written to child table, not to entity scalar fields.
        assertEquals("Alpha_Build", tcRepo.findByComponentId(alice).single().projectId)

        // SYS-051: TeamCity sync is an automated reconciliation (changedBy=system) and
        // must NOT write audit rows — that noise was cluttering the component history.
        assertTrue(
            publisher.events.filterIsInstance<AuditEvent>().isEmpty(),
            "TeamCity sync must not publish an AuditEvent even when it writes/updates the TC row (SYS-051)",
        )
    }

    @Test
    @DisplayName("unchanged: existing TC row already matches — no audit event, no save, count unchanged")
    fun unchangedNoAudit() {
        val alphaComponent = component(alice, "alpha")
        val components = listOf(alphaComponent)
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject(
                        "Alpha_Build",
                        "https://teamcity.example.com/project/Alpha_Build",
                        hasCdReleaseBuild = false,
                    ),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        // Pre-seed: alpha already has the correct TC row in the child table.
        tcRepo.preload(
            ComponentTeamcityProjectEntity(component = alphaComponent, projectId = "Alpha_Build", sortOrder = 0),
        )
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(1, result.unchanged)
        assertTrue(publisher.events.isEmpty(), "no audit events when projectId unchanged")
        assertTrue(tcRepo.saveCalls.isEmpty(), "no save when projectId unchanged")
    }

    @Test
    @DisplayName("ambiguous + nobody has CDRelease build → skippedAmbiguous, no write")
    fun ambiguousNoCdReleaseSkipped() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Project_A", "https://teamcity.example.com/project/A", hasCdReleaseBuild = false),
                    TcProject("Project_B", "https://teamcity.example.com/project/B", hasCdReleaseBuild = false),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(1, result.skippedAmbiguous)
        assertEquals(0, result.ambiguousAutoResolved)
        assertTrue(tcRepo.findByComponentId(alice).isEmpty(), "ambiguous + no CDRelease → no TC row written")
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    @DisplayName("ambiguous + exactly one has CDRelease → auto-pick that one, count ambiguousAutoResolved")
    fun ambiguousSingleCdReleaseAutoResolved() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Project_A_NoRelease", "https://tc/a", hasCdReleaseBuild = false),
                    TcProject("Project_B_Release", "https://tc/b", hasCdReleaseBuild = true),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(1, result.ambiguousAutoResolved)
        // v2: TC row stores only the projectId; URL is derived at read-time via composeTeamcityProjectUrl.
        assertEquals("Project_B_Release", tcRepo.findByComponentId(alice).single().projectId)
        assertTrue(
            publisher.events.filterIsInstance<AuditEvent>().isEmpty(),
            "TeamCity sync must not publish an AuditEvent (SYS-051)",
        )
    }

    @Test
    @DisplayName("ambiguous + multiple CDRelease candidates → pick lexicographically smallest by id")
    fun ambiguousMultipleCdReleasePickFirstById() {
        val components = listOf(component(alice, "alpha"))
        // "Project_A_Release" < "Project_B_Release"; list order is inverted to prove
        // the tie-break is by id, not by input order.
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Project_B_Release", "https://tc/b", hasCdReleaseBuild = true),
                    TcProject("Project_A_Release", "https://tc/a", hasCdReleaseBuild = true),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(1, result.ambiguousAutoResolved)
        assertEquals("Project_A_Release", tcRepo.findByComponentId(alice).single().projectId)
    }

    @Test
    @DisplayName("ambiguous tie-break to CDRelease project with blank webUrl → no-match, ambiguousAutoResolved stays 0")
    fun ambiguousTieBreakBlankUrlIsNoMatch() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Project_NoUrl", "", hasCdReleaseBuild = true),
                    TcProject("Project_Other", "https://tc/other", hasCdReleaseBuild = false),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        // Tie-break selected the CDRelease project but its webUrl is blank → counted as no-match.
        // ambiguousAutoResolved is a sub-counter of updated+unchanged per the result KDoc, so it
        // must NOT increment for rows that fall through to skippedNoMatch.
        assertEquals(1, result.skippedNoMatch)
        assertEquals(0, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(0, result.ambiguousAutoResolved)
        assertTrue(tcRepo.findByComponentId(alice).isEmpty(), "no TC row written when webUrl unusable")
    }

    @Test
    @DisplayName("no match: 0 candidates → skippedNoMatch, no write")
    fun noMatchSkipped() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(emptyMap())
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(1, result.skippedNoMatch)
        assertTrue(tcRepo.findByComponentId(alice).isEmpty(), "no TC row for no-match component")
    }

    @Test
    @DisplayName("blank webUrl: treated as no-match")
    fun blankWebUrlIsNoMatch() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf("alpha" to listOf(TcProject("Project_A", "", hasCdReleaseBuild = false))),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.skippedNoMatch)
        assertEquals(0, result.updated)
        assertTrue(tcRepo.findByComponentId(alice).isEmpty())
    }

    @Test
    @DisplayName("null-id component: counted in scanned but silently skipped, no error counter")
    fun nullIdComponentSkipped() {
        // null-id components are filtered from the name→id map and skipped in the loop via `continue`.
        val nullIdComponent = ComponentEntity(id = null, componentKey = "no-id")
        val components = listOf(component(alice, "alpha"), nullIdComponent)
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject(
                        "Alpha_Build",
                        "https://teamcity.example.com/project/Alpha_Build",
                        hasCdReleaseBuild = false,
                    ),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(2, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(0, result.skippedNoMatch)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    @DisplayName("blank base-url: resync throws IllegalStateException instead of returning all-NO_MATCH")
    fun blankBaseUrlThrows() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = TcProjectFetcher { _ -> emptyMap() }
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"), TeamcityProperties())

        val ex = assertThrows<IllegalStateException> { svc.resync() }
        assertTrue(ex.message!!.contains("teamcity.base-url"), "message should mention the config property")
    }

    @Test
    @DisplayName("blank base-url: throws even when registry is empty (no silent scanned=0 on misconfiguration)")
    fun blankBaseUrlThrowsOnEmptyRegistry() {
        val fetcher = TcProjectFetcher { _ -> emptyMap() }
        val repo = StubComponentRepository(emptyList())
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"), TeamcityProperties())

        val ex = assertThrows<IllegalStateException> { svc.resync() }
        assertTrue(ex.message!!.contains("teamcity.base-url"), "message should mention the config property")
    }

    @Test
    @DisplayName("TC client failure on fetch: exception propagates out of resync")
    fun clientFailurePropagates() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = TcProjectFetcher { _ -> throw RuntimeException("TC unavailable") }
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("admin"))

        val ex = assertThrows<RuntimeException> { svc.resync() }
        assertEquals("TC unavailable", ex.message)
    }

    @Test
    @DisplayName("mixed batch: counts every category in one pass")
    fun mixedBatch() {
        val alphaComponent = component(alice, "alpha")
        val betaComponent  = component(bob, "beta")
        val gammaComponent = component(carol, "gamma")
        val components = listOf(alphaComponent, betaComponent, gammaComponent)

        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Alpha_Build", "https://teamcity.example.com/project/Alpha_Build", hasCdReleaseBuild = false),
                ),
                "beta" to listOf(
                    TcProject("Beta_Build", "https://teamcity.example.com/project/Beta_Build", hasCdReleaseBuild = false),
                ),
                // gamma has no TC match → skippedNoMatch
            ),
        )
        val repo = StubComponentRepository(components)
        val tcRepo = StubTcProjectRepository()
        // Pre-seed beta as already correct so it counts as unchanged.
        tcRepo.preload(
            ComponentTeamcityProjectEntity(component = betaComponent, projectId = "Beta_Build", sortOrder = 0),
        )
        val publisher = RecordingPublisher()
        val svc = service(repo, tcRepo, fetcher, publisher, fixedUser("alice"))

        val result = svc.resync()

        assertEquals(3, result.scanned)
        assertEquals(1, result.updated)       // alpha newly written
        assertEquals(1, result.unchanged)     // beta already correct
        assertEquals(1, result.skippedNoMatch) // gamma
        assertEquals(0, result.skippedAmbiguous)
        assertTrue(result.errors.isEmpty())
        assertTrue(
            publisher.events.filterIsInstance<AuditEvent>().isEmpty(),
            "TeamCity sync must not publish an AuditEvent for any synced component (SYS-051)",
        )
    }

    // -- Test doubles ---------------------------------------------------------

    private fun fixedUser(name: String): CurrentUserResolver =
        object : CurrentUserResolver() {
            override fun currentUsername(): String = name
        }

    /**
     * No-op [TransactionTemplate] that runs the callback inline.
     * In-memory stubs have no JPA transaction semantics so a real manager
     * would only add ceremony.
     */
    private fun inlineTx(): TransactionTemplate =
        object : TransactionTemplate() {
            override fun <T> execute(action: TransactionCallback<T>): T? =
                action.doInTransaction(SimpleTransactionStatus())
        }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) { events.add(event) }
        override fun publishEvent(event: ApplicationEvent) { events.add(event) }
    }

    /**
     * In-memory stub for [ComponentTeamcityProjectRepository].
     *
     * Stores entities in a mutable list. [preload] seeds pre-existing rows
     * before the sync run. [saveCalls] lets tests assert that no write
     * happened when idempotency is expected.
     */
    private class StubTcProjectRepository : ComponentTeamcityProjectRepository {
        private val store = mutableListOf<ComponentTeamcityProjectEntity>()
        val saveCalls = mutableListOf<ComponentTeamcityProjectEntity>()

        /** Seed a row as already-persisted before the sync run. */
        fun preload(entity: ComponentTeamcityProjectEntity) { store.add(entity) }

        override fun findByComponentId(componentId: UUID): List<ComponentTeamcityProjectEntity> =
            store.filter { it.component.id == componentId }

        override fun findByProjectId(projectId: String): List<ComponentTeamcityProjectEntity> =
            store.filter { it.projectId == projectId }

        override fun <S : ComponentTeamcityProjectEntity> save(entity: S): S {
            store.add(entity)
            saveCalls.add(entity)
            return entity
        }

        override fun deleteAllInBatch(entities: Iterable<ComponentTeamcityProjectEntity>) {
            store.removeAll(entities.toList())
        }

        // Unused standard JPA methods — throw to surface accidental call-sites during refactors.

        override fun <S : ComponentTeamcityProjectEntity> saveAll(entities: Iterable<S>): List<S> = unsupported()
        override fun <S : ComponentTeamcityProjectEntity> saveAndFlush(entity: S): S = unsupported()
        override fun <S : ComponentTeamcityProjectEntity> saveAllAndFlush(entities: Iterable<S>): List<S> = unsupported()
        override fun findById(id: UUID): Optional<ComponentTeamcityProjectEntity> =
            Optional.ofNullable(store.firstOrNull { it.id == id })
        override fun existsById(id: UUID): Boolean = store.any { it.id == id }
        override fun findAll(): List<ComponentTeamcityProjectEntity> = store.toList()
        override fun findAllById(ids: Iterable<UUID>): List<ComponentTeamcityProjectEntity> = unsupported()
        override fun count(): Long = store.size.toLong()
        override fun deleteById(id: UUID) { unsupported() }
        override fun delete(entity: ComponentTeamcityProjectEntity) { unsupported() }
        override fun deleteAllById(ids: Iterable<UUID>) { unsupported() }
        override fun deleteAll(entities: Iterable<ComponentTeamcityProjectEntity>) { unsupported() }
        override fun deleteAll() { unsupported() }
        override fun deleteAllInBatch() { unsupported() }
        override fun deleteAllByIdInBatch(ids: Iterable<UUID>) { unsupported() }
        override fun getOne(id: UUID): ComponentTeamcityProjectEntity = unsupported()

        @Suppress("DEPRECATION")
        override fun getById(id: UUID): ComponentTeamcityProjectEntity = unsupported()
        override fun getReferenceById(id: UUID): ComponentTeamcityProjectEntity = unsupported()
        override fun flush() { unsupported() }
        override fun findAll(sort: Sort): List<ComponentTeamcityProjectEntity> = store.toList()
        override fun findAll(pageable: Pageable): Page<ComponentTeamcityProjectEntity> =
            PageImpl(store, pageable, store.size.toLong())
        override fun <S : ComponentTeamcityProjectEntity> findOne(example: Example<S>): Optional<S> = Optional.empty()
        override fun <S : ComponentTeamcityProjectEntity> findAll(example: Example<S>): List<S> = unsupported()
        override fun <S : ComponentTeamcityProjectEntity> findAll(example: Example<S>, sort: Sort): List<S> =
            unsupported()
        override fun <S : ComponentTeamcityProjectEntity> findAll(
            example: Example<S>,
            pageable: Pageable,
        ): Page<S> = unsupported()
        override fun <S : ComponentTeamcityProjectEntity> count(example: Example<S>): Long = 0L
        override fun <S : ComponentTeamcityProjectEntity> exists(example: Example<S>): Boolean = false
        override fun <S : ComponentTeamcityProjectEntity, R : Any?> findBy(
            example: Example<S>,
            queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("not used by TeamcitySyncService")
    }

    /**
     * Hand-rolled [ComponentRepository] stub.
     *
     * Implements only the methods the sync service uses. All other calls throw
     * so any accidental new call-site is caught immediately during refactors.
     *
     * v2 diff from the pre-stub version:
     *  - `findByName` / `existsByName` removed (v1 methods, gone from interface).
     *  - `findByNameWithVersions` / `findByNameWithAllRelations` / `findByIdWithAllRelations` removed.
     *  - `findByArchivedFalse(Pageable)` removed (not declared in v2 interface).
     *  - `findByComponentKey` / `findByComponentKeyAndArchivedFalse` / `existsByComponentKey` added.
     *  - `savedIds` tracking removed; TC writes are now tracked by [StubTcProjectRepository.saveCalls].
     */
    @Suppress("TooManyFunctions")
    private class StubComponentRepository(
        private val components: List<ComponentEntity>,
    ) : ComponentRepository {
        override fun findByComponentKey(componentKey: String): ComponentEntity? =
            components.firstOrNull { it.componentKey == componentKey }

        override fun findByComponentKeyIn(componentKeys: Collection<String>): List<ComponentEntity> =
            components.filter { it.componentKey in componentKeys }

        override fun findByComponentKeyAndArchivedFalse(componentKey: String): ComponentEntity? =
            components.firstOrNull { it.componentKey == componentKey && !it.archived }

        override fun findByArchivedFalse(): List<ComponentEntity> = components.filter { !it.archived }

        override fun existsByComponentKey(componentKey: String): Boolean =
            components.any { it.componentKey == componentKey }

        override fun findDistinctOwners(): List<String> =
            components.mapNotNull { it.componentOwner }.distinct().sorted()

        override fun findDistinctSystemCodes(): List<String> =
            components.mapNotNull { it.systemCode }.filter { it.isNotBlank() }.distinct().sorted()

        override fun findDistinctClientCodes(): List<String> =
            components.mapNotNull { it.clientCode }.filter { it.isNotBlank() }.distinct().sorted()

        override fun findDistinctJiraProjectKeys(): List<String> =
            components
                .flatMap { it.configurations }
                .filter { it.rowType == "BASE" }
                .mapNotNull { it.jiraProjectKey }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

        override fun findDistinctParentComponentNames(): List<String> =
            components.mapNotNull { it.parentComponent?.componentKey }.filter { it.isNotBlank() }.distinct().sorted()

        override fun existsByParentComponentId(parentId: UUID): Boolean =
            components.any { it.parentComponent?.id == parentId }

        override fun findByComponentGroupId(groupId: UUID): List<ComponentEntity> =
            components.filter { it.componentGroup?.id == groupId }

        override fun <S : ComponentEntity> save(entity: S): S = entity
        override fun <S : ComponentEntity> saveAll(entities: Iterable<S>): List<S> = unsupported()
        override fun <S : ComponentEntity> saveAndFlush(entity: S): S = entity
        override fun <S : ComponentEntity> saveAllAndFlush(entities: Iterable<S>): List<S> = unsupported()
        override fun findById(id: UUID): Optional<ComponentEntity> =
            Optional.ofNullable(components.firstOrNull { it.id == id })
        override fun existsById(id: UUID): Boolean = components.any { it.id == id }
        override fun findAll(): List<ComponentEntity> = components
        override fun findAllById(ids: Iterable<UUID>): List<ComponentEntity> = unsupported()
        override fun count(): Long = components.size.toLong()
        override fun deleteById(id: UUID) { unsupported() }
        override fun delete(entity: ComponentEntity) { unsupported() }
        override fun deleteAllById(ids: Iterable<UUID>) { unsupported() }
        override fun deleteAll(entities: Iterable<ComponentEntity>) { unsupported() }
        override fun deleteAll() { unsupported() }
        override fun deleteAllInBatch() { unsupported() }
        override fun deleteAllByIdInBatch(ids: Iterable<UUID>) { unsupported() }
        override fun deleteAllInBatch(entities: Iterable<ComponentEntity>) { unsupported() }
        override fun getOne(id: UUID): ComponentEntity = unsupported()

        @Suppress("DEPRECATION")
        override fun getById(id: UUID): ComponentEntity = unsupported()
        override fun getReferenceById(id: UUID): ComponentEntity = unsupported()
        override fun flush() { unsupported() }
        override fun findAll(sort: Sort): List<ComponentEntity> = components
        override fun findAll(pageable: Pageable): Page<ComponentEntity> =
            PageImpl(components, pageable, components.size.toLong())
        override fun findAll(spec: Specification<ComponentEntity>): List<ComponentEntity> = components
        override fun findAll(
            spec: Specification<ComponentEntity>,
            pageable: Pageable,
        ): Page<ComponentEntity> = PageImpl(components, pageable, components.size.toLong())
        override fun findAll(spec: Specification<ComponentEntity>, sort: Sort): List<ComponentEntity> = components
        override fun findOne(spec: Specification<ComponentEntity>): Optional<ComponentEntity> = Optional.empty()
        override fun count(spec: Specification<ComponentEntity>): Long = components.size.toLong()
        override fun exists(spec: Specification<ComponentEntity>): Boolean = false
        override fun delete(spec: Specification<ComponentEntity>): Long = unsupported()
        override fun <S : ComponentEntity, R : Any?> findBy(
            spec: Specification<ComponentEntity>,
            queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = unsupported()
        override fun <S : ComponentEntity> findOne(example: Example<S>): Optional<S> = Optional.empty()
        override fun <S : ComponentEntity> findAll(example: Example<S>): List<S> = unsupported()
        override fun <S : ComponentEntity> findAll(example: Example<S>, sort: Sort): List<S> = unsupported()
        override fun <S : ComponentEntity> findAll(example: Example<S>, pageable: Pageable): Page<S> = unsupported()
        override fun <S : ComponentEntity> count(example: Example<S>): Long = 0L
        override fun <S : ComponentEntity> exists(example: Example<S>): Boolean = false
        override fun <S : ComponentEntity, R : Any?> findBy(
            example: Example<S>,
            queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("not used by TeamcitySyncService")
    }
}
