package org.octopusden.octopus.components.registry.server.mapper

interface Mapper<SOURCE, TARGET> {
    fun convert(src: SOURCE): TARGET

    fun convert(sources: List<SOURCE>): List<TARGET> = sources.map { convert(it) }

    fun convert(sources: Set<SOURCE>): Set<TARGET> = sources.map { convert(it) }.toSet()
}
