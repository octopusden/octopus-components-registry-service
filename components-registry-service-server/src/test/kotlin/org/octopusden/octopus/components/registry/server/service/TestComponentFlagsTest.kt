package org.octopusden.octopus.components.registry.server.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.springframework.beans.factory.ObjectProvider

/** SYS-099: where the v1–v3 `testComponent` comes from, per component source. */
class TestComponentFlagsTest {
    private val repository = mock(ComponentRepository::class.java)
    private val sourceRegistry = mock(ComponentSourceRegistry::class.java)
    private val resolver = mock(ComponentRegistryResolver::class.java)
    private val properties =
        ComponentsRegistryProperties(
            groovyPath = "g",
            workDir = "w",
            mainGroovyFile = "m",
            dependencyMappingFile = null,
            vcs = ComponentsRegistryProperties.VcsSettings(root = null, username = null),
            versionName = ComponentsRegistryProperties.VersionNameSettings("s", "s", "m"),
            copyrightPath = null,
            defaultSource = "git",
        )

    @Suppress("UNCHECKED_CAST")
    private fun <T> provider(bean: T?): ObjectProvider<T> =
        (mock(ObjectProvider::class.java) as ObjectProvider<T>).also { `when`(it.getIfAvailable()).thenReturn(bean) }

    private fun flags(withDb: Boolean) =
        TestComponentFlags(
            provider(if (withDb) repository else null),
            provider(if (withDb) sourceRegistry else null),
            resolver,
            properties,
        )

    private fun component(
        key: String,
        vararg labels: String,
    ) = ComponentV2(key, null, "owner").apply { this.labels = labels.toSet() }

    @Test
    @DisplayName("SYS-099: no-db mode derives the flag from the test-component label, without a DB read")
    fun `SYS-099 no-db mode is label-derived`() {
        val labelled = component("labelled", "test-component")
        val plain = component("plain", "java")
        flags(withDb = false).mark(listOf(labelled, plain))

        assertTrue(labelled.testComponent)
        assertFalse(plain.testComponent)
        verify(repository, never()).findTestComponentKeys()
    }

    @Test
    @DisplayName("SYS-099: a v1 list of unlabelled git components makes no per-component resolver lookups")
    fun `SYS-099 unlabelled list components are not looked up one by one`() {
        val components = (1..LIST_SIZE).map { component("plain-$it") }
        `when`(sourceRegistry.getDbComponentNames()).thenReturn(emptySet())
        `when`(sourceRegistry.getGitComponentNames()).thenReturn(components.map { it.id }.toSet())

        flags(withDb = true).mark(components)

        assertTrue(components.none { it.testComponent })
        verify(resolver, never()).getComponentById(anyString())
        verify(sourceRegistry, never()).getSource(anyString())
    }

    @Test
    @DisplayName("SYS-099: a label-less DTO (find-by-artifact) of a git component reads the label from the resolved model")
    fun `SYS-099 find-by-artifact git component reads the resolved model`() {
        val config = mock(EscrowModuleConfig::class.java)
        `when`(config.labels).thenReturn(setOf("test-component"))
        val module = EscrowModule().apply { moduleName = "gitcomp" }
        module.componentLevelConfiguration = config
        `when`(resolver.getComponentById("gitcomp")).thenReturn(module)
        `when`(sourceRegistry.getSource("gitcomp")).thenReturn("git")

        val found = VersionedComponent("gitcomp", null, "1.0", "owner")
        flags(withDb = true).mark(listOf(found))

        assertTrue(found.testComponent)
        verify(repository, never()).findTestComponentKeys()
    }

    @Test
    @DisplayName("SYS-099: a DB component reads the cached flagged-key set once; eviction reloads it")
    fun `SYS-099 db components use the cached key set`() {
        `when`(sourceRegistry.getDbComponentNames()).thenReturn(setOf("dbtest", "dbreal"))
        `when`(sourceRegistry.getGitComponentNames()).thenReturn(emptySet())
        `when`(repository.findTestComponentKeys()).thenReturn(listOf("dbtest"))
        val flags = flags(withDb = true)

        // The label is ignored for a DB component: the DB flag is the source of truth.
        val test = component("dbtest")
        val real = component("dbreal", "test-component")
        flags.mark(listOf(test, real))
        flags.mark(listOf(component("dbtest"), component("dbreal")))
        assertTrue(test.testComponent)
        assertFalse(real.testComponent)
        verify(repository, times(1)).findTestComponentKeys()

        flags.evictAfterCommit()
        flags.mark(listOf(component("dbtest"), component("dbreal")))
        verify(repository, times(2)).findTestComponentKeys()
    }

    private companion object {
        const val LIST_SIZE = 2000
    }
}
