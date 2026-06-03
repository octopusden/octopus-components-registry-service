package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.octopusden.octopus.components.registry.server.controller.ComponentControllerV4
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import javax.sql.DataSource

/**
 * Fast basic-functionality smoke for the [1.0] Compile & UT gate.
 *
 * Boots the FULL DB-backed Spring context on in-memory H2 (profile `smoke` — mirrors the
 * existing `ft-db` H2 profile but with auto-migrate off, so the context comes up empty and
 * fast) WITHOUT Docker / Testcontainers, and checks the basic functionality the gate must
 * keep covering:
 *   - DB-coupled wiring is present (DataSource + the v4 CRUD controller) — the db-enabled
 *     counterpart of [NoDbModeContextTest], which proves the same context in no-db mode;
 *   - the web -> service -> JPA -> H2 read path (`GET /rest/api/4/components`);
 *   - a JPA write+read round-trip including the `@JdbcTypeCode(SqlTypes.JSON)` -> `TEXT`
 *     mapping (`RegistryConfigEntity`), the dialect-portability point the unit/integration
 *     split relies on.
 *
 * Deliberately UNtagged (no `@Tag("integration")`) so it runs in the fast gate; the heavy
 * Postgres / ft-db `@Tag("integration")` suite runs in CI [1.2] / `qualityCoverage`.
 *
 * webEnvironment = MOCK + auth-server.disabled=true so security wires as in production
 * (AnonymousSecurityConfig; ROLE_ANONYMOUS carries ACCESS_COMPONENTS) and anonymous reads
 * pass without a real Keycloak. The companion initializer sets
 * COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR (which application-common.yml needs) before the
 * context is built — same pattern as [NoDbModeContextTest].
 */
@ExtendWith(SpringExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [ComponentRegistryServiceApplication::class],
    properties = ["auth-server.disabled=true"],
)
@AutoConfigureMockMvc
@ActiveProfiles("common", "smoke")
class BasicFunctionalitySmokeTest {
    companion object {
        init {
            BaseComponentsRegistryServiceTest.configureSpringAppTestDataDir()
        }
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var registryConfigRepository: RegistryConfigRepository

    @Test
    @DisplayName("smoke: DB-backed context wires a DataSource and the v4 CRUD controller")
    fun `db-backed beans are wired`() {
        Assertions.assertTrue(
            ctx.getBeanNamesForType(DataSource::class.java).isNotEmpty(),
            "smoke mode runs with the database enabled, so a DataSource must be wired",
        )
        Assertions.assertEquals(
            1,
            ctx.getBeanNamesForType(ComponentControllerV4::class.java).size,
            "the v4 CRUD controller must be wired when the database is enabled",
        )
    }

    @Test
    @DisplayName("smoke: GET /rest/api/4/components serves the JPA->H2 read path (no components -> 200)")
    fun `list endpoint returns ok with no components in h2`() {
        mvc.perform(get("/rest/api/4/components").accept(APPLICATION_JSON))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("smoke: RegistryConfigEntity round-trips on H2 incl. @JdbcTypeCode(JSON)->TEXT")
    fun `registry config json value round-trips on h2`() {
        val value = mapOf("flag" to "yes", "name" to "smoke")
        registryConfigRepository.save(RegistryConfigEntity(key = "smoke-test", value = value))

        val reloaded = registryConfigRepository.findById("smoke-test").orElseThrow()
        Assertions.assertEquals(
            value,
            reloaded.value,
            "JSON value stored via @JdbcTypeCode(SqlTypes.JSON) on a TEXT column must round-trip on H2",
        )
    }
}
