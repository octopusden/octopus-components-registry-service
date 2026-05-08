package org.octopusden.octopus.components.registry.server.teamcity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.event.AuditEvent
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
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
 * Unit tests for the TC sync service, using handwritten doubles for all
 * dependencies. The [TcProjectFetcher] interface allows simple lambda-based
 * stubs — no Mockito or base-class extension needed.
 */
class TeamcitySyncServiceTest {
    private val alice = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val bob = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val carol = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private fun component(
        id: UUID,
        name: String,
        tcId: String? = null,
        tcUrl: String? = null,
    ) = ComponentEntity(
        id = id,
        name = name,
        teamcityProjectId = tcId,
        teamcityProjectUrl = tcUrl,
    )

    private fun stubFetcher(matchesByName: Map<String, List<TcProject>>): TcProjectFetcher =
        TcProjectFetcher { componentsByName ->
            componentsByName.entries
                .mapNotNull { (name, uuid) ->
                    val projects = matchesByName[name] ?: return@mapNotNull null
                    uuid to projects
                }.toMap()
        }

    private fun service(
        repo: ComponentRepository,
        fetcher: TcProjectFetcher,
        publisher: ApplicationEventPublisher,
        user: CurrentUserResolver,
        properties: TeamcityProperties = configuredProperties(),
    ) = TeamcitySyncService(repo, fetcher, publisher, user, inlineTx(), properties)

    private fun configuredProperties() = TeamcityProperties(baseUrl = "https://teamcity.example.com")

    @Test
    @DisplayName("happy path: writes both id and url; counts updated; emits audit event")
    fun happyPath() {
        val components = listOf(component(alice, "alpha"))
        val fetcher =
            stubFetcher(
                mapOf(
                    "alpha" to listOf(TcProject("Alpha_Build", "https://teamcity.example.com/project/Alpha_Build", hasCdReleaseBuild = false)),
                ),
            )
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(0, result.skippedNoMatch)
        assertEquals(0, result.skippedAmbiguous)
        assertTrue(result.errors.isEmpty())
        assertEquals("Alpha_Build", components[0].teamcityProjectId)
        assertEquals("https://teamcity.example.com/project/Alpha_Build", components[0].teamcityProjectUrl)

        val audit = publisher.events.filterIsInstance<AuditEvent>().single()
        assertEquals("Component", audit.entityType)
        assertEquals("UPDATE", audit.action)
        assertEquals("admin", audit.changedBy)
        assertEquals(mapOf("teamcityProjectId" to null, "teamcityProjectUrl" to null), audit.oldValue)
        assertEquals(
            mapOf(
                "teamcityProjectId" to "Alpha_Build",
                "teamcityProjectUrl" to "https://teamcity.example.com/project/Alpha_Build",
            ),
            audit.newValue,
        )
    }

    @Test
    @DisplayName("unchanged: id+url already match — no audit event, no save, count unchanged")
    fun unchangedNoAudit() {
        val components =
            listOf(
                component(
                    alice,
                    "alpha",
                    tcId = "Alpha_Build",
                    tcUrl = "https://teamcity.example.com/project/Alpha_Build",
                ),
            )
        val fetcher =
            stubFetcher(
                mapOf(
                    "alpha" to listOf(TcProject("Alpha_Build", "https://teamcity.example.com/project/Alpha_Build", hasCdReleaseBuild = false)),
                ),
            )
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(1, result.unchanged)
        assertTrue(publisher.events.isEmpty(), "no audit events when nothing changed")
        assertEquals(0, repo.savedIds.size, "no save when nothing changed")
    }

    @Test
    @DisplayName("ambiguous + nobody has CDRelease build → skip + count, no write")
    fun ambiguousNoCdReleaseSkipped() {
        val components = listOf(component(alice, "alpha"))
        val fetcher =
            stubFetcher(
                mapOf(
                    "alpha" to
                        listOf(
                            TcProject("Project_A", "https://teamcity.example.com/project/A", hasCdReleaseBuild = false),
                            TcProject("Project_B", "https://teamcity.example.com/project/B", hasCdReleaseBuild = false),
                        ),
                ),
            )
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(1, result.skippedAmbiguous)
        assertEquals(0, result.ambiguousAutoResolved)
        assertNull(components[0].teamcityProjectId, "ambiguous → no write")
        assertNull(components[0].teamcityProjectUrl, "ambiguous → no write")
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    @DisplayName("ambiguous + exactly one has CDRelease → tie-break to that one, count auto-resolved")
    fun ambiguousSingleCdReleaseAutoResolved() {
        val components = listOf(component(alice, "alpha"))
        val fetcher =
            stubFetcher(
                mapOf(
                    "alpha" to
                        listOf(
                            TcProject("Project_A_NoRelease", "https://tc/a", hasCdReleaseBuild = false),
                            TcProject("Project_B_Release", "https://tc/b", hasCdReleaseBuild = true),
                        ),
                ),
            )
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(1, result.ambiguousAutoResolved)
        assertEquals("Project_B_Release", components[0].teamcityProjectId)
        assertEquals("https://tc/b", components[0].teamcityProjectUrl)
        assertEquals(1, publisher.events.filterIsInstance<AuditEvent>().size)
    }

    @Test
    @DisplayName("ambiguous + multiple have CDRelease → pick lexicographically smallest by id")
    fun ambiguousMultipleCdReleasePickFirstById() {
        val components = listOf(component(alice, "alpha"))
        // "Project_A_Release" < "Project_B_Release" lexicographically; both have CDRelease.
        // Order in the list is intentionally inverted so the tie-break is by id, not by input order.
        val fetcher =
            stubFetcher(
                mapOf(
                    "alpha" to
                        listOf(
                            TcProject("Project_B_Release", "https://tc/b", hasCdReleaseBuild = true),
                            TcProject("Project_A_Release", "https://tc/a", hasCdReleaseBuild = true),
                        ),
                ),
            )
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(1, result.ambiguousAutoResolved)
        assertEquals("Project_A_Release", components[0].teamcityProjectId)
        assertEquals("https://tc/a", components[0].teamcityProjectUrl)
    }

    @Test
    @DisplayName("ambiguous tie-break to a CDRelease project with blank webUrl → no-match, ambiguousAutoResolved stays 0")
    fun ambiguousTieBreakBlankUrlIsNoMatch() {
        val components = listOf(component(alice, "alpha"))
        val fetcher =
            stubFetcher(
                mapOf(
                    "alpha" to
                        listOf(
                            TcProject("Project_NoUrl", "", hasCdReleaseBuild = true),
                            TcProject("Project_Other", "https://tc/other", hasCdReleaseBuild = false),
                        ),
                ),
            )
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        // Tie-break selected the CDRelease one, but its webUrl is unusable → counts as no-match.
        // ambiguousAutoResolved is a sub-counter of `updated`+`unchanged` (per the result KDoc), so
        // it must NOT increment for rows that ultimately fall into skipped_no_match.
        assertEquals(1, result.skippedNoMatch)
        assertEquals(0, result.updated)
        assertEquals(0, result.skippedAmbiguous)
        assertEquals(0, result.ambiguousAutoResolved)
        assertNull(components[0].teamcityProjectId)
    }

    @Test
    @DisplayName("no match: 0 candidates → skip + count, no write")
    fun noMatchSkipped() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(emptyMap())
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.scanned)
        assertEquals(0, result.updated)
        assertEquals(1, result.skippedNoMatch)
        assertNull(components[0].teamcityProjectId)
        assertNull(components[0].teamcityProjectUrl)
    }

    @Test
    @DisplayName("null-id component: counted in scanned but silently skipped, no error counter")
    fun nullIdComponentSkipped() {
        val nullIdComponent = ComponentEntity(id = null, name = "no-id")
        val components = listOf(component(alice, "alpha"), nullIdComponent)
        val fetcher =
            stubFetcher(
                mapOf(
                    "alpha" to listOf(TcProject("Alpha_Build", "https://teamcity.example.com/project/Alpha_Build", hasCdReleaseBuild = false)),
                ),
            )
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(2, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(0, result.skippedNoMatch)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    @DisplayName("blank webUrl: treated as no-match")
    fun blankWebUrlIsNoMatch() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = stubFetcher(mapOf("alpha" to listOf(TcProject("Project_A", "", hasCdReleaseBuild = false))))
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val result = svc.resync()

        assertEquals(1, result.skippedNoMatch)
        assertEquals(0, result.updated)
        assertNull(components[0].teamcityProjectId)
    }

    @Test
    @DisplayName("blank base-url: resync throws IllegalStateException instead of returning all-NO_MATCH")
    fun blankBaseUrlThrows() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = TcProjectFetcher { _ -> emptyMap() }
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"), TeamcityProperties())

        val ex = assertThrows<IllegalStateException> { svc.resync() }
        assertTrue(ex.message!!.contains("teamcity.base-url"), "message should mention the config property")
    }

    @Test
    @DisplayName("blank base-url: throws even when the registry is empty (no silent scanned=0 on misconfiguration)")
    fun blankBaseUrlThrowsOnEmptyRegistry() {
        val fetcher = TcProjectFetcher { _ -> emptyMap() }
        val repo = StubComponentRepository(emptyList())
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"), TeamcityProperties())

        val ex = assertThrows<IllegalStateException> { svc.resync() }
        assertTrue(ex.message!!.contains("teamcity.base-url"), "message should mention the config property")
    }

    @Test
    @DisplayName("TC client failure on fetch: exception propagates")
    fun clientFailurePropagates() {
        val components = listOf(component(alice, "alpha"))
        val fetcher = TcProjectFetcher { _ -> throw RuntimeException("TC unavailable") }
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("admin"))

        val ex = assertThrows<RuntimeException> { svc.resync() }
        assertEquals("TC unavailable", ex.message)
    }

    @Test
    @DisplayName("mixed batch: counts every category in one pass")
    fun mixedBatch() {
        val components =
            listOf(
                component(alice, "alpha"),
                component(bob, "beta", tcId = "Beta_Build", tcUrl = "https://teamcity.example.com/project/Beta_Build"),
                component(carol, "gamma"),
            )
        val fetcher =
            stubFetcher(
                mapOf(
                    "alpha" to listOf(TcProject("Alpha_Build", "https://teamcity.example.com/project/Alpha_Build", hasCdReleaseBuild = false)),
                    "beta" to listOf(TcProject("Beta_Build", "https://teamcity.example.com/project/Beta_Build", hasCdReleaseBuild = false)),
                ),
            )
        val repo = StubComponentRepository(components)
        val publisher = RecordingPublisher()
        val svc = service(repo, fetcher, publisher, fixedUser("alice"))

        val result = svc.resync()

        assertEquals(3, result.scanned)
        assertEquals(1, result.updated)
        assertEquals(1, result.unchanged)
        assertEquals(1, result.skippedNoMatch)
        assertEquals(0, result.skippedAmbiguous)
        assertTrue(result.errors.isEmpty())
        assertEquals(1, publisher.events.filterIsInstance<AuditEvent>().size)
    }

    // -- Test doubles -------------------------------------------------------

    private fun fixedUser(name: String): CurrentUserResolver =
        object : CurrentUserResolver() {
            override fun currentUsername(): String = name
        }

    /**
     * No-op [TransactionTemplate] that runs the callback inline. The repo
     * stubs don't simulate JPA transaction semantics, so wrapping calls in
     * a real [PlatformTransactionManager] would only add ceremony.
     */
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
     * Hand-rolled ComponentRepository stub. Implements only the methods the
     * sync service uses (findByArchivedFalse, save). Other JpaRepository /
     * JpaSpecificationExecutor methods throw — keeps any accidental new
     * call-site visible during refactors.
     */
    @Suppress("TooManyFunctions")
    private class StubComponentRepository(
        private val components: List<ComponentEntity>,
    ) : ComponentRepository {
        val savedIds = mutableListOf<UUID?>()

        override fun findByName(name: String): ComponentEntity? = components.firstOrNull { it.name == name }

        override fun findByArchivedFalse(): List<ComponentEntity> = components.filter { !it.archived }

        override fun findByArchivedFalse(pageable: Pageable): Page<ComponentEntity> = unsupported()

        override fun findByNameWithVersions(name: String): ComponentEntity? = unsupported()

        override fun findByNameWithAllRelations(name: String): ComponentEntity? = unsupported()

        override fun findByIdWithAllRelations(id: UUID): ComponentEntity? = unsupported()

        override fun existsByName(name: String): Boolean = components.any { it.name == name }

        override fun findDistinctOwners(): List<String> = unsupported()

        override fun <S : ComponentEntity> save(entity: S): S {
            savedIds.add(entity.id)
            return entity
        }

        override fun <S : ComponentEntity> saveAll(entities: Iterable<S>): List<S> = unsupported()

        override fun <S : ComponentEntity> saveAndFlush(entity: S): S {
            savedIds.add(entity.id)
            return entity
        }

        override fun <S : ComponentEntity> saveAllAndFlush(entities: Iterable<S>): List<S> = unsupported()

        override fun findById(id: UUID): Optional<ComponentEntity> = Optional.ofNullable(components.firstOrNull { it.id == id })

        override fun existsById(id: UUID): Boolean = components.any { it.id == id }

        override fun findAll(): List<ComponentEntity> = components

        override fun findAllById(ids: Iterable<UUID>): List<ComponentEntity> = unsupported()

        override fun count(): Long = components.size.toLong()

        override fun deleteById(id: UUID) {
            unsupported()
        }

        override fun delete(entity: ComponentEntity) {
            unsupported()
        }

        override fun deleteAllById(ids: Iterable<UUID>) {
            unsupported()
        }

        override fun deleteAll(entities: Iterable<ComponentEntity>) {
            unsupported()
        }

        override fun deleteAll() {
            unsupported()
        }

        override fun deleteAllInBatch() {
            unsupported()
        }

        override fun deleteAllByIdInBatch(ids: Iterable<UUID>) {
            unsupported()
        }

        override fun deleteAllInBatch(entities: Iterable<ComponentEntity>) {
            unsupported()
        }

        override fun getOne(id: UUID): ComponentEntity = unsupported()

        @Suppress("DEPRECATION")
        override fun getById(id: UUID): ComponentEntity = unsupported()

        override fun getReferenceById(id: UUID): ComponentEntity = unsupported()

        override fun flush() {
            unsupported()
        }

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
