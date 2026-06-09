package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(name = "tools")
// Class-level @BatchSize so the LAZY `ComponentRequiredToolEntity.tool` to-one is batch-loaded
// when the mapper builds the escrow view. Unlike `parentComponent` (a ComponentEntity co-loaded
// by the read path's `findAll()`), tools live in a separate table and are NOT pre-loaded, so
// without this each distinct required tool would issue its own SELECT (a real N+1). To-one batch
// size is read from the target class, not the referencing field.
@BatchSize(size = BATCH_FETCH_SIZE)
class ToolEntity(
    @Id
    @Column(name = "name", length = 100, nullable = false)
    var name: String = "",

    @Column(name = "escrow_env_variable")
    var escrowEnvVariable: String? = null,

    @Column(name = "target_location", columnDefinition = "TEXT")
    var targetLocation: String? = null,

    @Column(name = "source_location", columnDefinition = "TEXT")
    var sourceLocation: String? = null,

    @Column(name = "install_script", columnDefinition = "TEXT")
    var installScript: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)
