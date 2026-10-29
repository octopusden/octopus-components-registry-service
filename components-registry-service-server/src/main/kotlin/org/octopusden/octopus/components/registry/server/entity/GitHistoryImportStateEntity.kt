package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Allowed values for `git_history_import_state.status`. Persisted as a plain VARCHAR. */
enum class GitHistoryImportStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    ;

    companion object {
        fun safeValueOf(value: String?): GitHistoryImportStatus? = value?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}

/**
 * Schema v2 — per-import-key state row tracking a git-history import run.
 *
 * See `ComponentEntity` kdoc for the cross-cutting v2 entity conventions.
 */
@Entity
@Table(name = "git_history_import_state")
class GitHistoryImportStateEntity(
    @Id
    @Column(name = "import_key", length = 64)
    var importKey: String = "",
    @Column(name = "target_ref", nullable = false)
    var targetRef: String = "",
    @Column(name = "target_sha", nullable = false, length = 64)
    var targetSha: String = "",
    @Column(name = "status", nullable = false, length = 16)
    var status: String = "",
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
