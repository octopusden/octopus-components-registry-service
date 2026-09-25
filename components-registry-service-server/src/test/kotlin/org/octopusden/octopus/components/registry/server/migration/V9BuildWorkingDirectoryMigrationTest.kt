package org.octopusden.octopus.components.registry.server.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

/**
 * ONB-001 rev. 3 — `V9__` adds a nullable `build_working_directory` to `component_configurations`
 * without touching data: from an empty database (V8 and V9 together) and from a database already on
 * V8 (as QA is), where every entry keeps its placement.
 */
@Timeout(120)
@Tag("integration")
class V9BuildWorkingDirectoryMigrationTest {
    private fun flyway(target: String? = null) =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(false)
            .apply { target?.let { target(it) } }
            .load()

    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() } }
        }

    private fun execute(sql: String) =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { it.executeUpdate(sql) }
        }

    @BeforeEach
    fun cleanDatabase() {
        flyway().clean()
    }

    @Test
    @DisplayName("ONB-001 rev. 3: V8 and V9 from an empty database add a nullable build_working_directory")
    fun `from an empty database`() {
        flyway().migrate()

        assertEquals(
            listOf("YES"),
            query(
                "select is_nullable from information_schema.columns " +
                    "where table_name = 'component_configurations' and column_name = 'build_working_directory'",
            ) { it.getString(1) },
        )
    }

    @Test
    @DisplayName("ONB-001 rev. 3: V9 on a V8 database keeps every entry's placement and sets no Build Working Directory")
    fun `from a V8 database`() {
        flyway("8").migrate()
        val componentId = UUID.randomUUID()
        val configId = UUID.randomUUID()
        execute("insert into components(id, component_key) values ('$componentId', 'test-component-$componentId')")
        execute(
            "insert into component_configurations(id, component_id, version_range, row_type, build_system) " +
                "values ('$configId', '$componentId', '(,0),[0,)', 'BASE', 'MAVEN')",
        )
        execute(
            "insert into vcs_settings_entries(component_configuration_id, name, vcs_path, sort_order, source_path, checkout_directory) " +
                "values ('$configId', 'core', 'ssh://git@example.test/proj/core.git', 0, null, 'core'), " +
                "('$configId', 'main', 'ssh://git@example.test/proj/app.git', 1, 'data', null)",
        )

        flyway().migrate()

        assertEquals(
            listOf(Triple("core", null, "core"), Triple("main", "data", null)),
            query(
                "select name, source_path, checkout_directory from vcs_settings_entries " +
                    "where component_configuration_id = '$configId' order by sort_order",
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) },
        )
        assertEquals(
            listOf<String?>(null),
            query("select build_working_directory from component_configurations where id = '$configId'") { it.getString(1) },
        )
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
