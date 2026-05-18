package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

data class ComponentSystemId(
    var componentId: UUID = UUID(0, 0),
    var systemCode: String = "",
) : Serializable

@Entity
@Table(name = "component_systems")
@IdClass(ComponentSystemId::class)
class ComponentSystemEntity(
    @Id
    @Column(name = "component_id", nullable = false)
    var componentId: UUID = UUID(0, 0),

    @Id
    @Column(name = "system_code", length = 50, nullable = false)
    var systemCode: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id", insertable = false, updatable = false)
    var component: ComponentEntity? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "system_code", insertable = false, updatable = false)
    var system: SystemEntity? = null,
)
