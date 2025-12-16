package org.octopusden.octopus.components.registry.automation

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) {
    println("ARGS: ${args.joinToString()}")
    println("ENV: ${System.getenv()}")
    println("PROPS: ${System.getProperties()}")
    ComponentsRegistryCommand().subcommands(
        ComponentsRegistryDownloadCopyright()
    ).main(args)
}
