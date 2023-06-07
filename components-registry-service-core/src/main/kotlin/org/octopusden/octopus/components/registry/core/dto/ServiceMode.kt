package org.octopusden.octopus.components.registry.core.dto

enum class ServiceMode {
    FS,
    VCS;

    companion object {
        fun byVcsEnabled(vcsEnabled: Boolean): ServiceMode {
            return if (vcsEnabled) VCS else FS
        }
    }
}