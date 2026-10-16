package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.support.TEMPLATE_ID
import org.octopusden.octopus.components.registry.server.support.designExampleProperties
import org.octopusden.octopus.components.registry.server.support.profileProperties
import org.octopusden.octopus.components.registry.server.support.standaloneTemplateProperties
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
    @DisplayName("Decision 3: no profile subtree at construction starts with no profiles")
    fun absentAtStartup() {
        val catalog = ComponentProfileCatalog { emptyMap() }

        assertEquals(emptyList<Any>(), catalog.profiles())
    }

    @Test
    @DisplayName("Decision 3: a subtree with only template entries at construction throws, a regular profile being required")
    fun onlyTemplatesAtStartup() {
        val error =
            assertThrows(ComponentProfilesException::class.java) { ComponentProfileCatalog { mapOf("ww-modpack.kind" to "template") } }

        assertTrue(requireNotNull(error.message).contains("at least one regular profile is required"))
    }

    @Test
    @DisplayName("Decision 2: a snapshot keeps one revision's profiles, templates, entries and keys together across a reload")
    fun snapshotIsOneRevision() {
        val source = SwitchableSource(designExampleProperties())
        val catalog = ComponentProfileCatalog(source::read)
        val before = catalog.snapshot()
        source.properties = designExampleProperties() + standaloneTemplateProperties()

        catalog.reload()

        assertEquals(emptyList<Any>(), before.templates)
        assertFalse(before.entries.any { it.id == TEMPLATE_ID })
        assertFalse(TEMPLATE_ID in before.raw)
        val after = catalog.snapshot()
        assertEquals(listOf(TEMPLATE_ID), after.templates.map { it.id })
        assertTrue(after.entries.any { it.id == TEMPLATE_ID })
        assertTrue(TEMPLATE_ID in after.raw)
        assertEquals(before.profiles, after.profiles)
    }

    @Test
    @DisplayName("Decision 11: the configuration version is the one the entries in use were loaded from, kept by a reload that fails")
    fun versionOfEntriesInUse() {
        val source = SwitchableSource(designExampleProperties())
        var version = "v1"
        val catalog = ComponentProfileCatalog(source::read, { emptyMap() }, { version })

        version = "v2"
        source.properties = designExampleProperties() + profileProperties("broken", order = null)
        catalog.reload()

        assertEquals("v1", catalog.snapshot().configVersion)
        assertFalse(catalog.snapshot().lastLoad.usable)

        version = "v3"
        source.properties = designExampleProperties()
        catalog.reload()

        assertEquals("v3", catalog.snapshot().configVersion)
    }

    @Test
    @DisplayName("Decision 2: a reload after the subtree is removed applies no profiles")
    fun removedSubtreeReloadAppliesEmpty() {
        val source = SwitchableSource(designExampleProperties())
        val catalog = ComponentProfileCatalog(source::read)
        source.properties = emptyMap()

        val load = catalog.reload()

        assertTrue(load.usable)
        assertEquals(emptyList<Any>(), catalog.profiles())
    }

    @Test
    @DisplayName("a reload after the subtree is removed applies no templates either")
    fun removedSubtreeReloadClearsTemplates() {
        val source = SwitchableSource(designExampleProperties() + standaloneTemplateProperties())
        val catalog = ComponentProfileCatalog(source::read)
        assertEquals(listOf(TEMPLATE_ID), catalog.templates().map { it.id })
        source.properties = emptyMap()

        assertTrue(catalog.reload().usable)
        assertEquals(emptyList<Any>(), catalog.templates())
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
    @DisplayName("Decision 3: a broken template entry next to valid profiles does not stop construction; it is not offered")
    fun templateEntryAtStartup() {
        val catalog = ComponentProfileCatalog { designExampleProperties() + ("client-plugin.kind" to "template") }

        assertEquals(4, catalog.profiles().size)
        assertEquals(emptyList<Any>(), catalog.templates())
    }

    @Test
    @DisplayName("Decision 1: a usable load swaps profiles and templates together")
    fun usableLoadSwapsBoth() {
        val source = SwitchableSource(designExampleProperties())
        val catalog = ComponentProfileCatalog(source::read)
        source.properties = designExampleProperties() + ("solution.title" to "Solution (renamed)") + standaloneTemplateProperties()

        catalog.reload()

        assertEquals("Solution (renamed)", catalog.profiles().single { it.id == "solution" }.title)
        assertEquals(listOf("client-plugin"), catalog.templates().map { it.id })
        assertEquals("client-plugin", catalog.template("client-plugin")?.id)
    }

    @Test
    @DisplayName("Decision 1: a load with an invalid regular profile keeps both, even a template whose change was valid")
    fun invalidRegularKeepsBoth() {
        val source = SwitchableSource(designExampleProperties() + standaloneTemplateProperties())
        val catalog = ComponentProfileCatalog(source::read)
        source.properties =
            designExampleProperties() + ("dmp-bundle.order" to "ten") + standaloneTemplateProperties() +
            ("client-plugin.title" to "Client plugin (renamed)")

        catalog.reload()

        assertEquals("Client plugin", catalog.templates().single().title)
    }

    @Test
    @DisplayName("Decision 1: the catalog keeps the last load's outcome, even an unusable one")
    fun keepsLastLoad() {
        val source = SwitchableSource(designExampleProperties())
        val catalog = ComponentProfileCatalog(source::read)
        source.properties = designExampleProperties() + ("dmp-bundle.order" to "ten")

        val load = catalog.reload()

        assertEquals(load, catalog.lastLoad())
        assertFalse(catalog.lastLoad().usable)
    }

    @Test
    @DisplayName("Decision 11: the catalog keeps each entry's raw keys for the entries in use, failed ones included")
    fun keepsRawKeys() {
        val catalog = ComponentProfileCatalog { designExampleProperties() + ("client-plugin.kind" to "template") }

        assertEquals(mapOf("kind" to "template"), catalog.snapshot().raw["client-plugin"])
        assertEquals("Title of solution", catalog.snapshot().raw.getValue("solution")["title"])
    }

    @Test
    @DisplayName("Decision 4: the defaults the catalog is given count for a template's required fields")
    fun defaultsReachTheParser() {
        val properties = designExampleProperties() + standaloneTemplateProperties().filterKeys { !it.endsWith("vcsEntries[0].branch") }

        assertEquals(emptyList<Any>(), ComponentProfileCatalog({ properties }).templates())
        val withDefault = ComponentProfileCatalog({ properties }, { mapOf("baseConfiguration.vcsEntries[0].branch" to "main") })
        assertEquals(listOf("client-plugin"), withDefault.templates().map { it.id })
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
