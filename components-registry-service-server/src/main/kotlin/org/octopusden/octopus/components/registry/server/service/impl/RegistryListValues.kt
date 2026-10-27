package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.TemplateList
import org.octopusden.octopus.components.registry.server.service.ListValues
import org.octopusden.octopus.components.registry.server.util.TemplateFields

/** [ListValues] as the registry serves them: [labels] is the dictionary, read on every call. */
class RegistryListValues(
    private val labels: () -> Collection<String>,
) : ListValues {
    override fun values(list: TemplateList): Set<String> = TemplateFields.staticValues(list) ?: labels().toSet()
}
