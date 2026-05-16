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
import java.util.UUID

/**
 * One structured build-tool bean row in `component_build_tool_beans`.
 *
 * Stores the serialised form of DSL `build { tools { ... } }` entries that
 * cannot be represented as plain tool-name strings in `component_required_tools`
 * (e.g., `OracleDatabaseToolBean` with its typed fields).
 *
 * Six bean types are supported: oracleDatabase, cProduct, kProduct, dProduct,
 * dDbProduct, odbc (CHECK constraint on the DB side).
 *
 * The `edition` column is only populated for `oracleDatabase` rows; a
 * cross-column CHECK enforces this invariant at the DB level.
 */
@Entity
@Table(name = "component_build_tool_beans")
class ComponentBuildToolBeanEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_configuration_id", nullable = false)
    var componentConfiguration: ComponentConfigurationEntity,

    /** Discriminator: one of oracleDatabase, cProduct, kProduct, dProduct, dDbProduct, odbc. */
    @Column(name = "bean_type", length = 32, nullable = false)
    var beanType: String,

    /** Tool type string (e.g. "ORACLE" for oracleDatabase; null for product-type beans). */
    @Column(name = "tool_type", length = 32)
    var toolType: String? = null,

    /** Settings property key used by the build system (e.g. "db", "uscschema"). */
    @Column(name = "settings_property", length = 64)
    var settingsProperty: String? = null,

    /** Version pattern / version string (e.g. "[12,)" for Oracle, "12.2" for ODBC). */
    @Column(name = "version_pattern", columnDefinition = "TEXT")
    var versionPattern: String? = null,

    /** Oracle DB edition (e.g. "ENTERPRISE"). Only valid when beanType = "oracleDatabase". */
    @Column(name = "edition", length = 32)
    var edition: String? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)
