package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.support.designExampleProperties
import org.octopusden.octopus.components.registry.server.support.profileProperties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ComponentProfileCatalogTest {
    private class SwitchableSource(
        var properties: Map<String, String>,
    ) {
        fun read(): Map<String, String> = properties
    }

    @Test
    @DisplayName("Decision 3: an unusable configuration at construction throws with every problem in the message")
    fun unusableAtStartup() {
        val error =
            assertThrows(ComponentProfilesException::class.java) {
                ComponentProfileCatalog { profileProperties("broken", order = null, title = null) }
            }

        val message = requireNotNull(error.message)
        assertTrue(message.contains("broken.order: required"), message)
        assertTrue(message.contains("broken.title: required"), message)
    }

    @Test
    @DisplayName("Decision 3: no regular profile at construction throws")
    fun noRegularAtStartup() {
        val error = assertThrows(ComponentProfilesException::class.java) { ComponentProfileCatalog { emptyMap() } }

        assertTrue(requireNotNull(error.message).contains("at least one regular profile is required"))
    }

    @Test
    @DisplayName("Decision 3: a configuration that cannot be read at construction throws, saying why")
    fun unreadableAtStartup() {
        val error =
            assertThrows(ComponentProfilesException::class.java) {
                ComponentProfileCatalog { throw IllegalArgumentException("Could not resolve placeholder 'missing'") }
            }

        assertTrue(requireNotNull(error.message).contains("the configuration cannot be read: Could not resolve placeholder 'missing'"))
    }

    @Test
    @DisplayName("Decision 3: a broken template entry next to valid profiles does not stop construction")
    fun templateEntryAtStartup() {
        val catalog = ComponentProfileCatalog { designExampleProperties() + ("client-plugin.kind" to "template") }

        assertEquals(4, catalog.profiles().size)
    }

    @Test
    @DisplayName("Decision 2: a usable reload replaces the whole set")
    fun usableReloadReplaces() {
        val source = SwitchableSource(designExampleProperties())
        val catalog = ComponentProfileCatalog(source::read)
        source.properties = designExampleProperties() + ("solution.title" to "Solution (renamed)")

        val load = catalog.reload()

        assertTrue(load.usable)
        assertEquals("Solution (renamed)", catalog.profiles().single { it.id == "solution" }.title)
    }

    @Test
    @DisplayName("Decision 2: a reload with an invalid regular profile keeps the whole previous set, valid changes included")
    fun unusableReloadKeepsPrevious() {
        val source = SwitchableSource(designExampleProperties())
        val catalog = ComponentProfileCatalog(source::read)
        val before = catalog.profiles()
        source.properties = designExampleProperties() + ("solution.title" to "Solution (renamed)") + ("dmp-bundle.order" to "ten")

        val load = catalog.reload()

        assertFalse(load.usable)
        assertTrue(
            load.entries
                .single { it.id == "dmp-bundle" }
                .problems
                .any { it.startsWith("dmp-bundle.order:") },
        )
        assertEquals(before, catalog.profiles())
    }

    @Test
    @DisplayName("a reload whose configuration cannot be read keeps the previous set and reports why")
    fun unreadableReloadKeepsPrevious() {
        var fail = false
        val catalog =
            ComponentProfileCatalog {
                check(!fail) { "Could not resolve placeholder 'missing'" }
                designExampleProperties()
            }
        val before = catalog.profiles()
        fail = true

        val load = catalog.reload()

        assertFalse(load.usable)
        assertTrue(load.problems.single().contains("Could not resolve placeholder 'missing'"), "${load.problems}")
        assertEquals(before, catalog.profiles())
    }

    @Test
    @DisplayName("a corrected configuration reloaded after a failure is applied")
    fun fixedAfterFailure() {
        val source = SwitchableSource(designExampleProperties())
        val catalog = ComponentProfileCatalog(source::read)
        source.properties = designExampleProperties() + ("dmp-bundle.order" to "ten")
        catalog.reload()
        source.properties = designExampleProperties() + ("dmp-bundle.order" to "45")

        val load = catalog.reload()

        assertTrue(load.usable)
        assertEquals(45, catalog.profiles().single { it.id == "dmp-bundle" }.order)
    }

    @Test
    @DisplayName("Decision 4: two concurrent reloads each return the outcome of their own read")
    fun concurrentReloadsOwnOutcome() {
        val bothReading = CountDownLatch(2)
        val catalog =
            ComponentProfileCatalog {
                when (Thread.currentThread().name) {
                    "valid-reload" -> designExampleProperties() + ("solution.title" to "From valid reload")
                    "broken-reload" -> designExampleProperties() + ("dmp-bundle.order" to "ten")
                    else -> designExampleProperties()
                }
            }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val valid = executor.submit<ProfileLoad> { reloadAs(catalog, "valid-reload", bothReading) }
            val broken = executor.submit<ProfileLoad> { reloadAs(catalog, "broken-reload", bothReading) }

            assertTrue(valid.get(10, TimeUnit.SECONDS).usable)
            assertFalse(broken.get(10, TimeUnit.SECONDS).usable)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun reloadAs(
        catalog: ComponentProfileCatalog,
        name: String,
        bothReady: CountDownLatch,
    ): ProfileLoad {
        Thread.currentThread().name = name
        bothReady.countDown()
        bothReady.await(10, TimeUnit.SECONDS)
        return catalog.reload()
    }
}
