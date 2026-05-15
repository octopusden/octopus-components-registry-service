package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.MigrationProgressListener
import org.octopusden.octopus.components.registry.server.service.MigrationResult
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.octopusden.octopus.components.registry.server.service.ValidationResult
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Schema-v2 stub: the DSL → DB import pipeline is being rewritten per
 * `docs/db-migration/schema-spec.md` §6 (pre-pass dictionary discovery,
 * aggregator detection, two-pass `parentComponent`, per-attribute scalar
 * override emission, distribution family split, synthetic-base flag).
 *
 * The legacy `EscrowModule.toComponentEntity()` shortcut wrote the flat
 * v1-style `ComponentEntity` (with `metadata: Map`, single VCS settings
 * entity, etc.) and is no longer compatible with the v2 schema. Until the
 * §6 pipeline lands, the migrate endpoints surface
 * [UnsupportedOperationException] (HTTP 501 via `GlobalExceptionHandler`).
 *
 * **Still working under v2:**
 *   - [migrateDefaults] — writes `registry_config[component-defaults]`
 *     from `Defaults.groovy`. Reuses `RegistryConfigEntity` which mapped
 *     cleanly to v2; no entity-graph changes.
 *   - [getMigrationStatus] — counts component_source rows; v2-compatible.
 *
 * **Stubbed (Phase 5b — `MIG-039`):**
 *   - [migrateComponent], [migrateAllComponents], [migrate],
 *     [validateMigration]. These need the full §6 pipeline; tracked in
 *     `todo.md`. QA / FT seeding currently goes through the v4 CRUD API
 *     directly until the pipeline lands.
 */
@Service
class ImportServiceImpl(
    private val gitResolver: ComponentRegistryResolverImpl,
    @Qualifier("databaseComponentRegistryResolver")
    @Suppress("unused") private val dbResolver: DatabaseComponentRegistryResolver,
    private val componentSourceRepository: ComponentSourceRepository,
    @Suppress("unused") private val sourceRegistry: ComponentSourceRegistry,
    private val configurationLoader: EscrowConfigurationLoader,
    private val registryConfigRepository: RegistryConfigRepository,
) : ImportService {
    @Transactional
    override fun migrateComponent(
        name: String,
        dryRun: Boolean,
    ): MigrationResult =
        throw UnsupportedOperationException(SCHEMA_V2_STUB_MESSAGE)

    override fun migrateAllComponents(progress: MigrationProgressListener): BatchMigrationResult =
        throw UnsupportedOperationException(SCHEMA_V2_STUB_MESSAGE)

    override fun getMigrationStatus(): MigrationStatus {
        val dbCount = componentSourceRepository.countBySource("db")
        val totalInGit =
            try {
                gitResolver.getComponents().size.toLong()
            } catch (_: Exception) {
                0L
            }
        return MigrationStatus(
            git = totalInGit - dbCount,
            db = dbCount,
            total = totalInGit,
        )
    }

    override fun validateMigration(name: String): ValidationResult =
        throw UnsupportedOperationException(SCHEMA_V2_STUB_MESSAGE)

    /**
     * Startup-friendly variant: callers (incl. `ComponentsRegistryServiceImpl.cloneVcsData`
     * during `@PostConstruct` when `components-registry.auto-migrate=true`) need a
     * working call. Until the §6 import pipeline lands we still migrate the defaults
     * (which works under v2) and return zero components migrated. Operator-driven
     * `POST /admin/migrate` goes through [migrateAllComponents], which still throws —
     * the auto-migrate path here just gets the empty result + a warning log so the
     * application can boot.
     */
    override fun migrate(progress: MigrationProgressListener): FullMigrationResult {
        LOG.warn(SCHEMA_V2_STUB_MESSAGE)
        val defaults = migrateDefaults()
        val components =
            BatchMigrationResult(
                total = 0,
                migrated = 0,
                failed = 0,
                skipped = 0,
                results = emptyList(),
            )
        return FullMigrationResult(defaults = defaults, components = components)
    }

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "LongMethod")
    override fun migrateDefaults(): Map<String, Any?> {
        LOG.info("Migrating component defaults from Git DSL")
        val defaults = configurationLoader.loadCommonDefaults(emptyMap())
        val map =
            buildMap<String, Any?> {
                defaults.buildSystem?.let { put("buildSystem", it.name) }
                defaults.buildFilePath?.let { put("buildFilePath", it) }
                defaults.artifactIdPattern?.let { put("artifactIdPattern", it) }
                defaults.groupIdPattern?.let { put("groupIdPattern", it) }
                defaults.componentDisplayName?.let { put("componentDisplayName", it) }
                defaults.componentOwner?.let { put("componentOwner", it) }
                defaults.releaseManager?.let { put("releaseManager", it) }
                defaults.securityChampion?.let { put("securityChampion", it) }
                defaults.system?.let { put("system", it) }
                defaults.clientCode?.let { put("clientCode", it) }
                defaults.parentComponent?.let { put("parentComponent", it) }
                defaults.releasesInDefaultBranch?.let { put("releasesInDefaultBranch", it) }
                defaults.solution?.let { put("solution", it) }
                defaults.archived?.let { put("archived", it) }
                defaults.copyright?.let { put("copyright", it) }
                defaults.labels?.takeIf { it.isNotEmpty() }?.let { put("labels", it.toList()) }
                defaults.deprecated?.let { put("deprecated", it) }
                defaults.octopusVersion?.let { put("octopusVersion", it) }

                defaults.buildParameters?.let { bp ->
                    try {
                        val buildMap =
                            buildMap<String, Any?> {
                                bp.javaVersion?.let { put("javaVersion", it) }
                                bp.mavenVersion?.let { put("mavenVersion", it) }
                                bp.gradleVersion?.let { put("gradleVersion", it) }
                                put("requiredProject", bp.requiredProject)
                                bp.projectVersion?.let { put("projectVersion", it) }
                                bp.systemProperties?.let { put("systemProperties", it) }
                                bp.buildTasks?.let { put("buildTasks", it) }
                            }
                        if (buildMap.isNotEmpty()) put("build", buildMap)
                    } catch (e: Exception) {
                        LOG.warn("Failed to serialize buildParameters defaults: {}", e.message)
                    }
                }

                defaults.jiraComponent?.let { jira ->
                    try {
                        val jiraMap =
                            buildMap<String, Any?> {
                                jira.projectKey?.let { put("projectKey", it) }
                                jira.displayName?.let { put("displayName", it) }
                                put("technical", jira.isTechnical)
                                jira.componentVersionFormat?.let { cvf ->
                                    val cvfMap =
                                        buildMap<String, Any?> {
                                            cvf.majorVersionFormat?.let { put("majorVersionFormat", it) }
                                            cvf.releaseVersionFormat?.let { put("releaseVersionFormat", it) }
                                            cvf.buildVersionFormat?.let { put("buildVersionFormat", it) }
                                            cvf.lineVersionFormat?.let { put("lineVersionFormat", it) }
                                            cvf.hotfixVersionFormat?.let { put("hotfixVersionFormat", it) }
                                        }
                                    if (cvfMap.isNotEmpty()) put("componentVersionFormat", cvfMap)
                                }
                            }
                        if (jiraMap.isNotEmpty()) put("jira", jiraMap)
                    } catch (e: Exception) {
                        LOG.warn("Failed to serialize jiraComponent defaults: {}", e.message)
                    }
                }

                defaults.distribution?.let { dist ->
                    try {
                        val distMap =
                            buildMap<String, Any?> {
                                put("explicit", dist.explicit())
                                put("external", dist.external())
                                dist.GAV()?.let { put("GAV", it) }
                                dist.DEB()?.let { put("DEB", it) }
                                dist.RPM()?.let { put("RPM", it) }
                                dist.docker()?.let { put("docker", it) }
                                dist.securityGroups?.let { sg ->
                                    val sgMap =
                                        buildMap<String, Any?> {
                                            sg.read?.let { put("read", it) }
                                        }
                                    if (sgMap.isNotEmpty()) put("securityGroups", sgMap)
                                }
                            }
                        if (distMap.isNotEmpty()) put("distribution", distMap)
                    } catch (e: Exception) {
                        LOG.warn("Failed to serialize distribution defaults: {}", e.message)
                    }
                }

                defaults.vcsSettingsWrapper?.let { wrapper ->
                    try {
                        val vcsMap =
                            buildMap<String, Any?> {
                                wrapper.vcsSettings?.let { vcs ->
                                    vcs.externalRegistry?.let { put("externalRegistry", it) }
                                }
                                wrapper.defaultVCSSettings?.let { root ->
                                    root.vcsPath?.let { put("vcsPath", it) }
                                    root.repositoryType?.let { put("repositoryType", it.name) }
                                    root.tag?.let { put("tag", it) }
                                    root.branch?.let { put("branch", it) }
                                }
                            }
                        if (vcsMap.isNotEmpty()) put("vcs", vcsMap)
                    } catch (e: Exception) {
                        LOG.warn("Failed to serialize vcsSettingsWrapper defaults: {}", e.message)
                    }
                }

                defaults.escrow?.let { escrow ->
                    try {
                        val escrowMap =
                            buildMap<String, Any?> {
                                escrow.buildTask?.let { put("buildTask", it) }
                                escrow.generation.orElse(null)?.let { put("generation", it.name) }
                                put("reusable", escrow.isReusable)
                                escrow.diskSpaceRequirement.orElse(null)?.let { put("diskSpace", it) }
                                escrow.providedDependencies.takeIf { it.isNotEmpty() }?.let {
                                    put("providedDependencies", it.toList())
                                }
                                escrow.additionalSources.takeIf { it.isNotEmpty() }?.let {
                                    put("additionalSources", it.toList())
                                }
                            }
                        if (escrowMap.isNotEmpty()) put("escrow", escrowMap)
                    } catch (e: Exception) {
                        LOG.warn("Failed to serialize escrow defaults: {}", e.message)
                    }
                }

                defaults.doc?.let { doc ->
                    try {
                        val docMap =
                            buildMap<String, Any?> {
                                doc.component()?.let { put("component", it) }
                                doc.majorVersion()?.let { put("majorVersion", it) }
                            }
                        if (docMap.isNotEmpty()) put("doc", docMap)
                    } catch (e: Exception) {
                        LOG.warn("Failed to serialize doc defaults: {}", e.message)
                    }
                }
            }
        val entity =
            registryConfigRepository.findById("component-defaults").orElse(
                RegistryConfigEntity(key = "component-defaults"),
            )
        entity.value = map
        entity.updatedAt = Instant.now()
        registryConfigRepository.save(entity)
        LOG.info("Migrated component defaults: {} keys", map.size)
        return map
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ImportServiceImpl::class.java)
        private const val SCHEMA_V2_STUB_MESSAGE =
            "Component import is not yet ported to schema v2 (Model A'); see " +
                "docs/db-migration/schema-spec.md §6 and MIG-039 in todo.md. " +
                "Seed test data via the v4 CRUD API in the meantime."
    }
}
