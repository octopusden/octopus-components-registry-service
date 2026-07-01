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
 * CRS #387 — the import must reject per-range distribution.explicit / .external /
 * .securityGroups.read that diverge from the base, and must NOT fire when a range
 * merely omits them (loader inherits the base). Driven through the REAL DSL loader
 * over self-contained fixtures so the declared-vs-inherited resolution is authentic
 * (loader wiring mirrors RangeOnlyParityGuardTest). Plain unit test — no Spring/DB —
 * so it runs under `:test`.
 */
class PerComponentDistributionGuardTest {

    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val component = "vrComponent"

    private fun loadConfigs(fixture: String): List<EscrowModuleConfig> {
        val productTypes = ProductTypes.values().associateWith { it.name }
        val loader = EscrowConfigurationLoader(
            ConfigLoader(
                ComponentRegistryInfo.fromClassPath("per-range-distribution-guard/$fixture"),
                versionNames,
                productTypes,
            ),
            listOf("org.octopusden.octopus", "io.bcomponent"),
            listOf("NONE", "CLASSIC", "ALFA"),
            versionNames,
            Files.createTempDirectory("per-range-dist-guard-copyrights"),
        )
        val config = loader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap<String, String>())
        return config.escrowModules[component]!!.moduleConfigurations
    }

    @Test
    @DisplayName("rejects per-range distribution.explicit / .external that diverge from the base")
    fun rejectsDivergentBooleans() {
        val ex = assertThrows<IllegalStateException> {
            validatePerComponentDistributionInvariants(component, loadConfigs("DivergentBooleans.groovy"))
        }
        val msg = ex.message!!
        assertTrue(msg.contains("distribution.explicit"), "message names the attribute: $msg")
        assertTrue(msg.contains("distribution.external"), "message names the attribute: $msg")
        assertTrue(msg.contains("[03.54,03.55)"), "message names the offending range: $msg")
        assertTrue(msg.contains(component), "message names the component: $msg")
    }

    @Test
    @DisplayName("rejects per-range distribution.securityGroups.read that diverges from the base")
    fun rejectsDivergentSecurityGroups() {
        val ex = assertThrows<IllegalStateException> {
            validatePerComponentDistributionInvariants(component, loadConfigs("DivergentSecurityGroups.groovy"))
        }
        assertTrue(
            ex.message!!.contains("distribution.securityGroups.read"),
            "message names the attribute: ${ex.message}",
        )
    }

    @Test
    @DisplayName("accepts ranges that omit the per-component fields and change only docker (inherited == base)")
    fun acceptsTopLevelOnlyWithPerRangeDocker() {
        assertDoesNotThrow {
            validatePerComponentDistributionInvariants(component, loadConfigs("TopLevelOnly.groovy"))
        }
    }
}
