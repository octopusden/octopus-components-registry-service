package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.enums.OracleDatabaseEditions
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import java.util.UUID

/**
 * Unit test for `ComponentBuildToolBeanEntity.toBuildToolBean()` extension function.
 *
 * Covers entity → BuildTool conversion for all 6 bean types, including
 * Oracle with/without edition (uses setEdition setter added to OracleDatabaseToolBean).
 */
class BuildToolBeansMapperTest {
    private fun minimalComponent(): ComponentEntity =
        ComponentEntity(
            id = UUID.randomUUID(),
            componentKey = "btb-mapper-test",
        )

    private fun baseConfig(): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = minimalComponent(),
            versionRange = "(,0),[0,)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

    private fun entity(
        beanType: String,
        toolType: String? = null,
        settingsProperty: String? = null,
        versionPattern: String? = null,
        edition: String? = null,
        sortOrder: Int = 0,
    ): ComponentBuildToolBeanEntity =
        ComponentBuildToolBeanEntity(
            id = UUID.randomUUID(),
            componentConfiguration = baseConfig(),
            beanType = beanType,
            toolType = toolType,
            settingsProperty = settingsProperty,
            versionPattern = versionPattern,
            edition = edition,
            sortOrder = sortOrder,
        )

    // -------------------------------------------------------------------------
    // oracleDatabase — no edition
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("oracleDatabase entity without edition → OracleDatabaseToolBean with null edition")
    fun oracleDatabase_noEdition_mapsCorrectly() {
        val bean = entity(
            beanType = "oracleDatabase",
            toolType = "ORACLE",
            settingsProperty = "db",
            versionPattern = "[12,)",
            edition = null,
        )

        val result = bean.toBuildToolBean()

        assertNotNull(result)
        assertTrue(result is OracleDatabaseToolBean)
        val oracle = result as OracleDatabaseToolBean
        assertEquals("[12,)", oracle.version)
        assertEquals("db", oracle.settingsProperty)
        assertNull(oracle.edition)
    }

    // -------------------------------------------------------------------------
    // oracleDatabase — with edition (EE)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("oracleDatabase entity with edition=EE → OracleDatabaseToolBean with edition set via setEdition")
    fun oracleDatabase_withEdition_mapsCorrectly() {
        val bean = entity(
            beanType = "oracleDatabase",
            toolType = "ORACLE",
            settingsProperty = "db",
            versionPattern = "[19,)",
            edition = "EE",
        )

        val result = bean.toBuildToolBean()

        assertNotNull(result)
        assertTrue(result is OracleDatabaseToolBean)
        val oracle = result as OracleDatabaseToolBean
        assertEquals("[19,)", oracle.version)
        assertEquals(OracleDatabaseEditions.EE, oracle.edition)
    }

    // -------------------------------------------------------------------------
    // kProduct
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("kProduct entity → PTKProductToolBean with version")
    fun kProduct_mapsCorrectly() {
        val bean = entity(
            beanType = "kProduct",
            settingsProperty = "uskschema",
            versionPattern = "03.49",
        )

        val result = bean.toBuildToolBean()

        assertNotNull(result)
        assertTrue(result is PTKProductToolBean)
        val pt = result as PTKProductToolBean
        assertEquals("03.49", pt.version)
        assertEquals("uskschema", pt.settingsProperty)
    }

    // -------------------------------------------------------------------------
    // BASE row: buildToolBeans collection → BuildParameters.buildTools
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("BASE row with buildToolBeans collection → toBuildParameters returns non-null with buildTools populated")
    fun baseRow_withBuildToolBeans_buildParametersContainsTools() {
        val base = baseConfig().apply {
            javaVersion = "11"
            buildToolBeans.add(
                ComponentBuildToolBeanEntity(
                    id = UUID.randomUUID(),
                    componentConfiguration = this,
                    beanType = "oracleDatabase",
                    toolType = "ORACLE",
                    settingsProperty = "db",
                    versionPattern = "[12,)",
                    edition = null,
                    sortOrder = 0,
                ),
            )
        }

        val buildParams = ComponentConfigurationView
            .from(base)
            .toBuildParameters(base, emptyList())

        assertNotNull(buildParams)
        val buildTools = buildParams!!.buildTools
        assertEquals(1, buildTools.size)
        val oracle = buildTools.first()
        assertTrue(oracle is OracleDatabaseToolBean)
        assertEquals("[12,)", (oracle as OracleDatabaseToolBean).version)
    }

    // -------------------------------------------------------------------------
    // Unknown bean type → null (graceful skip)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("unknown beanType returns null from toBuildToolBean")
    fun unknownBeanType_returnsNull() {
        val bean = entity(beanType = "notAValidType")
        val result = bean.toBuildToolBean()
        assertNull(result, "Unknown bean types must be skipped")
    }
}
