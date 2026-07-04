package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewRequest

/**
 * SYS-059: stateless renderer for the Jira version-format preview.
 *
 * Given ad-hoc formats (base + per-range overrides) and an input version, it
 * resolves the applicable range, materialises the effective
 * `ComponentVersionFormat`, and reuses the persisted-path render seam
 * (`Mapper<JiraComponentVersion, DetailedComponentVersion>`) so preview output
 * is byte-for-byte the same as `detailed-version` for the same effective config
 * and version. No persistence, no component lookup.
 */
interface VersionPreviewService {
    fun preview(request: VersionPreviewRequest): DetailedComponentVersion
}
