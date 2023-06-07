package org.octopusden.octopus.components.registry.server.service

interface VcsService {
    /**
     * @return Actual Commit ID of checkout
     */
    fun cloneComponentsRegistry(): String?
}