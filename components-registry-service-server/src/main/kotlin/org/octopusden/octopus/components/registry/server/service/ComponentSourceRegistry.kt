package org.octopusden.octopus.components.registry.server.service

interface ComponentSourceRegistry {
    fun isDbComponent(name: String): Boolean

    fun isGitComponent(name: String): Boolean

    fun getSource(name: String): String

    fun setComponentSource(
        name: String,
        source: String,
    )

    fun getDbComponentNames(): Set<String>

    fun getGitComponentNames(): Set<String>
}
