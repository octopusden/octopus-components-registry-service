package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.model.TemplateList

/** The current values of a registry list (Decision 5): labels from the dictionary, the others from their enums. */
fun interface ListValues {
    fun values(list: TemplateList): Set<String>
}
