package org.octopusden.octopus.components.registry.server.util

object VcsUrlCanonicalizer {
    private val SCHEME = Regex("^(ssh|https|http|git)://", RegexOption.IGNORE_CASE)

    // Matches SCP-style SSH shorthand, e.g. "git@host:owner/repo" (no "://" involved).
    // Only applied when the input has no "://" at all, so a "ssh://host:2222/..." URL's
    // port is never mistaken for the SCP-style host/path separator.
    private val SCP_STYLE = Regex("^[^@/]+@([^:/]+):(.*)$")

    // Leading userinfo, e.g. "git@" in "ssh://git@host/owner/repo" — the "://"-scheme branch's
    // equivalent of what SCP_STYLE already strips for the SCP-style branch. Without this,
    // "ssh://git@host/owner/repo.git" and "git@host:owner/repo.git" (the same repository)
    // canonicalize to different strings.
    private val USERINFO = Regex("^[^@/]+@")

    fun canonicalize(url: String): String {
        val trimmed = url.trim()
        val schemeNormalized = if (!trimmed.contains("://")) {
            SCP_STYLE.matchEntire(trimmed)?.let { match ->
                val (host, path) = match.destructured
                "$host/$path"
            } ?: trimmed
        } else {
            USERINFO.replace(SCHEME.replace(trimmed, ""), "")
        }
        return schemeNormalized
            .trimEnd('/')
            .lowercase()
            .removeSuffix(".git")
    }
}
