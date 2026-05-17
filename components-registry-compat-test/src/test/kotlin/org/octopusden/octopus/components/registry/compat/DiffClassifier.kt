package org.octopusden.octopus.components.registry.compat

/**
 * Categories used to bucket compat-run divergences. See plan §Diff classification scheme.
 */
enum class DiffClassifier {
    // Environment / precondition issues — surfaced once at the top of the report.
    // Both categories are non-suppressible via known-deltas.json (see envCategories in build.gradle).
    SNAPSHOT_MISMATCH,         // baseline vs candidate /service/status .versionControlRevision differ
    CANDIDATE_NOT_DB_MODE,     // candidate /service/status .defaultSource != "db" or .dbComponentCount == 0 —
                               // the candidate is serving the V1 in-memory resolver, schema-v2 DB code path
                               // is dormant, and any diff measurements will reflect V1-vs-V1 drift, not real
                               // schema-v2-vs-V1 regressions. SnapshotPreconditionTest records this.

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
