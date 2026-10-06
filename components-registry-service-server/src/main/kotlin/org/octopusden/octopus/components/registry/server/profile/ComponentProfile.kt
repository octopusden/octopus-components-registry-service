package org.octopusden.octopus.components.registry.server.profile

/** A `regular` start-page profile read from `components-registry.component-profiles`. */
data class ComponentProfile(
    val id: String,
    val title: String,
    val description: String,
    val order: Int,
    val classification: ProfileClassification,
    val rules: List<FieldRule>,
)

data class ProfileClassification(
    val external: Boolean,
    val explicit: ExplicitChoice,
    val solution: Boolean,
)

enum class ExplicitChoice {
    TRUE,
    FALSE,

    /** The create wizard asks; a create request may carry either value. */
    ASK,
}

/**
 * A rule a create naming the profile must satisfy: the value at [path], absent read as empty,
 * must match [pattern] as a whole. [path] is one of [CreateRequestPaths.PATHS].
 */
data class FieldRule(
    val path: String,
    val pattern: String,
    val message: String,
) {
    val regex: Regex by lazy { Regex(pattern) }
}

enum class EntryStatus {
    LIVE,
    FAILED,
}

/** One configured entry as loaded: its [kind] as written (null when absent) and every problem found. */
data class ProfileEntry(
    val id: String,
    val kind: String?,
    val status: EntryStatus,
    val problems: List<String>,
)

/**
 * The outcome of one load. [profiles] are the live profiles sorted by order, then id; [problems]
 * are configuration-level. The load is [usable] when it has no configuration-level problem and
 * no failed entry other than a template — a failed template never blocks a load.
 */
data class ProfileParseResult(
    val profiles: List<ComponentProfile>,
    val entries: List<ProfileEntry>,
    val problems: List<String>,
) {
    val usable: Boolean
        get() = problems.isEmpty() && entries.none { it.status == EntryStatus.FAILED && it.kind != TEMPLATE_KIND }
}

const val REGULAR_KIND = "regular"
const val TEMPLATE_KIND = "template"
