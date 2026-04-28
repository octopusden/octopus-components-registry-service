package org.octopusden.octopus.components.registry.server.migration

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * SYS-026 — Flyway-managed PostgreSQL schema passes Hibernate validate.
 *
 * Regression guard for PR #148's loosening of `ComponentEntity.system` (dropped
 * `@Column(columnDefinition = "text[]")`). Production profiles (`dev-db`, okd)
 * run with `spring.jpa.hibernate.ddl-auto=validate` on a Flyway-owned schema.
 * Existing DB tests all use `ddl-auto=create` / `create-drop`, so a mismatch
 * between Hibernate's dialect-derived DDL for `@JdbcTypeCode(SqlTypes.ARRAY)
 * Array<String>` and the Flyway `text[]` column would not surface until prod.
 *
 * This test stands up a PostgreSQL 16 testcontainer, lets Flyway apply V1–Vn,
 * then boots the app with `ddl-auto=validate` and asserts the context starts.
 * If Hibernate derives a different column type (e.g. `varchar[]`), Spring's
 * context initialization fails with SchemaManagementException, which bubbles
 * up as the test failure — exactly the signal we want.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db-validate")
@Timeout(120)
class FlywayValidatePostgresStartupTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    init {
        // The `common` test profile resolves `components-registry.work-dir` against this
        // system property; set it the same way every other integration test in this package does.
        val testResourcesPath =
            Paths.get(FlywayValidatePostgresStartupTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-026: Flyway-managed PostgreSQL schema passes Hibernate validate")
    fun flywayManagedSchemaPassesHibernateValidate() {
        // If the @SpringBootTest context fails to load (e.g. Hibernate's
        // SchemaManagementException because the entity-derived DDL doesn't
        // match the Flyway-applied schema), this test never runs — JUnit
        // reports it as failed with the real cause.
        assertNotNull(applicationContext, "Spring context must be initialized")
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
