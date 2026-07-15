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

data class ComponentRequiredToolId(
    var componentConfigurationId: UUID = UUID(0, 0),
    var toolName: String = "",
) : Serializable

/**
 * Junction between `component_configurations` and `tools`.
 *
 * Note: this junction's parent is `component_configurations`, not `components` —
 * `build.requiredTools` is a per-version-rangeable marker attribute (see
 * schema-spec.md §3.3). FK + composite-key columns reflect this.
 */
@Entity
@Table(name = "component_required_tools")
@IdClass(ComponentRequiredToolId::class)
class ComponentRequiredToolEntity(
    @Id
    @Column(name = "component_configuration_id", nullable = false)
    var componentConfigurationId: UUID = UUID(0, 0),
    @Id
    @Column(name = "tool_name", length = 100, nullable = false)
    var toolName: String = "",
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_configuration_id", insertable = false, updatable = false)
    var componentConfiguration: ComponentConfigurationEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tool_name", insertable = false, updatable = false)
    var tool: ToolEntity? = null,
)
