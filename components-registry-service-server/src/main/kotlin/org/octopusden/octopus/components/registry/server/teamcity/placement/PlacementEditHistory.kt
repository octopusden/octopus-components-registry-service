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
 * `updateFieldOverride`), which always re-publishes the row's full current state as the audit
 * event's `newValue` snapshot (never a diff). So: if this repository's checkout/source path (or the
 * row's Build Working Directory) shows up in ANY audit snapshot recorded after V8 ran, a real save
 * touched this row at least once since — the current value cannot be assumed to still be V8's guess.
 *
 * Deliberately biased toward false positives (flags more as "manual" than strictly is): a person
 * re-saving vcsEntries without changing this particular value also counts. That is the safe
 * direction for a writer — it costs Sync a row it could have safely touched, never a clobbered edit.
 * When V8's own timestamp cannot be read at all (pre-V8 schema, or the flyway table is unreadable),
 * every value fails closed as "manual".
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
        return postV8AuditRows(componentId)?.any { newValue ->
            findAll(newValue, "vcsEntries").any { entries ->
                (entries as? List<*>)?.any entry@{ entry ->
                    val row = entry as? Map<*, *> ?: return@entry false
                    repoKey(row["vcsPath"] as? String) == key &&
                        (row["checkoutDirectory"] != null || row["sourcePath"] != null)
                } == true
            }
        } ?: true
    }

    fun wasBuildWorkingDirectoryEverManuallySet(componentId: UUID): Boolean =
        postV8AuditRows(componentId)?.any { newValue -> findAll(newValue, "buildWorkingDirectory").any { it != null } }
            ?: true

    /** Every UPDATE/RENAME `newValue` snapshot for [componentId] since V8 ran, or `null` when V8's
     * own timestamp is unknown (caller then fails closed). */
    private fun postV8AuditRows(componentId: UUID): List<Map<String, Any?>?>? {
        val since = v8Timestamp.v8AppliedAt() ?: return null
        return auditLogRepository
            .findByEntityTypeAndEntityIdAndChangedAtAfterAndActionIn(
                "Component",
                componentId.toString(),
                since,
                listOf("UPDATE", "RENAME"),
            ).map { it.newValue }
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
