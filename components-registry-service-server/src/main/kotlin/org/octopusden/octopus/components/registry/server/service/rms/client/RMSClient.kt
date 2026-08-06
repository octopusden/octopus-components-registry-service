package org.octopusden.octopus.components.registry.server.service.rms.client

/** One RC/RELEASE build's registered Java/Maven version — either value may be null. */
data class RMSBuild(
    val version: String,
    val javaVersion: String?,
    val mavenVersion: String?,
)

/** [Unavailable] covers every outcome that isn't a confirmed response, so a caller never mistakes "couldn't tell" for "confirmed empty". */
sealed interface RMSBuildsResult {
    data class Available(
        val builds: List<RMSBuild>,
    ) : RMSBuildsResult

    data object Unavailable : RMSBuildsResult
}

/** Fetches a component's RC/RELEASE build history from RMS, ascending by real version. Used by the sweep and the write-time gate alike. */
fun interface RMSClient {
    fun getBuilds(component: String): RMSBuildsResult
}
