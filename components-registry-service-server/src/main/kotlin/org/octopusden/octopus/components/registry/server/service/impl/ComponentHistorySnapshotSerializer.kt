package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.springframework.stereotype.Component

/**
 * Produces a stable, comparable snapshot of a single `EscrowModule` for
 * history backfill. The output is intentionally a pure `Map<String, Any?>` so
 * it can be stored as JSONB in `audit_log.old_value` / `new_value` and diffed
 * via [org.octopusden.octopus.components.registry.server.util.AuditDiff].
 *
 * Ordering is stable (LinkedHashMap at the top level, alphabetical in nested
 * maps) so byte-for-byte equal inputs yield byte-for-byte equal output.
 */
@Component
class ComponentHistorySnapshotSerializer {
    private val mapper: ObjectMapper =
        ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    fun serialize(module: EscrowModule): Map<String, Any?> {
        val snapshot = linkedMapOf<String, Any?>()
        snapshot["moduleName"] = module.moduleName
        snapshot["moduleConfigurations"] = module.moduleConfigurations.map(::serializeConfig)
        return snapshot
    }

    @Suppress("LongMethod")
    private fun serializeConfig(cfg: EscrowModuleConfig): Map<String, Any?> {
        val s = linkedMapOf<String, Any?>()
        s["versionRange"] = cfg.versionRangeString
        s["buildSystem"] = cfg.buildSystem?.name
        s["artifactIdPattern"] = cfg.artifactIdPattern
        s["groupIdPattern"] = cfg.groupIdPattern
        s["buildFilePath"] = cfg.buildFilePath
        s["componentDisplayName"] = cfg.componentDisplayName
        s["componentOwner"] = cfg.componentOwner
        s["releaseManager"] = cfg.releaseManager
        s["securityChampion"] = cfg.securityChampion
        s["system"] = cfg.system
        s["clientCode"] = cfg.clientCode
        s["releasesInDefaultBranch"] = cfg.releasesInDefaultBranch
        s["solution"] = cfg.solution
        s["parentComponent"] = cfg.parentComponent
        s["octopusVersion"] = cfg.octopusVersion
        s["productType"] = cfg.productType?.name
        s["deprecated"] = cfg.isDeprecated
        s["archived"] = cfg.archived
        s["copyright"] = cfg.copyright
        s["labels"] = cfg.labels?.sorted()
        s["jiraConfiguration"] = toMap(cfg.jiraConfiguration)
        s["buildConfiguration"] = toMap(cfg.buildConfiguration)
        s["vcsSettings"] = toMap(cfg.vcsSettings)
        s["distribution"] = toMap(cfg.distribution)
        s["escrow"] = toMap(cfg.escrow)
        s["doc"] = toMap(cfg.doc)
        return s
    }

    private fun toMap(obj: Any?): Map<String, Any?>? = obj?.let { mapper.convertValue(it, MAP_TYPE) }

    companion object {
        private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
