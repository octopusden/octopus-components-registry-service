package org.octopusden.octopus.components.registry.cli

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintCompletionMessage
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.ParameterFormatter
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.client.ExitCode
import org.octopusden.octopus.components.registry.cli.commands.ComponentAsCodeCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentGetCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentOverridesCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentsCommand
import org.octopusden.octopus.components.registry.cli.commands.ComponentsListCommand
import org.octopusden.octopus.components.registry.cli.commands.LoginCommand
import org.octopusden.octopus.components.registry.cli.commands.LogoutCommand
import org.octopusden.octopus.components.registry.cli.commands.WhoamiCommand
import org.octopusden.octopus.components.registry.cli.commands.auditCommand
import org.octopusden.octopus.components.registry.cli.commands.metaCommand
import org.octopusden.octopus.components.registry.cli.config.ConfigLoader
import org.octopusden.octopus.components.registry.cli.config.CrsctlConfig
import org.octopusden.octopus.components.registry.cli.output.OutputFormat
import org.octopusden.octopus.components.registry.cli.output.Renderer

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
    auditCommand(),
    WhoamiCommand(),
    LoginCommand(),
    LogoutCommand(),
)

/**
 * Parses and runs [command] over [args], mapping every Clikt outcome onto the crsctl exit-code +
 * structured-error contract instead of Clikt's default human-text-on-stderr behaviour.
 *
 * Returns the process exit code (it never calls `exitProcess`, so it is unit-testable):
 *   - normal completion -> 0
 *   - --help / no-subcommand help / --version / completion script -> printed to STDOUT, exit 0
 *   - [ProgramResult] (a command already rendered our structured error to STDERR) -> its status code,
 *     printing nothing more
 *   - [Abort] -> 1
 *   - [UsageError] / any other input-level [CliktError] -> our `{"errorCode","message"}` JSON on
 *     STDERR and exit [ExitCode.USAGE] (2)
 */
internal fun runCli(args: Array<String>, command: CliktCommand = crsctl()): Int {
    return try {
        command.parse(args)
        ExitCode.OK.code
    } catch (e: PrintHelpMessage) {
        if (e.error) {
            // An "error" help (e.g. no subcommand / missing required argument) is a usage failure:
            // honour the structured-error + USAGE(2) contract rather than printing help to stdout.
            System.err.println(
                Renderer.renderError(
                    IllegalArgumentException("missing or invalid command — run with --help for usage"),
                ),
            )
            ExitCode.USAGE.code
        } else {
            println(command.getFormattedHelp(e).orEmpty())
            ExitCode.OK.code
        }
    } catch (e: PrintCompletionMessage) {
        println(e.message.orEmpty())
        e.statusCode
    } catch (e: PrintMessage) {
        println(e.message.orEmpty())
        e.statusCode
    } catch (e: Abort) {
        1
    } catch (e: ProgramResult) {
        // A command already rendered our structured error to STDERR; honour its exit code only.
        e.statusCode
    } catch (e: UsageError) {
        System.err.println(Renderer.renderError(IllegalArgumentException(usageMessage(e, command))))
        ExitCode.USAGE.code
    } catch (e: CliktError) {
        System.err.println(Renderer.renderError(IllegalArgumentException(e.message ?: "usage error")))
        ExitCode.USAGE.code
    }
}

/**
 * Best-effort human message for a [UsageError]. Subclasses such as `MissingArgument` / `NoSuchOption`
 * leave [Throwable.message] null and compute it via `formatMessage`, so prefer that (using the error's
 * own context for localization) and fall back to the raw message.
 */
private fun usageMessage(e: UsageError, command: CliktCommand): String {
    val localization = (e.context ?: command.currentContext).localization
    val formatted = e.formatMessage(localization, ParameterFormatter.Plain)
    return formatted.ifBlank { e.message ?: "usage error" }
}

fun main(args: Array<String>) {
    kotlin.system.exitProcess(runCli(args))
}
