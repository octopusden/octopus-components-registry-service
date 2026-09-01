package org.octopusden.octopus.components.registry.server.util

object VcsUrlCanonicalizer {
    private val SCHEME = Regex("^(ssh|https|http|git)://", RegexOption.IGNORE_CASE)

    fun canonicalize(url: String): String =
        url.trim()
            .let { SCHEME.replace(it, "") }
            .trimEnd('/')
            .removeSuffix(".git")
            .lowercase()
}
