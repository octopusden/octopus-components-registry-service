package org.octopusden.octopus.components.registry.server.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

/**
 * ONB-001 — `V8__` adds `source_path` / `checkout_directory` to `vcs_settings_entries` and back-fills
 * `checkout_directory := name` for the secondary entries (`sort_order > 0`) of multi-entry rows. Rows
 * are seeded on the V7 schema, then Flyway applies the rest; names that are not valid or not distinct
 * Checkout Directories are copied verbatim and never fail the migration.
 */
@Timeout(120)
@Tag("integration")
class V8VcsPlacementMigrationTest {
    private fun flyway(target: String?) =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .apply { target?.let { target(it) } }
            .load()

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun seedRow(vararg names: String): UUID {
        val componentId = UUID.randomUUID()
        val configId = UUID.randomUUID()
        connection().use { c ->
            c.prepareStatement("insert into components(id, component_key) values (?, ?)").use {
                it.setObject(1, componentId)
                it.setString(2, "test-component-$componentId")
                it.executeUpdate()
            }
            c
                .prepareStatement(
                    "insert into component_configurations(id, component_id, version_range, row_type, build_system) " +
                        "values (?, ?, '(,0),[0,)', 'BASE', 'MAVEN')",
                ).use {
                    it.setObject(1, configId)
                    it.setObject(2, componentId)
                    it.executeUpdate()
                }
            names.forEachIndexed { index, name ->
                c
                    .prepareStatement(
                        "insert into vcs_settings_entries(component_configuration_id, name, vcs_path, sort_order) values (?, ?, ?, ?)",
                    ).use {
                        it.setObject(1, configId)
                        it.setString(2, name)
                        it.setString(3, "ssh://git@example.test/proj/repo-$index.git")
                        it.setInt(4, index)
                        it.executeUpdate()
                    }
            }
        }
        return configId
    }

    /** `name -> (source_path, checkout_directory)` in `sort_order`. */
    private fun placement(configId: UUID): List<Triple<String, String?, String?>> =
        connection().use { c ->
            c
                .prepareStatement(
                    "select name, source_path, checkout_directory from vcs_settings_entries " +
                        "where component_configuration_id = ? order by sort_order",
                ).use { st ->
                    st.setObject(1, configId)
                    st.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null }.toList()
                    }
                }
        }

    @Test
    @DisplayName("ONB-001: V8 back-fills checkout_directory = name on secondary entries only; unusable names are copied, not rejected")
    fun `v8 back-fills secondary entries of multi-entry rows`() {
        flyway("7").migrate()
        val twoEntries = seedRow("alpha", "beta")
        val single = seedRow("core")
        val repeatsPrimary = seedRow("main", "main")
        val invalidName = seedRow("main", "a/b")

        flyway(null).migrate()

        assertEquals(listOf(Triple("alpha", null, null), Triple("beta", null, "beta")), placement(twoEntries))
        assertEquals(listOf(Triple("core", null, null)), placement(single))
        assertEquals(listOf(Triple("main", null, null), Triple("main", null, "main")), placement(repeatsPrimary))
        assertEquals(listOf(Triple("main", null, null), Triple("a/b", null, "a/b")), placement(invalidName))
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
