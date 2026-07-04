package org.octopusden.octopus.components.registry.server.service.impl

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.releng.versions.VersionNames

/**
 * TD-011 / #349 — the import must FAIL LOUD on a malformed `distribution.GAV`
 * Maven coordinate (groupId-only / blank segment) that it previously silently
 * dropped, and must NOT fire for a well-formed coordinate or a file/http URL
 * (fileUrl) entry. Driven through the REAL DSL loader over self-contained
 * fixtures (loader wiring mirrors PerComponentDistributionGuardTest). Plain unit
 * test — no Spring/DB — so it runs under `:test`.
 */
class DistributionCoordinateGuardTest {

    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val component = "distComponent"

    private fun loadConfigs(fixture: String): List<EscrowModuleConfig> {
        val productTypes = ProductTypes.values().associateWith { it.name }
        val loader = EscrowConfigurationLoader(
            ConfigLoader(
                ComponentRegistryInfo.fromClassPath("distribution-coordinate-guard/$fixture"),
                versionNames,
                productTypes,
            ),
            listOf("org.octopusden.octopus", "io.bcomponent"),
            listOf("NONE", "CLASSIC", "ALFA"),
            versionNames,
            Files.createTempDirectory("dist-coordinate-guard-copyrights"),
        )
        val config = loader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap<String, String>())
        return config.escrowModules[component]!!.moduleConfigurations
    }

    @Test
    @DisplayName("fails loud on a groupId-only distribution.GAV (no ':'), naming component + raw entry")
    fun failsOnBareGroupId() {
        val ex = assertThrows<IllegalStateException> {
            validateDistributionCoordinates(component, loadConfigs("BareGroupIdGav.groovy"))
        }
        val msg = ex.message!!
        assertTrue(msg.contains(component), "message names the component: $msg")
        assertTrue(msg.contains("org.octopusden.octopus.bare"), "message names the raw entry: $msg")
        assertTrue(msg.contains("groupId:artifactId"), "message states the reason/expected shape: $msg")
    }

    @Test
    @DisplayName("does not fire for a well-formed group:artifact:ext coordinate")
    fun passesOnValidGav() {
        assertDoesNotThrow {
            validateDistributionCoordinates(component, loadConfigs("ValidGav.groovy"))
        }
    }

    @Test
    @DisplayName("skips file://|http(s) URL entries (fileUrl artifacts, not Maven coordinates)")
    fun skipsUrlEntries() {
        assertDoesNotThrow {
            validateDistributionCoordinates(component, loadConfigs("UrlGavSkipped.groovy"))
        }
    }
}
