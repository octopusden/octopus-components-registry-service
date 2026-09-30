package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Tells a placement value that is safe for Sync to overwrite from one that isn't — the "never
 * overwrite a manual edit" rule (owner review, revised): a field is manual only if its LAST
 * audited change was a real user write, not one tagged as coming from Sync itself
 * ([SYNC_CHANGE_COMMENT_PREFIX]). A field with NO audit record that ever touched it — a null
 * value, the ADR-001 V8 migration's raw-SQL back-fill (which bypassed the app and left no
 * `audit_log` row), or a never-touched V9 Build Working Directory — is non-manual: nothing has
 * ever "really" set it, so there's nothing to protect. A value Sync itself last wrote is also
 * non-manual, so a component TeamCity changed again after a Sync can resolve on the next Diff
 * instead of getting permanently stuck on the Sync's own write.
 *
 * Every real edit goes through `ComponentManagementServiceImpl` (`replaceVcsEntries` /
 * `updateFieldOverride`), which snapshots the row's full state into BOTH `oldValue` (before the
 * write) and `newValue` (after) on the SAME audit row — same shape either side. So a field really
 * changed in a given row iff its value differs between `oldValue` and `newValue` there; that
 * catches a set, a re-point, and a manual CLEAR to null alike (checking `newValue` alone would miss
 * a clear — a value nulled on purpose looks identical to one that was never set). Walking rows
 * newest-first and stopping at the first such difference finds the LAST real change; a row that
 * never touches the field (`oldValue` and `newValue` agree there, including both lacking it)
 * contributes no signal and is skipped.
 */
@ConditionalOnDatabaseEnabled
@Service
class PlacementEditHistory(
    private val auditLogRepository: AuditLogRepository,
) {
    /** Tracked independently of [isSourcePathManuallySet] (owner review finding 2 hardening): a
     * Sync write touching only `sourcePath` must not "launder" an earlier manual
     * `checkoutDirectory` edit into overwritable just because it shares an audit row with a
     * sync-tagged change to the OTHER field. */
    fun isCheckoutDirectoryManuallySet(
        componentId: UUID,
        vcsPath: String,
    ): Boolean = isVcsEntryFieldManual(componentId, vcsPath, "checkoutDirectory")

    fun isSourcePathManuallySet(
        componentId: UUID,
        vcsPath: String,
    ): Boolean = isVcsEntryFieldManual(componentId, vcsPath, "sourcePath")

    private fun isVcsEntryFieldManual(
        componentId: UUID,
        vcsPath: String,
        fieldName: String,
    ): Boolean {
        val key = repoKey(vcsPath)
        for (auditRow in auditRows(componentId)) {
            val old = vcsEntryField(auditRow.oldValue, key, fieldName)
            val new = vcsEntryField(auditRow.newValue, key, fieldName)
            if (old != new) return !isSyncTagged(auditRow)
        }
        return false
    }

    /** [anyVcsPath] is any one of the row's own VCS entries — it scopes the search to the audit
     * snapshot section that carries THIS row's `vcsEntries` (a component may have several
     * `vcs.settings` rows, each with its own Build Working Directory). */
    fun isBuildWorkingDirectoryManuallySet(
        componentId: UUID,
        anyVcsPath: String,
    ): Boolean {
        val key = repoKey(anyVcsPath)
        for (auditRow in auditRows(componentId)) {
            val old = scopedBuildWorkingDirectory(auditRow.oldValue, key)
            val new = scopedBuildWorkingDirectory(auditRow.newValue, key)
            if (old != new) return !isSyncTagged(auditRow)
        }
        return false
    }

    private fun auditRows(componentId: UUID): List<AuditLogEntity> =
        auditLogRepository.findByEntityTypeAndEntityIdAndActionInOrderByChangedAtDesc(
            "Component",
            componentId.toString(),
            // CREATE too: a value set at creation is a human choice (CREATE has no oldValue, so
            // any non-null field in newValue counts as a change). The Git import writes no audit.
            listOf("CREATE", "UPDATE", "RENAME"),
        )

    private fun isSyncTagged(auditRow: AuditLogEntity): Boolean = auditRow.changeComment?.startsWith(SYNC_CHANGE_COMMENT_PREFIX) == true

    /** The single field [fieldName] (`checkoutDirectory` or `sourcePath`) of the `vcsEntries`
     * element matching [key], found anywhere in the (arbitrarily nested) snapshot — a snapshot
     * carries at most one matching section (the base row's, or one override row's; never both in
     * the same audit event). Takes the FIRST matching section, not the first NON-NULL field value:
     * a legitimately null field (e.g. a cleared checkoutDirectory) must not be treated as "not
     * found" and fall through to searching an unrelated section. */
    private fun vcsEntryField(
        node: Any?,
        key: String,
        fieldName: String,
    ): Any? =
        findSections(node, key)
            .firstOrNull()
            ?.let { section ->
                (section["vcsEntries"] as? List<*>)
                    ?.asSequence()
                    ?.mapNotNull { it as? Map<*, *> }
                    ?.firstOrNull { repoKey(it["vcsPath"] as? String ?: "") == key }
                    ?.get(fieldName)
            }

    /** The `buildWorkingDirectory` sibling of the `vcsEntries` section that mentions [key] — never a
     * DIFFERENT row's Build Working Directory, even when the same snapshot happens to carry one. */
    private fun scopedBuildWorkingDirectory(
        node: Any?,
        key: String,
    ): Any? = findSections(node, key).firstOrNull()?.get("buildWorkingDirectory")

    /** Every JSON object anywhere in the (arbitrarily nested map/list) tree that has a `vcsEntries`
     * list containing an entry whose `vcsPath` canonicalizes to [key]. */
    private fun findSections(
        node: Any?,
        key: String,
    ): List<Map<*, *>> =
        when (node) {
            is Map<*, *> -> {
                val ownMatch = (node["vcsEntries"] as? List<*>)
                    ?.any { (it as? Map<*, *>)?.get("vcsPath")?.let { p -> repoKey(p as? String ?: "") } == key } == true
                (if (ownMatch) listOf(node) else emptyList()) + node.values.flatMap { findSections(it, key) }
            }
            is List<*> -> node.flatMap { findSections(it, key) }
            else -> emptyList()
        }

    companion object {
        /** Prefix `TeamcityPlacementSyncService` writes into `audit_log.change_comment` on every
         * field it writes — the provenance marker above, and the rollback trace (technical-design.md
         * §6.8): every row a Sync job wrote carries its job id right after this prefix. */
        const val SYNC_CHANGE_COMMENT_PREFIX = "sync from TeamCity"
    }
}
