package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * SYS-066 — result of the one-off composite field-override split. A `dryRun` invocation returns the
 * [manifest] + [manifestToken] without writing; a write must echo the token back (see the service),
 * which is recomputed in-transaction and compared to guarantee the reviewed state is the one changed.
 */
data class CompositeOverrideSplitResult(
    val dryRun: Boolean,
    /** Deterministic hash over the canonically-ordered manifest — pins the reviewed state. */
    val manifestToken: String,
    val rowsSplit: Int,
    val segmentsCreated: Int,
    /** Segments not created because an identical sibling row already covered that exact interval. */
    val segmentsSkippedAsDuplicate: Int,
    val componentsAffected: Int,
    val manifest: List<CompositeOverrideSplitEntry>,
)

/** One composite row that will be (or was) split into [segments]. */
data class CompositeOverrideSplitEntry(
    val componentKey: String,
    val overriddenAttribute: String,
    val originalRange: String,
    val segments: List<String>,
    val vcsEntryCount: Int,
)
