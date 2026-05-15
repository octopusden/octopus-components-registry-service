package org.octopusden.octopus.components.registry.compat

/**
 * Categories used to bucket compat-run divergences. See plan §Diff classification scheme.
 */
enum class DiffClassifier {
    // Environment / precondition issues — surfaced once at the top of the report.
    SNAPSHOT_MISMATCH,         // baseline vs candidate /service/status .versionControlRevision differ
    CANDIDATE_NOT_DB_MODE,     // reserved — currently unused (no DB value in ServiceMode enum on this branch)

    // Per-endpoint divergence categories.
    MISSING_COMPONENT,
    STRUCTURAL_DIFF,
    VALUE_DIFF,
    COLLECTION_ORDER,
    NULL_VS_EMPTY,
    STATUS_CODE_DIFF,
    TIMESTAMP_DRIFT,
    HEADER_DIFF,
    MALFORMED_INPUT,
    OOR_PROBE_OK,
    UNCLASSIFIED,
}
