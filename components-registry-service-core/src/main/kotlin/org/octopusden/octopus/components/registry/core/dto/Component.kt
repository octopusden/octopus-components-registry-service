package org.octopusden.octopus.components.registry.core.dto

abstract class Component(val id: String, val name: String?, val componentOwner: String) {
    var system: Set<String> = emptySet()
    var clientCode: String? = null
    var releasesInDefaultBranch: Boolean? = null
    var solution: Boolean? = null
    var parentComponent: String? = null
    var securityChampion: String? = null
    var releaseManager: String? = null
    var distribution: DistributionDTO? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Component

        if (id != other.id) return false
        if (name != other.name) return false
        if (componentOwner != other.componentOwner) return false
        if (system != other.system) return false
        if (clientCode != other.clientCode) return false
        if (releasesInDefaultBranch != other.releasesInDefaultBranch) return false
        if (solution != other.solution) return false
        if (parentComponent != other.parentComponent) return false
        if (securityChampion != other.securityChampion) return false
        if (releaseManager != other.releaseManager) return false
        if (distribution != other.distribution) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + componentOwner.hashCode()
        result = 31 * result + system.hashCode()
        result = 31 * result + (clientCode?.hashCode() ?: 0)
        result = 31 * result + (releasesInDefaultBranch?.hashCode() ?: 0)
        result = 31 * result + (solution?.hashCode() ?: 0)
        result = 31 * result + (parentComponent?.hashCode() ?: 0)
        result = 31 * result + (securityChampion?.hashCode() ?: 0)
        result = 31 * result + (releaseManager?.hashCode() ?: 0)
        result = 31 * result + (distribution?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Component(id='$id', name=$name, componentOwner='$componentOwner', system=$system, clientCode=$clientCode, releasesInDefaultBranch=$releasesInDefaultBranch, solution=$solution, parentComponent=$parentComponent, securityChampion=$securityChampion, releaseManager=$releaseManager, distribution=$distribution)"
    }
}
