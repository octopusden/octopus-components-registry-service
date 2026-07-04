package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewFormats
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewOverride
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewRequest
import org.octopusden.octopus.components.registry.server.mapper.JiraComponentVersionToDetailedComponentVersionMapper
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames

/**
 * SYS-059: unit coverage for the stateless version-format preview renderer.
 *
 * Uses the canonical test `VersionNames("serviceCBranch", "serviceC", "minorC")`
 * and the real `JiraComponentVersionToDetailedComponentVersionMapper`, so these
 * assertions prove preview output equals the persisted `detailed-version` render
 * for the same effective config + version.
 */
class VersionPreviewServiceImplTest {
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val mapper =
        JiraComponentVersionToDetailedComponentVersionMapper(
            JiraComponentVersionFormatter(versionNames),
            NumericVersionFactory(versionNames),
        )
    private val service = VersionPreviewServiceImpl(versionNames, mapper)

    @Test
    @DisplayName("SYS-059: base formats render the six coordinates for the input version")
    fun `SYS-059 base render`() {
        val result =
            service.preview(
                VersionPreviewRequest(
                    version = "1.2.3",
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service",
                            buildVersionFormat = "\$major.\$minor.\$service-\$build",
                            lineVersionFormat = "\$major.\$minor",
                        ),
                ),
            )

        assertEquals("1.2", result.minorVersion.version)
        assertEquals("1.2", result.minorVersion.jiraVersion)
        assertEquals("1.2", result.lineVersion.version)
        assertEquals("1.2.3", result.releaseVersion.version)
        assertEquals("1.2.3", result.releaseVersion.jiraVersion)
        assertEquals("1.2.3-0", result.buildVersion.version)
        assertEquals("1.2.3_RC", result.rcVersion.version)
        assertEquals("1.2.3_RC", result.rcVersion.jiraVersion)
        assertNull(result.hotfixVersion)
    }

    @Test
    @DisplayName("SYS-059: a version inside an override range uses the override format, outside uses base")
    fun `SYS-059 override range selection`() {
        val request = { version: String ->
            VersionPreviewRequest(
                version = version,
                base =
                    VersionPreviewFormats(
                        minorVersionFormat = "\$major.\$minor",
                        releaseVersionFormat = "\$major.\$minor.\$service",
                        lineVersionFormat = "\$major.\$minor",
                    ),
                overrides =
                    listOf(
                        VersionPreviewOverride(
                            versionRange = "(,1.0.107)",
                            // Same shape as base but zero-padded service — a clear,
                            // round-tripping difference between override and base.
                            releaseVersionFormat = "\$major.\$minor.\$service02",
                        ),
                    ),
            )
        }

        // 1.0.5 is inside (,1.0.107) → override release format (padded service).
        assertEquals("1.0.05", service.preview(request("1.0.5")).releaseVersion.version)
        // 2.0.5 is outside → base release format (unpadded service).
        assertEquals("2.0.5", service.preview(request("2.0.5")).releaseVersion.version)
    }

    @Test
    @DisplayName("SYS-059: custom versionPrefix renders jiraVersion with the wrapper; version stays unwrapped")
    fun `SYS-059 custom prefix render`() {
        val result =
            service.preview(
                VersionPreviewRequest(
                    version = "1.2.3",
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service",
                            versionPrefix = "acme",
                            versionFormat = "\$versionPrefix-\$baseVersionFormat",
                        ),
                ),
            )

        // The mapper's `.version` is the raw template render (no wrapper); the
        // `.jiraVersion` is the customer-formatted coordinate (with prefix).
        // This dichotomy is exactly what the saved `detailed-version` produces.
        assertEquals("1.2.3", result.releaseVersion.version)
        assertEquals("acme-1.2.3", result.releaseVersion.jiraVersion)
    }

    @Test
    @DisplayName("SYS-059: zero-padding is driven purely by the format template (no buildSystem input)")
    fun `SYS-059 padding is template-driven`() {
        val result =
            service.preview(
                VersionPreviewRequest(
                    version = "1.2.3",
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service02",
                        ),
                ),
            )

        // $service02 pads the service segment to two digits — purely from the template.
        assertEquals("1.2.03", result.releaseVersion.version)
    }

    @Test
    @DisplayName("SYS-059: a hotfix format renders a hotfix coordinate; its absence renders none")
    fun `SYS-059 hotfix coordinate`() {
        val withHotfix =
            service.preview(
                VersionPreviewRequest(
                    version = "1.2.3.4.5",
                    // Hotfix eligibility is caller-supplied (VCS-derived), like the persisted path.
                    hotfixEnabled = true,
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service",
                            hotfixVersionFormat = "\$major.\$minor.\$service-\$fix-\$build",
                        ),
                ),
            )
        assertNotNull(withHotfix.hotfixVersion)
        assertEquals("1.2.3-4-5", withHotfix.hotfixVersion!!.version)

        val withoutHotfix =
            service.preview(
                VersionPreviewRequest(
                    version = "1.2.3.4.5",
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service",
                        ),
                ),
            )
        assertNull(withoutHotfix.hotfixVersion)

        // A hotfix FORMAT alone does not render a hotfix coordinate — eligibility
        // is VCS-derived (hotfixEnabled), exactly as detailed-version behaves.
        val formatButDisabled =
            service.preview(
                VersionPreviewRequest(
                    version = "1.2.3.4.5",
                    hotfixEnabled = false,
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service",
                            hotfixVersionFormat = "\$major.\$minor.\$service-\$fix-\$build",
                        ),
                ),
            )
        assertNull(formatButDisabled.hotfixVersion)

        // Hotfix-eligible but no format supplied: the persisted path stores "" and
        // renders a degenerate empty hotfix coordinate — preview matches that.
        val enabledNoFormat =
            service.preview(
                VersionPreviewRequest(
                    version = "1.2.3.4.5",
                    hotfixEnabled = true,
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service",
                        ),
                ),
            )
        assertNotNull(enabledNoFormat.hotfixVersion)
        assertEquals("", enabledNoFormat.hotfixVersion!!.version)
    }

    @Test
    @DisplayName("SYS-059: a version matching none of the supplied formats is not-found (404), mirroring detailed-version")
    fun `SYS-059 unmatched version is not found`() {
        assertThrows<NotFoundException> {
            service.preview(
                VersionPreviewRequest(
                    // A single numeric segment matches neither the 2-var minor
                    // nor the 3-var release format → normalizeVersion returns null.
                    version = "5",
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service",
                        ),
                ),
            )
        }
    }

    @Test
    @DisplayName("SYS-059: a blank or non-numeric version is rejected (IllegalArgumentException → 400)")
    fun `SYS-059 invalid version rejected`() {
        val base = VersionPreviewFormats(minorVersionFormat = "\$major.\$minor", releaseVersionFormat = "\$major.\$minor.\$service")
        assertThrows<IllegalArgumentException> {
            service.preview(VersionPreviewRequest(version = "", base = base))
        }
        assertThrows<IllegalArgumentException> {
            service.preview(VersionPreviewRequest(version = "   ", base = base))
        }
        assertThrows<IllegalArgumentException> {
            service.preview(VersionPreviewRequest(version = "not-a-version", base = base))
        }
    }

    @Test
    @DisplayName("SYS-059: a malformed override range is rejected (IllegalArgumentException → 400)")
    fun `SYS-059 malformed override range rejected`() {
        assertThrows<IllegalArgumentException> {
            service.preview(
                VersionPreviewRequest(
                    version = "1.0.50",
                    base = VersionPreviewFormats(minorVersionFormat = "\$major.\$minor", releaseVersionFormat = "\$major.\$minor.\$service"),
                    overrides = listOf(VersionPreviewOverride(versionRange = "not-a-range", releaseVersionFormat = "\$major.\$minor")),
                ),
            )
        }
    }

    @Test
    @DisplayName("SYS-059: versionPrefix without versionFormat is rejected (IllegalArgumentException → 400)")
    fun `SYS-059 prefix without wrapper format rejected`() {
        assertThrows<IllegalArgumentException> {
            service.preview(
                VersionPreviewRequest(
                    version = "1.2.3",
                    base =
                        VersionPreviewFormats(
                            minorVersionFormat = "\$major.\$minor",
                            releaseVersionFormat = "\$major.\$minor.\$service",
                            versionPrefix = "acme",
                        ),
                ),
            )
        }
    }
}
