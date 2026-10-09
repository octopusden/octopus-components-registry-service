package org.octopusden.octopus.components.registry.server.model

/**
 * The outcome of one load. [profiles] are the live profiles sorted by order, then id; [problems]
 * are configuration-level. The load is [usable] when it has no configuration-level problem and
 * no failed entry other than a template — a failed template never blocks a load.
 */
data class ProfileLoad(
    val profiles: List<ComponentProfile>,
    val entries: List<Entry>,
    val problems: List<String>,
) {
    val usable: Boolean
        get() = problems.isEmpty() && entries.none { it.status == Entry.Status.FAILED && it.kind != ComponentProfile.TEMPLATE_KIND }

    /** One configured entry as loaded: its [kind] as written (null when absent) and every problem found. */
    data class Entry(
        val id: String,
        val kind: String?,
        val status: Status,
        val problems: List<String>,
    ) {
        enum class Status {
            LIVE,
            FAILED,
        }
    }
}
