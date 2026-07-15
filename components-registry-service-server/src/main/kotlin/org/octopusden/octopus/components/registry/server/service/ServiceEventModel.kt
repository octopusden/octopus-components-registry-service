package org.octopusden.octopus.components.registry.server.service

import java.util.Locale

/**
 * SYS-060: domain enums for the operational service-event journal (`service_event`).
 *
 * Stored as their `name()` string in the entity (like `audit_log.action` / `.source`)
 * rather than as a JPA `@Enumerated` column, so a new value added here never risks an
 * ordinal shift and unknown values read back gracefully. Validation happens at the
 * recorder / ingest boundary.
 */

/**
 * Coarse split so the Admin "Events" tab can separate operational/system events from
 * product-usage events triggered by an end user (e.g. watching the onboarding video).
 */
enum class ServiceEventCategory {
    /** Operational / lifecycle events emitted by a service (startup, migration, sweep, …). */
    SYSTEM,

    /** Product-usage events triggered by a specific end user (`triggeredBy` = username). */
    USER,
}

enum class ServiceEventType(
    val category: ServiceEventCategory,
) {
    /** A service process (re)started. `serviceVersion` carries the build version. */
    STARTUP(ServiceEventCategory.SYSTEM),

    /** Git→DB components migration run. */
    MIGRATION_COMPONENTS(ServiceEventCategory.SYSTEM),

    /** Git-history backfill run. */
    MIGRATION_HISTORY(ServiceEventCategory.SYSTEM),

    /** TeamCity project-id/url resync run. */
    TEAMCITY_RESYNC(ServiceEventCategory.SYSTEM),

    /** Portal-owned scheduled component-validation sweep run (source=portal). */
    VALIDATION_SWEEP(ServiceEventCategory.SYSTEM),

    /** A user opened the onboarding intro video (source=portal, triggeredBy=username). */
    ONBOARDING_VIDEO_VIEW(ServiceEventCategory.USER),
    ;

    companion object {
        /**
         * Category of a stored `event_type` string. Unknown values (a forward-compat row
         * written by a newer service) read back as SYSTEM so they never leak into the
         * user-facing product-usage view.
         */
        fun categoryOf(eventType: String): ServiceEventCategory =
            runCatching { valueOf(eventType.trim().uppercase(Locale.ROOT)) }.getOrNull()?.category
                ?: ServiceEventCategory.SYSTEM

        /** Enum names whose category is [category] — for translating a category filter to an `event_type IN (…)`. */
        fun namesOf(category: ServiceEventCategory): List<String> = entries.filter { it.category == category }.map { it.name }
    }
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
    val wire: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun fromWire(value: String): ServiceEventSource? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
