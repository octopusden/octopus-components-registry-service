package org.octopusden.octopus.components.registry.server.model

import org.octopusden.octopus.components.registry.server.model.ComponentProfile

/**
 * A live `template` entry of `components-registry.component-profiles`: a fixed classification,
 * the parameters a creator fills, and the create-request fields built from them (Decision 1).
 *
 * Only a template that passed every load check exists as this type; a failed one is a
 * [ProfileLoad.Entry] with problems.
 * [classification] is never [ComponentProfile.Explicit.ASK].
 */
data class ComponentTemplate(
    val id: String,
    val title: String,
    val description: String,
    val order: Int,
    val version: Int,
    val classification: ComponentProfile.Classification,
    val parameters: List<TemplateParameter>,
    val fields: Map<String, TemplateField>,
    val overridable: List<String>,
    val rules: List<ComponentProfile.FieldRule>,
)

/** One parameter a creator fills, in configured order. [required] defaults to `true`. */
sealed interface TemplateParameter {
    val name: String
    val label: String
    val hint: String?
    val required: Boolean
    val multiple: Boolean

    data class Text(
        override val name: String,
        override val label: String,
        override val hint: String?,
        override val required: Boolean,
        val pattern: String?,
        val message: String?,
        val maxLength: Int?,
        val default: String?,
    ) : TemplateParameter {
        override val multiple = false
    }

    data class Select(
        override val name: String,
        override val label: String,
        override val hint: String?,
        override val required: Boolean,
        override val multiple: Boolean,
        val options: List<String>,
        val maxSelection: Int?,
        val default: List<String>,
    ) : TemplateParameter

    data class CrsList(
        override val name: String,
        override val label: String,
        override val hint: String?,
        override val required: Boolean,
        val list: TemplateList,
        val default: List<String>,
    ) : TemplateParameter {
        override val multiple = list.multiple
    }

    /** [default] holds logins, or [CURRENT_USER], resolved to the caller when values are checked. */
    data class Person(
        override val name: String,
        override val label: String,
        override val hint: String?,
        override val required: Boolean,
        override val multiple: Boolean,
        val default: List<String>,
    ) : TemplateParameter

    companion object {
        const val CURRENT_USER = "current-user"
    }
}

/** A registry list a `crs-list` parameter draws from. There is no client-code list (Decision 13). */
enum class TemplateList(
    val key: String,
    val multiple: Boolean,
) {
    BUILD_SYSTEMS("build-systems", false),
    ESCROW_GENERATION("escrow-generation", false),

    /** The labels dictionary: database rows that change at runtime, so never checked on load. */
    LABELS("labels", true),
    ;

    companion object {
        fun byKey(key: String): TemplateList? = entries.find { it.key == key }
    }
}

/** The kind of value a template field takes (Decision 2). */
enum class TemplateFieldKind(
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

/** A field the template sets: one value, or the items of a list field in index order. */
sealed interface TemplateField {
    val path: String
    val kind: TemplateFieldKind
    val expressions: List<TemplateExpression>

    val parameters: Set<String>
        get() = expressions.flatMapTo(linkedSetOf()) { it.parameters }

    data class Single(
        override val path: String,
        override val kind: TemplateFieldKind,
        val value: TemplateExpression,
    ) : TemplateField {
        override val expressions get() = listOf(value)
    }

    data class Items(
        override val path: String,
        override val kind: TemplateFieldKind,
        val items: List<TemplateExpression>,
    ) : TemplateField {
        override val expressions get() = items
    }
}
