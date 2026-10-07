package org.octopusden.octopus.components.registry.server.template

import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.mapper.PACKAGE_TYPE_NAMES
import org.octopusden.octopus.components.registry.server.util.CreateRequestPaths
import org.octopusden.octopus.escrow.BuildSystem

/**
 * Every create-request path a template may set, with the kind of value it takes (Decision 2).
 * Paths are as `ComponentProfilesSource` flattens them under `fields.`; a list field's items are
 * the indexed keys under its path.
 */
object TemplateFields {
    enum class Kind(
        val list: Boolean,
    ) {
        /** Text with expressions; filters allowed. */
        FREE_TEXT(false),

        /** A value of a registry list, or exactly `{{ NAME }}` of a `crs-list` parameter of that list. */
        CRS_VALUE(false),

        /** A login, or exactly `{{ NAME }}` of a single `person` parameter. */
        PERSON(false),

        /** Items, each free text or exactly `{{ NAME }}` of a multi-value `select`. */
        FREE_TEXT_LIST(true),

        /** Labels, or exactly `{{ NAME }}` of a `labels` parameter. */
        CRS_LIST(true),

        /** Logins, or exactly `{{ NAME }}` of a `person` parameter. */
        PEOPLE_LIST(true),

        /** A fixed value of a static set; never a parameter. */
        FIXED_CHOICE(false),
    }

    const val BUILD_SYSTEM = "baseConfiguration.build.buildSystem"
    const val ESCROW_GENERATION = "baseConfiguration.escrow.generation"

    /** As the Portal hides VCS for them; any other build system, or one from a parameter, needs VCS. */
    val NO_VCS_BUILD_SYSTEMS = setOf("PROVIDED", "ESCROW_PROVIDED_MANUALLY", "ESCROW_NOT_SUPPORTED", "WHISKEY", "BS2_0")

    val CRS_VALUE_LISTS: Map<String, TemplateList> =
        mapOf(BUILD_SYSTEM to TemplateList.BUILD_SYSTEMS, ESCROW_GENERATION to TemplateList.ESCROW_GENERATION)

    val FIXED_CHOICES: Map<String, Set<String>> =
        mapOf(
            "artifactIds[0].mode" to ArtifactIdMode.entries.map { it.name }.toSet(),
            "baseConfiguration.packages[0].packageType" to PACKAGE_TYPE_NAMES,
        )

    val KINDS: Map<String, Kind> =
        CreateRequestPaths.PATHS.associateWith { Kind.FREE_TEXT } +
            CRS_VALUE_LISTS.keys.associateWith { Kind.CRS_VALUE } +
            mapOf(
                "componentOwner" to Kind.PERSON,
                "artifactIds[0].artifactTokens" to Kind.FREE_TEXT_LIST,
                "labels" to Kind.CRS_LIST,
                "releaseManager" to Kind.PEOPLE_LIST,
                "securityChampion" to Kind.PEOPLE_LIST,
            ) +
            FIXED_CHOICES.keys.associateWith { Kind.FIXED_CHOICE }

    /** The values a list has on load, or `null` for one that changes at runtime. */
    fun staticValues(list: TemplateList): Set<String>? =
        when (list) {
            TemplateList.BUILD_SYSTEMS -> BuildSystem.entries.map { it.name }.toSet()
            TemplateList.ESCROW_GENERATION -> EscrowGenerationMode.entries.map { it.name }.toSet()
            TemplateList.LABELS -> null
        }
}
