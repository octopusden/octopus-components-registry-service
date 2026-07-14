package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * SYS-060 — append-only operational service-event row.
 *
 * Records JOB RUNS (component/history migration, TeamCity resync, portal
 * validation sweep) and DEPLOY markers (STARTUP), which today live only in an
 * in-memory `AtomicReference` + logs and are lost on pod restart. One row per
 * run: created RUNNING, transitioned in place to COMPLETED/FAILED (matched by
 * [correlationId]); STARTUP rows are written terminal.
 *
 * `detail` mirrors the `audit_log` JSON convention exactly — a plain `TEXT`
 * column at the DDL level with `@JdbcTypeCode(SqlTypes.JSON)` so Hibernate
 * serializes/deserializes the `Map<String, Any?>` payload (result counters /
 * errorMessage). See [AuditLogEntity].
 */
@Entity
@Table(name = "service_event")
class ServiceEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "event_type", nullable = false, length = 40)
    var eventType: String = "",
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "",
    @Column(name = "source", nullable = false, length = 20)
    var source: String = "",
    @Column(name = "triggered_by")
    var triggeredBy: String? = null,
    @Column(name = "service_version", length = 100)
    var serviceVersion: String? = null,
    @Column(name = "correlation_id")
    var correlationId: String? = null,
    @Column(name = "summary", columnDefinition = "TEXT")
    var summary: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "TEXT")
    var detail: Map<String, Any?>? = null,
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),
    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
)
