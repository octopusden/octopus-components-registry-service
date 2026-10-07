package org.octopusden.octopus.components.registry.server.template

/** [ListValues] as the registry serves them: [labels] is the dictionary, read on every call. */
class RegistryListValues(
    private val labels: () -> Collection<String>,
) : ListValues {
    override fun values(list: TemplateList): Set<String> = TemplateFields.staticValues(list) ?: labels().toSet()
}
