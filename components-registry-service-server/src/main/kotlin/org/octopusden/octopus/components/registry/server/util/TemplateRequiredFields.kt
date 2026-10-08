package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.TemplateField
import org.octopusden.octopus.components.registry.server.model.TemplateParameter

/**
 * The spec's required-fields table. A field is produced when the template fixes it, a required
 * parameter fills it, or [defaults] supply it; the Portal's own fallbacks do not count.
 */
internal class TemplateRequiredFields(
    parameters: List<TemplateParameter>,
    private val fields: Map<String, TemplateField>,
    private val defaults: Map<String, String>,
    private val keys: EntryKeys,
) {
    private val required = parameters.filter { it.required }.map { it.name }.toSet()

    fun check(classification: ComponentProfile.Classification) {
        val buildSystem = fixedOrDefault(TemplateFields.BUILD_SYSTEM)
        val needsVcs = buildSystem == null || buildSystem !in TemplateFields.NO_VCS_BUILD_SYSTEMS
        val explicitExternal = classification.external && classification.explicit == ComponentProfile.Explicit.TRUE
        val paths =
            ALWAYS +
                (if (needsVcs) VCS else emptyList()) +
                (if (explicitExternal) EXPLICIT_EXTERNAL else emptyList())
        paths.filterNot { produced(it) }.forEach {
            keys.problem(
                "fields.$it",
                "required for this classification; fix it, fill it from a required parameter, or give it a component default",
            )
        }
        if (explicitExternal && buildSystem != WHISKEY && DISTRIBUTIONS.none { group -> group.all { produced(it) } }) {
            keys.problem(
                "fields",
                "an explicit, external template needs a Maven GAV (baseConfiguration.mavenArtifacts[0].groupPattern and " +
                    ".artifactPattern), a Docker image (baseConfiguration.dockerImages[0].imageName) or a package " +
                    "(baseConfiguration.packages[0].packageName)",
            )
        }
    }

    /** As rendering does: a field that may end up empty still takes its default. */
    private fun produced(path: String): Boolean {
        val fromTemplate =
            fields[path]?.expressions.orEmpty().any { expression ->
                expression.parameters.any { it in required } || expression.literal().isNotBlank()
            }
        return fromTemplate || !defaults[path].isNullOrBlank()
    }

    /** The value when the template fixes it or a default supplies it; `null` when a parameter or nothing gives it. */
    private fun fixedOrDefault(path: String): String? {
        val field = fields[path] as? TemplateField.Single ?: return defaults[path]?.takeIf { it.isNotBlank() }
        return field.value.literal().takeIf { field.value.parameters.isEmpty() }
    }

    companion object {
        private const val WHISKEY = "WHISKEY"
        private val ALWAYS =
            listOf(
                "name",
                "componentOwner",
                TemplateFields.BUILD_SYSTEM,
                "baseConfiguration.jira.projectKey",
                "baseConfiguration.jira.versionFormat",
            )
        private val VCS =
            listOf(
                "baseConfiguration.vcsEntries[0].vcsPath",
                "baseConfiguration.vcsEntries[0].branch",
                "baseConfiguration.vcsEntries[0].tag",
            )
        private val EXPLICIT_EXTERNAL = listOf("displayName", "releaseManager", "securityChampion")
        private val DISTRIBUTIONS =
            listOf(
                listOf("baseConfiguration.mavenArtifacts[0].groupPattern", "baseConfiguration.mavenArtifacts[0].artifactPattern"),
                listOf("baseConfiguration.dockerImages[0].imageName"),
                listOf("baseConfiguration.packages[0].packageName"),
            )
    }
}
