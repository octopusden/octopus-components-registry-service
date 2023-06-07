package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class DetailedComponentVersion {
    lateinit var component: String
    lateinit var minorVersion: ComponentRegistryVersion
    lateinit var lineVersion: ComponentRegistryVersion
    lateinit var buildVersion: ComponentRegistryVersion
    lateinit var rcVersion: ComponentRegistryVersion
    lateinit var releaseVersion: ComponentRegistryVersion

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetailedComponentVersion) return false

        if (component != other.component) return false
        if (minorVersion != other.minorVersion) return false
        if (lineVersion != other.lineVersion) return false
        if (buildVersion != other.buildVersion) return false
        if (rcVersion != other.rcVersion) return false
        if (releaseVersion != other.releaseVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = component.hashCode()
        result = 31 * result + minorVersion.hashCode()
        result = 31 * result + lineVersion.hashCode()
        result = 31 * result + buildVersion.hashCode()
        result = 31 * result + rcVersion.hashCode()
        result = 31 * result + releaseVersion.hashCode()
        return result
    }

    override fun toString(): String {
        return "DetailedComponentVersion(component='$component', lineVersion=$lineVersion, buildVersion=$buildVersion, rcVersion=$rcVersion, releaseVersion=$releaseVersion)"
    }
}
