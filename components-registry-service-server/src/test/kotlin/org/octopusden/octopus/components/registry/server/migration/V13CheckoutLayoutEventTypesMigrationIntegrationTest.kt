package org.octopusden.octopus.components.registry.server.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/** SYS-102: V13__ renames the stored placement event types to checkout layout and leaves the others alone. */
@Timeout(120)
@Tag("integration")
class V13CheckoutLayoutEventTypesMigrationIntegrationTest {
    @Test
    @DisplayName("SYS-102: V13 renames the stored placement event types to checkout layout")
    fun `SYS-102 V13 renames the placement event types`() {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()

            fun flyway(target: String) =
                Flyway
                    .configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .locations("classpath:db/migration")
                    .target(target)
                    .load()

            flyway("12").migrate()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                listOf("TEAMCITY_PLACEMENT_DIFF", "TEAMCITY_PLACEMENT_SYNC", "TEAMCITY_RESYNC").forEach { type ->
                    conn.createStatement().use {
                        it.execute("INSERT INTO service_event(event_type, status, source) VALUES ('$type', 'COMPLETED', 'TEST')")
                    }
                }

                flyway("13").migrate()

                val types =
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT event_type FROM service_event ORDER BY id").use { rs ->
                            generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                        }
                    }
                assertEquals(listOf("TEAMCITY_CHECKOUT_LAYOUT_DIFF", "TEAMCITY_CHECKOUT_LAYOUT_SYNC", "TEAMCITY_RESYNC"), types)
            }
        }
    }
}
