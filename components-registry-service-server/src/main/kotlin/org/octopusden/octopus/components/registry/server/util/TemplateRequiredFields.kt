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
        checkVcsEntries(reported = paths.toSet())
        if (!MAVEN_COORDINATE.all { it in fields }) {
            MAVEN_QUALIFIERS.filter { it in fields }.forEach {
                keys.problem(
                    "fields.$it",
                    "needs the Maven GAV it qualifies; set baseConfiguration.mavenArtifacts[0].groupPattern and .artifactPattern too",
                )
            }
        }
    }

    /**
     * The create checks out at most one VCS entry at the checkout root, and needs the folder the build runs
     * in when none is there. The renderer builds every entry up to the last index set, so the indexes must
     * run from 0 without a gap. Only the indexes set are walked, so a large one costs nothing; they come from
     * the keys, so an entry whose fields failed to parse is no gap, though its required fields are reported as
     * missing too. [reported] are the paths already checked for the classification.
     */
    private fun checkVcsEntries(reported: Set<String>) {
        val indexes =
            keys.values.keys
                .filter { it.startsWith(FIELDS) }
                .mapNotNull { TemplateFields.vcsEntryIndex(it.removePrefix(FIELDS)) }
                .toSortedSet()
        if (indexes.any { it > 0 }) {
            indexes.withIndex().firstOrNull { it.index != it.value }?.let { (missing, _) ->
                keys.problem(
                    "fields.$VCS_ENTRIES[$missing]",
                    "missing: VCS entries are numbered from 0 without gaps; set entry $missing or renumber the entries after it",
                )
            }
            val required =
                indexes.flatMap { i ->
                    listOfNotNull("$VCS_ENTRIES[$i].vcsPath", "$VCS_ENTRIES[$i].checkoutDirectory".takeIf { i > 0 })
                }
            (required - reported).filterNot { produced(it) }.forEach {
                keys.problem("fields.$it", "required with more than one VCS entry; fix it or fill it from a required parameter")
            }
        }
        if ("$VCS_ENTRIES[0].checkoutDirectory" in fields && !produced(TemplateFields.BUILD_WORKING_DIRECTORY)) {
            keys.problem(
                "fields.${TemplateFields.BUILD_WORKING_DIRECTORY}",
                "required when the first VCS entry has a checkoutDirectory; fix it or fill it from a required parameter",
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

    /**
     * The value when the template fixes it or a default supplies it, a blank fixed value taking the
     * default as R2 and R7 do; `null` when a parameter or nothing gives it.
     */
    private fun fixedOrDefault(path: String): String? {
        val field = fields[path] as? TemplateField.Single ?: return defaults[path]?.takeIf { it.isNotBlank() }
        return field.value
            .literal()
            .ifBlank { defaults[path]?.takeIf { it.isNotBlank() } }
            .takeIf { field.value.parameters.isEmpty() }
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
        private const val FIELDS = "fields."
        private const val VCS_ENTRIES = "baseConfiguration.vcsEntries"
        private val EXPLICIT_EXTERNAL = listOf("displayName", "releaseManager", "securityChampion")
        private val MAVEN_COORDINATE =
            listOf("baseConfiguration.mavenArtifacts[0].groupPattern", "baseConfiguration.mavenArtifacts[0].artifactPattern")
        private val MAVEN_QUALIFIERS =
            listOf("baseConfiguration.mavenArtifacts[0].extension", "baseConfiguration.mavenArtifacts[0].classifier")
        private val DISTRIBUTIONS =
            listOf(
                MAVEN_COORDINATE,
                listOf("baseConfiguration.dockerImages[0].imageName"),
                listOf("baseConfiguration.packages[0].packageName"),
            )
    }
}
