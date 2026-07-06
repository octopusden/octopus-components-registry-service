package org.octopusden.octopus.components.registry.server.listener

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.service.ServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component

/**
 * SYS-060: on each pod start, (1) reconcile any service-event rows left RUNNING by a
 * previous pod that died mid-run — flip them to FAILED("interrupted by restart") so a
 * job that "failed" by being killed is reported, never left hanging RUNNING — then
 * (2) write a terminal STARTUP row carrying the build version, giving the Admin
 * "Events" tab a redeploy history.
 *
 * Hooks [ApplicationReadyEvent] (context fully refreshed, DataSource up), NOT the
 * too-early [org.springframework.boot.context.event.ApplicationStartingEvent] that
 * [StartupApplicationListener] uses. Ordering with the TeamCity cron is safe: the
 * scheduled tasks start after context-ready and the resync cron fires Sun 04:00 UTC,
 * so the reconcile cannot race a freshly-created RUNNING row. (`ApplicationReadyEvent`
 * fires just after the web server opens, so in theory an admin POST /migrate landing in
 * the sub-millisecond window before the reconcile runs could have its fresh RUNNING row
 * flipped to FAILED; negligible in practice on a single pod and self-correcting on the
 * next run.)
 *
 * SINGLE-POD ONLY: the reconcile flips every crs RUNNING row, which would clobber a
 * peer pod's live run under multi-pod. Prod runs `replicas: 1`; mirror the single-pod
 * caveats on `AsyncJobLifecycle` / `MigrationStatusControllerV4`. `@ConditionalOnDatabaseEnabled`
 * keeps it out of the no-db profile.
 */
@ConditionalOnDatabaseEnabled
@Component
class ServiceStartupListener(
    private val serviceEventRecorder: ServiceEventRecorder,
    private val buildProperties: ObjectProvider<BuildProperties>,
) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        val reconciled = serviceEventRecorder.reconcileOrphanedRunning(ServiceEventSource.CRS)
        if (reconciled > 0) {
            LOG.warn("Reconciled {} service-event row(s) left RUNNING by a previous pod -> FAILED", reconciled)
        }
        // version is nullable on Spring Boot 4 and absent in dev runs without build-info;
        // fall back to empty so a STARTUP row is still written (matches PortalInfoController).
        val version = buildProperties.ifAvailable?.version.orEmpty()
        serviceEventRecorder.recordInstant(
            type = ServiceEventType.STARTUP,
            source = ServiceEventSource.CRS,
            triggeredBy = "system",
            serviceVersion = version,
            summary = "components-registry-service started",
        )
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ServiceStartupListener::class.java)
    }
}
