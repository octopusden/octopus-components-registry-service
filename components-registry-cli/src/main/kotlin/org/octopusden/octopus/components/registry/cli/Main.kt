package org.octopusden.octopus.components.registry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.commands.ComponentAsCodeCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentGetCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentOverridesCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentsCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentsListCommand
import org.octopusden.octopus.components.registry.cli.commands.LoginCommand
import org.octopusden.octopus.components.registry.cli.commands.LogoutCommand
import org.octopusden.octopus.components.registry.cli.commands.WhoamiCommand
import org.octopusden.octopus.components.registry.cli.commands.metaCommand
import org.octopusden.octopus.components.registry.cli.config.ConfigLoader
import org.octopusden.octopus.components.registry.cli.config.CrsctlConfig
import org.octopusden.octopus.components.registry.cli.output.OutputFormat

/**
 * Root `crsctl` command. Owns the global options and publishes a [CliContext] (via Clikt's
 * `findOrSetObject`) for the subcommands to consume.
 *
 * The two seams — [configLoader] and [clientFactory] — default to production behaviour but can be
 * overridden by tests to run the whole command tree offline against a fake
 * [org.octopusden.octopus.components.registry.cli.client.HttpExchange].
 */
class Crsctl(
    private val configLoader: () -> CrsctlConfig = { ConfigLoader.load() },
    private val clientFactory: CrsClientFactory = CrsClientFactory { target ->
        CrsClient(baseUrl = target.crsUrl, token = target.token)
    },
) : CliktCommand(
    name = "crsctl",
    help = "Command-line client for the Components Registry Service.",
    invokeWithoutSubcommand = true,
) {
    private val env by option("--env", help = "Named config profile to target.")
    private val crsUrl by option("--crs-url", help = "CRS base URL (overrides --env and CRS_URL).")
    private val token by option("--token", help = "Bearer token (overrides CRS_TOKEN). Anonymous if omitted.")
    private val output by option("-o", "--output", help = "Output format.")
        .choice("json", "table", ignoreCase = true)
        .convert { OutputFormat.parse(it) }
    private val verbose by option("-v", "--verbose", help = "Verbose diagnostics.").flag()
    private val insecureTokenStore by option(
        "--insecure-token-store",
        help = "Store the refresh token in a plaintext file (0600) instead of the system keychain.",
    ).flag()

    init {
        versionOption(VERSION)
    }

    override fun run() {
        currentContext.findOrSetObject {
            CliContext(
                envFlag = env,
                crsUrlFlag = crsUrl,
                tokenFlag = token,
                output = output ?: OutputFormat.TABLE,
                verbose = verbose,
                insecureTokenStore = insecureTokenStore,
                configLoader = configLoader,
                clientFactory = clientFactory,
            )
        }
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
        }
    }

    companion object {
        const val VERSION = "1.0-SNAPSHOT"
    }
}

/** Builds the full command tree (root + all read subcommands) with the given seams. */
fun crsctl(
    configLoader: () -> CrsctlConfig = { ConfigLoader.load() },
    clientFactory: CrsClientFactory = CrsClientFactory { target ->
        CrsClient(baseUrl = target.crsUrl, token = target.token)
    },
): Crsctl = Crsctl(configLoader, clientFactory).subcommands(
    ComponentsCommand().subcommands(
        ComponentsListCommand(),
    ),
    ComponentCommand().subcommands(
        ComponentGetCommand(),
        ComponentAsCodeCommand(),
        ComponentOverridesCommand(),
    ),
    metaCommand(),
    WhoamiCommand(),
    LoginCommand(),
    LogoutCommand(),
)

fun main(args: Array<String>) = crsctl().main(args)
