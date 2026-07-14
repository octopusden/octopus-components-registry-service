package org.octopusden.octopus.components.registry.cli.config

import org.octopusden.octopus.components.registry.cli.client.ConfigResolutionException
import java.net.URI
import java.net.URISyntaxException

/**
 * The resolved CRS target a command will talk to. [token] may be null (anonymous read); [envName] is
 * the profile name when the URL came from a profile, otherwise null.
 */
data class EffectiveTarget(
    val crsUrl: String,
    val token: String? = null,
    val envName: String? = null,
)

/**
 * Inputs the resolver considers, lowest-friction first. The command layer fills these from Clikt
 * options / the environment; the resolver applies a fixed precedence and never guesses a URL.
 */
data class ResolverInputs(
    val crsUrlFlag: String? = null,
    val tokenFlag: String? = null,
    val envFlag: String? = null,
    val crsUrlEnv: String? = null,
    val tokenEnv: String? = null,
)

/**
 * Resolves the effective CRS URL and token with explicit precedence:
 *
 *   URL:    --crs-url flag  >  CRS_URL env  >  named profile (--env, else config defaultProfile)
 *   token:  --token flag    >  CRS_TOKEN env  >  (no token — anonymous)
 *
 * If no URL can be resolved from any source, a [ConfigResolutionException] is thrown rather than
 * silently defaulting to a possibly wrong registry. An explicit `--env` that names a profile absent
 * from the config is also an error.
 */
object TargetResolver {
    fun resolve(
        inputs: ResolverInputs,
        config: CrsctlConfig,
    ): EffectiveTarget {
        val token = firstNonBlank(inputs.tokenFlag, inputs.tokenEnv)

        firstNonBlank(inputs.crsUrlFlag)?.let { url ->
            return EffectiveTarget(crsUrl = validate(url), token = token, envName = null)
        }

        firstNonBlank(inputs.crsUrlEnv)?.let { url ->
            return EffectiveTarget(crsUrl = validate(url), token = token, envName = null)
        }

        // Profile path: explicit --env wins; otherwise fall back to config defaultProfile.
        val requestedEnv = firstNonBlank(inputs.envFlag)
        val profileName = requestedEnv ?: firstNonBlank(config.defaultProfile)

        if (profileName != null) {
            val profile = config.profiles[profileName]
                ?: throw ConfigResolutionException(
                    "No profile named '$profileName' in config. Known profiles: " +
                        knownProfiles(config),
                )
            return EffectiveTarget(crsUrl = validate(profile.crsUrl), token = token, envName = profileName)
        }

        throw ConfigResolutionException(
            "No CRS URL resolved. Provide --crs-url, set CRS_URL, or configure a profile " +
                "(--env / defaultProfile). Known profiles: ${knownProfiles(config)}",
        )
    }

    /**
     * Validates the resolved CRS URL and strips a single trailing slash so base+path joins stay
     * clean. The URL must be a syntactically valid absolute URI with an http/https scheme and a
     * non-empty host; anything else is a [ConfigResolutionException] (which maps to USAGE/exit 2)
     * rather than a confusing transport-level failure later on.
     */
    private fun validate(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            throw ConfigResolutionException("Invalid CRS URL '$rawUrl': ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ConfigResolutionException("Invalid CRS URL '$rawUrl': ${e.message}")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw ConfigResolutionException(
                "Invalid CRS URL '$rawUrl': scheme must be http or https.",
            )
        }
        if (uri.host.isNullOrBlank()) {
            throw ConfigResolutionException("Invalid CRS URL '$rawUrl': missing host.")
        }
        return trimmed.trimEnd('/')
    }

    private fun knownProfiles(config: CrsctlConfig): String =
        if (config.profiles.isEmpty()) {
            "(none)"
        } else {
            config.profiles.keys
                .sorted()
                .joinToString(", ")
        }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }
}
