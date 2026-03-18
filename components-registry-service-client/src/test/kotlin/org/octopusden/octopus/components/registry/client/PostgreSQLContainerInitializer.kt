package org.octopusden.octopus.components.registry.client

import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils
import org.testcontainers.containers.PostgreSQLContainer

class PostgreSQLContainerInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        if (!postgres.isRunning) {
            postgres.start()
        }
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
            context,
            "spring.datasource.url=${postgres.jdbcUrl}",
            "spring.datasource.username=${postgres.username}",
            "spring.datasource.password=${postgres.password}",
            "spring.datasource.driver-class-name=org.postgresql.Driver",
        )
    }

    companion object {
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}
