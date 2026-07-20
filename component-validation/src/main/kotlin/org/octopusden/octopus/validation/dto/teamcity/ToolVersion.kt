package org.octopusden.octopus.validation.dto.teamcity

/** A resolved tool version. Sealed so the version-extraction machinery stays exhaustive. */
sealed interface ToolVersion {
    val raw: String
}

/**
 * A resolved Java version. [isEight] accepts both the legacy `"1.8"` spelling and the modern
 * `"8"` spelling — both are seen in real TeamCity configs (see docs/teamcity-validation-design.md
 * §4 and the implementation brief §2).
 */
data class JavaVersion(
    override val raw: String,
) : ToolVersion {
    val isEight: Boolean
        get() = raw.trim() == "1.8" || raw.trim() == "8"
}

/** A resolved Maven version. No threshold check — there is no "old Maven" question. */
data class MavenVersion(
    override val raw: String,
) : ToolVersion
