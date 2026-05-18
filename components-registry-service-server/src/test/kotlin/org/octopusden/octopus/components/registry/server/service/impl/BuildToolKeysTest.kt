package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean

/**
 * Unit tests for the top-level [buildBuildToolKeys] discriminator.
 *
 * Reproduces the silent-override-drop regression flagged in PR #208 review:
 * `emitMarkerOverrides` only emits a `build.buildTools` marker row when
 * `buildBuildToolKeys(base) != buildBuildToolKeys(override)`. If the key set
 * fails to encode a distinguishing field (settingsProperty in particular),
 * a per-range override that differs only in that field is silently dropped
 * and the base value bleeds into the override range.
 */
class BuildToolKeysTest {
    @Test
    @DisplayName(
        "BTK-001: two OracleDatabaseToolBean with same version but different settingsProperty " +
            "MUST produce distinct keys",
    )
    fun `BTK-001 oracle differing only in settingsProperty - keys must differ`() {
        val base =
            OracleDatabaseToolBean().apply {
                setVersion("11.2")
                setSettingsProperty("db")
            }
        val over =
            OracleDatabaseToolBean().apply {
                setVersion("11.2")
                setSettingsProperty("db2")
            }
        val baseKeys = buildBuildToolKeys(listOf(base))
        val overKeys = buildBuildToolKeys(listOf(over))
        assertNotEquals(
            baseKeys,
            overKeys,
            "OracleDatabaseToolBean with same version but different settingsProperty " +
                "must produce distinct keys, otherwise emitMarkerOverrides will silently " +
                "drop the override and the base value will bleed into the override range.",
        )
    }

    @Test
    @DisplayName(
        "BTK-002: two PTKProductToolBean with same version but different settingsProperty " +
            "MUST produce distinct keys",
    )
    fun `BTK-002 ptk differing only in settingsProperty - keys must differ`() {
        val base =
            PTKProductToolBean().apply {
                setVersion("03.49")
                setSettingsProperty("kschema")
            }
        val over =
            PTKProductToolBean().apply {
                setVersion("03.49")
                setSettingsProperty("kschema_alt")
            }
        val baseKeys = buildBuildToolKeys(listOf(base))
        val overKeys = buildBuildToolKeys(listOf(over))
        assertNotEquals(
            baseKeys,
            overKeys,
            "PTKProductToolBean with same version but different settingsProperty " +
                "must produce distinct keys.",
        )
    }

    @Test
    @DisplayName(
        "BTK-003: two PTCProductToolBean with same version but different settingsProperty " +
            "MUST produce distinct keys",
    )
    fun `BTK-003 ptc differing only in settingsProperty - keys must differ`() {
        val base =
            PTCProductToolBean().apply {
                setVersion("01.00")
                setSettingsProperty("cschema")
            }
        val over =
            PTCProductToolBean().apply {
                setVersion("01.00")
                setSettingsProperty("cschema_alt")
            }
        val baseKeys = buildBuildToolKeys(listOf(base))
        val overKeys = buildBuildToolKeys(listOf(over))
        assertNotEquals(
            baseKeys,
            overKeys,
            "PTCProductToolBean with same version but different settingsProperty " +
                "must produce distinct keys.",
        )
    }

    @Test
    @DisplayName(
        "BTK-004: identical beans (same version, same settingsProperty) produce identical keys " +
            "— regression guard for the equality path",
    )
    fun `BTK-004 identical beans produce equal keys - regression guard`() {
        val a =
            OracleDatabaseToolBean().apply {
                setVersion("19.0")
                setSettingsProperty("db")
            }
        val b =
            OracleDatabaseToolBean().apply {
                setVersion("19.0")
                setSettingsProperty("db")
            }
        assertEquals(
            buildBuildToolKeys(listOf(a)),
            buildBuildToolKeys(listOf(b)),
            "Two beans with identical fields must produce equal key sets " +
                "(no marker row emitted for a no-op override).",
        )
    }
}
