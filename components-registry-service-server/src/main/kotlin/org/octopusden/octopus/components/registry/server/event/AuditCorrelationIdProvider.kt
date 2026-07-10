package org.octopusden.octopus.components.registry.server.event

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Supplies the `correlation_id` shared by every audit row written during a single
 * save, so the audit history can group same-transaction changes into one record.
 *
 * The id is bound to the CURRENT TRANSACTION: generated lazily on first use,
 * reused for the rest of that transaction, and cleared when the transaction
 * completes — so a pooled request thread never leaks an id into the next save.
 * A component save is a single `@Transactional` method that publishes one
 * `AuditEvent` per changed entity (base attribute, field override, …); binding
 * to the transaction rather than the request means all of those rows — and only
 * those — share one id, even if a request were ever to span several transactions.
 *
 * Called outside an active transaction (should not happen for saves, but e.g.
 * background paths that publish audit events directly), it returns a fresh id
 * each call: no grouping, but also no `ThreadLocal` leak.
 */
@Component
class AuditCorrelationIdProvider {
    private val holder = ThreadLocal<String>()

    fun current(): String {
        holder.get()?.let { return it }
        val id = UUID.randomUUID().toString()
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            holder.set(id)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        holder.remove()
                    }
                },
            )
        }
        return id
    }
}
