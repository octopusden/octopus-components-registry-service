package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * 409 Conflict body returned when one migration kind is rejected because the
 * other kind currently holds the lifecycle gate.
 *
 * Distinct from the existing ErrorResponse so the SPA can switch on `code` to
 * decide UX:
 *  - "components-migration-running" — render destructive block ("Components
 *    migration is currently running. Wait for it to finish.")
 *  - "history-migration-running" — same with "History migration is..."
 *
 * The same-kind 409 (a second components POST while one is RUNNING) does NOT
 * use this shape — it returns a full MigrationJobResponse so the SPA can
 * "attach" to the in-flight job. Cross-kind has no equivalent attach path.
 */
data class MigrationConflictResponse(
    val code: String,
    val message: String,
    val activeKind: String,
    /**
     * Active job id when known (same-pod conflict, gate-tracked active job).
     * Null when the controller can't determine the id — currently the
     * cross-pod staleness path (`history-import-likely-live-elsewhere`)
     * where the row is owned by a different pod and the in-memory gate
     * has no reference to it. Previous form `"(unknown — owned by another pod)"`
     * stuffed human prose into a field whose other paths are real UUIDs;
     * downstream log scrapers / metrics had to special-case the parens.
     */
    val activeJobId: String?,
    /**
     * Discriminator. Always `"conflict"` for this shape — counterpart to
     * `kind = "job"` on MigrationJobResponse / HistoryMigrationJobResponse.
     * The SPA branches on this directly instead of inferring from the
     * presence/absence of fields, which is brittle under contract drift.
     */
    val kind: String = "conflict",
)
