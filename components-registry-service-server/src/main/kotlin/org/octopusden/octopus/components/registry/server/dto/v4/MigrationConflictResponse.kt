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
    val activeJobId: String,
)
