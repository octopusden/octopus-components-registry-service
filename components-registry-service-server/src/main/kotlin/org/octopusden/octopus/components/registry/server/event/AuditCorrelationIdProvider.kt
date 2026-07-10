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
 */
@Component
class AuditCorrelationIdProvider {
    // Stable per-bean key for the transaction-scoped resource map (the provider is
    // a singleton, so the same key object is used for bind/get/unbind across calls).
    private val resourceKey = Any()

    fun current(): String {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return UUID.randomUUID().toString()
        }
        (TransactionSynchronizationManager.getResource(resourceKey) as String?)?.let { return it }
        val id = UUID.randomUUID().toString()
        TransactionSynchronizationManager.bindResource(resourceKey, id)
        TransactionSynchronizationManager.registerSynchronization(CorrelationIdSynchronization(resourceKey, id))
        return id
    }

    /**
     * Keeps the correlation-id resource in step with the transaction lifecycle:
     * unbind while the transaction is suspended (so an inner `REQUIRES_NEW` sees
     * none and mints its own), rebind on resume, and unbind for good on completion
     * (both commit and rollback).
     */
    private class CorrelationIdSynchronization(
        private val key: Any,
        private val id: String,
    ) : TransactionSynchronization {
        override fun suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(key)
        }

        override fun resume() {
            if (!TransactionSynchronizationManager.hasResource(key)) {
                TransactionSynchronizationManager.bindResource(key, id)
            }
        }

        override fun afterCompletion(status: Int) {
            TransactionSynchronizationManager.unbindResourceIfPossible(key)
        }
    }
}
