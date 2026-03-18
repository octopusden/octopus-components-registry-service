package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

@Entity
@Table(name = "jira_component_configs")
class JiraComponentConfigEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id")
    var component: ComponentEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_version_id")
    var componentVersion: ComponentVersionEntity? = null,
    @Column(name = "project_key")
    var projectKey: String? = null,
    @Column(name = "display_name")
    var displayName: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "component_version_format", columnDefinition = "jsonb")
    var componentVersionFormat: Map<String, Any?>? = null,
    var technical: Boolean = false,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var metadata: MutableMap<String, Any?> = mutableMapOf(),
)
