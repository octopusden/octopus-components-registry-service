package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "git_history_import_state")
class GitHistoryImportStateEntity(
    @Id
    @Column(name = "import_key")
    var importKey: String = "",
    @Column(name = "target_ref", nullable = false)
    var targetRef: String = "",
    @Column(name = "target_sha", nullable = false)
    var targetSha: String = "",
    @Column(nullable = false)
    var status: String = GitHistoryImportStatus.IN_PROGRESS.name,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

enum class GitHistoryImportStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}
