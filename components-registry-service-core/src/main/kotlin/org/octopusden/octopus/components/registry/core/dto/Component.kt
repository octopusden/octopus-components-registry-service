package org.octopusden.octopus.components.registry.core.dto

import java.util.Objects

abstract class Component(val id: String, val name: String?, val componentOwner: String) {
    var system: Set<String> = emptySet()
    var clientCode: String? = null
    var releasesInDefaultBranch: Boolean? = null
    var solution: Boolean? = null
    var parentComponent: String? = null
    var securityChampion: String? = null
    var releaseManager: String? = null
    var distribution: DistributionDTO? = null
    var archived: Boolean = false
    var doc: DocDTO? = null
    var escrow: EscrowDTO? = null
    var labels: Set<String> = emptySet()
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
        if (archived != other.archived) return false
        if (doc != other.doc) return false
        if (escrow != other.escrow) return false
        if (labels != other.labels) return false

        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(
            id,
            name,
            componentOwner,
            system,
            clientCode,
            releasesInDefaultBranch,
            solution,
            parentComponent,
            securityChampion,
            releaseManager,
            distribution,
            archived,
            doc,
            escrow,
            labels,
        )
    }

    override fun toString(): String {
        return "Component(id='$id', name=$name, componentOwner='$componentOwner', system=$system, " +
                "clientCode=$clientCode, releasesInDefaultBranch=$releasesInDefaultBranch, solution=$solution, " +
                "parentComponent=$parentComponent, securityChampion=$securityChampion, releaseManager=$releaseManager, " +
                "distribution=$distribution, archived=$archived, doc=$doc, escrow=$escrow, " +
                "labels='${labels.joinToString()}')"
    }
}
