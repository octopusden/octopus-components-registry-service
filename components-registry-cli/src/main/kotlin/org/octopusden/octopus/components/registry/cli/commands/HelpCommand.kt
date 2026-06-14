package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

/**
 * `crsctl help [<command>...]` — print help for crsctl or a named (possibly nested) subcommand,
 * e.g. `crsctl help`, `crsctl help components list`.
 *
 * Dispatch is actually handled in `runCli` (Main.kt), which rewrites a leading `help <command>...`
 * into `<command>... --help` BEFORE parsing — that reuses Clikt's own help renderer for any depth
 * and keeps exit codes consistent (0 for help). This class exists so `help` is listed under
 * Commands and has its own usage line; its [run] is a safety net that is normally never reached.
 */
class HelpCommand : CliktCommand(
    name = "help",
    help = "Show help for crsctl or a specific command (e.g. `help components list`).",
) {
    private val command by argument(
        name = "command",
        help = "Command path to show help for; omit for top-level help.",
    ).multiple()

    override fun run() {
        // Safety net: runCli normally rewrites `help ...` before this runs. If reached, show the
        // root help (the parent context), ignoring any command path.
        command.let { /* registered for usage display; dispatch happens in runCli */ }
        throw PrintHelpMessage(currentContext.parent ?: currentContext)
    }
}
