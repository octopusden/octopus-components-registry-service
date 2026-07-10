package org.octopusden.octopus.components.registry.server.event

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Transaction-scoping guarantees for [AuditCorrelationIdProvider]: one id per
 * transaction, a fresh id per new transaction, a nested `REQUIRES_NEW` gets its
 * own id (and the outer id is restored afterwards), and no id leaks onto the
 * thread once a transaction has completed.
 */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class AuditCorrelationIdProviderTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var provider: AuditCorrelationIdProvider

    @Autowired
    private lateinit var txManager: PlatformTransactionManager

    private fun requiresNewTemplate() =
        TransactionTemplate(txManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    @Test
    @DisplayName("the same id is returned for every call within one transaction")
    fun sameIdWithinTransaction() {
        val (a, b) = TransactionTemplate(txManager).execute { provider.current() to provider.current() }!!
        assert(a == b) { "expected one id per transaction, got $a and $b" }
    }

    @Test
    @DisplayName("each transaction gets a distinct id")
    fun distinctIdPerTransaction() {
        val tt = TransactionTemplate(txManager)
        val first = tt.execute { provider.current() }
        val second = tt.execute { provider.current() }
        assert(first != null && second != null && first != second) {
            "expected different ids across transactions, got $first and $second"
        }
    }

    @Test
    @DisplayName("a nested REQUIRES_NEW transaction gets its own id; the outer id is restored")
    fun nestedRequiresNewIsIsolated() {
        lateinit var outerBefore: String
        lateinit var inner: String
        lateinit var outerAfter: String
        TransactionTemplate(txManager).execute {
            outerBefore = provider.current()
            inner = requiresNewTemplate().execute { provider.current() }!!
            outerAfter = provider.current()
        }
        assert(inner != outerBefore) { "inner REQUIRES_NEW must not reuse the outer id ($outerBefore)" }
        assert(outerAfter == outerBefore) { "outer id must be restored after the inner txn ($outerBefore vs $outerAfter)" }
    }

    @Test
    @DisplayName("no id leaks onto the thread after a transaction completes")
    fun noLeakAfterCompletion() {
        val inTx = TransactionTemplate(txManager).execute { provider.current() }
        // Outside any transaction: fresh id each call, never the completed txn's id.
        val out1 = provider.current()
        val out2 = provider.current()
        assert(out1 != inTx) { "completed transaction's id must not linger on the thread" }
        assert(out1 != out2) { "each out-of-transaction call must mint a fresh id" }
    }
}
