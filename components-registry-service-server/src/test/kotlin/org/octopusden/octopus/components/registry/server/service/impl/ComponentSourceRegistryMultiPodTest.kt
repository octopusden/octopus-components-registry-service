package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.nio.file.Paths
import java.util.UUID

/**
 * Review finding #2 — `ComponentSourceRegistryImpl.sourceCache` is a per-JVM
 * Caffeine cache with a 5-minute write-expiry. CRS is deployed behind a
 * horizontally-scaled pod set sharing one database: a `component_source`
 * write on pod A is invisible to pod B's routing decision (via `getSource`)
 * until the cached entry expires. This re-introduces the ghost that SYS-029
 * eliminated on a single-JVM setup, for up to five minutes per rename.
 *
 * `ComponentRoutingResolver.resolverFor` calls `getSource`, so this is the
 * hot read path the tests exercise. (`isDbComponent` goes straight to the
 * repository since the SYS-029 fix and is not affected.)
 *
 * The test writes directly through the `ComponentSourceRepository`
 * (simulating another pod) after the in-process registry has populated its
 * cache, then asserts the registry reflects the new state on the very next
 * `getSource` read.
 *
 * Under `ft-db` the default source is `db`, which collapses the before/after
 * outcomes for a missing row; the test therefore forces `default-source=git`
 * so a row's absence is observably different from its presence.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@TestPropertySource(properties = ["components-registry.default-source=git"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ComponentSourceRegistryMultiPodTest {
    @Autowired
    private lateinit var registry: ComponentSourceRegistry

    @Autowired
    private lateinit var repository: ComponentSourceRepository

    init {
        val testResourcesPath =
            Paths.get(ComponentSourceRegistryMultiPodTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("Review #2: getSource must reflect an external delete (pod B wipes a row pod A cached)")
    fun getSource_reflectsExternalDelete() {
        val name = "MULTI_POD_DEL_${UUID.randomUUID().toString().take(8)}"

        // Pod A: mark the component db-sourced and populate the local cache.
        registry.setComponentSource(name, "db")
        assertEquals("db", registry.getSource(name), "row just written by pod A should be db-sourced")

        // Pod B: delete the row directly, bypassing pod A's registry (and its cache).
        repository.deleteById(name)
        repository.flush()

        // Pod A's next read must see the delete. With the per-JVM Caffeine cache
        // this returns "db" for up to 5 minutes (stale hit) — the ghost.
        assertEquals(
            "git",
            registry.getSource(name),
            "stale Caffeine hit: pod A still routes $name as db after pod B deleted the row",
        )
    }

    @Test
    @DisplayName("Review #2: getSource must reflect an external insert (pod B adds a row pod A already consulted)")
    fun getSource_reflectsExternalInsert() {
        val name = "MULTI_POD_INS_${UUID.randomUUID().toString().take(8)}"

        // Pod A: read first, before any row exists — caches the default ("git").
        assertEquals("git", registry.getSource(name), "no row yet, default-source=git")

        // Pod B: insert the row directly.
        repository.save(ComponentSourceEntity(componentName = name, source = "db"))
        repository.flush()

        // Pod A's next read must see the insert.
        assertEquals(
            "db",
            registry.getSource(name),
            "stale Caffeine hit: pod A still reports default after pod B inserted a db row for $name",
        )
    }

    @Test
    @DisplayName("Review #2: getSource must reflect an external rename (pod B renames a row pod A cached)")
    fun getSource_reflectsExternalRename() {
        val oldName = "MULTI_POD_REN_OLD_${UUID.randomUUID().toString().take(8)}"
        val newName = "MULTI_POD_REN_NEW_${UUID.randomUUID().toString().take(8)}"

        // Pod A: prime both cache entries — old name cached as db, new as default.
        registry.setComponentSource(oldName, "db")
        assertEquals("db", registry.getSource(oldName))
        assertEquals("git", registry.getSource(newName))

        // Pod B: rewrite component_source directly (same atomic pattern as
        // ComponentSourceRegistryImpl.renameComponent, but bypassing pod A).
        val existing = repository.findById(oldName).orElseThrow()
        repository.delete(existing)
        repository.flush()
        repository.save(
            ComponentSourceEntity(
                componentName = newName,
                source = existing.source,
                migratedAt = existing.migratedAt,
                migratedBy = existing.migratedBy,
            ),
        )
        repository.flush()

        // Pod A must see the new state on the very next read.
        assertEquals(
            "git",
            registry.getSource(oldName),
            "stale Caffeine hit on old name — pod A still routes $oldName to the DB resolver after pod B's rename",
        )
        assertEquals(
            "db",
            registry.getSource(newName),
            "stale Caffeine hit on new name — pod A still treats $newName as default after pod B's rename",
        )
    }
}
