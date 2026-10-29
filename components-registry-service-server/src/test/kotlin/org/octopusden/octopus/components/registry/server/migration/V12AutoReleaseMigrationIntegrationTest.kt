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
 * SYS-104 schema migration: V12__ adds `components.auto_release` NOT NULL DEFAULT false, so every
 * component that exists before it reads false. Applies V1..V11 to a real PostgreSQL, seeds rows,
 * then applies V12.
 */
@Timeout(120)
@Tag("integration")
class V12AutoReleaseMigrationIntegrationTest {
    @Test
    @DisplayName("SYS-104: V12 adds auto_release defaulting to false for existing and new components")
    fun `SYS-104 V12 adds auto_release defaulting to false`() {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()

            fun flyway(target: String) =
                Flyway
                    .configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .locations("classpath:db/migration")
                    .target(target)
                    .load()

            flyway("11").migrate()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { it.execute("INSERT INTO components(component_key) VALUES ('before')") }

                flyway("12").migrate()

                conn.createStatement().use { it.execute("INSERT INTO components(component_key) VALUES ('after')") }
                val flags =
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT component_key, auto_release FROM components").use { rs ->
                            generateSequence { if (rs.next()) rs.getString(1) to rs.getBoolean(2) else null }.toMap()
                        }
                    }
                assertEquals(mapOf("before" to false, "after" to false), flags)
            }
        }
    }
}
