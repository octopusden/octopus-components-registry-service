package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityValidationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Registers [TeamcityValidationProperties] only when the database is enabled.
 *
 * The TeamCity validation feature is DB-backed (it persists findings and derives its scope from
 * `version_line`), so it is unavailable in `no-db` / Git-only mode. Its five template/step-id
 * properties are mandatory (`@NotBlank`/`@NotEmpty`); registering them unconditionally would force
 * a pure Git/no-db deployment to supply configuration for a feature it cannot use, or fail startup.
 */
@Configuration
@ConditionalOnDatabaseEnabled
@EnableConfigurationProperties(TeamcityValidationProperties::class)
class TeamcityValidationConfig
