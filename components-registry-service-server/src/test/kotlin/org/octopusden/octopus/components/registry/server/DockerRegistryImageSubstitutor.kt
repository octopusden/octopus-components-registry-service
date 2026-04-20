package org.octopusden.octopus.components.registry.server

import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.ImageNameSubstitutor

/**
 * Redirects all Testcontainers image pulls through the internal Docker registry
 * when the DOCKER_REGISTRY environment variable is set.
 *
 * This prevents Docker Hub rate-limit errors (HTTP 429) on CI agents that pull images
 * anonymously. The variable is expected to point to a registry mirror that proxies
 * Docker Hub, e.g. "registry.example.com/dockerhub-proxy".
 */
class DockerRegistryImageSubstitutor : ImageNameSubstitutor() {
    private val registry: String? = System.getenv("DOCKER_REGISTRY")

    override fun apply(original: DockerImageName): DockerImageName {
        if (registry.isNullOrBlank()) return original
        val originalName = original.asCanonicalNameString()
        // avoid double-prefixing if image already references the internal registry
        if (originalName.startsWith(registry)) return original
        return DockerImageName.parse("$registry/$originalName").asCompatibleSubstituteFor(originalName)
    }

    override fun getDescription() = "DockerRegistryImageSubstitutor (DOCKER_REGISTRY=$registry)"
}
