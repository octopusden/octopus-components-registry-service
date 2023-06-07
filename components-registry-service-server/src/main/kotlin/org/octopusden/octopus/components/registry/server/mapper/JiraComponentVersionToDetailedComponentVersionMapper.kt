package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.core.dto.ComponentRegistryVersion
import org.octopusden.octopus.components.registry.core.dto.ComponentVersionType
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.server.util.formatVersion
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.springframework.stereotype.Component

@Component
class JiraComponentVersionToDetailedComponentVersionMapper(
        private val jiraComponentVersionFormatter: JiraComponentVersionFormatter
) : Mapper<JiraComponentVersion, DetailedComponentVersion> {

    override fun convert(src: JiraComponentVersion): DetailedComponentVersion {
        val target = DetailedComponentVersion()
        target.component = src.component.displayName ?: src.componentVersion.componentName
        val componentVersionFormat = src.component.componentVersionFormat
        target.lineVersion = ComponentRegistryVersion(
                ComponentVersionType.LINE,
                componentVersionFormat.lineVersionFormat.formatVersion(src.version),
                jiraComponentVersionFormatter.getLineVersion(src)
        )
        target.minorVersion = ComponentRegistryVersion(
                ComponentVersionType.MINOR,
                componentVersionFormat.majorVersionFormat.formatVersion(src.version),
                src.majorVersion
        )
        target.buildVersion = ComponentRegistryVersion(
                ComponentVersionType.BUILD,
                componentVersionFormat.buildVersionFormat.formatVersion(src.version),
                src.buildVersion
        )
        target.rcVersion = ComponentRegistryVersion(
                ComponentVersionType.RC,
                componentVersionFormat.releaseVersionFormat.formatVersion(src.version) + JiraComponentVersion.RC_SUFFIX,
                src.rcVersion
        )
        target.releaseVersion = ComponentRegistryVersion(
                ComponentVersionType.RELEASE,
                componentVersionFormat.releaseVersionFormat.formatVersion(src.version),
                src.releaseVersion
        )
        return target
    }
}
