package org.octopusden.octopus.components.registry.server.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

// Deliberately NOT @SpringBootTest: that boots via SpringApplication.run(), which runs the
// Spring Cloud Config bootstrap phase (bootstrap.yml has fail-fast retry up to 5000 attempts /
// 60s max-interval) unless the "test" profile is active to pull in bootstrap-test.yml's
// `spring.cloud.config.enabled: false`. A property-binding test has no need to go through that
// phase at all — ApplicationContextRunner builds a plain context and never touches it.
class ArchiveReadinessPropertiesTest {
    @Configuration
    @EnableConfigurationProperties(ArchiveReadinessProperties::class)
    class TestConfig

    private val contextRunner = ApplicationContextRunner().withUserConfiguration(TestConfig::class.java)

    @Test
    fun `jira properties bind and isJiraConfigured true when baseUrl set`() {
        contextRunner
            .withPropertyValues(
                "archive-readiness.jira.base-url=https://jira.example.com",
                "archive-readiness.jira.username=svc",
                "archive-readiness.jira.password=secret",
                "archive-readiness.retired-jira-project-categories=X Archive,Archived",
            ).run { context ->
                val props = context.getBean(ArchiveReadinessProperties::class.java)
                assertThat(props.jira.baseUrl).isEqualTo("https://jira.example.com")
                assertThat(props.retiredJiraProjectCategories).containsExactlyInAnyOrder("X Archive", "Archived")
                assertThat(props.isJiraConfigured()).isTrue()
            }
    }

    @Test
    fun `isJiraConfigured false when baseUrl blank`() {
        val blank = ArchiveReadinessProperties()
        assertThat(blank.isJiraConfigured()).isFalse()
    }
}
