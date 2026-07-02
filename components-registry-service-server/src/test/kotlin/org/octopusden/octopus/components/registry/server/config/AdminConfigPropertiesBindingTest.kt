package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/**
 * Characterization of the Spring binding seam for field-config map keys.
 *
 * Section field keys are plain map keys, so a key that itself contains a dot
 * (the distribution section uses "maven.groupPattern"-style keys — the Portal
 * resolver splits the path on the FIRST dot only) must be written in bracket
 * notation in service-config YAML:
 *
 *   field-config:
 *     distribution:
 *       "[maven.groupPattern]": { label: ... }
 *
 * Without brackets the binder treats the dot as a path separator and the
 * trailing segment dies in ignoreUnknownFields — silently. Documented in
 * ADR-016; this test pins the behavior so a binder upgrade can't change it
 * unnoticed.
 */
class AdminConfigPropertiesBindingTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminConfigProperties::class)
    open class BindingConfig

    private val runner = ApplicationContextRunner().withUserConfiguration(BindingConfig::class.java)

    @Test
    fun `bracket notation binds a dotted field key verbatim`() {
        runner
            .withPropertyValues(
                "components-registry.field-config.distribution.[maven.groupPattern].label=Example Label",
            )
            .run { ctx ->
                val props = ctx.getBean(AdminConfigProperties::class.java)
                val entry = props.fieldConfig["distribution"]?.get("maven.groupPattern")
                assertEquals("Example Label", entry?.label)
            }
    }

    @Test
    fun `un-bracketed dotted key splits on the dot and loses the leaf`() {
        runner
            .withPropertyValues(
                "components-registry.field-config.distribution.maven.groupPattern.label=Example Label",
            )
            .run { ctx ->
                val props = ctx.getBean(AdminConfigProperties::class.java)
                // The binder consumes "maven" as the field key; "groupPattern" is
                // not a FieldEntry property and is dropped by ignoreUnknownFields.
                assertNull(props.fieldConfig["distribution"]?.get("maven.groupPattern"))
                assertNull(props.fieldConfig["distribution"]?.get("maven")?.label)
            }
    }

    // ── component-defaults majorVersionFormat → minorVersionFormat alias ───────
    // service-config still emits the old `majorVersionFormat` YAML key until it is
    // renamed in lockstep; the deprecated write-through setter must bind it to the
    // renamed `minorVersionFormat`, and `minorVersionFormat` must win when both
    // keys are present (regardless of binder property order). This exercises the
    // real Spring relaxed binder, not just the Kotlin setter.

    @Test
    fun `legacy majorVersionFormat YAML key binds through to minorVersionFormat`() {
        runner
            .withPropertyValues(
                "components-registry.component-defaults.jira.componentVersionFormat.majorVersionFormat=legacy",
            )
            .run { ctx ->
                val cvf = ctx.getBean(AdminConfigProperties::class.java)
                    .componentDefaults.jira?.componentVersionFormat
                assertEquals("legacy", cvf?.minorVersionFormat)
            }
    }

    @Test
    fun `minorVersionFormat wins when both YAML keys are bound`() {
        runner
            .withPropertyValues(
                "components-registry.component-defaults.jira.componentVersionFormat.majorVersionFormat=legacy",
                "components-registry.component-defaults.jira.componentVersionFormat.minorVersionFormat=new",
            )
            .run { ctx ->
                val cvf = ctx.getBean(AdminConfigProperties::class.java)
                    .componentDefaults.jira?.componentVersionFormat
                assertEquals("new", cvf?.minorVersionFormat)
            }
    }
}
