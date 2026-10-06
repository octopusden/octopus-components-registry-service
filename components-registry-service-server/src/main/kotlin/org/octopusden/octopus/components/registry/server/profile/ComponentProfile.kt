package org.octopusden.octopus.components.registry.server.profile

/** A `regular` start-page profile read from `components-registry.component-profiles`. */
data class ComponentProfile(
    val id: String,
    val title: String,
    val description: String,
    val order: Int,
    val classification: Classification,
    val rules: List<FieldRule>,
) {
    data class Classification(
        val external: Boolean,
        val explicit: Explicit,
        val solution: Boolean,
    )

    enum class Explicit {
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
}
