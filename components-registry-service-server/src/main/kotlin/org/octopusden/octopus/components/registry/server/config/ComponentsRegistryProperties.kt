package org.octopusden.octopus.components.registry.server.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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
    // Restrict to the exact set ComponentRoutingResolver understands so a typo
    // ("DB", "database", "Git") fails the bootstrap instead of silently routing
    // every component to the git resolver.
    @field:Pattern(regexp = "git|db", message = "default-source must be exactly 'git' or 'db'")
    val defaultSource: String = "git",
    // SYS-047: when false (set by the `no-db` profile), every DB-coupled bean is
    // gated out via @ConditionalOnDatabaseEnabled and the JDBC/JPA/Flyway
    // auto-configs are excluded, so the service boots with no database and serves
    // v1/v2/v3 purely from the Git resolver. Default true → unchanged db-mode.
    val database: DatabaseSettings = DatabaseSettings(),
    // Allowed Java / Maven build-tool versions offered as dropdown options in the Portal
    // (served via /meta/java-versions and /meta/maven-versions). Defaults live in
    // application.yml; each installation overrides the whole list in its service-config
    // (Spring relaxed binding replaces — not merges — the list), so different installs can
    // offer different version sets. Empty list ⇒ the Portal dropdown shows no preset options.
    val buildToolVersions: BuildToolVersionsSettings = BuildToolVersionsSettings(),
    // SYS-099: a component may be flagged `testComponent` only when its key matches one of these
    // regexes (find semantics — anchor with `^`). Defaults in application.yml; service-config replaces
    // the list per installation. Empty list ⇒ no component can be flagged.
    val testComponents: TestComponentsSettings = TestComponentsSettings(),
) {
    data class TestComponentsSettings(
        val namePatterns: List<String> = emptyList(),
    ) {
        // Compiled at binding, so an invalid pattern fails the bootstrap instead of a later write.
        val nameRegexes: List<Regex> = namePatterns.map(::Regex)

        fun matches(componentKey: String): Boolean = nameRegexes.any { it.containsMatchIn(componentKey) }
    }

    data class DatabaseSettings(
        val enabled: Boolean = true,
    )

    data class BuildToolVersionsSettings(
        val java: List<String> = emptyList(),
        val maven: List<String> = emptyList(),
    )

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
