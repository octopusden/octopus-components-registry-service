package org.octopusden.octopus.components.registry.server.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * SYS-099 data migration: V11__ flags every component carrying the `test-component` label and
 * leaves every other component untouched. Applies V1..V10 to a real PostgreSQL, seeds rows, then
 * applies V11 — the Spring-context migration tests start from an empty schema and cannot see this.
 */
@Timeout(120)
@Tag("integration")
class V11TestComponentMigrationIntegrationTest {
    @Test
    @DisplayName("SYS-099: V11 sets test_component only on components labelled test-component")
    fun `SYS-099 V11 sets test_component only on components labelled test-component`() {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()

            fun flyway(target: String) =
                Flyway
                    .configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .locations("classpath:db/migration")
                    .target(target)
                    .load()

            flyway("10").migrate()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { st ->
                    st.execute("INSERT INTO labels(code) VALUES ('test-component'), ('other')")
                    st.execute("INSERT INTO components(component_key) VALUES ('labelled'), ('other-label'), ('plain')")
                    st.execute(
                        "INSERT INTO component_labels(component_id, label_code) " +
                            "SELECT id, 'test-component' FROM components WHERE component_key = 'labelled' " +
                            "UNION ALL SELECT id, 'other' FROM components WHERE component_key IN ('labelled', 'other-label')",
                    )
                }

                flyway("11").migrate()

                val flags =
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT component_key, test_component FROM components").use { rs ->
                            generateSequence { if (rs.next()) rs.getString(1) to rs.getBoolean(2) else null }.toMap()
                        }
                    }
                assertEquals(mapOf("labelled" to true, "other-label" to false, "plain" to false), flags)
            }
        }
    }
}
