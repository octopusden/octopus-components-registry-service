package org.octopusden.octopus.automation.componentsregistryservice

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) {
    ComponentsRegistryCommand().subcommands(
        ComponentsRegistryDownloadCopyright()
    ).main(args)
}
