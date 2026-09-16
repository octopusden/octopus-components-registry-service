package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * RELENG-3561: `idpmonitoring` Prometheus scrapes `/actuator/prometheus` in-cluster with no
 * token, so that one endpoint must be anonymous. It exposes counters and timers only — no
 * configuration, no memory contents.
 *
 * The negative case matters as much as the positive one. [WebSecurityConfig] opens a single
 * literal path; a wildcard under `/actuator` would be shorter and would also open `heapdump`,
 * which is a memory dump of a production process. `/actuator/metrics` is the guard against
 * that regression: it is exposed (see `application-common.yml`) yet must stay authenticated,
 * so a 401 here proves the security rule is doing the work. An endpoint that is merely
 * unexposed would answer 404 and prove nothing.
 *
 * `AuthServerClient` is mocked rather than setting `auth-server.disabled=true`: that flag
 * swaps in [AnonymousSecurityConfig], whose chain is `anyRequest().permitAll()`, and every
 * assertion below would then pass without [WebSecurityConfig] being involved at all.
 *
 * Runs on the H2 `smoke` profile rather than the Testcontainers-backed `test` one. Nothing
 * here touches the database, so requiring Docker would buy nothing — and staying off
 * `@Tag("integration")` keeps this in the fast gate, where a security regression should
 * surface immediately rather than in the heavy suite.
 *
 * **What this test does not prove.** It asserts that the filter chain lets the scrape path
 * through, not that the endpoint returns metrics. In this context it cannot: Spring reports
 * `@ConditionalOnEnabledMetricsExport management.defaults.metrics.export.enabled is considered
 * false`, so no `PrometheusMeterRegistry` and no `PrometheusScrapeEndpoint` bean is created and
 * the path answers 404 once security permits it. Nothing in this repository, in service-config,
 * or in the bootstrap files sets that property — the cause was not identified. Whether the
 * deployed service registers the endpoint is therefore still open, and is answered by probing
 * production (RELENG-3506), not from here. Until it is, RELENG-3561 alone does not make this
 * service scrapeable.
 *
 * Asserting "not 401" rather than "200" keeps the assertion aligned with what the ticket
 * changes — a security matcher — instead of quietly depending on metrics-export wiring that
 * this ticket does not touch.
 *
 * There is likewise no assertion on `/actuator/health`: it answers 404 under this profile,
 * which predates this change, and a 404 says nothing about a security rule since a
 * blocked-but-mapped endpoint would answer 401.
 */
@ExtendWith(SpringExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@AutoConfigureMockMvc
@ActiveProfiles("common", "smoke")
class ActuatorPrometheusAccessTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    fun `prometheus scrape endpoint is not blocked by security`() {
        val status =
            mvc
                .perform(get("/actuator/prometheus"))
                .andReturn()
                .response
                .status
        assertNotEquals(
            401,
            status,
            "the Prometheus scrape path must pass the filter chain anonymously, but security rejected it",
        )
    }

    @Test
    fun `metrics endpoint stays behind auth`() {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized)
    }

    companion object {
        init {
            BaseComponentsRegistryServiceTest.configureSpringAppTestDataDir()
        }
    }
}
