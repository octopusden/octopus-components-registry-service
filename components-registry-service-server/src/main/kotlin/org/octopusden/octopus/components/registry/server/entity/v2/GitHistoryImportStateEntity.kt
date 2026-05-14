package org.octopusden.octopus.components.registry.server.entity.v2

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Schema v2 — per-import-key state row tracking a git-history import run.
 *
 * Status values come from `GitHistoryImportStatus` (`IN_PROGRESS`, `COMPLETED`,
 * `FAILED`); the enum lives next to the legacy entity in
 * `entity/GitHistoryImportStateEntity.kt` for now and stays valid until Phase 2.5.
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
