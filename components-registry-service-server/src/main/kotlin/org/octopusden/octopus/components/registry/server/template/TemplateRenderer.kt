package org.octopusden.octopus.components.registry.server.template

import org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BaseConfigurationRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BuildAspectRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DockerImageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.EscrowAspectRequest
import org.octopusden.octopus.components.registry.server.dto.v4.JiraAspectRequest
import org.octopusden.octopus.components.registry.server.dto.v4.MavenArtifactRequest
import org.octopusden.octopus.components.registry.server.dto.v4.PackageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest
import org.octopusden.octopus.components.registry.server.model.ComponentProfile

/**
 * A rendered template: the create request, every field it sets by path, and where each came from.
 * [sources] maps a set path to the parameters it used — empty for a fixed or defaulted value;
 * [overridden] are the paths an override replaced.
 */
data class RenderedTemplate(
    val request: ComponentCreateRequest,
    val fields: Map<String, List<String>>,
    val sources: Map<String, Set<String>>,
    val overridden: Set<String>,
) {
    val fixedOrDefaulted: Set<String>
        get() = sources.filterValues { it.isEmpty() }.keys - overridden
}

/**
 * Turns a template, values that passed [ParameterChecker], overrides and `component-defaults`
 * into a create request, following rules R1–R8 in order: the template's fields, then defaults on
 * fields still unset, then overrides (Decision 6). Pure and deterministic.
 *
 * A nested entry with any field set is built even when a field today's create requires is
 * missing; the missing text is empty, so the create step reports it as it would for any request.
 */
object TemplateRenderer {
    private val VCS_DEFAULTS = setOf(ComponentDefaultsSeed.VCS_TAG, ComponentDefaultsSeed.VCS_BRANCH)

    fun render(
        template: ComponentTemplate,
        values: Map<String, List<String>>,
        overrides: Map<String, List<String>>,
        defaults: Map<String, String>,
        jiraTaskKey: String?,
        changeComment: String?,
    ): RenderedTemplate {
        overrides.keys.forEach { require(it in template.overridable) { "$it: not overridable in template '${template.id}'" } }
        val multiValue = template.parameters
            .filter { it.multiple }
            .map { it.name }
            .toSet()
        val fields = linkedMapOf<String, List<String>>()
        val sources = linkedMapOf<String, Set<String>>()
        template.fields.values.forEach { field ->
            val rendered = render(field, values, multiValue)
            if (rendered.isNotEmpty()) {
                fields[field.path] = rendered
                sources[field.path] = field.parameters
            }
        }
        applyDefaults(template.classification, defaults, fields, sources)
        overrides.forEach { (path, value) -> fields[path] = value.filter { it.isNotBlank() } }
        val request = RequestBuilder(fields).build(template.classification, jiraTaskKey, changeComment)
        return RenderedTemplate(request, fields, sources, overrides.keys)
    }

    private fun render(
        field: TemplateField,
        values: Map<String, List<String>>,
        multiValue: Set<String>,
    ): List<String> =
        when (field) {
            is TemplateField.Single -> listOf(text(field.value, values)).filter { it.isNotBlank() }
            is TemplateField.Items -> {
                val items =
                    field.items
                        .flatMap { item ->
                            val whole = item.wholeParameter
                            if (whole != null && whole in multiValue) values[whole].orEmpty() else listOf(text(item, values))
                        }.filter { it.isNotBlank() }
                if (field.kind == TemplateFields.Kind.FREE_TEXT_LIST) items else items.distinct()
            }
        }

    private fun text(
        expression: TemplateExpression,
        values: Map<String, List<String>>,
    ): String =
        expression.parts.joinToString("") { part ->
            when (part) {
                is TemplateExpression.Part.Literal -> part.text
                is TemplateExpression.Part.Parameter -> {
                    val value = values[part.name]?.firstOrNull().orEmpty()
                    when (part.filter) {
                        TemplateExpression.Filter.LOWER -> value.lowercase()
                        TemplateExpression.Filter.UPPER -> value.uppercase()
                        null -> value
                    }
                }
            }
        }

    private fun applyDefaults(
        classification: ComponentProfile.Classification,
        defaults: Map<String, String>,
        fields: MutableMap<String, List<String>>,
        sources: MutableMap<String, Set<String>>,
    ) {
        val explicitExternal = classification.external && classification.explicit == ComponentProfile.Explicit.TRUE
        val buildSystem = fields[TemplateFields.BUILD_SYSTEM]?.firstOrNull() ?: defaults[TemplateFields.BUILD_SYSTEM]
        val needsVcs = buildSystem == null || buildSystem !in TemplateFields.NO_VCS_BUILD_SYSTEMS
        defaults
            .filterKeys { it !in fields }
            .filterKeys { it != ComponentDefaultsSeed.COPYRIGHT || explicitExternal }
            .filterKeys { it !in VCS_DEFAULTS || needsVcs }
            .forEach { (path, value) ->
                fields[path] = listOf(value)
                sources[path] = emptySet()
            }
    }

    private class RequestBuilder(
        private val fields: Map<String, List<String>>,
    ) {
        private fun one(path: String): String? = fields[path]?.firstOrNull()

        private fun any(vararg paths: String): Boolean = paths.any { it in fields }

        fun build(
            classification: ComponentProfile.Classification,
            jiraTaskKey: String?,
            changeComment: String?,
        ) = ComponentCreateRequest(
            name = one("name").orEmpty(),
            displayName = one("displayName"),
            componentOwner = one("componentOwner"),
            clientCode = one("clientCode"),
            solution = classification.solution,
            releaseManager = fields["releaseManager"].orEmpty(),
            securityChampion = fields["securityChampion"].orEmpty(),
            copyright = one(ComponentDefaultsSeed.COPYRIGHT),
            labels = fields["labels"].orEmpty().toCollection(linkedSetOf()),
            distributionExplicit = classification.explicit == ComponentProfile.Explicit.TRUE,
            distributionExternal = classification.external,
            artifactIds = artifactIds(),
            baseConfiguration = baseConfiguration(),
            jiraTaskKey = jiraTaskKey,
            changeComment = changeComment,
        )

        private fun artifactIds(): List<ArtifactIdRequest> =
            if (any(ARTIFACT_GROUP, ARTIFACT_MODE, ARTIFACT_TOKENS)) {
                listOf(
                    ArtifactIdRequest(
                        groupPattern = one(ARTIFACT_GROUP).orEmpty(),
                        mode = one(ARTIFACT_MODE),
                        artifactTokens = fields[ARTIFACT_TOKENS].orEmpty(),
                    ),
                )
            } else {
                emptyList()
            }

        private fun baseConfiguration() =
            BaseConfigurationRequest(
                build =
                    BuildAspectRequest(buildSystem = one(TemplateFields.BUILD_SYSTEM), buildTasks = one("$BASE.build.buildTasks"))
                        .takeIf { any(TemplateFields.BUILD_SYSTEM, "$BASE.build.buildTasks") },
                escrow = EscrowAspectRequest(
                    generation = one(TemplateFields.ESCROW_GENERATION),
                ).takeIf { any(TemplateFields.ESCROW_GENERATION) },
                jira = jira(),
                vcsEntries =
                    listOf(VcsEntryRequest(vcsPath = one("$VCS.vcsPath").orEmpty(), branch = one("$VCS.branch"), tag = one("$VCS.tag")))
                        .takeIf { any("$VCS.vcsPath", "$VCS.branch", "$VCS.tag") },
                mavenArtifacts =
                    listOf(
                        MavenArtifactRequest(
                            groupPattern = one("$MAVEN.groupPattern").orEmpty(),
                            artifactPattern = one("$MAVEN.artifactPattern").orEmpty(),
                        ),
                    ).takeIf { any("$MAVEN.groupPattern", "$MAVEN.artifactPattern") },
                dockerImages =
                    listOf(DockerImageRequest(imageName = one("$DOCKER.imageName").orEmpty(), flavor = one("$DOCKER.flavor")))
                        .takeIf { any("$DOCKER.imageName", "$DOCKER.flavor") },
                packages =
                    listOf(
                        PackageRequest(
                            packageType = one("$PACKAGE.packageType").orEmpty(),
                            packageName = one("$PACKAGE.packageName").orEmpty(),
                        ),
                    ).takeIf { any("$PACKAGE.packageType", "$PACKAGE.packageName") },
            )

        private fun jira(): JiraAspectRequest? {
            val paths = JIRA_FIELDS.map { "$BASE.jira.$it" }
            if (paths.none { it in fields }) return null
            return JiraAspectRequest(
                projectKey = one("$BASE.jira.projectKey"),
                versionPrefix = one("$BASE.jira.versionPrefix"),
                versionFormat = one("$BASE.jira.versionFormat"),
                lineVersionFormat = one("$BASE.jira.lineVersionFormat"),
                minorVersionFormat = one("$BASE.jira.minorVersionFormat"),
                releaseVersionFormat = one("$BASE.jira.releaseVersionFormat"),
                buildVersionFormat = one("$BASE.jira.buildVersionFormat"),
            )
        }

        companion object {
            private const val BASE = "baseConfiguration"
            private const val VCS = "$BASE.vcsEntries[0]"
            private const val MAVEN = "$BASE.mavenArtifacts[0]"
            private const val DOCKER = "$BASE.dockerImages[0]"
            private const val PACKAGE = "$BASE.packages[0]"
            private const val ARTIFACT_GROUP = "artifactIds[0].groupPattern"
            private const val ARTIFACT_MODE = "artifactIds[0].mode"
            private const val ARTIFACT_TOKENS = "artifactIds[0].artifactTokens"
            private val JIRA_FIELDS =
                listOf(
                    "projectKey",
                    "versionPrefix",
                    "versionFormat",
                    "lineVersionFormat",
                    "minorVersionFormat",
                    "releaseVersionFormat",
                    "buildVersionFormat",
                )
        }
    }
}
