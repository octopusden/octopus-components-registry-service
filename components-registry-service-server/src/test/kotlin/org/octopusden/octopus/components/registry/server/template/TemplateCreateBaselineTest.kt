package org.octopusden.octopus.components.registry.server.template

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.dto.v4.BaseConfigurationRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BuildAspectRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Paths
import java.util.UUID

/**
 * Characterization of today's create, on the two behaviours the template dry run is built on
 * (Decision 8): a taken key fails with a `name:`-prefixed [IllegalArgumentException], and a create
 * rolled back after it has flushed leaves no component, no new label dictionary row and no audit row.
 */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class TemplateCreateBaselineTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var service: ComponentManagementService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var labelRepository: LabelRepository

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    init {
        val testResourcesPath =
            Paths.get(TemplateCreateBaselineTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeEach
    fun signInAsAdmin() {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("preferred_username", "alice")
                .build()
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun uniqueKey(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun request(
        name: String,
        labels: Set<String> = emptySet(),
    ) = ComponentCreateRequest(
        name = name,
        componentOwner = "owner1",
        labels = labels,
        baseConfiguration = BaseConfigurationRequest(build = BuildAspectRequest(buildSystem = "MAVEN")),
    )

    @Test
    @DisplayName("Decision 9 baseline: a taken key fails with \"name: a component with name '<key>' already exists\"")
    fun takenKey() {
        val key = uniqueKey("baseline-taken")
        service.createComponent(request(key))
        val auditRows = auditLogRepository.count()

        val failure = assertThrows<IllegalArgumentException> { service.createComponent(request(key)) }

        assertEquals("name: a component with name '$key' already exists", failure.message)
        assertEquals(auditRows, auditLogRepository.count())
    }

    @Test
    @DisplayName("Decision 8 baseline: a create rolled back after it flushed leaves no component, label or audit row")
    fun rolledBackCreateLeavesNothing() {
        val key = uniqueKey("baseline-rollback")
        val label = uniqueKey("baseline-label")
        val auditRows = auditLogRepository.count()

        TransactionTemplate(transactionManager).execute { status ->
            status.setRollbackOnly()
            service.createComponent(request(key, labels = setOf(label)))
            assertTrue(componentRepository.existsByComponentKey(key), "the create must have flushed inside the transaction")
        }

        assertFalse(componentRepository.existsByComponentKey(key))
        assertNull(labelRepository.findByCode(label))
        assertEquals(auditRows, auditLogRepository.count())
    }
}
