package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** When the ADR-001 V8 migration (`checkout_directory = name` backfill) ran on this database. */
fun interface V8MigrationTimestampProvider {
    /** `null` when V8 has not (yet) been observed to run — a fresh pre-V8 schema, or the row is unreadable. */
    fun v8AppliedAt(): Instant?
}

/** Reads V8's `installed_on` straight from Flyway's own history table — no app-level record of it exists. */
@ConditionalOnDatabaseEnabled
@Component
class JdbcV8MigrationTimestampProvider(
    private val jdbcTemplate: JdbcTemplate,
) : V8MigrationTimestampProvider {
    override fun v8AppliedAt(): Instant? =
        runCatching {
            jdbcTemplate
                .queryForObject(
                    "SELECT installed_on FROM flyway_schema_history WHERE version = '8'",
                    java.sql.Timestamp::class.java,
                )?.toInstant()
        }.getOrNull()
}

/**
 * Tells a placement value the ADR-001 V8 migration set automatically from one a person actually
 * edited — the safety check `TeamcityPlacementSyncService` uses before overwriting anything
 * (Diff/Sync brief: "Overwrite only V8-auto values, never manual edits").
 *
 * V8 ran a raw SQL backfill (`UPDATE vcs_settings_entries SET checkout_directory = name WHERE
 * sort_order > 0`) that bypassed the application entirely, so it left no `audit_log` row. Every
 * *real* edit, by contrast, goes through `ComponentManagementServiceImpl` (`replaceVcsEntries` /
 * `updateFieldOverride`), which snapshots the row's full state into BOTH `oldValue` (before the
 * patch) and `newValue` (after) — same shape, captured by the same function on either side of the
 * mutation. So: this repository's placement (or the row's Build Working Directory) really changed
 * in a given audit row iff its value in `newValue` differs from its value in `oldValue` — that
 * catches a set, a re-point, AND a manual CLEAR to null alike (checking `newValue` alone would miss
 * a clear: nulling a field on purpose looks identical to it never having been set). If ANY post-V8
 * row shows such a difference, a real save touched it since — the current value cannot be assumed
 * to still be V8's guess.
 *
 * A row that never touches this field at all (`oldValue` and `newValue` agree, including both
 * lacking it) contributes no signal — most audit rows for a component don't touch this component's
 * placement. When V8's own timestamp cannot be read at all (pre-V8 schema, or the flyway table is
 * unreadable), every value fails closed as "manual".
 */
@ConditionalOnDatabaseEnabled
@Service
class PlacementEditHistory(
    private val auditLogRepository: AuditLogRepository,
    private val v8Timestamp: V8MigrationTimestampProvider,
) {
    fun wasEverManuallyPlaced(
        componentId: UUID,
        vcsPath: String,
    ): Boolean {
        val key = repoKey(vcsPath)
        return postV8AuditRows(componentId)?.any { (old, new) -> findVcsEntryFields(new, key) != findVcsEntryFields(old, key) }
            ?: true
    }

    fun wasBuildWorkingDirectoryEverManuallySet(componentId: UUID): Boolean =
        postV8AuditRows(componentId)?.any { (old, new) -> findAll(new, "buildWorkingDirectory") != findAll(old, "buildWorkingDirectory") }
            ?: true

    /** Every UPDATE/RENAME (oldValue, newValue) snapshot pair for [componentId] since V8 ran, or
     * `null` when V8's own timestamp is unknown (caller then fails closed). */
    private fun postV8AuditRows(componentId: UUID): List<Pair<Map<String, Any?>?, Map<String, Any?>?>>? {
        val since = v8Timestamp.v8AppliedAt() ?: return null
        return auditLogRepository
            .findByEntityTypeAndEntityIdAndChangedAtAfterAndActionIn(
                "Component",
                componentId.toString(),
                since,
                listOf("UPDATE", "RENAME"),
            ).map { it.oldValue to it.newValue }
    }

    /** Every (checkoutDirectory, sourcePath) pair of a `vcsEntries` element matching [key] found
     * anywhere in the (arbitrarily nested map/list) JSON tree — a component snapshot carries at
     * most one (the base row's, or one override row's; never both in the same audit event). */
    private fun findVcsEntryFields(
        node: Any?,
        key: String,
    ): List<Pair<Any?, Any?>> =
        findAll(node, "vcsEntries").flatMap { entries ->
            (entries as? List<*>).orEmpty().mapNotNull { entry ->
                val row = entry as? Map<*, *> ?: return@mapNotNull null
                if (repoKey(row["vcsPath"] as? String) == key) row["checkoutDirectory"] to row["sourcePath"] else null
            }
        }

    /** Every value found anywhere in the (arbitrarily nested map/list) JSON tree under [key]. */
    private fun findAll(
        node: Any?,
        key: String,
    ): List<Any?> =
        when (node) {
            is Map<*, *> -> (if (node.containsKey(key)) listOf(node[key]) else emptyList()) + node.values.flatMap { findAll(it, key) }
            is List<*> -> node.flatMap { findAll(it, key) }
            else -> emptyList()
        }
}
