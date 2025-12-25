package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import jakarta.validation.constraints.NotBlank

@ConfigurationProperties(prefix = "components-registry")
@Validated
class ComponentsRegistryProperties(
    @field:NotBlank
    val groovyPath: String,
    @field:NotBlank
    val workDir: String,
    @field:NotBlank
    val mainGroovyFile: String,
    val dependencyMappingFile: String?,
    val vcs: VcsSettings,
    val versionName: VersionNameSettings,
    val copyrightPath: String?,
) {
    data class VcsSettings(
        val enabled: Boolean = true,
        val root: String?,
        var username: String?,
        var password: String = "",
        var tagVersionPrefix: String = ""
    )

    data class VersionNameSettings(
        val serviceBranch: String,
        val service: String,
        val minor: String
    )
}
