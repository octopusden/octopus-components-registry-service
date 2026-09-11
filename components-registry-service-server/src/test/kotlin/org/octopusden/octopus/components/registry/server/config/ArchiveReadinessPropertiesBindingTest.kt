package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/**
 * Characterizes the real Spring relaxed-binding path for [ArchiveReadinessProperties] against
 * QA's actual service-config shape (components-registry-service-cloud-qa.yml), to rule in/out a
 * binding gap as the cause of archive-readiness silently omitting REPOSITORY/JIRA_PROJECT/
 * JIRA_ISSUES entries for components that do have the underlying data — unlike
 * JiraClientConfigTest, which constructs the properties object directly and so cannot catch a
 * binder-level gap.
 */
class ArchiveReadinessPropertiesBindingTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ArchiveReadinessProperties::class)
    open class BindingConfig

    private val runner = ApplicationContextRunner().withUserConfiguration(BindingConfig::class.java)

    @Test
    fun `service-config shape binds jira and vcs-facade base urls`() {
        runner
            .withPropertyValues(
                "archive-readiness.retired-jira-project-categories[0]=X Archive",
                "archive-readiness.jira.base-url=https://ows-jira-copy.example.com",
                "archive-readiness.vcs-facade.base-url=https://f1-gateway-test.example.com/vcs-facade",
                "archive-readiness.vcs-facade.time-retry-in-millis=3000",
            ).run { ctx ->
                val props = ctx.getBean(ArchiveReadinessProperties::class.java)
                assertEquals("https://ows-jira-copy.example.com", props.jira.baseUrl)
                assertEquals("https://f1-gateway-test.example.com/vcs-facade", props.vcsFacade.baseUrl)
                assertEquals(3000, props.vcsFacade.timeRetryInMillis)
                assertEquals(setOf("X Archive"), props.retiredJiraProjectCategories)
                assertTrue(props.isJiraConfigured())
            }
    }

    @Test
    fun `no properties set yields blank base urls, not a binder default that looks configured`() {
        runner.run { ctx ->
            val props = ctx.getBean(ArchiveReadinessProperties::class.java)
            assertEquals("", props.jira.baseUrl)
            assertEquals("", props.vcsFacade.baseUrl)
            assertTrue(!props.isJiraConfigured())
        }
    }
}
