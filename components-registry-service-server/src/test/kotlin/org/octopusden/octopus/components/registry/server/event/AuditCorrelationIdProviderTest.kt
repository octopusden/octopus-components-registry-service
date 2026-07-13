package org.octopusden.octopus.components.registry.server.event

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Transaction-scoping guarantees for [AuditCorrelationIdProvider]: one id per
 * transaction, a fresh id per new transaction, a nested `REQUIRES_NEW` gets its
 * own id (with the outer id restored afterwards), the id is unbound on rollback,
 * and none leaks onto the thread once a transaction has completed.
 *
 * Focused unit test: it drives a real transaction lifecycle (suspend/resume/
 * completion) via a [DataSourceTransactionManager] over an in-memory H2 datasource,
 * with no Spring context — the provider touches no DB, only the synchronization
 * manager, so this exercises exactly the behaviour under test without a full boot.
 */
class AuditCorrelationIdProviderTest {
    private val provider = AuditCorrelationIdProvider()
    private lateinit var txManager: PlatformTransactionManager

    @BeforeEach
    fun setUp() {
        val ds = DriverManagerDataSource("jdbc:h2:mem:audit-corr;DB_CLOSE_DELAY=-1", "sa", "")
        ds.setDriverClassName("org.h2.Driver")
        txManager = DataSourceTransactionManager(ds)
    }

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
    @DisplayName("the id is unbound on rollback (afterCompletion fires for both outcomes)")
    fun unbindsOnRollback() {
        lateinit var rolledBack: String
        runCatching {
            TransactionTemplate(txManager).execute {
                rolledBack = provider.current()
                throw RuntimeException("boom") // forces rollback
            }
        }
        // After the rolled-back transaction, no id lingers: an out-of-transaction
        // call mints a fresh one rather than returning the rolled-back id.
        val after = provider.current()
        assert(after != rolledBack) { "rolled-back transaction's id must not linger on the thread" }
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
