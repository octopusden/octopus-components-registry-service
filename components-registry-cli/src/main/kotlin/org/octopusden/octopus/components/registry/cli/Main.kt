package org.octopusden.octopus.components.registry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.versionOption

/**
 * Root `crsctl` command. Acts as a container for subcommands; on its own it prints help.
 * Subcommands (read/auth operations) are registered in later layers.
 */
class Crsctl : CliktCommand(
    name = "crsctl",
    help = "Command-line client for the Components Registry Service.",
    invokeWithoutSubcommand = true,
) {
    init {
        versionOption(VERSION)
    }

    override fun run() {
        // No-op root: Clikt prints help when invoked without a subcommand.
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
        }
    }

    companion object {
        const val VERSION = "1.0-SNAPSHOT"
    }
}

fun main(args: Array<String>) = Crsctl().main(args)
