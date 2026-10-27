package org.octopusden.octopus.components.registry.server.dto.v4

data class ActualRange(
    val versionRange: String,
    val value: String,
)

data class ActualDisagreement(
    val subRange: String,
    val actualValue: String,
)

data class RegisteredBuildParametersDetail(
    val javaActualRanges: List<ActualRange>,
    val javaWarnings: List<ActualDisagreement>,
    val mavenActualRanges: List<ActualRange>,
    val mavenWarnings: List<ActualDisagreement>,
    val actualDataUnavailable: Boolean,
)
