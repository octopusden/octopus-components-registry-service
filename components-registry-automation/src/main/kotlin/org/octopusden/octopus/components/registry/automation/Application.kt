package org.octopusden.octopus.components.registry.automation

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) {
    ComponentsRegistryCommand().subcommands(
        ComponentsRegistryDownloadAndMoveCopyrightFile()
    ).main(args)
}
