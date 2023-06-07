package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.validation.annotation.Validated
import javax.validation.constraints.NotBlank

@ConfigurationProperties(prefix = "components-registry")
@ConstructorBinding
@Validated
class ComponentsRegistryProperties(
    @field:NotBlank
    val groovyPath: String,
    @field:NotBlank
    val workDir: String,
    @field:NotBlank
    val mainGroovyFile: String,
    val dependencyMappingFile: String?,
    val vcs: VcsSettings
) {
    data class VcsSettings(
        val enabled: Boolean = true,
        val root: String?,
        var username: String?,
        var password: String = "",
        var tagVersionPrefix: String = ""
    )
}
