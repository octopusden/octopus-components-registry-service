package org.octopusden.octopus.validation.dto.teamcity

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.teamcity.buildConfig
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildConfigurationTest {
    @Test
    @DisplayName("inheritsFrom is true when the template id is in templateIds")
    fun `inheritsFrom true when present`() {
        val config = buildConfig("Gradle", templateIds = setOf("CDGradleBuild"))

        assertTrue(config.inheritsFrom("CDGradleBuild"))
    }

    @Test
    @DisplayName("inheritsFrom is false when the template id is not in templateIds")
    fun `inheritsFrom false when absent`() {
        val config = buildConfig("Plain", templateIds = setOf("SomeOtherTemplate"))

        assertFalse(config.inheritsFrom("CDGradleBuild"))
    }

    @Test
    @DisplayName("inheritsFrom is false when templateIds is empty")
    fun `inheritsFrom false when no templates`() {
        val config = buildConfig("Plain")

        assertFalse(config.inheritsFrom("CDGradleBuild"))
    }
}
