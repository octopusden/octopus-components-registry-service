package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the rollout behavior of the deprecated `majorVersionFormat` compatibility
 * alias on [AdminConfigProperties.Jira.ComponentVersionFormat]. service-config still
 * emits `...componentVersionFormat.majorVersionFormat` until it is renamed in lockstep,
 * so binding that key must populate the renamed [minorVersionFormat]; when both keys are
 * present `minorVersionFormat` wins regardless of Spring's (unspecified) binding order.
 */
@Suppress("DEPRECATION")
class AdminConfigPropertiesAliasTest {

    private fun cvf() = AdminConfigProperties.Jira.ComponentVersionFormat()

    @Test
    fun `legacy majorVersionFormat binds through to minorVersionFormat`() {
        val c = cvf().apply { majorVersionFormat = "\$major.\$minor" }
        assertEquals("\$major.\$minor", c.minorVersionFormat)
    }

    @Test
    fun `minorVersionFormat wins when bound before legacy majorVersionFormat`() {
        val c = cvf().apply {
            minorVersionFormat = "new"
            majorVersionFormat = "legacy"
        }
        assertEquals("new", c.minorVersionFormat)
    }

    @Test
    fun `minorVersionFormat wins when bound after legacy majorVersionFormat`() {
        val c = cvf().apply {
            majorVersionFormat = "legacy"
            minorVersionFormat = "new"
        }
        assertEquals("new", c.minorVersionFormat)
    }
}
