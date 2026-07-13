package org.octopusden.octopus.components.registry.server.event

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Supplies the `correlation_id` shared by every audit row written during a single
 * save, so the audit history can group same-transaction changes into one record.
 *
 * The id is bound to the CURRENT TRANSACTION as a [TransactionSynchronizationManager]
 * resource: generated lazily on first use, reused for the rest of that transaction,
 * and unbound when the transaction completes. Binding it as a managed resource (not
 * a raw `ThreadLocal`) means Spring suspends/resumes it correctly, so a nested
 * `REQUIRES_NEW` transaction gets its OWN id and the outer id is restored afterwards
 * — a plain `ThreadLocal` would leak the outer id into the inner transaction.
 *
 * A component save is a single `@Transactional` method that publishes one
 * `AuditEvent` per changed entity (base attribute, field override, …); they all
 * therefore share one id, and rows from a different save get a different one.
 *
 * Called with no active transaction, it returns a fresh id each time and binds
 * nothing — no grouping, no leak. (The audit listener is a `@TransactionalEventListener`,
 * so audit events are only ever published inside a transaction in practice.)
 *
 * NOTE — `audit_log.correlation_id` is intentionally overloaded: rows from this API
 * save-path carry a per-save UUID (here), while git-history import rows
 * (`GitHistoryImportServiceImpl`, `source = "git-history"`) carry the git commit
 * SHA, written directly to the entity and never through this provider. The two
 * shapes never collide (UUID vs SHA) and never overwrite each other; a grouping
 * consumer should expect both, keyed by `source`.
 */
@Component
class AuditCorrelationIdProvider {
    fun current(): String {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return UUID.randomUUID().toString()
        }
        (TransactionSynchronizationManager.getResource(RESOURCE_KEY) as String?)?.let { return it }
        val id = UUID.randomUUID().toString()
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, id)
        TransactionSynchronizationManager.registerSynchronization(CorrelationIdSynchronization(id))
        return id
    }

    /**
     * Keeps the correlation-id resource in step with the transaction lifecycle:
     * unbind while the transaction is suspended (so an inner `REQUIRES_NEW` sees
     * none and mints its own), rebind THIS transaction's own id on resume, and
     * unbind for good on completion (both commit and rollback). Resume rebinds
     * unconditionally (unbind-any-then-bind-own), so it can never silently adopt a
     * foreign id — mis-grouping is structurally impossible, not ordering-dependent.
     */
    private class CorrelationIdSynchronization(
        private val id: String,
    ) : TransactionSynchronization {
        override fun suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
        }

        override fun resume() {
            TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, id)
        }

        override fun afterCompletion(status: Int) {
            TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
        }
    }

    private companion object {
        // Instance-independent resource key: even if a manually-constructed provider
        // (a unit test) and the Spring singleton ran in one transaction, they'd share
        // the same resource and mint a single id — never two.
        private val RESOURCE_KEY = Any()
    }
}
