package org.octopusden.octopus.components.registry.dsl.jackson

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule

class JacksonFactory {
    val objectMapper = ObjectMapper()

    init {
        val jdk8Module = Jdk8Module()
        jdk8Module.configureAbsentsAsNulls(true)
        objectMapper.registerModule(jdk8Module)
        objectMapper.registerModule(JavaTimeModule())
    }

    companion object {
        val instance = JacksonFactory()
    }
}