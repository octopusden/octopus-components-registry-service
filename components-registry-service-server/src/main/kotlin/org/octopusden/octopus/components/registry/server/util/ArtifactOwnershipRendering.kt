package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode

/**
 * Renders an artifact-ownership mapping's mode + literal tokens into the legacy v1–v3
 * `artifactIdPattern` string consumed by `MavenArtifactMatcher` (a regex matcher). Shared by the
 * resolver (`/maven-artifacts`, `find-by-artifact`) and the EscrowModuleConfig mapper so the wire
 * form is identical everywhere.
 *
 *  - ALL / ALL_EXCEPT_CLAIMED → the catch-all [CATCH_ALL_PATTERN]. (On the v3 DB resolver,
 *    specificity makes ALL_EXCEPT yield to a rival's EXPLICIT; ALL is sole-owner-by-validation.)
 *  - EXPLICIT → its literal tokens with regex metacharacters escaped (so the regex matcher
 *    matches them literally — `foo.bar` ⇒ `foo\.bar`, not `fooXbar`), joined by `,`.
 *
 * NOTE: the DSL/code-export render of ALL_EXCEPT_CLAIMED is a negative-lookahead and lives in
 * `ComponentCodeRenderer` — NOT here; this is the v3 API wire render only.
 */
object ArtifactOwnershipRendering {
    /** Legacy ANY_ARTIFACT catch-all (the `[\w-\.]+` default form). */
    const val CATCH_ALL_PATTERN: String = "[\\w-\\.]+"

    /** Escape regex metacharacters in a literal artifact token (allowlist `[A-Za-z0-9_.-]` ⇒ only `.`). */
    fun escapeLiteralToken(token: String): String = token.replace(".", "\\.")

    /** Render the legacy `artifactIdPattern` for a mapping from its [mode] and ordered literal [tokens]. */
    fun renderArtifactPattern(mode: ArtifactIdMode, tokens: List<String>): String =
        when (mode) {
            ArtifactIdMode.ALL, ArtifactIdMode.ALL_EXCEPT_CLAIMED -> CATCH_ALL_PATTERN
            ArtifactIdMode.EXPLICIT -> tokens.joinToString(",") { escapeLiteralToken(it) }
        }
}
