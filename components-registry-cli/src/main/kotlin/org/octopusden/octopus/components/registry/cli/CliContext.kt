package org.octopusden.octopus.components.registry.cli

import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.config.ConfigLoader
import org.octopusden.octopus.components.registry.cli.config.CrsctlConfig
import org.octopusden.octopus.components.registry.cli.config.EffectiveTarget
import org.octopusden.octopus.components.registry.cli.config.ResolverInputs
import org.octopusden.octopus.components.registry.cli.config.TargetResolver
import org.octopusden.octopus.components.registry.cli.output.OutputFormat

/**
 * Builds a [CrsClient] for a resolved [EffectiveTarget]. Pulled behind an interface so tests can
 * inject a client backed by a fake [org.octopusden.octopus.components.registry.cli.client.HttpExchange]
 * without going through real config / network.
 */
fun interface CrsClientFactory {
    fun create(target: EffectiveTarget): CrsClient
}

/**
 * Shared state threaded from the root command down to every subcommand via Clikt's
 * `findOrSetObject`. Holds the parsed global options plus the seams (config loader + client factory)
 * that the target-resolution helper needs.
 *
 * The two seams default to production behaviour (load the real config file, build a real
 * [CrsClient]); tests override them so commands can run fully offline.
 */
class CliContext(
    val envFlag: String? = null,
    val crsUrlFlag: String? = null,
    val tokenFlag: String? = null,
    val output: OutputFormat = OutputFormat.TABLE,
    val verbose: Boolean = false,
    val configLoader: () -> CrsctlConfig = { ConfigLoader.load() },
    val clientFactory: CrsClientFactory = CrsClientFactory { target ->
        CrsClient(baseUrl = target.crsUrl, token = target.token)
    },
    private val getenv: (String) -> String? = System::getenv,
) {
    /**
     * Resolves the effective target from flags + `CRS_URL` / `CRS_TOKEN` env vars + the loaded
     * config (see [TargetResolver] for precedence). Throws
     * [org.octopusden.octopus.components.registry.cli.client.ConfigResolutionException] when no URL
     * can be resolved.
     */
    fun resolveTarget(): EffectiveTarget =
        TargetResolver.resolve(
            ResolverInputs(
                crsUrlFlag = crsUrlFlag,
                tokenFlag = tokenFlag,
                envFlag = envFlag,
                crsUrlEnv = getenv("CRS_URL"),
                tokenEnv = getenv("CRS_TOKEN"),
            ),
            configLoader(),
        )

    /** Resolves the target and builds a [CrsClient] for it. */
    fun client(): CrsClient = clientFactory.create(resolveTarget())
}
