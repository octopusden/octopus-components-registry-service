package org.octopusden.octopus.validation.validators

import org.octopusden.octopus.validation.core.ValidatorSuite
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.MavenVersion
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.JavaHomeReferenceResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.JavaVersionResolver
import org.octopusden.octopus.validation.validators.teamcity.AttachedToBuildTemplateValidator
import org.octopusden.octopus.validation.validators.teamcity.CustomBuildStepValidator
import org.octopusden.octopus.validation.validators.teamcity.EnvJavaHomeValidator
import org.octopusden.octopus.validation.validators.teamcity.MultipleVersionValidator
import org.octopusden.octopus.validation.validators.teamcity.OldJavaVersionValidator
import org.octopusden.octopus.validation.validators.teamcity.OverridesDefaultBuildStepValidator
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType

/**
 * The TeamCity validation suite: seven checks over one [TeamcityProject]. [catalog] supplies the
 * real template/step ids for a given TeamCity instance (see [TemplateCatalog] — this module ships
 * no concrete implementation, that is a caller/server concern).
 */
class TeamCityValidators(
    catalog: TemplateCatalog,
) : ValidatorSuite<TeamcityProject>() {
    private val buildStepToolVersionResolver = DefaultBuildStepToolVersionResolver.standard()
    private val buildConfigurationResolver = DefaultBuildConfigurationResolver(catalog)
    private val buildStepResolver = DefaultBuildStepResolver(catalog)
    private val javaHomeReferenceResolver = JavaHomeReferenceResolver(JavaVersionResolver())

    override val validators = listOf(
        AttachedToBuildTemplateValidator(buildConfigurationResolver),
        OverridesDefaultBuildStepValidator(buildConfigurationResolver, buildStepResolver),
        CustomBuildStepValidator(buildConfigurationResolver, buildStepToolVersionResolver),
        OldJavaVersionValidator(buildConfigurationResolver, buildStepResolver, buildStepToolVersionResolver),
        MultipleVersionValidator(
            TeamCityValidationType.MULTIPLE_JAVA_VERSIONS,
            buildConfigurationResolver,
            buildStepResolver,
            buildStepToolVersionResolver,
            "Java",
        ) { it is JavaVersion },
        MultipleVersionValidator(
            TeamCityValidationType.MULTIPLE_MAVEN_VERSIONS,
            buildConfigurationResolver,
            buildStepResolver,
            buildStepToolVersionResolver,
            "Maven",
        ) { it is MavenVersion },
        EnvJavaHomeValidator(
            buildConfigurationResolver,
            buildStepResolver,
            buildStepToolVersionResolver,
            javaHomeReferenceResolver,
        ),
    )
}
