package org.octopusden.octopus.components.registry.server.service

/**
 * SYS-060: domain enums for the operational service-event journal (`service_event`).
 *
 * Stored as their `name()` string in the entity (like `audit_log.action` / `.source`)
 * rather than as a JPA `@Enumerated` column, so a new value added here never risks an
 * ordinal shift and unknown values read back gracefully. Validation happens at the
 * recorder / ingest boundary.
 */
enum class ServiceEventType {
    /** A service process (re)started. `serviceVersion` carries the build version. */
    STARTUP,

    /** Git→DB components migration run. */
    MIGRATION_COMPONENTS,

    /** Git-history backfill run. */
    MIGRATION_HISTORY,

    /** TeamCity project-id/url resync run. */
    TEAMCITY_RESYNC,

    /** Portal-owned scheduled component-validation sweep run (source=portal). */
    VALIDATION_SWEEP,
}

/** Lifecycle status of a service-event row. STARTUP rows are written terminal (COMPLETED). */
enum class ServiceEventStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

/** Which service emitted the event. */
enum class ServiceEventSource {
    /** components-registry-service (this service). */
    CRS,

    /** components-management-portal BFF. */
    PORTAL,
    ;

    /**
     * Wire form is lowercase (`crs` / `portal`) to match the `service_event.source`
     * DDL comment and the portal ingest contract; parsing is case-insensitive.
     */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): ServiceEventSource? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
