package org.octopusden.octopus.components.registry.server.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest(classes = [ArchiveReadinessProperties::class])
@EnableConfigurationProperties(ArchiveReadinessProperties::class)
@TestPropertySource(properties = [
    "archive-readiness.jira.base-url=https://jira.example.com",
    "archive-readiness.jira.username=svc",
    "archive-readiness.jira.password=secret",
    "archive-readiness.retired-jira-project-categories=X Archive,Archived",
])
class ArchiveReadinessPropertiesTest {
    @org.springframework.beans.factory.annotation.Autowired
    lateinit var props: ArchiveReadinessProperties

    @Test
    fun `jira properties bind and isJiraConfigured true when baseUrl set`() {
        assertThat(props.jira.baseUrl).isEqualTo("https://jira.example.com")
        assertThat(props.retiredJiraProjectCategories).containsExactlyInAnyOrder("X Archive", "Archived")
        assertThat(props.isJiraConfigured()).isTrue()
    }

    @Test
    fun `isJiraConfigured false when baseUrl blank`() {
        val blank = ArchiveReadinessProperties()
        assertThat(blank.isJiraConfigured()).isFalse()
    }
}
