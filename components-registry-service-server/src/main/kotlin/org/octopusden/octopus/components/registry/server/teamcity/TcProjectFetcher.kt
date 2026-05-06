package org.octopusden.octopus.components.registry.server.teamcity

import java.util.UUID

data class TcProject(val id: String, val webUrl: String)

fun interface TcProjectFetcher {
    fun findByComponentNames(componentsByName: Map<String, UUID>): Map<UUID, List<TcProject>>
}
