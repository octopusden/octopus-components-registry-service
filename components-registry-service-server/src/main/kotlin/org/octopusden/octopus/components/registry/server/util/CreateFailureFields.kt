package org.octopusden.octopus.components.registry.server.util

/**
 * The template paths a failure message of today's create concerns (Decision 9), read from the
 * field the message starts with. Today's create names the field first — `name: …`,
 * `componentOwner '…' …`, `Invalid build.buildSystem: …` — and the Portal relies on the same
 * prefixes. A message that matches no rule concerns no field; the dry run still reports it.
 *
 * Attribution depends on that wording: a message reworded in the create loses its field, which
 * is why every prefix has a test.
 */
object CreateFailureFields {
    private const val MAVEN_GROUP = "baseConfiguration.mavenArtifacts[0].groupPattern"
    private const val MAVEN_ARTIFACT = "baseConfiguration.mavenArtifacts[0].artifactPattern"
    private const val DOCKER_IMAGE = "baseConfiguration.dockerImages[0].imageName"
    private const val ARTIFACT_GROUP = "artifactIds[0].groupPattern"

    private val EDITABILITY = Regex("""^Field '([^']+)' is (?:not editable|editable by administrators only)""")
    private val LEADING_FIELD = Regex("""^(?:Invalid )?([A-Za-z][A-Za-z0-9_.]*)""")

    /** Field-config sections, as editability messages name them, to the create-request path they sit under. */
    private val SECTIONS =
        mapOf(
            "component." to "",
            "jira." to "baseConfiguration.jira.",
            "build." to "baseConfiguration.build.",
            "escrow." to "baseConfiguration.escrow.",
        )

    /** Checked in order: the uniqueness messages name no field first. */
    private val UNIQUENESS =
        listOf(
            "uniqueness violation: jira project" to listOf("baseConfiguration.jira.projectKey", "baseConfiguration.jira.versionPrefix"),
            "uniqueness violation: docker image name" to listOf(DOCKER_IMAGE),
            "uniqueness violation:" to listOf(MAVEN_GROUP, MAVEN_ARTIFACT, ARTIFACT_GROUP),
        )

    private val ALIASES =
        mapOf(
            "artifactIds" to listOf(ARTIFACT_GROUP, "artifactIds[0].mode", "artifactIds[0].artifactTokens"),
            "distribution.mavenArtifacts" to listOf(MAVEN_GROUP, MAVEN_ARTIFACT),
            "distribution" to
                listOf(MAVEN_GROUP, MAVEN_ARTIFACT, DOCKER_IMAGE, "baseConfiguration.packages[0].packageName", ARTIFACT_GROUP),
            "groupId" to listOf(MAVEN_GROUP, ARTIFACT_GROUP),
            "build.buildSystem" to listOf(TemplateFields.BUILD_SYSTEM),
            "escrow.generation" to listOf(TemplateFields.ESCROW_GENERATION),
            "package.packageType" to listOf("baseConfiguration.packages[0].packageType"),
            ComponentDefaultsSeed.COPYRIGHT to listOf(ComponentDefaultsSeed.COPYRIGHT),
        )

    fun of(message: String): List<String> {
        UNIQUENESS.firstOrNull { message.startsWith(it.first) }?.let { return it.second }
        EDITABILITY.find(message)?.let { match -> return editable(match.groupValues[1]) }
        val field = LEADING_FIELD.find(message)?.groupValues?.get(1) ?: return emptyList()
        return ALIASES[field] ?: listOfNotNull(field.takeIf { it in TemplateFields.KINDS })
    }

    private fun editable(fieldPath: String): List<String> {
        val (section, path) = SECTIONS.entries.firstOrNull { fieldPath.startsWith(it.key) } ?: return emptyList()
        return listOfNotNull((path + fieldPath.removePrefix(section)).takeIf { it in TemplateFields.KINDS || it in ALIASES })
    }
}
