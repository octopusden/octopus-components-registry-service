package org.octopusden.octopus.components.registry.server.teamcity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.octopusden.octopus.components.registry.server.event.AuditEvent
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.TeamcityProjectRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
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
 * Unit tests for [TeamcitySyncService] against the version_line / teamcity_project schema.
 *
 * All dependencies are hand-rolled in-memory stubs — no Spring context, no Mockito, no DB.
 * [TcProjectFetcher] is a SAM interface. [StubVersionLineRepository] owns the per-component
 * link rows; [StubTeamcityProjectRepository] holds the deduplicated TeamCity projects.
 */
class TeamcitySyncServiceTest {
    private val alice = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val bob = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val carol = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private fun component(
        id: UUID,
        key: String,
    ) = ComponentEntity(id = id, componentKey = key)

    /** Build a persisted-style version_line for pre-seeding. */
    private fun versionLine(
        component: ComponentEntity,
        projectId: String,
        version: String? = null,
    ) = VersionLineEntity(
        component = component,
        version = version,
        teamcityProject = TeamcityProjectEntity(id = UUID.randomUUID(), projectId = projectId),
    )

    private fun stubFetcher(matchesByKey: Map<String, List<TcProject>>): TcProjectFetcher =
        TcProjectFetcher { componentsByName ->
            componentsByName.entries
                .mapNotNull { (key, uuid) ->
                    val projects = matchesByKey[key] ?: return@mapNotNull null
                    uuid to projects
                }.toMap()
        }

    private fun service(
        repo: ComponentRepository,
        versionLineRepo: StubVersionLineRepository,
        teamcityProjectRepo: StubTeamcityProjectRepository,
        fetcher: TcProjectFetcher,
        publisher: ApplicationEventPublisher,
        user: CurrentUserResolver,
        properties: TeamcityProperties = configuredProperties(),
    ) = TeamcitySyncService(repo, versionLineRepo, teamcityProjectRepo, fetcher, publisher, user, inlineTx(), properties)

    private fun configuredProperties() = TeamcityProperties(baseUrl = "https://teamcity.example.com")

    private fun projectIds(repo: StubVersionLineRepository, componentId: UUID): List<String> =
        repo.findByComponentId(componentId).map { it.teamcityProject.projectId }.sorted()

    // ---------- Tests --------------------------------------------------------

    @Test
    @DisplayName("SYS-051 happy path: writes version_line; counts updated; NO audit event")
    fun happyPath() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(mapOf("alpha" to listOf(TcProject("Alpha_Build", hasCdReleaseBuild = false))))
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(0, result.skippedNoMatch)
        assertEquals(0, result.skippedAmbiguous)
        assertTrue(result.errors.isEmpty())
        assertEquals("Alpha_Build", vlRepo.findByComponentId(alice).single().teamcityProject.projectId)
        assertTrue(
            publisher.events.filterIsInstance<AuditEvent>().isEmpty(),
            "TeamCity sync must not publish an AuditEvent (SYS-051)",
        )
    }

    @Test
    @DisplayName("unchanged: existing line already matches — no audit event, no save, count unchanged")
    fun unchangedNoAudit() {
        val alphaComponent = component(alice, "alpha")
        val fetcher = stubFetcher(mapOf("alpha" to listOf(TcProject("Alpha_Build", hasCdReleaseBuild = false))))
        val repo = StubComponentRepository(listOf(alphaComponent))
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        // Pre-seed: alpha already has the correct link (version null).
        vlRepo.preload(versionLine(alphaComponent, "Alpha_Build"))
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(1, result.unchanged)
        assertTrue(publisher.events.isEmpty(), "no audit events when unchanged")
        assertTrue(vlRepo.saveCalls.isEmpty(), "no save when unchanged")
    }

    @Test
    @DisplayName("ambiguous + nobody has CDRelease build → skippedAmbiguous, no write")
    fun ambiguousNoCdReleaseSkipped() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Project_A", hasCdReleaseBuild = false),
                    TcProject("Project_B", hasCdReleaseBuild = false),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(1, result.skippedAmbiguous)
        assertEquals(0, result.ambiguousAutoResolved)
        // Fully-skipped component: reflected in skippedAmbiguous, NOT droppedLines.
        assertEquals(0, result.droppedLines)
        assertTrue(vlRepo.findByComponentId(alice).isEmpty(), "ambiguous + no CDRelease → no line written")
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    @DisplayName("ambiguous + exactly one has CDRelease → auto-pick that one, count ambiguousAutoResolved")
    fun ambiguousSingleCdReleaseAutoResolved() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Project_A_NoRelease", hasCdReleaseBuild = false),
                    TcProject("Project_B_Release", hasCdReleaseBuild = true),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(1, result.ambiguousAutoResolved)
        assertEquals("Project_B_Release", vlRepo.findByComponentId(alice).single().teamcityProject.projectId)
        assertTrue(publisher.events.filterIsInstance<AuditEvent>().isEmpty(), "no AuditEvent (SYS-051)")
    }

    @Test
    @DisplayName("ambiguous + multiple CDRelease candidates → pick lexicographically smallest by id")
    fun ambiguousMultipleCdReleasePickFirstById() {
        val components = listOf(component(alice, "alpha"))
        // Input order inverted to prove tie-break is by id, not input order.
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Project_B_Release", hasCdReleaseBuild = true),
                    TcProject("Project_A_Release", hasCdReleaseBuild = true),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(1, result.ambiguousAutoResolved)
        assertEquals("Project_A_Release", vlRepo.findByComponentId(alice).single().teamcityProject.projectId)
    }

    @Test
    @DisplayName("no match: 0 candidates → skippedNoMatch, no write")
    fun noMatchSkipped() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(emptyMap())
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(1, result.skippedNoMatch)
        assertTrue(vlRepo.findByComponentId(alice).isEmpty(), "no line for no-match component")
    }

    @Test
    @DisplayName("null-id component: counted in scanned but silently skipped, no error counter")
    fun nullIdComponentSkipped() {
        val nullIdComponent = ComponentEntity(id = null, componentKey = "no-id")
        val components = listOf(component(alice, "alpha"), nullIdComponent)
        val fetcher = stubFetcher(mapOf("alpha" to listOf(TcProject("Alpha_Build", hasCdReleaseBuild = false))))
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

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
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"), TeamcityProperties())

        val ex = assertThrows<IllegalStateException> { svc.resync() }
        assertTrue(ex.message!!.contains("teamcity.base-url"), "message should mention the config property")
    }

    @Test
    @DisplayName("blank base-url: throws even when registry is empty")
    fun blankBaseUrlThrowsOnEmptyRegistry() {
        val fetcher = TcProjectFetcher { _ -> emptyMap() }
        val repo = StubComponentRepository(emptyList())
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"), TeamcityProperties())

        val ex = assertThrows<IllegalStateException> { svc.resync() }
        assertTrue(ex.message!!.contains("teamcity.base-url"), "message should mention the config property")
    }

    @Test
    @DisplayName("TC client failure on fetch: exception propagates out of resync")
    fun clientFailurePropagates() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = TcProjectFetcher { _ -> throw RuntimeException("TC unavailable") }
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val ex = assertThrows<RuntimeException> { svc.resync() }
        assertEquals("TC unavailable", ex.message)
    }

    @Test
    @DisplayName("mixed batch: counts every category in one pass")
    fun mixedBatch() {
        val alphaComponent = component(alice, "alpha")
        val betaComponent = component(bob, "beta")
        val gammaComponent = component(carol, "gamma")
        val components = listOf(alphaComponent, betaComponent, gammaComponent)

        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(TcProject("Alpha_Build", hasCdReleaseBuild = false)),
                "beta" to listOf(TcProject("Beta_Build", hasCdReleaseBuild = false)),
                // gamma has no TC match → skippedNoMatch
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        // Pre-seed beta as already correct so it counts as unchanged.
        vlRepo.preload(versionLine(betaComponent, "Beta_Build"))
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("alice"))

        val result = svc.resync()

        assertEquals(3, result.scanned)
        assertEquals(1, result.updated) // alpha newly written
        assertEquals(1, result.unchanged) // beta already correct
        assertEquals(1, result.skippedNoMatch) // gamma
        assertEquals(0, result.skippedAmbiguous)
        assertTrue(result.errors.isEmpty())
        assertTrue(publisher.events.filterIsInstance<AuditEvent>().isEmpty(), "no AuditEvent (SYS-051)")
    }

    @Test
    @DisplayName("multiple PROJECT_VERSION lines: keeps one project per version, writes one row each")
    fun multipleVersionsKeepOnePerLine() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Alpha_1x", hasCdReleaseBuild = false, projectVersion = "1.x"),
                    TcProject("Alpha_2x", hasCdReleaseBuild = false, projectVersion = "2.x"),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(0, result.ambiguousAutoResolved) // each line had a single candidate
        val rows = vlRepo.findByComponentId(alice).sortedBy { it.teamcityProject.projectId }
        assertEquals(listOf("Alpha_1x", "Alpha_2x"), rows.map { it.teamcityProject.projectId })
        assertEquals(listOf("1.x", "2.x"), rows.map { it.version })
    }

    @Test
    @DisplayName("tie-break is per PROJECT_VERSION: one line resolves trivially, the other via CDRelease")
    fun sameVersionTieBreakWithinLine() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Alpha_2x_A", hasCdReleaseBuild = false, projectVersion = "2.x"),
                    TcProject("Alpha_2x_B", hasCdReleaseBuild = true, projectVersion = "2.x"),
                    TcProject("Alpha_1x", hasCdReleaseBuild = false, projectVersion = "1.x"),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(1, result.ambiguousAutoResolved) // the 2.x line needed a tie-break
        val rows = vlRepo.findByComponentId(alice).sortedBy { it.teamcityProject.projectId }
        assertEquals(listOf("Alpha_1x", "Alpha_2x_B"), rows.map { it.teamcityProject.projectId })
        assertEquals(listOf("1.x", "2.x"), rows.map { it.version })
    }

    @Test
    @DisplayName("one line resolves, another is ambiguous-unresolved: usable winner still written, no skippedAmbiguous")
    fun mixedResolvableAndAmbiguousLines() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    // 1.x resolves trivially
                    TcProject("Alpha_1x", hasCdReleaseBuild = false, projectVersion = "1.x"),
                    // 2.x is ambiguous with no CDRelease → that line is dropped
                    TcProject("Alpha_2x_A", hasCdReleaseBuild = false, projectVersion = "2.x"),
                    TcProject("Alpha_2x_B", hasCdReleaseBuild = false, projectVersion = "2.x"),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        // Component counts as updated because at least one line produced a winner.
        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        // The dropped 2.x line is invisible to skippedAmbiguous but counted here (partial failure).
        assertEquals(1, result.droppedLines)
        assertEquals(listOf("Alpha_1x"), projectIds(vlRepo, alice))
    }

    @Test
    @DisplayName("null-version candidate is discarded when another candidate declares a version")
    fun nullVersionDiscardedWhenVersionedPresent() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Alpha_NoVer", hasCdReleaseBuild = false, projectVersion = null),
                    TcProject("Alpha_2x", hasCdReleaseBuild = false, projectVersion = "2.x"),
                    TcProject("Alpha_3x", hasCdReleaseBuild = false, projectVersion = "3.x"),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.updated)
        val rows = vlRepo.findByComponentId(alice).sortedBy { it.teamcityProject.projectId }
        // The null-version project is dropped; only the versioned lines survive.
        assertEquals(listOf("Alpha_2x", "Alpha_3x"), rows.map { it.teamcityProject.projectId })
        assertEquals(listOf("2.x", "3.x"), rows.map { it.version })
    }

    @Test
    @DisplayName("all-null candidates keep default behavior (collapse to one line)")
    fun allNullKeepsDefaultBehavior() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(
            mapOf(
                "alpha" to listOf(
                    TcProject("Alpha_A", hasCdReleaseBuild = false, projectVersion = null),
                    TcProject("Alpha_B", hasCdReleaseBuild = true, projectVersion = null),
                ),
            ),
        )
        val repo = StubComponentRepository(components)
        val vlRepo = StubVersionLineRepository()
        val tpRepo = StubTeamcityProjectRepository()
        val publisher = RecordingPublisher()
        val svc = service(repo, vlRepo, tpRepo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        // Both null → single null group → CDRelease tie-break picks one row.
        assertEquals(1, result.updated)
        assertEquals(1, result.ambiguousAutoResolved)
        val rows = vlRepo.findByComponentId(alice)
        assertEquals(listOf("Alpha_B"), rows.map { it.teamcityProject.projectId })
        assertEquals(listOf<String?>(null), rows.map { it.version })
    }

    // -- Test doubles ---------------------------------------------------------

    private fun fixedUser(name: String): CurrentUserResolver =
        object : CurrentUserResolver() {
            override fun currentUsername(): String = name
        }

    /** No-op [TransactionTemplate] that runs the callback inline. */
    private fun inlineTx(): TransactionTemplate =
        object : TransactionTemplate() {
            override fun <T> execute(action: TransactionCallback<T>): T? = action.doInTransaction(SimpleTransactionStatus())
        }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()

        override fun publishEvent(event: Any) {
            events.add(event)
        }

        override fun publishEvent(event: ApplicationEvent) {
            events.add(event)
        }
    }

    /**
     * In-memory stub for [VersionLineRepository]. [preload] seeds pre-existing rows;
     * [saveCalls] lets tests assert no write happened when idempotency is expected.
     */
    private class StubVersionLineRepository : VersionLineRepository {
        private val store = mutableListOf<VersionLineEntity>()
        val saveCalls = mutableListOf<VersionLineEntity>()

        fun preload(entity: VersionLineEntity) {
            store.add(entity)
        }

        override fun findByComponentId(componentId: UUID): List<VersionLineEntity> = store.filter { it.component.id == componentId }

        override fun <S : VersionLineEntity> save(entity: S): S {
            store.add(entity)
            saveCalls.add(entity)
            return entity
        }

        override fun deleteAllInBatch(entities: Iterable<VersionLineEntity>) {
            store.removeAll(entities.toList())
        }

        override fun <S : VersionLineEntity> saveAll(entities: Iterable<S>): List<S> = unsupported()

        override fun <S : VersionLineEntity> saveAndFlush(entity: S): S = unsupported()

        override fun <S : VersionLineEntity> saveAllAndFlush(entities: Iterable<S>): List<S> = unsupported()

        override fun findById(id: UUID): Optional<VersionLineEntity> = Optional.ofNullable(store.firstOrNull { it.id == id })

        override fun existsById(id: UUID): Boolean = store.any { it.id == id }

        override fun findAll(): List<VersionLineEntity> = store.toList()

        override fun findAllById(ids: Iterable<UUID>): List<VersionLineEntity> = unsupported()

        override fun count(): Long = store.size.toLong()

        override fun deleteById(id: UUID) = unsupported()

        override fun delete(entity: VersionLineEntity) = unsupported()

        override fun deleteAllById(ids: Iterable<UUID>) = unsupported()

        override fun deleteAll(entities: Iterable<VersionLineEntity>) = unsupported()

        override fun deleteAll() = unsupported()

        override fun deleteAllInBatch() = unsupported()

        override fun deleteAllByIdInBatch(ids: Iterable<UUID>) = unsupported()

        override fun getOne(id: UUID): VersionLineEntity = unsupported()

        @Suppress("DEPRECATION")
        override fun getById(id: UUID): VersionLineEntity = unsupported()

        override fun getReferenceById(id: UUID): VersionLineEntity = unsupported()

        override fun flush() = unsupported()

        override fun findAll(sort: Sort): List<VersionLineEntity> = store.toList()

        override fun findAll(pageable: Pageable): Page<VersionLineEntity> = PageImpl(store, pageable, store.size.toLong())

        override fun <S : VersionLineEntity> findOne(example: Example<S>): Optional<S> = Optional.empty()

        override fun <S : VersionLineEntity> findAll(example: Example<S>): List<S> = unsupported()

        override fun <S : VersionLineEntity> findAll(
            example: Example<S>,
            sort: Sort,
        ): List<S> = unsupported()

        override fun <S : VersionLineEntity> findAll(
            example: Example<S>,
            pageable: Pageable,
        ): Page<S> = unsupported()

        override fun <S : VersionLineEntity> count(example: Example<S>): Long = 0L

        override fun <S : VersionLineEntity> exists(example: Example<S>): Boolean = false

        override fun <S : VersionLineEntity, R : Any?> findBy(
            example: Example<S>,
            queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by TeamcitySyncService")
    }

    /** In-memory stub for [TeamcityProjectRepository] with find-or-create-by-projectId dedup. */
    private class StubTeamcityProjectRepository : TeamcityProjectRepository {
        private val store = mutableListOf<TeamcityProjectEntity>()

        override fun findByProjectId(projectId: String): TeamcityProjectEntity? = store.firstOrNull { it.projectId == projectId }

        override fun <S : TeamcityProjectEntity> save(entity: S): S {
            if (entity.id == null) entity.id = UUID.randomUUID()
            store.add(entity)
            return entity
        }

        override fun <S : TeamcityProjectEntity> saveAll(entities: Iterable<S>): List<S> = unsupported()

        override fun <S : TeamcityProjectEntity> saveAndFlush(entity: S): S = unsupported()

        override fun <S : TeamcityProjectEntity> saveAllAndFlush(entities: Iterable<S>): List<S> = unsupported()

        override fun findById(id: UUID): Optional<TeamcityProjectEntity> = Optional.ofNullable(store.firstOrNull { it.id == id })

        override fun existsById(id: UUID): Boolean = store.any { it.id == id }

        override fun findAll(): List<TeamcityProjectEntity> = store.toList()

        override fun findAllById(ids: Iterable<UUID>): List<TeamcityProjectEntity> = unsupported()

        override fun count(): Long = store.size.toLong()

        override fun deleteById(id: UUID) = unsupported()

        override fun delete(entity: TeamcityProjectEntity) = unsupported()

        override fun deleteAllById(ids: Iterable<UUID>) = unsupported()

        override fun deleteAll(entities: Iterable<TeamcityProjectEntity>) = unsupported()

        override fun deleteAll() = unsupported()

        override fun deleteAllInBatch() = unsupported()

        override fun deleteAllInBatch(entities: Iterable<TeamcityProjectEntity>) = unsupported()

        override fun deleteAllByIdInBatch(ids: Iterable<UUID>) = unsupported()

        override fun getOne(id: UUID): TeamcityProjectEntity = unsupported()

        @Suppress("DEPRECATION")
        override fun getById(id: UUID): TeamcityProjectEntity = unsupported()

        override fun getReferenceById(id: UUID): TeamcityProjectEntity = unsupported()

        override fun flush() = unsupported()

        override fun findAll(sort: Sort): List<TeamcityProjectEntity> = store.toList()

        override fun findAll(pageable: Pageable): Page<TeamcityProjectEntity> = PageImpl(store, pageable, store.size.toLong())

        override fun <S : TeamcityProjectEntity> findOne(example: Example<S>): Optional<S> = Optional.empty()

        override fun <S : TeamcityProjectEntity> findAll(example: Example<S>): List<S> = unsupported()

        override fun <S : TeamcityProjectEntity> findAll(
            example: Example<S>,
            sort: Sort,
        ): List<S> = unsupported()

        override fun <S : TeamcityProjectEntity> findAll(
            example: Example<S>,
            pageable: Pageable,
        ): Page<S> = unsupported()

        override fun <S : TeamcityProjectEntity> count(example: Example<S>): Long = 0L

        override fun <S : TeamcityProjectEntity> exists(example: Example<S>): Boolean = false

        override fun <S : TeamcityProjectEntity, R : Any?> findBy(
            example: Example<S>,
            queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by TeamcitySyncService")
    }

    /**
     * Hand-rolled [ComponentRepository] stub — implements only the methods the sync uses;
     * all other calls throw to surface accidental new call-sites during refactors.
     */
    @Suppress("TooManyFunctions")
    private class StubComponentRepository(
        private val components: List<ComponentEntity>,
    ) : ComponentRepository {
        override fun findByComponentKey(componentKey: String): ComponentEntity? = components.firstOrNull { it.componentKey == componentKey }

        override fun findByComponentKeyIn(componentKeys: Collection<String>): List<ComponentEntity> =
            components.filter { it.componentKey in componentKeys }

        override fun findByComponentKeyAndArchivedFalse(componentKey: String): ComponentEntity? =
            components.firstOrNull { it.componentKey == componentKey && !it.archived }

        override fun findByArchivedFalse(): List<ComponentEntity> = components.filter { !it.archived }

        override fun existsByComponentKey(componentKey: String): Boolean = components.any { it.componentKey == componentKey }

        override fun existsByDisplayName(displayName: String): Boolean = components.any { it.displayName == displayName }

        override fun existsByDisplayNameAndIdNot(
            displayName: String,
            id: UUID,
        ): Boolean = components.any { it.displayName == displayName && it.id != id }

        override fun findAllDisplayNamePairs(): List<org.octopusden.octopus.components.registry.server.repository.DisplayNamePairRow> =
            components.mapNotNull { c ->
                c.displayName?.let { name ->
                    object : org.octopusden.octopus.components.registry.server.repository.DisplayNamePairRow {
                        override val componentKey: String = c.componentKey
                        override val displayName: String = name
                    }
                }
            }

        override fun findDistinctOwners(): List<String> = components.mapNotNull { it.componentOwner }.distinct().sorted()

        override fun findDistinctClientCodes(): List<String> =
            components
                .mapNotNull { it.clientCode }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

        override fun findDistinctJiraProjectKeys(): List<String> =
            components
                .flatMap { it.configurations }
                .filter { it.rowType == "BASE" }
                .mapNotNull { it.jiraProjectKey }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

        override fun findDistinctParentComponentNames(): List<String> =
            components
                .mapNotNull { it.parentComponent?.componentKey }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

        override fun existsByParentComponentId(parentId: UUID): Boolean = components.any { it.parentComponent?.id == parentId }

        override fun findByComponentGroupId(groupId: UUID): List<ComponentEntity> = components.filter { it.componentGroup?.id == groupId }

        override fun findComponentOwnerById(id: UUID): String? = components.firstOrNull { it.id == id }?.componentOwner

        override fun findReleaseManagerUsernames(id: UUID): List<String> =
            components.firstOrNull { it.id == id }?.releaseManagerUsernames() ?: emptyList()

        override fun findSecurityChampionUsernames(id: UUID): List<String> =
            components.firstOrNull { it.id == id }?.securityChampionUsernames() ?: emptyList()

        private fun isFakeAggregator(c: ComponentEntity): Boolean =
            c.componentGroup?.let { it.isFake && it.groupKey == c.componentKey } ?: false

        private fun regularComponents(): List<ComponentEntity> = components.filterNot(::isFakeAggregator)

        private fun activeRegularComponents(): List<ComponentEntity> = regularComponents().filterNot { it.archived }

        override fun countRegularComponents(): Long = regularComponents().size.toLong()

        override fun countRegularComponentsByArchived(archived: Boolean): Long =
            regularComponents().count { it.archived == archived }.toLong()

        private fun nameCounts(counts: Map<String, Int>): List<org.octopusden.octopus.components.registry.server.repository.NameCountRow> =
            counts.map { (n, c) ->
                object : org.octopusden.octopus.components.registry.server.repository.NameCountRow {
                    override val name: String = n
                    override val count: Long = c.toLong()
                }
            }

        override fun countComponentsByOwner() =
            nameCounts(
                activeRegularComponents()
                    .mapNotNull { it.componentOwner?.takeIf { o -> o.isNotBlank() } }
                    .groupingBy { it }
                    .eachCount(),
            )

        override fun countComponentsByReleaseManager() =
            nameCounts(
                activeRegularComponents()
                    .flatMap { it.releaseManagerUsernames() }
                    .groupingBy { it }
                    .eachCount(),
            )

        override fun countComponentsBySecurityChampion() =
            nameCounts(
                activeRegularComponents()
                    .flatMap { it.securityChampionUsernames() }
                    .groupingBy { it }
                    .eachCount(),
            )

        override fun <S : ComponentEntity> save(entity: S): S = entity

        override fun <S : ComponentEntity> saveAll(entities: Iterable<S>): List<S> = unsupported()

        override fun <S : ComponentEntity> saveAndFlush(entity: S): S = entity

        override fun <S : ComponentEntity> saveAllAndFlush(entities: Iterable<S>): List<S> = unsupported()

        override fun findById(id: UUID): Optional<ComponentEntity> = Optional.ofNullable(components.firstOrNull { it.id == id })

        override fun existsById(id: UUID): Boolean = components.any { it.id == id }

        override fun findAll(): List<ComponentEntity> = components

        override fun findAllById(ids: Iterable<UUID>): List<ComponentEntity> = unsupported()

        override fun count(): Long = components.size.toLong()

        override fun deleteById(id: UUID) = unsupported()

        override fun delete(entity: ComponentEntity) = unsupported()

        override fun deleteAllById(ids: Iterable<UUID>) = unsupported()

        override fun deleteAll(entities: Iterable<ComponentEntity>) = unsupported()

        override fun deleteAll() = unsupported()

        override fun deleteAllInBatch() = unsupported()

        override fun deleteAllByIdInBatch(ids: Iterable<UUID>) = unsupported()

        override fun deleteAllInBatch(entities: Iterable<ComponentEntity>) = unsupported()

        override fun getOne(id: UUID): ComponentEntity = unsupported()

        @Suppress("DEPRECATION")
        override fun getById(id: UUID): ComponentEntity = unsupported()

        override fun getReferenceById(id: UUID): ComponentEntity = unsupported()

        override fun flush() = unsupported()

        override fun findAll(sort: Sort): List<ComponentEntity> = components

        override fun findAll(pageable: Pageable): Page<ComponentEntity> = PageImpl(components, pageable, components.size.toLong())

        override fun findAll(spec: Specification<ComponentEntity>): List<ComponentEntity> = components

        override fun findAll(
            spec: Specification<ComponentEntity>,
            pageable: Pageable,
        ): Page<ComponentEntity> = PageImpl(components, pageable, components.size.toLong())

        override fun findAll(
            spec: Specification<ComponentEntity>,
            sort: Sort,
        ): List<ComponentEntity> = components

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

        override fun <S : ComponentEntity> findAll(
            example: Example<S>,
            sort: Sort,
        ): List<S> = unsupported()

        override fun <S : ComponentEntity> findAll(
            example: Example<S>,
            pageable: Pageable,
        ): Page<S> = unsupported()

        override fun <S : ComponentEntity> count(example: Example<S>): Long = 0L

        override fun <S : ComponentEntity> exists(example: Example<S>): Boolean = false

        override fun <S : ComponentEntity, R : Any?> findBy(
            example: Example<S>,
            queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by TeamcitySyncService")
    }
}
