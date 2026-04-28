package org.octopusden.octopus.components.registry.server.service.impl

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.NoHeadException
import org.octopusden.releng.versions.NumericVersionFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class ResolvedTarget(
    val ref: String,
    val sha: String,
)

/**
 * Picks a "validated" commit to check out: max numeric tag matching
 * [tagVersionPrefix], else the current HEAD of the default branch.
 *
 * Tolerates non-numeric tags that share the prefix (e.g. `components-registry-qa-1`)
 * by skipping them instead of failing the whole resolution.
 */
@Component
class GitTagResolver(
    private val numericVersionFactory: NumericVersionFactory,
) {
    fun resolve(
        git: Git,
        tagVersionPrefix: String,
    ): ResolvedTarget {
        val tagged =
            git
                .tagList()
                .call()
                .filter { it.name.startsWith(tagVersionPrefix) }
                .mapNotNull { ref ->
                    val shortName = ref.name.removePrefix(REFS_TAGS_PREFIX)
                    @Suppress("TooGenericExceptionCaught")
                    // NumericVersionFactory.create wraps parse failures in plain
                    // RuntimeException; tolerating everything here keeps anomalous
                    // tags like `components-registry-qa-1` from aborting resolution.
                    try {
                        numericVersionFactory.create(shortName) to ref
                    } catch (e: RuntimeException) {
                        log.debug("Skipping non-numeric tag '{}': {}", ref.name, e.message)
                        null
                    }
                }.maxByOrNull { (version, _) -> version }
                ?.let { (_, ref) ->
                    val peeled = git.repository.refDatabase.peel(ref)
                    ResolvedTarget(
                        ref = ref.name,
                        sha = peeled.peeledObjectId?.name ?: peeled.objectId.name,
                    )
                }

        if (tagged != null) return tagged

        val latest =
            try {
                git
                    .log()
                    .setMaxCount(1)
                    .call()
                    .firstOrNull()
            } catch (e: NoHeadException) {
                log.debug("Repository has no HEAD: {}", e.message)
                null
            }
                ?: error("Components Registry is empty, can not continue")

        return ResolvedTarget(ref = "master", sha = latest.name)
    }

    companion object {
        private const val REFS_TAGS_PREFIX = "refs/tags/"
        private val log = LoggerFactory.getLogger(GitTagResolver::class.java)
    }
}
