package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult
import org.octopusden.octopus.components.registry.server.service.GitHistoryImportService
import org.octopusden.octopus.components.registry.server.service.HistoryImportProgressListener
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.impl.GitHistoryImportServiceImpl
import org.octopusden.octopus.components.registry.server.service.impl.ImportServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile

/**
 * Test-only fault injection. Loaded ONLY when both gates fire:
 *   - Spring profile `dev-fault-injection` is active (the @Profile annotation
 *     means the @Configuration class is not even instantiated otherwise);
 *   - the matching @ConditionalOnProperty is set to "true".
 *
 * Two gates instead of one because a stray JVM property without the profile
 * activation should NOT trigger fault injection — defense-in-depth against an
 * accidentally-set env var becoming a production kill switch. Operators must
 * explicitly opt into both layers.
 *
 * Used by Verification 5/5a in the plan: drives the SPA into the FAILED
 * destructive-block UX so the Retry flow can be validated end-to-end without
 * killing pods (which would only exercise the loss-of-state path, see
 * Verification 7).
 */
@Configuration
@Profile("dev-fault-injection")
class FaultInjectionConfig {
    init {
        // Startup log — critical that operators can spot "fault injection
        // active" in pod boot logs. ERROR level so it surfaces in
        // alert/monitoring pipelines that filter on ERROR-only.
        log.error(
            "[fault-injection] dev-fault-injection profile is ACTIVE. " +
                "If you see this in a production deployment, the wrong profile is set — " +
                "investigate spring.profiles.active and the matching property gates immediately.",
        )
    }

    /**
     * Wraps the real [ImportService], throwing once `migrateDefaults()` is
     * called (so the SPA observes phase=DEFAULTS first, then a FAILED
     * transition with a recognisable errorMessage). All other methods
     * delegate.
     */
    @Bean
    @Primary
    @ConditionalOnProperty("migration.fault-injection.fail-after-defaults", havingValue = "true")
    fun faultyImportService(real: ImportServiceImpl): ImportService = FaultInjectingImportService(real)

    /**
     * Wraps the real [GitHistoryImportService], throwing immediately at the
     * start of `importHistory` without delegating. No progress events are
     * emitted — the throw happens before the clone+resolve phase, so the
     * SPA goes straight from RUNNING to FAILED with no totalCommits update.
     * The underlying impl's `markState(FAILED)` path handles state row
     * cleanup, which is the same path a real-world failure takes.
     * Verification 5b uses this to test Force-reset.
     */
    @Bean
    @Primary
    @ConditionalOnProperty("migration.fault-injection.fail-history-after-clone", havingValue = "true")
    fun faultyHistoryImportService(real: GitHistoryImportServiceImpl): GitHistoryImportService = FaultInjectingHistoryImportService(real)

    private class FaultInjectingImportService(
        private val delegate: ImportService,
    ) : ImportService by delegate {
        override fun migrateDefaults(): Map<String, Any?> {
            // Run once for real so the operator sees the existing log lines and
            // knows the path was hit, then bomb. A pure throw without delegation
            // would also work but makes the failure feel synthetic.
            delegate.migrateDefaults()
            log.error("[fault-injection] throwing IllegalStateException after migrateDefaults")
            error("fault-injection: migration.fault-injection.fail-after-defaults=true")
        }
    }

    private class FaultInjectingHistoryImportService(
        private val delegate: GitHistoryImportService,
    ) : GitHistoryImportService {
        override fun importHistory(
            toRef: String?,
            reset: Boolean,
            listener: HistoryImportProgressListener,
        ): HistoryImportResult {
            log.error("[fault-injection] throwing IllegalStateException from importHistory before walk")
            // We could delegate part-way, but we don't actually want to leave
            // partial DB state — the underlying impl already marks FAILED on
            // throw via its outer try/catch, that's what we want to exercise.
            error(
                "fault-injection: migration.fault-injection.fail-history-after-clone=true",
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FaultInjectionConfig::class.java)
    }
}
