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
 * Two render targets, intentionally different for ALL_EXCEPT_CLAIMED (see the plan / ADR-017):
 *  - [renderArtifactPattern] — the v3 API WIRE form (catch-all; the resolver's specificity yields).
 *  - [renderExportPattern] — the DSL/code-export + UI-preview form (a negative-lookahead built from
 *    the in-force EXPLICIT siblings, so re-feeding the exported DSL to the legacy strict validator
 *    does NOT reintroduce the overlap ALL_EXCEPT_CLAIMED encodes away).
 */
object ArtifactOwnershipRendering {
    /** Legacy ANY_ARTIFACT catch-all (the `[\w-\.]+` default form). */
    const val CATCH_ALL_PATTERN: String = "[\\w-\\.]+"

    /** Escape regex metacharacters in a literal artifact token (allowlist `[A-Za-z0-9_.-]` ⇒ only `.`). */
    fun escapeLiteralToken(token: String): String = token.replace(".", "\\.")

    /** Render the v3 WIRE `artifactIdPattern` for a mapping from its [mode] and ordered literal [tokens]. */
    fun renderArtifactPattern(
        mode: ArtifactIdMode,
        tokens: List<String>,
    ): String =
        when (mode) {
            ArtifactIdMode.ALL, ArtifactIdMode.ALL_EXCEPT_CLAIMED -> CATCH_ALL_PATTERN
            ArtifactIdMode.EXPLICIT -> tokens.joinToString(",") { escapeLiteralToken(it) }
        }

    /**
     * Render the DSL/code-export + UI-preview `artifactIdPattern`. Same as the wire form EXCEPT
     * ALL_EXCEPT_CLAIMED becomes an anchored negative-lookahead over the [siblings] (the EXPLICIT
     * tokens claimed under the same group in an intersecting range, escaped + alternated). With no
     * siblings there is nothing to exclude, so it degrades to the plain catch-all.
     */
    fun renderExportPattern(
        mode: ArtifactIdMode,
        tokens: List<String>,
        siblings: List<String>,
    ): String =
        when (mode) {
            ArtifactIdMode.ALL -> CATCH_ALL_PATTERN
            ArtifactIdMode.EXPLICIT -> renderArtifactPattern(mode, tokens)
            ArtifactIdMode.ALL_EXCEPT_CLAIMED ->
                if (siblings.isEmpty()) {
                    CATCH_ALL_PATTERN
                } else {
                    "(?!(?:${siblings.joinToString("|") { escapeLiteralToken(it) }})$)$CATCH_ALL_PATTERN"
                }
        }
}
