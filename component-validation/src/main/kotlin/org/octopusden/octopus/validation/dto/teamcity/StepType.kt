package org.octopusden.octopus.validation.dto.teamcity

/**
 * The runner category of a [BuildStep]. Mapping TeamCity's raw step `type` string (e.g.
 * `gradle-runner`, `Maven2`, `simpleRunner`) to this enum is a mapping-layer concern owned by the
 * server (decision D11) — this module never sees the raw string. Unmapped raw types resolve to
 * [OTHER] on the server side.
 */
enum class StepType {
    GRADLE,
    MAVEN,
    COMMAND_LINE,
    IN_CONTAINER,
    OTHER,
}
