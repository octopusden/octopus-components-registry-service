package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.octopus.components.registry.server.dto.v4.ConfigurationRowType
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * MIG-047 P1-B: V4 field-override projection must handle the synthetic
 * `group-artifact-pattern` MARKER name.
 *
 * Background:
 *   `ComponentManagementServiceImpl.listFieldOverrides` projects every
 *   SCALAR_OVERRIDE / MARKER row through `ComponentConfigurationEntity.toFieldOverrideResponse()`.
 *   The marker branch dispatches via `toMarkerChildrenPayload()`, whose
 *   `when (overriddenAttribute)` recognises only `MarkerAttributes.ALL` values
 *   and falls through to `error("Marker row has unknown overriddenAttribute …")`
 *   for anything else.
 *
 *   Once the MIG-047 import path persists a `GROUP_ARTIFACT_PATTERN` marker,
 *   `GET /rest/api/4/components/{id}/field-overrides` for that component throws
 *   `IllegalStateException`, breaking the V4 admin UI for the entire component.
 *
 * Pre-fix behaviour (RED):
 *   `toFieldOverrideResponse()` throws `IllegalStateException` on a synthetic
 *   in-memory entity whose `overriddenAttribute = "group-artifact-pattern"`.
 *
 * Post-fix behaviour (GREEN):
 *   The mapper recognises the marker and projects it the same way as
 *   `DISTRIBUTION_MAVEN` (the underlying child collection — `mavenArtifacts` —
 *   is shared between the two markers; see `ImportServiceImpl.attachMavenArtifactsFromGroupArtifact`).
 *
 * Pure in-memory test — no Spring context, no DB. Synthetic ID + collections.
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class MIG047V4FieldOverrideMapperTest {

    @Test
    @DisplayName(
        "MIG-047 P1-B: toFieldOverrideResponse must not throw on GROUP_ARTIFACT_PATTERN marker; " +
            "must project the synthetic mavenArtifacts children",
    )
    fun mig047_p1b_groupArtifactPatternMarkerProjectsAsMavenArtifacts() {
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "alpha-fixture")
        val markerRow =
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = component,
                versionRange = "[1.1,)",
                overriddenAttribute = MarkerAttributes.GROUP_ARTIFACT_PATTERN,
                rowType = "MARKER",
            )
        markerRow.mavenArtifacts.addAll(
            listOf(
                DistributionMavenArtifactEntity(
                    id = UUID.randomUUID(),
                    componentConfiguration = markerRow,
                    groupPattern = "com.example.ic",
                    artifactPattern = "core-a",
                    extension = null,
                    classifier = null,
                    sortOrder = 0,
                ),
                DistributionMavenArtifactEntity(
                    id = UUID.randomUUID(),
                    componentConfiguration = markerRow,
                    groupPattern = "com.example.ic",
                    artifactPattern = "core-b",
                    extension = null,
                    classifier = null,
                    sortOrder = 1,
                ),
            ),
        )

        // Pre-fix this call throws IllegalStateException from the `else` branch
        // of toMarkerChildrenPayload(). Post-fix it returns a populated response.
        val response = markerRow.toFieldOverrideResponse()

        assertNotNull(response, "Mapper must produce a FieldOverrideResponse, not throw")
        assertEquals(ConfigurationRowType.MARKER, response.rowType)
        assertEquals(MarkerAttributes.GROUP_ARTIFACT_PATTERN, response.overriddenAttribute)
        assertEquals("[1.1,)", response.versionRange)
        assertNull(response.value, "Marker rows must not carry a scalar value")

        val payload = response.markerChildren
        assertNotNull(payload, "Marker payload must be present")
        val artifacts = payload!!.mavenArtifacts
        assertNotNull(artifacts, "GROUP_ARTIFACT_PATTERN payload must surface mavenArtifacts (same shape as DISTRIBUTION_MAVEN)")
        assertEquals(2, artifacts!!.size, "Both synthetic maven artifact rows must round-trip")
        assertEquals("core-a", artifacts[0].artifactPattern)
        assertEquals("com.example.ic", artifacts[0].groupPattern)
        assertEquals("core-b", artifacts[1].artifactPattern)
    }
}
