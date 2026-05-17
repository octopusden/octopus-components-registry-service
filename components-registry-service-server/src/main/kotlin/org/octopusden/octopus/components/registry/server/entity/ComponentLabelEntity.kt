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

data class ComponentLabelId(
    var componentId: UUID = UUID(0, 0),
    var labelCode: String = "",
) : Serializable

@Entity
@Table(name = "component_labels")
@IdClass(ComponentLabelId::class)
class ComponentLabelEntity(
    @Id
    @Column(name = "component_id", nullable = false)
    var componentId: UUID = UUID(0, 0),

    @Id
    @Column(name = "label_code", length = 100, nullable = false)
    var labelCode: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id", insertable = false, updatable = false)
    var component: ComponentEntity? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "label_code", insertable = false, updatable = false)
    var label: LabelEntity? = null,
)
