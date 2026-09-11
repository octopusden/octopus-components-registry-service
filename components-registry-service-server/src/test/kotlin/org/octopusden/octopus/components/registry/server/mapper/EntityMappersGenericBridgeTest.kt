package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionGenericArtifactEntity
import java.util.UUID

class EntityMappersGenericBridgeTest {
    private fun cfg(): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = ComponentEntity(id = UUID.randomUUID(), componentKey = "test"),
            versionRange = "(,0),[0,)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

    private fun generic(
        parent: ComponentConfigurationEntity,
        path: String,
        sortOrder: Int,
    ): DistributionGenericArtifactEntity =
        DistributionGenericArtifactEntity(
            id = UUID.randomUUID(),
            componentConfiguration = parent,
            path = path,
            sortOrder = sortOrder,
        )

    @Test
    @DisplayName("SYS-094: empty generic list → Distribution.generic() is null")
    fun `SYS-094 empty generic list yields null`() {
        val d = buildDistribution(
            explicit = true,
            external = true,
            mavenArtifacts = emptyList(),
            fileUrlArtifacts = emptyList(),
            dockerImages = emptyList(),
            packages = emptyList(),
            genericArtifacts = emptyList(),
            securityGroups = emptyList(),
        )
        assertNull(d?.generic())
    }

    @Test
    @DisplayName("SYS-094: single generic path → Distribution.generic() equals that path")
    fun `SYS-094 single generic path passes through`() {
        val parent = cfg()
        val d = buildDistribution(
            explicit = null,
            external = null,
            mavenArtifacts = emptyList(),
            fileUrlArtifacts = emptyList(),
            dockerImages = emptyList(),
            packages = emptyList(),
            genericArtifacts = listOf(
                generic(parent, "releases/foo/1.0.0/foo.tar.gz", 0),
            ),
            securityGroups = emptyList(),
        )
        assertEquals("releases/foo/1.0.0/foo.tar.gz", d?.generic())
    }

    @Test
    @DisplayName("SYS-094: many generic paths → Distribution.generic() is comma-joined in sortOrder")
    fun `SYS-094 many generic paths comma joined in sort order`() {
        val parent = cfg()
        val d = buildDistribution(
            explicit = null,
            external = null,
            mavenArtifacts = emptyList(),
            fileUrlArtifacts = emptyList(),
            dockerImages = emptyList(),
            packages = emptyList(),
            genericArtifacts = listOf(
                generic(parent, "releases/foo/2.0.0/foo.tar.gz", 1),
                generic(parent, "releases/foo/1.0.0/foo.tar.gz", 0),
            ),
            securityGroups = emptyList(),
        )
        assertEquals(
            "releases/foo/1.0.0/foo.tar.gz,releases/foo/2.0.0/foo.tar.gz",
            d?.generic(),
        )
    }

    @Test
    @DisplayName("SYS-094: generic-only distribution with no explicit/external flags → non-null Distribution surfaces the URL")
    fun `SYS-094 generic-only distribution produces non-null result`() {
        val parent = cfg()
        val d = buildDistribution(
            explicit = null,
            external = null,
            mavenArtifacts = emptyList(),
            fileUrlArtifacts = emptyList(),
            dockerImages = emptyList(),
            packages = emptyList(),
            genericArtifacts = listOf(generic(parent, "releases/only-generic/1.0.0/only-generic.tar.gz", 0)),
            securityGroups = emptyList(),
        )
        assertEquals("releases/only-generic/1.0.0/only-generic.tar.gz", d?.generic())
    }
}
