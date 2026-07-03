package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.AdminConfigProperties
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Syncs the code-as-config admin blobs ([AdminConfigProperties], bound from
 * `service-config`) into the `registry_config` DB cache, so the existing readers
 * (`FieldConfigService`, the `GET /rest/api/4/config/...` endpoints, Portal) keep reading from the
 * DB unchanged.
 *
 * Invoked:
 *  - on startup, from `ComponentsRegistryServiceImpl.@PostConstruct`, BEFORE any
 *    auto-migration (so defaults/field-config are present before migrate runs);
 *  - on `POST /admin/reload-config`, via `ContextRefresher.refresh()` →
 *    `RefreshScopeRefreshedEvent` (see [ConfigRefreshListener]).
 *
 * No-clobber & validation contract ([P1-validation]):
 *  - Enum values are validated; an invalid `visibility`/`searchable` aborts the
 *    whole sync (throws [ConfigValidationException]) so a misconfiguration is
 *    surfaced loudly instead of silently degrading enforcement.
 *  - An EMPTY bound subtree is never written: it leaves the existing known-good
 *    DB cache untouched (a missing profile section must not blank out production
 *    policy and drop `FieldConfigService` back to `editable` everywhere).
 *  - Both keys are written in a single transaction ([Q1]) after both validate.
 *
 * @ConditionalOnDatabaseEnabled: writes `registry_config`, absent in no-db mode.
 */
@ConditionalOnDatabaseEnabled
@Service
class ConfigSyncService(
    private val adminConfigProperties: AdminConfigProperties,
    private val registryConfigRepository: RegistryConfigRepository,
) {
    private val log = LoggerFactory.getLogger(ConfigSyncService::class.java)

    /** Build, validate and upsert both blobs atomically. Returns the maps actually written. */
    @Transactional
    fun syncToCache(): SyncResult {
        val fieldConfig = buildFieldConfigMap() // validates enums (may throw)
        val componentDefaults = buildComponentDefaultsMap()

        val wroteField = upsertIfNotEmpty(FIELD_CONFIG_KEY, fieldConfig)
        val wroteDefaults = upsertIfNotEmpty(COMPONENT_DEFAULTS_KEY, componentDefaults)
        log.info(
            "ConfigSync: field-config {} ({} sections), component-defaults {} ({} keys)",
            if (wroteField) "written" else "skipped (empty — cache preserved)",
            fieldConfig.size,
            if (wroteDefaults) "written" else "skipped (empty — cache preserved)",
            componentDefaults.size,
        )
        return SyncResult(fieldConfig, componentDefaults)
    }

    /**
     * Sync ONLY the component-defaults blob from service-config into the cache —
     * used by `ImportServiceImpl.migrateDefaults()` (and the `/admin/migrate-defaults`
     * endpoint / migration-job DEFAULTS phase) so those paths produce the same blob
     * without the Groovy loader. No-clobber: an empty map leaves the cache untouched.
     */
    @Transactional
    fun syncComponentDefaults(): Map<String, Any?> {
        val map = buildComponentDefaultsMap()
        val wrote = upsertIfNotEmpty(COMPONENT_DEFAULTS_KEY, map)
        log.info(
            "ConfigSync: component-defaults {} ({} keys)",
            if (wrote) "written" else "skipped (empty — cache preserved)",
            map.size,
        )
        return map
    }

    /** The component-defaults map as it would be written, without touching the DB. */
    fun componentDefaultsMap(): Map<String, Any?> = buildComponentDefaultsMap()

    private fun upsertIfNotEmpty(key: String, value: Map<String, Any?>): Boolean {
        if (value.isEmpty()) return false
        val entity = registryConfigRepository.findById(key).orElse(RegistryConfigEntity(key = key))
        entity.value = value
        entity.updatedAt = Instant.now()
        registryConfigRepository.save(entity)
        return true
    }

    // ── field-config ────────────────────────────────────────────────────────

    private fun buildFieldConfigMap(): Map<String, Any?> =
        buildMap {
            for ((section, fields) in adminConfigProperties.fieldConfig) {
                val sectionMap =
                    buildMap<String, Any?> {
                        for ((field, entry) in fields) {
                            val entryMap = serializeFieldEntry(section, field, entry)
                            if (entryMap.isNotEmpty()) put(field, entryMap)
                        }
                    }
                if (sectionMap.isNotEmpty()) put(section, sectionMap)
            }
        }

    private fun serializeFieldEntry(
        section: String,
        field: String,
        entry: AdminConfigProperties.FieldEntry,
    ): Map<String, Any?> =
        buildMap {
            entry.visibility?.let {
                val v = it.trim().lowercase()
                require(v in VISIBILITIES) {
                    invalid("$section.$field.visibility", it, VISIBILITIES)
                }
                // build.buildSystem is a required enum on every BASE configuration row — it can
                // never be stripped or absent, so the CREATE/PATCH writers deliberately EXEMPT it
                // from the hidden-strip (see ComponentManagementServiceImpl.applyBaseConfiguration*).
                // Hiding it would therefore silently do nothing; reject it structurally here so the
                // misconfiguration is surfaced loudly. Narrowing to adminOnly/none is still allowed.
                if (v == "hidden" && "$section.$field" == BUILD_SYSTEM_PATH) {
                    throw ConfigValidationException("build.buildSystem is a required enum and cannot be hidden")
                }
                put("visibility", v)
            }
            entry.editable?.let {
                val e = it.trim().lowercase()
                require(e in EDITABILITIES) {
                    invalid("$section.$field.editable", it, EDITABILITIES)
                }
                put("editable", e)
            }
            entry.searchable?.let {
                require(it.trim() in SEARCHABLES) {
                    invalid("$section.$field.searchable", it, SEARCHABLES)
                }
                put("searchable", it.trim())
            }
            // Dropdown options (e.g. External Registry names): trimmed, blanks dropped;
            // an all-blank/empty list is omitted rather than written as `[]`.
            entry.options
                ?.mapNotNull { opt -> opt.trim().takeIf { it.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("options", it) }
            entry.required?.let { put("required", it) }
            entry.defaultValue?.let { put("defaultValue", it) }
            // Free-text display overrides — no validation beyond blank-dropping.
            entry.label?.takeIf { it.isNotBlank() }?.let { put("label", it.trim()) }
            entry.description?.takeIf { it.isNotBlank() }?.let { put("description", it.trim()) }
        }

    private fun invalid(path: String, value: String, allowed: Set<String>): Nothing =
        throw ConfigValidationException(
            "Invalid field-config value '$value' at 'components-registry.field-config.$path'; allowed: $allowed",
        )

    // ── component-defaults (mirrors legacy migrateDefaults output) ────────────

    @Suppress("CyclomaticComplexMethod")
    private fun buildComponentDefaultsMap(): Map<String, Any?> {
        val d = adminConfigProperties.componentDefaults
        return buildMap {
            d.buildSystem?.let { put("buildSystem", it) }
            d.buildFilePath?.let { put("buildFilePath", it) }
            d.artifactIdPattern?.let { put("artifactIdPattern", it) }
            d.groupIdPattern?.let { put("groupIdPattern", it) }
            d.componentDisplayName?.let { put("componentDisplayName", it) }
            d.system?.let { put("system", it) }
            d.clientCode?.let { put("clientCode", it) }
            d.parentComponent?.let { put("parentComponent", it) }
            d.releasesInDefaultBranch?.let { put("releasesInDefaultBranch", it) }
            d.solution?.let { put("solution", it) }
            d.archived?.let { put("archived", it) }
            d.copyright?.let { put("copyright", it) }
            d.labels.takeIf { it.isNotEmpty() }?.let { put("labels", it.toList()) }
            d.deprecated?.let { put("deprecated", it) }
            d.octopusVersion?.let { put("octopusVersion", it) }

            d.build?.let { bp ->
                buildMap<String, Any?> {
                    bp.javaVersion?.let { put("javaVersion", it) }
                    bp.mavenVersion?.let { put("mavenVersion", it) }
                    bp.gradleVersion?.let { put("gradleVersion", it) }
                    bp.requiredProject?.let { put("requiredProject", it) }
                    bp.projectVersion?.let { put("projectVersion", it) }
                    bp.systemProperties.takeIf { it.isNotEmpty() }?.let { put("systemProperties", it.toMap()) }
                    bp.buildTasks?.let { put("buildTasks", it) }
                }.takeIf { it.isNotEmpty() }?.let { put("build", it) }
            }

            d.jira?.let { jira ->
                buildMap<String, Any?> {
                    jira.projectKey?.let { put("projectKey", it) }
                    jira.displayName?.let { put("displayName", it) }
                    jira.technical?.let { put("technical", it) }
                    jira.componentVersionFormat?.let { cvf ->
                        buildMap<String, Any?> {
                            cvf.minorVersionFormat?.let { put("minorVersionFormat", it) }
                            cvf.releaseVersionFormat?.let { put("releaseVersionFormat", it) }
                            cvf.buildVersionFormat?.let { put("buildVersionFormat", it) }
                            cvf.lineVersionFormat?.let { put("lineVersionFormat", it) }
                            cvf.hotfixVersionFormat?.let { put("hotfixVersionFormat", it) }
                        }.takeIf { it.isNotEmpty() }?.let { put("componentVersionFormat", it) }
                    }
                }.takeIf { it.isNotEmpty() }?.let { put("jira", it) }
            }

            d.distribution?.let { dist ->
                buildMap<String, Any?> {
                    dist.explicit?.let { put("explicit", it) }
                    dist.external?.let { put("external", it) }
                    dist.GAV?.let { put("GAV", it) }
                    dist.DEB?.let { put("DEB", it) }
                    dist.RPM?.let { put("RPM", it) }
                    dist.docker?.let { put("docker", it) }
                    dist.securityGroups?.read?.let { put("securityGroups", mapOf("read" to it)) }
                }.takeIf { it.isNotEmpty() }?.let { put("distribution", it) }
            }

            d.vcs?.let { vcs ->
                buildMap<String, Any?> {
                    vcs.externalRegistry?.let { put("externalRegistry", it) }
                    vcs.vcsPath?.let { put("vcsPath", it) }
                    vcs.repositoryType?.let { put("repositoryType", it) }
                    vcs.tag?.let { put("tag", it) }
                    vcs.branch?.let { put("branch", it) }
                }.takeIf { it.isNotEmpty() }?.let { put("vcs", it) }
            }

            d.escrow?.let { escrow ->
                buildMap<String, Any?> {
                    escrow.buildTask?.let { put("buildTask", it) }
                    escrow.generation?.let { put("generation", it) }
                    escrow.reusable?.let { put("reusable", it) }
                    escrow.diskSpace?.let { put("diskSpace", it) }
                    escrow.providedDependencies.takeIf { it.isNotEmpty() }?.let { put("providedDependencies", it.toList()) }
                    escrow.additionalSources.takeIf { it.isNotEmpty() }?.let { put("additionalSources", it.toList()) }
                }.takeIf { it.isNotEmpty() }?.let { put("escrow", it) }
            }

            d.doc?.let { doc ->
                buildMap<String, Any?> {
                    doc.component?.let { put("component", it) }
                    doc.majorVersion?.let { put("majorVersion", it) }
                }.takeIf { it.isNotEmpty() }?.let { put("doc", it) }
            }
        }
    }

    data class SyncResult(
        val fieldConfig: Map<String, Any?>,
        val componentDefaults: Map<String, Any?>,
    )

    companion object {
        const val FIELD_CONFIG_KEY = "field-config"
        const val COMPONENT_DEFAULTS_KEY = "component-defaults"
        /** Required enum on the BASE row — cannot be hidden (see [serializeFieldEntry]). */
        private const val BUILD_SYSTEM_PATH = "build.buildSystem"
        private val VISIBILITIES = setOf("editable", "readonly", "hidden")
        private val EDITABILITIES = setOf("all", "adminonly", "none")
        private val SEARCHABLES = setOf("Main", "Extended", "None")
    }
}

/** Thrown when bound admin config fails validation; aborts the sync without clobbering the DB cache. */
class ConfigValidationException(message: String) : RuntimeException(message)
