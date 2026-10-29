package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersReport
import java.time.Instant

/**
 * Admin status view over the RMS build-parameters sweep itself — not any one component's data
 * (see [ComponentDetailResponse.registeredBuildParameters] for that). `enabled = false` means the
 * feature is off entirely (see `RMSBuildParametersService.isEnabled`); every other field is then
 * always at its empty/null default, since a disabled integration never sweeps.
 */
data class RMSSweepStatusResponse(
    val enabled: Boolean,
    val generatedAt: Instant?,
    val lastAttemptAt: Instant?,
    val lastSweepDurationMillis: Long?,
    val refreshError: String?,
    val componentsWithData: Int,
    val unavailableComponents: List<String>,
) {
    companion object {
        fun from(
            enabled: Boolean,
            report: RMSBuildParametersReport,
        ): RMSSweepStatusResponse =
            RMSSweepStatusResponse(
                enabled = enabled,
                generatedAt = report.generatedAt,
                lastAttemptAt = report.lastAttemptAt,
                lastSweepDurationMillis = report.lastSweepDuration?.toMillis(),
                refreshError = report.refreshError,
                componentsWithData = report.components.size,
                unavailableComponents = report.unavailableComponents.sorted(),
            )
    }
}
