package org.octopusden.octopus.components.registry.server.service.rms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.dto.v4.ActualDisagreement
import org.octopusden.octopus.components.registry.server.dto.v4.ActualRange
import org.octopusden.octopus.components.registry.server.dto.v4.BuildAspectResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentConfigurationResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ConfigurationRowType
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser
import java.time.Instant
import java.util.UUID

class RegisteredBuildParametersMapperDetailForTest {
    private val intCompare: (String, String) -> Int = { a, b -> a.toInt().compareTo(b.toInt()) }

    private fun response(
        name: String = "comp-a",
        buildSystem: String? = "MAVEN",
        overrides: List<ComponentConfigurationResponse> = emptyList(),
    ): ComponentDetailResponse {
        val base =
            ComponentConfigurationResponse(
                id = UUID.randomUUID(),
                versionRange = "(,0),[0,)",
                rowType = ConfigurationRowType.BASE,
                overriddenAttribute = null,
                isSyntheticBase = false,
                build = buildSystem?.let { BuildAspectResponse(buildSystem = it) },
            )
        return ComponentDetailResponse(
            id = UUID.randomUUID(),
            name = name,
            displayName = null,
            componentOwner = null,
            productType = null,
            systems = emptySet(),
            clientCode = null,
            archived = false,
            solution = null,
            parentComponentName = null,
            version = 0,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            configurations = listOf(base) + overrides,
        )
    }

    private fun override(
        versionRange: String,
        javaVersion: String? = null,
        mavenVersion: String? = null,
    ) = ComponentConfigurationResponse(
        id = UUID.randomUUID(),
        versionRange = versionRange,
        rowType = ConfigurationRowType.SCALAR_OVERRIDE,
        overriddenAttribute = if (javaVersion != null) "build.javaVersion" else "build.mavenVersion",
        isSyntheticBase = false,
        build = BuildAspectResponse(javaVersion = javaVersion, mavenVersion = mavenVersion),
    )

    @Test
    @DisplayName("a non-Maven/Gradle component carries no ACTUAL data at all")
    fun `a non-Maven-Gradle component is null`() {
        val result =
            RegisteredBuildParametersMapper.detailFor(
                response(buildSystem = "GOLANG"),
                mapOf("comp-a" to ComponentBuildRanges(emptyList(), emptyList())),
                intCompare,
            )
        assertNull(result)
    }

    @Test
    @DisplayName("a component with no BASE build system at all is null")
    fun `a component with no build system is null`() {
        val result = RegisteredBuildParametersMapper.detailFor(response(buildSystem = null), emptyMap(), intCompare)
        assertNull(result)
    }

    @Test
    @DisplayName("a component with no entry in the report is marked actualDataUnavailable, never having been swept")
    fun `no report entry marks actualDataUnavailable`() {
        val result = RegisteredBuildParametersMapper.detailFor(response(), emptyMap(), intCompare)
        assertEquals(true, result?.actualDataUnavailable)
        assertTrue(result!!.javaActualRanges.isEmpty())
        assertTrue(result.javaWarnings.isEmpty())
    }

    @Test
    @DisplayName("a component with a report entry gets its ACTUAL ranges, and is not marked unavailable")
    fun `a swept component gets its ACTUAL ranges`() {
        val ranges =
            ComponentBuildRanges(
                javaRanges = listOf(BuildRangeCollapser.Run("[1,)", "17")),
                mavenRanges = listOf(BuildRangeCollapser.Run("[1,)", "3.3.6")),
            )
        val result = RegisteredBuildParametersMapper.detailFor(response(), mapOf("comp-a" to ranges), intCompare)
        assertEquals(false, result?.actualDataUnavailable)
        assertEquals(listOf(ActualRange("[1,)", "17")), result?.javaActualRanges)
        assertEquals(listOf(ActualRange("[1,)", "3.3.6")), result?.mavenActualRanges)
    }

    @Test
    @DisplayName("a matching override produces no warning")
    fun `a matching override produces no warning`() {
        val ranges = ComponentBuildRanges(listOf(BuildRangeCollapser.Run("[1,)", "17")), emptyList())
        val result =
            RegisteredBuildParametersMapper.detailFor(
                response(overrides = listOf(override("[1,3)", javaVersion = "17"))),
                mapOf("comp-a" to ranges),
                intCompare,
            )
        assertTrue(result!!.javaWarnings.isEmpty())
    }

    @Test
    @DisplayName("a disagreeing override produces a warning naming the intersecting sub-range")
    fun `a disagreeing override produces a warning`() {
        val ranges = ComponentBuildRanges(listOf(BuildRangeCollapser.Run("[3,)", "21")), emptyList())
        val result =
            RegisteredBuildParametersMapper.detailFor(
                response(overrides = listOf(override("[1,5)", javaVersion = "11"))),
                mapOf("comp-a" to ranges),
                intCompare,
            )
        assertEquals(listOf(ActualDisagreement("[3,5)", "21")), result?.javaWarnings)
    }

    @Test
    @DisplayName("the DEFAULT (BASE) row's own configured value is checked too, not just overrides")
    fun `the BASE row's own value is checked against ACTUAL`() {
        val base =
            ComponentConfigurationResponse(
                id = UUID.randomUUID(),
                versionRange = "(,0),[0,)",
                rowType = ConfigurationRowType.BASE,
                overriddenAttribute = null,
                isSyntheticBase = false,
                build = BuildAspectResponse(buildSystem = "MAVEN", javaVersion = "11"),
            )
        val response =
            ComponentDetailResponse(
                id = UUID.randomUUID(),
                name = "comp-a",
                displayName = null,
                componentOwner = null,
                productType = null,
                systems = emptySet(),
                clientCode = null,
                archived = false,
                solution = null,
                parentComponentName = null,
                version = 0,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                configurations = listOf(base),
            )
        val ranges = ComponentBuildRanges(listOf(BuildRangeCollapser.Run("[1,)", "21")), emptyList())
        val result = RegisteredBuildParametersMapper.detailFor(response, mapOf("comp-a" to ranges), intCompare)
        assertEquals(listOf(ActualDisagreement("[1,)", "21")), result?.javaWarnings)
    }

    @Test
    @DisplayName("java and maven are gated independently — a Java disagreement never produces a Maven warning")
    fun `java and maven warnings are independent`() {
        val ranges =
            ComponentBuildRanges(
                javaRanges = listOf(BuildRangeCollapser.Run("[1,)", "21")),
                mavenRanges = emptyList(),
            )
        val result =
            RegisteredBuildParametersMapper.detailFor(
                response(overrides = listOf(override("[1,3)", javaVersion = "11"))),
                mapOf("comp-a" to ranges),
                intCompare,
            )
        assertTrue(result!!.javaWarnings.isNotEmpty())
        assertTrue(result.mavenWarnings.isEmpty())
    }
}
