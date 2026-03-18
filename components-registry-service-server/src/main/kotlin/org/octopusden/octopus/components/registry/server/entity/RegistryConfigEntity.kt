package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "registry_config")
class RegistryConfigEntity(
    @Id
    var key: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var value: Map<String, Any?> = emptyMap(),
    @Column(name = "updated_by")
    var updatedBy: String? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
