package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.mapper.PACKAGE_TYPE_NAMES
import org.octopusden.octopus.components.registry.server.model.TemplateFieldKind
import org.octopusden.octopus.components.registry.server.model.TemplateList
import org.octopusden.octopus.components.registry.server.util.CreateRequestPaths
import org.octopusden.octopus.escrow.BuildSystem

/**
 * Every create-request path a template may set, with the kind of value it takes (Decision 2).
 * Paths are as `ComponentProfilesSource` flattens them under `fields.`; a list field's items are
 * the indexed keys under its path.
 */
object TemplateFields {
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

    val KINDS: Map<String, TemplateFieldKind> =
        CreateRequestPaths.PATHS.associateWith { TemplateFieldKind.FREE_TEXT } +
            CRS_VALUE_LISTS.keys.associateWith { TemplateFieldKind.CRS_VALUE } +
            mapOf(
                "componentOwner" to TemplateFieldKind.PERSON,
                "artifactIds[0].artifactTokens" to TemplateFieldKind.FREE_TEXT_LIST,
                "labels" to TemplateFieldKind.CRS_LIST,
                "releaseManager" to TemplateFieldKind.PEOPLE_LIST,
                "securityChampion" to TemplateFieldKind.PEOPLE_LIST,
            ) +
            FIXED_CHOICES.keys.associateWith { TemplateFieldKind.FIXED_CHOICE }

    /** The values a list has on load, or `null` for one that changes at runtime. */
    fun staticValues(list: TemplateList): Set<String>? =
        when (list) {
            TemplateList.BUILD_SYSTEMS -> BuildSystem.entries.map { it.name }.toSet()
            TemplateList.ESCROW_GENERATION -> EscrowGenerationMode.entries.map { it.name }.toSet()
            TemplateList.LABELS -> null
        }
}
