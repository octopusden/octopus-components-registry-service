package org.octopusden.octopus.validation.teamcity

import org.octopusden.octopus.validation.dto.teamcity.BuildConfiguration
import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.Parameters
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog

/** Small in-memory fixture builders so test graphs (project -> configs -> steps -> params) stay readable. */

const val GRADLE_TEMPLATE_ID = "CDGradleBuild"
const val MAVEN_TEMPLATE_ID = "CDJavaMavenBuild"
const val RELEASE_TEMPLATE_ID = "CDRelease"
const val GRADLE_DEFAULT_STEP_ID = "RUNNER_GRADLE_DEFAULT"
const val MAVEN_DEFAULT_STEP_ID = "RUNNER_MAVEN_DEFAULT"

object TestTemplateCatalog : TemplateCatalog {
    override val gradleBuildTemplateId = GRADLE_TEMPLATE_ID
    override val mavenBuildTemplateId = MAVEN_TEMPLATE_ID
    override val releaseFamilyTemplateIds = setOf(RELEASE_TEMPLATE_ID, "CdReleaseCandidateNew", "CdReleaseChecklistValidation")

    override fun defaultBuildStepId(templateId: String): String? =
        when (templateId) {
            gradleBuildTemplateId -> GRADLE_DEFAULT_STEP_ID
            mavenBuildTemplateId -> MAVEN_DEFAULT_STEP_ID
            else -> null
        }
}

fun params(vararg pairs: Pair<String, String>): Parameters = Parameters(mapOf(*pairs))

fun buildStep(
    id: String,
    type: StepType,
    inherited: Boolean = true,
    disabled: Boolean = false,
    parameters: Parameters = params(),
    name: String = id,
): BuildStep = BuildStep(id, name, type, disabled, inherited, parameters)

fun buildConfig(
    id: String,
    templateIds: Set<String> = emptySet(),
    steps: List<BuildStep> = emptyList(),
    parameters: Parameters = params(),
    paused: Boolean = false,
    name: String = id,
): BuildConfiguration = BuildConfiguration(id, name, paused, templateIds, parameters, steps)

fun tcProject(
    id: String = "Project",
    configs: List<BuildConfiguration> = emptyList(),
    parameters: Parameters = params(),
): TeamcityProject = TeamcityProject(id, parameters, configs)
