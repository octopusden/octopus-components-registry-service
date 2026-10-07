package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema
import org.octopusden.octopus.components.registry.server.template.ComponentTemplate
import org.octopusden.octopus.components.registry.server.template.ListValues
import org.octopusden.octopus.components.registry.server.template.TemplateParameter

/** `GET /rest/api/4/component-templates/{id}`: a live template and its parameters, in configured order. */
data class ComponentTemplateResponse(
    val id: String,
    val version: Int,
    val title: String,
    val description: String,
    val classification: ComponentProfileResponse.Classification,
    @field:Schema(description = "Create-request paths a creator may override.")
    val overridable: List<String>,
    val parameters: List<Parameter>,
) {
    /** Only the settings of the parameter's [type] are present. */
    @Schema(name = "ComponentTemplateParameter")
    data class Parameter(
        val name: String,
        val label: String,
        val hint: String?,
        @field:Schema(allowableValues = ["text", "select", "crs-list", "person"])
        val type: String,
        val required: Boolean,
        val multiple: Boolean,
        @field:Schema(description = "The default values; a person parameter's `current-user` is returned as the caller.")
        val default: List<String>,
        val pattern: String? = null,
        val message: String? = null,
        val maxLength: Int? = null,
        val options: List<String>? = null,
        val maxSelection: Int? = null,
        @field:Schema(allowableValues = ["build-systems", "escrow-generation", "labels"])
        val list: String? = null,
        @field:Schema(description = "A crs-list parameter's list, as it is at the time of the call.")
        val values: List<String>? = null,
    )

    companion object {
        fun from(
            template: ComponentTemplate,
            lists: ListValues,
            caller: String,
        ) = ComponentTemplateResponse(
            id = template.id,
            version = template.version,
            title = template.title,
            description = template.description,
            classification = ComponentProfileResponse.Classification.from(template.classification),
            overridable = template.overridable,
            parameters = template.parameters.map { parameter(it, lists, caller) },
        )

        private fun parameter(
            parameter: TemplateParameter,
            lists: ListValues,
            caller: String,
        ): Parameter {
            val common =
                Parameter(
                    name = parameter.name,
                    label = parameter.label,
                    hint = parameter.hint,
                    type = "",
                    required = parameter.required,
                    multiple = parameter.multiple,
                    default = emptyList(),
                )
            return when (parameter) {
                is TemplateParameter.Text ->
                    common.copy(
                        type = "text",
                        default = listOfNotNull(parameter.default),
                        pattern = parameter.pattern,
                        message = parameter.message,
                        maxLength = parameter.maxLength,
                    )
                is TemplateParameter.Select ->
                    common.copy(
                        type = "select",
                        default = parameter.default,
                        options = parameter.options,
                        maxSelection = parameter.maxSelection,
                    )
                is TemplateParameter.CrsList ->
                    common.copy(
                        type = "crs-list",
                        default = parameter.default,
                        list = parameter.list.key,
                        values = lists.values(parameter.list).sorted(),
                    )
                is TemplateParameter.Person ->
                    common.copy(
                        type = "person",
                        default = parameter.default.map { if (it == TemplateParameter.CURRENT_USER) caller else it },
                    )
            }
        }
    }
}
