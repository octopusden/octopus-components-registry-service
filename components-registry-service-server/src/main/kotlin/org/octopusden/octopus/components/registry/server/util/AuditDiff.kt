package org.octopusden.octopus.components.registry.server.util

/**
 * Field-level diff of two map snapshots. Format:
 * `{ fieldName -> { "old" -> oldValue, "new" -> newValue } }`.
 *
 * Used by the runtime `AuditEventListener` and the history backfill importer
 * so both produce identical `audit_log.change_diff` payloads.
 */
object AuditDiff {
    fun compute(
        old: Map<String, Any?>?,
        new: Map<String, Any?>?,
    ): Map<String, Any?>? {
        if (old == null || new == null) return null
        val diff = mutableMapOf<String, Any?>()
        val allKeys = old.keys + new.keys
        for (key in allKeys) {
            val oldVal = old[key]
            val newVal = new[key]
            if (oldVal != newVal) {
                diff[key] = mapOf("old" to oldVal, "new" to newVal)
            }
        }
        return diff.ifEmpty { null }
    }
}
