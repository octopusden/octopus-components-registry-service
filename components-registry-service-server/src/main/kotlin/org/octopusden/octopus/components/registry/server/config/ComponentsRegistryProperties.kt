package org.octopusden.octopus.components.registry.server.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

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
    val autoMigrate: Boolean = false,
    // Source to assume when a component has no component_source row. Default "git"
    // keeps the mixed-source routing working for DSL-backed deployments; ft-db
    // sets this to "db" so that renamed/deleted names don't leak DSL ghosts through
    // the git resolver after all components have been migrated to the database.
    val defaultSource: String = "git",
) {
    data class VcsSettings(
        val enabled: Boolean = true,
        val root: String?,
        var username: String?,
        var password: String = "",
        var tagVersionPrefix: String = "",
    )

    data class VersionNameSettings(
        val serviceBranch: String,
        val service: String,
        val minor: String,
    )
}
