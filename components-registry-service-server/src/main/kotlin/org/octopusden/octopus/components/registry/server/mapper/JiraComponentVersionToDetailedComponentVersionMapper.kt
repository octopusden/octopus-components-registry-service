package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.core.dto.ComponentRegistryVersion
import org.octopusden.octopus.components.registry.core.dto.ComponentVersionType
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.server.util.formatVersion
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory
import org.springframework.stereotype.Component

@Component
class JiraComponentVersionToDetailedComponentVersionMapper(
        private val jiraComponentVersionFormatter: JiraComponentVersionFormatter,
        private val versionNumericVersionFactory: NumericVersionFactory
) : Mapper<JiraComponentVersion, DetailedComponentVersion> {

    override fun convert(src: JiraComponentVersion): DetailedComponentVersion {
        val componentVersionFormat = src.component.componentVersionFormat
        return DetailedComponentVersion(
            src.component.displayName ?: src.componentVersion.componentName,
            ComponentRegistryVersion(
                ComponentVersionType.MINOR,
                componentVersionFormat.majorVersionFormat.formatVersion(versionNumericVersionFactory, src.version),
                src.majorVersion
            ),
            ComponentRegistryVersion(
                ComponentVersionType.LINE,
                componentVersionFormat.lineVersionFormat.formatVersion(versionNumericVersionFactory, src.version),
                jiraComponentVersionFormatter.getLineVersion(src)
            ),
            ComponentRegistryVersion(
                ComponentVersionType.BUILD,
                componentVersionFormat.buildVersionFormat.formatVersion(versionNumericVersionFactory, src.version),
                src.buildVersion
            ),
            ComponentRegistryVersion(
                ComponentVersionType.RC,
                componentVersionFormat.releaseVersionFormat.formatVersion(versionNumericVersionFactory, src.version) + JiraComponentVersion.RC_SUFFIX,
                src.rcVersion
            ),
            ComponentRegistryVersion(
                ComponentVersionType.RELEASE,
                componentVersionFormat.releaseVersionFormat.formatVersion(versionNumericVersionFactory, src.version),
                src.releaseVersion
            ),
            src.hotfixVersion?.let {
                ComponentRegistryVersion(
                    ComponentVersionType.HOTFIX,
                    componentVersionFormat.hotfixVersionFormat.formatVersion(versionNumericVersionFactory, src.version),
                    it
                )
            } ?: null
        )
    }
}
