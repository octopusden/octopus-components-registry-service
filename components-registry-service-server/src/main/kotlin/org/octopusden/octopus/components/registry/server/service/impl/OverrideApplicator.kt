package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.FieldOverrideEntity
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory
import org.slf4j.LoggerFactory

/**
 * Stateless helper that applies field overrides to detached domain objects.
 * Phase 1: scalar fields only (String, Boolean, enum).
 *
 * Applied AFTER .to*() mapper calls produce detached domain objects,
 * NOT by mutating managed JPA entities.
 */
object OverrideApplicator {
    private val LOG = LoggerFactory.getLogger(OverrideApplicator::class.java)

    /**
     * Supported scalar field paths for EscrowModuleConfig.
     * Maps field path string → field name in EscrowModuleConfig (Groovy class).
     */
    private val ESCROW_MODULE_CONFIG_FIELDS =
        setOf(
            "componentOwner",
            "componentDisplayName",
            "releaseManager",
            "securityChampion",
            "system",
            "clientCode",
            "buildFilePath",
            "octopusVersion",
            "copyright",
            "parentComponent",
        )

    private val BOOLEAN_FIELDS =
        setOf(
            "deprecated",
            "archived",
            "releasesInDefaultBranch",
            "solution",
        )

    /**
     * Apply matching overrides to a cloned EscrowModuleConfig.
     * Returns a new object with overrides applied; the original is NOT modified.
     */
    fun applyToConfig(
        config: EscrowModuleConfig,
        overrides: List<FieldOverrideEntity>,
        version: String?,
        numericVersionFactory: NumericVersionFactory,
        versionRangeFactory: VersionRangeFactory,
    ): EscrowModuleConfig {
        val matching = filterMatchingOverrides(overrides, version, numericVersionFactory, versionRangeFactory)
        if (matching.isEmpty()) return config

        @Suppress("USELESS_CAST")
        val clone = config.clone() as EscrowModuleConfig
        for (override in matching) {
            applyScalarOverride(clone, override.fieldPath, override.value)
        }
        return clone
    }

    /**
     * Filter overrides whose versionRange contains the given version.
     */
    @Suppress("TooGenericExceptionCaught")
    fun filterMatchingOverrides(
        overrides: List<FieldOverrideEntity>,
        version: String?,
        numericVersionFactory: NumericVersionFactory,
        versionRangeFactory: VersionRangeFactory,
    ): List<FieldOverrideEntity> {
        if (overrides.isEmpty()) return emptyList()
        if (version == null) {
            // No version context — return overrides with catch-all range "(,)"
            return overrides.filter { it.versionRange == "(,)" }
        }
        val numericVersion =
            try {
                numericVersionFactory.create(version)
            } catch (e: Exception) {
                LOG.warn("Cannot parse version '{}': {}", version, e.message)
                return emptyList()
            }
        return overrides.filter { override ->
            try {
                val range = versionRangeFactory.create(override.versionRange)
                range.containsVersion(numericVersion)
            } catch (e: Exception) {
                LOG.warn("Cannot parse versionRange '{}': {}", override.versionRange, e.message)
                false
            }
        }
    }

    private fun applyScalarOverride(
        target: Any,
        fieldPath: String,
        value: Any?,
    ) {
        if (value == null) return

        // Phase 1: only top-level scalar fields on EscrowModuleConfig
        if (target !is EscrowModuleConfig) return

        val fieldName =
            fieldPath
                .removePrefix("build.")
                .removePrefix("jira.")
                .removePrefix("escrow.")
                .removePrefix("distribution.")
                .removePrefix("vcs.")

        if (fieldName in ESCROW_MODULE_CONFIG_FIELDS) {
            setFieldValue(target, fieldName, value.toString())
        } else if (fieldName in BOOLEAN_FIELDS) {
            val boolValue =
                when (value) {
                    is Boolean -> value
                    is String -> value.toBoolean()
                    else -> return
                }
            setFieldValue(target, fieldName, boolValue)
        } else {
            LOG.debug("Override field path '{}' is not supported in Phase 1", fieldPath)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun setFieldValue(
        target: Any,
        fieldName: String,
        value: Any?,
    ) {
        try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(target, value)
        } catch (e: Exception) {
            LOG.warn("Failed to set field '{}' on {}: {}", fieldName, target.javaClass.simpleName, e.message)
        }
    }
}
