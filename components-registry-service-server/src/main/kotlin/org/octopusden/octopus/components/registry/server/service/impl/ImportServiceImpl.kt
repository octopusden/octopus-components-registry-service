@file:Suppress("TooManyFunctions", "LargeClass", "LongMethod")

package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.api.beans.OdbcToolBean
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTDDbProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTDProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentSystemEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionFileUrlArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionPackageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
import org.octopusden.octopus.components.registry.server.entity.LabelEntity
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.entity.SystemEntity
import org.octopusden.octopus.components.registry.server.entity.ToolEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.MigrationProgressEvent
import org.octopusden.octopus.components.registry.server.service.MigrationProgressListener
import org.octopusden.octopus.components.registry.server.service.MigrationResult
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.octopusden.octopus.components.registry.server.service.ValidationResult
import org.octopusden.octopus.components.registry.server.util.MavenCoords
import org.octopusden.octopus.components.registry.server.util.parseMavenGavEntry
import org.octopusden.octopus.components.registry.server.util.splitCsv
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.VCSSettings
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Schema-v2 DSL → DB import pipeline (MIG-039, §6).
 *
 * Pipeline phases:
 *  §6.1  Pre-pass: upsert `systems`, `tools`, `labels` dictionaries.
 *  §6.2  Two-pass per-component import: Pass 1 saves all component rows with
 *        `parentComponent = null`; Pass 2 resolves `parentComponent` FKs.
 *  §6.3  Aggregator handling: detect REAL vs FAKE per §4.3 rule; upsert
 *        `component_groups` and link `component_group_id`.
 *  §6.4  Base row determination: `isSyntheticBase` flag.
 *  §6.5  Override row generation: scalar override rows + marker rows.
 *  §6.6  Distribution parsing: GAV split into Maven/fileUrl; docker/DEB/RPM.
 *  §6.7  Tools and required tools: junction rows.
 *  §6.8  `${version}` placeholders stored verbatim.
 */
@Service
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
class ImportServiceImpl(
    private val gitResolver: ComponentRegistryResolverImpl,
    @Qualifier("databaseComponentRegistryResolver")
    @Suppress("unused") private val dbResolver: DatabaseComponentRegistryResolver,
    private val componentSourceRepository: ComponentSourceRepository,
    private val sourceRegistry: ComponentSourceRegistry,
    private val configurationLoader: EscrowConfigurationLoader,
    private val registryConfigRepository: RegistryConfigRepository,
    private val componentRepository: ComponentRepository,
    private val configurationRepository: ComponentConfigurationRepository,
    private val componentGroupRepository: ComponentGroupRepository,
    private val systemRepository: SystemRepository,
    private val toolRepository: ToolRepository,
    private val labelRepository: LabelRepository,
    private val componentLabelRepository: ComponentLabelRepository,
    private val componentSystemRepository: ComponentSystemRepository,
    private val componentRequiredToolRepository: ComponentRequiredToolRepository,
    private val componentBuildToolBeanRepository: ComponentBuildToolBeanRepository,
) : ImportService {

    // =========================================================================
    // Public API
    // =========================================================================

    @Transactional
    override fun migrateComponent(
        name: String,
        dryRun: Boolean,
    ): MigrationResult {
        LOG.info("Migrating single component: {} (dryRun={})", name, dryRun)
        return try {
            // Use lenient loader (skip semantic validation) so migration tolerates
            // fixture DSL entries that have labels/hotfix/VCS validation warnings.
            val fullConfig = configurationLoader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap())
            val module =
                fullConfig.escrowModules[name]
                    ?: return MigrationResult(
                        componentName = name,
                        success = false,
                        dryRun = dryRun,
                        message = "Component '$name' not found in DSL",
                    )
            if (!dryRun) {
                // Pre-pass dictionary discovery for this single component
                preupsertSystemsForModule(module.moduleConfigurations)
                preupsertToolsFromLoader(fullConfig)
                preupsertLabelsFromLoader()

                // Check if already migrated → skip
                val existing = componentRepository.findByComponentKey(name)
                if (existing != null) {
                    return MigrationResult(
                        componentName = name,
                        success = true,
                        dryRun = false,
                        message = "Skipped (already in DB)",
                    )
                }

                // schema-spec §4.3: when this name is referenced as parentComponent by some
                // other DSL entry AND its first config classifies as a FAKE aggregator, it is
                // group-only — no ComponentEntity row. Still upsert the ComponentGroupEntity
                // and link any children that already exist in DB; otherwise an operator-driven
                // single-component migration of the aggregator key would leave the group
                // uncreated while marking source = "db".
                val firstCfgForCheck = module.moduleConfigurations.firstOrNull()
                val childrenReferencingThis: Map<String, String> =
                    fullConfig.escrowModules.mapNotNull { (otherKey, otherModule) ->
                        val parentRef = otherModule.moduleConfigurations.firstOrNull()?.parentComponent
                        if (otherKey != name && parentRef == name) otherKey to name else null
                    }.toMap()
                if (firstCfgForCheck != null && childrenReferencingThis.isNotEmpty() && isFakeAggregator(firstCfgForCheck)) {
                    sourceRegistry.setComponentSource(name, "db")
                    val fakePass3Failures = linkAggregatorGroups(fullConfig.escrowModules, childrenReferencingThis)
                    if (fakePass3Failures.isNotEmpty()) {
                        val msg = fakePass3Failures.joinToString(" | ") { "${it.first}=${it.second}" }
                        return MigrationResult(
                            componentName = name,
                            success = false,
                            dryRun = false,
                            message = "§6.3 Pass 3 group-linking failed: ${msg.take(280)}",
                        )
                    }
                    return MigrationResult(
                        componentName = name,
                        success = true,
                        dryRun = false,
                        message = "Skipped insert (FAKE aggregator: group-only per schema-spec §4.3); group upserted",
                    )
                }

                importModule(name, module.moduleConfigurations)
                sourceRegistry.setComponentSource(name, "db")

                // §6.3 Pass 3 for the single-component path: if the component
                // declares a parentComponent, link it to (or create) the
                // parent's aggregator group. The batch path handles this
                // across the full DSL; here we only have the one component,
                // so reverse-map just its parent.
                val firstConfig = module.moduleConfigurations.firstOrNull()
                val pass3Failures: List<Pair<String, String>> =
                    firstConfig?.parentComponent?.takeIf { it.isNotBlank() }?.let { parentKey ->
                        linkAggregatorGroups(fullConfig.escrowModules, mapOf(name to parentKey))
                    } ?: emptyList()
                if (pass3Failures.isNotEmpty()) {
                    val msg = pass3Failures.joinToString(" | ") { "${it.first}=${it.second}" }
                    return MigrationResult(
                        componentName = name,
                        success = false,
                        dryRun = false,
                        message = "§6.3 Pass 3 group-linking failed: ${msg.take(280)}",
                    )
                }
            }
            MigrationResult(
                componentName = name,
                success = true,
                dryRun = dryRun,
                message = if (dryRun) "Dry-run OK" else "Migrated",
            )
        } catch (e: Exception) {
            LOG.error("Failed to migrate component '{}'", name, e)
            MigrationResult(
                componentName = name,
                success = false,
                dryRun = dryRun,
                message = "Error: ${e.message?.take(300)}",
            )
        }
    }

    @Transactional
    override fun migrateAllComponents(progress: MigrationProgressListener): BatchMigrationResult {
        LOG.info("Starting full DSL → DB component migration (schema v2 §6 pipeline)")
        // Use lenient loader so migration tolerates DSL entries that have semantic
        // validation warnings (labels-not-available, hotfix-format, missing VCS roots,
        // etc.). The git-backed resolver validates at request time; the import pipeline
        // should import what is there, not refuse to run due to pre-existing warnings.
        val fullConfig = configurationLoader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap())
        val allModules = fullConfig.escrowModules

        // §6.1 Pre-pass dictionary discovery
        LOG.info("§6.1 Pre-pass: upserting systems, tools, labels")
        preupsertSystemsFromConfig(allModules.values.flatMap { it.moduleConfigurations })
        preupsertToolsFromLoader(fullConfig)
        preupsertLabelsFromLoader()

        // §6.2 Two-pass component import
        LOG.info("§6.2 Two-pass component import for {} modules", allModules.size)

        // Pre-compute parentComponent references from DSL up front, independent of Pass 1's
        // insert/skip decisions. This keeps Passes 2 and 3 idempotent on re-runs: even when
        // a component is already in the DB and Pass 1 skips it, its parent FK and aggregator-
        // group membership are still resolved against the current DSL.
        val pendingParentByKey: Map<String, String> =
            allModules.mapNotNull { (componentKey, escrowModule) ->
                val firstConfig = escrowModule.moduleConfigurations.firstOrNull() ?: return@mapNotNull null
                firstConfig.parentComponent?.takeIf { it.isNotBlank() }?.let { parentKey ->
                    componentKey to parentKey
                }
            }.toMap()

        // schema-spec §4.3: FAKE aggregators are group-only — no ComponentEntity row. Detect
        // which keys in `allModules` are referenced as parentComponent AND classify as FAKE,
        // and skip them in Pass 1. Pass 3 still creates their ComponentGroupEntity.
        val fakeAggregatorKeys: Set<String> =
            pendingParentByKey.values.toSet().filter { parentKey ->
                val firstConfig = allModules[parentKey]?.moduleConfigurations?.firstOrNull()
                firstConfig != null && isFakeAggregator(firstConfig)
            }.toSet()

        var total = 0
        var migrated = 0
        var failed = 0
        var skipped = 0
        val results = mutableListOf<MigrationResult>()

        // Pass 1: create all components (parentComponent = null)
        for ((componentKey, escrowModule) in allModules) {
            total++
            try {
                if (componentKey in fakeAggregatorKeys) {
                    // Register FAKE aggregator as "db"-sourced even though it has no
                    // ComponentEntity row — Pass 3 still owns its ComponentGroupEntity,
                    // and the source flag keeps `getMigrationStatus` totals balanced
                    // (total == db) so the orchestrator does not retry this component.
                    sourceRegistry.setComponentSource(componentKey, "db")
                    skipped++
                    results.add(
                        MigrationResult(
                            componentName = componentKey,
                            success = true,
                            dryRun = false,
                            message = "Skipped (FAKE aggregator: group-only per schema-spec §4.3)",
                        ),
                    )
                    continue
                }

                val existing = componentRepository.findByComponentKey(componentKey)
                if (existing != null) {
                    skipped++
                    results.add(
                        MigrationResult(
                            componentName = componentKey,
                            success = true,
                            dryRun = false,
                            message = "Skipped (already in DB)",
                        ),
                    )
                    continue
                }

                val configs = escrowModule.moduleConfigurations
                if (configs.isEmpty()) {
                    LOG.warn("Component '{}' has no configurations in DSL; skipping", componentKey)
                    skipped++
                    results.add(
                        MigrationResult(
                            componentName = componentKey,
                            success = true,
                            dryRun = false,
                            message = "Skipped (no configurations)",
                        ),
                    )
                    continue
                }

                importModule(componentKey, configs)
                sourceRegistry.setComponentSource(componentKey, "db")
                migrated++
                results.add(
                    MigrationResult(
                        componentName = componentKey,
                        success = true,
                        dryRun = false,
                        message = "Migrated",
                    ),
                )
                progress.onProgress(
                    MigrationProgressEvent(
                        componentName = componentKey,
                        migrated = migrated,
                        failed = failed,
                        skipped = skipped,
                        total = allModules.size,
                    ),
                )
            } catch (e: Exception) {
                LOG.error("Failed to migrate component '{}'", componentKey, e)
                failed++
                results.add(
                    MigrationResult(
                        componentName = componentKey,
                        success = false,
                        dryRun = false,
                        message = "Error: ${e.message?.take(300)}",
                    ),
                )
            }
        }

        // Pass 2: resolve parentComponent FK references
        LOG.info("§6.2 Pass 2: resolving {} parentComponent references", pendingParentByKey.size)
        for ((childKey, parentKey) in pendingParentByKey) {
            try {
                val parent = componentRepository.findByComponentKey(parentKey)
                if (parent == null) {
                    LOG.warn(
                        "parentComponent='{}' referenced by '{}' not found in DB; leaving null",
                        parentKey,
                        childKey,
                    )
                    continue
                }
                val child = componentRepository.findByComponentKey(childKey) ?: continue
                child.parentComponent = parent
                componentRepository.save(child)
            } catch (e: Exception) {
                LOG.warn("Failed to link parentComponent '{}' → '{}': {}", childKey, parentKey, e.message)
            }
        }

        // §6.3 Pass 3: upsert component_groups rows and link component_group_id FKs.
        // Runs after Pass 2 so all ComponentEntity rows are present and parentComponent FKs are resolved.
        LOG.info("§6.3 Pass 3: linking aggregator groups for {} parent references", pendingParentByKey.size)
        val pass3Failures = linkAggregatorGroups(allModules, pendingParentByKey)
        for ((parentKey, errorMessage) in pass3Failures) {
            failed++
            results.add(
                MigrationResult(
                    componentName = parentKey,
                    success = false,
                    dryRun = false,
                    message = "§6.3 Pass 3 group-linking failed: ${errorMessage.take(280)}",
                ),
            )
        }

        LOG.info(
            "Migration complete: total={}, migrated={}, failed={}, skipped={}",
            total,
            migrated,
            failed,
            skipped,
        )
        return BatchMigrationResult(
            total = total,
            migrated = migrated,
            failed = failed,
            skipped = skipped,
            results = results,
        )
    }

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

    override fun validateMigration(name: String): ValidationResult {
        val inDb = componentRepository.findByComponentKey(name) != null
        return ValidationResult(
            componentName = name,
            valid = inDb,
            discrepancies = if (inDb) emptyList() else listOf("Component '$name' not found in DB"),
        )
    }

    override fun migrate(progress: MigrationProgressListener): FullMigrationResult {
        LOG.info("Starting full migration (defaults + all components)")
        val defaults = migrateDefaults()
        val components = migrateAllComponents(progress)
        return FullMigrationResult(defaults = defaults, components = components)
    }

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    @Transactional
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

    // =========================================================================
    // §6.1 Pre-pass dictionary discovery
    // =========================================================================

    private fun preupsertSystemsForModule(configs: List<EscrowModuleConfig>) {
        for (cfg in configs) {
            val systemStr = cfg.system ?: continue
            for (token in systemStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                upsertSystem(token)
            }
        }
    }

    private fun preupsertSystemsFromConfig(configs: List<EscrowModuleConfig>) {
        val distinctSystems = mutableSetOf<String>()
        for (cfg in configs) {
            val systemStr = cfg.system ?: continue
            systemStr.split(",").mapNotNullTo(distinctSystems) { it.trim().takeIf { t -> t.isNotEmpty() } }
        }
        for (code in distinctSystems) {
            upsertSystem(code)
        }
    }

    private fun upsertSystem(code: String) {
        if (systemRepository.findByCode(code) == null) {
            systemRepository.save(SystemEntity(code = code))
        }
    }

    /**
     * Common-defaults tools (`Defaults.groovy` → `build { requiredTools = "..." }`),
     * lazy-loaded once per `ImportServiceImpl` instance and reused as a fallback
     * when a component's own merged `buildConfiguration.tools` came back empty
     * (the Groovy loader drops Defaults-inherited tools whenever the component
     * declares its own `build { ... }` block — see `importModule` for context).
     *
     * Limitation: this fallback cannot distinguish "loader merge dropped tools"
     * from "component explicitly cleared tools" — both surface as an empty list
     * on `EscrowModuleConfig.buildConfiguration.tools`. A future opt-out for
     * components that want NO tools would require loader changes (preserve the
     * null-vs-empty distinction). No current DSL component exercises that case.
     */
    private val commonDefaultsToolsCache: List<org.octopusden.octopus.escrow.model.Tool> by lazy {
        configurationLoader
            .loadCommonDefaults(emptyMap())
            .buildParameters
            ?.tools
            ?.toList()
            ?: emptyList()
    }

    private fun preupsertToolsFromLoader(
        @Suppress("UNUSED_PARAMETER") fullConfig: org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration,
    ) {
        // Tools are loaded via EscrowConfigurationLoader.getToolsConfiguration(configObject).
        // We re-use the loadCommonDefaults call (which calls getToolsConfiguration internally)
        // to discover tool names. The simplest approach: load tools via the raw loader's
        // getToolsConfiguration directly — but configLoader is the high-level facade.
        // We call loadCommonDefaults here only for its side-effect of loading tools;
        // the defaults map is discarded. The tool list is embedded in the loader's
        // internal state and surfaced via EscrowModuleConfig.buildConfiguration.tools
        // within each component's config.
        //
        // To upsert tools: collect from all component build configs.
        val toolsSeen = mutableSetOf<String>()
        for ((_, module) in fullConfig.escrowModules) {
            for (cfg in module.moduleConfigurations) {
                val buildCfg = cfg.buildConfiguration ?: continue
                for (tool in buildCfg.tools ?: emptyList()) {
                    val toolName = tool.name ?: continue
                    if (toolsSeen.add(toolName)) {
                        upsertTool(toolName, tool.escrowEnvironmentVariable, tool.sourceLocation, tool.targetLocation, tool.installScript)
                    }
                }
            }
        }
    }

    private fun upsertTool(
        name: String,
        escrowEnvVariable: String?,
        sourceLocation: String?,
        targetLocation: String?,
        installScript: String?,
    ) {
        val existing = toolRepository.findByName(name)
        if (existing == null) {
            toolRepository.save(
                ToolEntity(
                    name = name,
                    escrowEnvVariable = escrowEnvVariable,
                    sourceLocation = sourceLocation,
                    targetLocation = targetLocation,
                    installScript = installScript,
                ),
            )
        }
    }

    private fun preupsertLabelsFromLoader() {
        try {
            val validationConfig = configurationLoader.loadCommonDefaults(emptyMap())
            // ValidationConfig is accessed via configLoader.loadAndParseValidationConfigFile()
            // but we only have the EscrowConfigurationLoader facade here.
            // We collect labels from the loaded DSL default config's labels field.
            // The actual validation-config.yaml labels would need direct access to the loader's
            // internal configLoader. As a pragmatic approach, we skip seeding labels from
            // validation-config.yaml here since we can't access it through the facade.
            // Labels seen in DSL components are the real source of truth.
            // FIXME(MIG-039 review): expose ValidationConfig via EscrowConfigurationLoader or
            // inject IConfigLoader to access loadAndParseValidationConfigFile() for label seeding
            validationConfig.labels?.let { labels ->
                for (code in labels) {
                    upsertLabel(code)
                }
            }
        } catch (e: Exception) {
            LOG.warn("Could not seed labels from defaults: {}", e.message)
        }
    }

    private fun upsertLabel(code: String) {
        if (labelRepository.findByCode(code) == null) {
            labelRepository.save(LabelEntity(code = code))
        }
    }

    // =========================================================================
    // §6.2–6.7 Per-module import
    // =========================================================================

    /**
     * Import a single DSL module (top-level component + its sub-components if
     * it is an aggregator).
     *
     * §6.3: aggregator detection — if any config in [configs] has sub-components
     * (detected by `EscrowModule.moduleConfigurations` containing configs with
     * identical jira/build but different artifactIds, which is the DSL pattern),
     * then classify as aggregator. However, sub-components are returned as
     * separate top-level EscrowModule entries by the loader, so at this level
     * we only handle the top-level module. The loader already flattened the
     * sub-component tree. We detect aggregator by checking if an EscrowModule
     * in the config map appears to be a "parent" (has sub-components registered
     * under the same group pattern) — but the loader doesn't expose that
     * information directly.
     *
     * Since the loader flattens sub-components into peer-level EscrowModules,
     * the `componentGroup` linkage must be done via `parentComponent` field:
     * sub-components reference their parent via `parentComponent`.
     *
     * Simplified aggregator detection: an EscrowModule is an aggregator (and
     * thus should have a ComponentGroupEntity) when there exists at least one
     * other module in the full config whose configs reference this module's name
     * as `parentComponent` OR when the first config has `isFakeAggregator` = true
     * with a known aggregator VCS URL pattern.
     *
     * For the migration pipeline, we handle group creation in Pass 2 (after all
     * components exist) based on parentComponent linkages. A simpler approach
     * used here: check if the DSL for this module has a `components {}` block
     * by detecting `sub-components` in the full configuration.
     *
     * The loader returns all sub-components as top-level EscrowModules (peers).
     * The `parentComponent` field on a sub-component's config points to the
     * parent. So we DON'T need to detect aggregators at importModule time;
     * we just save each module as a standalone ComponentEntity and wire
     * parentComponent in Pass 2. ComponentGroupEntity is created as a follow-on
     * step based on parentComponent graph.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun importModule(
        componentKey: String,
        configs: List<EscrowModuleConfig>,
    ) {
        if (configs.isEmpty()) return

        val baseConfig = configs.firstOrNull { it.versionRangeString == ALL_VERSIONS } ?: configs.first()
        val hasAllVersionsBase = configs.any { it.versionRangeString == ALL_VERSIONS }
        val hasOnlyVersionRangeBlocks = !hasAllVersionsBase && configs.size > 1
        val isSyntheticBase = hasOnlyVersionRangeBlocks

        // §6.4 Build the ComponentEntity from the first/base config
        val componentEntity = buildComponentEntity(componentKey, baseConfig)
        val saved = componentRepository.save(componentEntity)

        // Wire M:N junctions (systems, labels)
        linkSystems(saved, baseConfig)
        linkLabels(saved, baseConfig)

        // §6.4 Base configuration row.
        // IMPORTANT: VCS entries and distribution artifacts are added to the
        // entity's collections BEFORE the first save so that JPA/Hibernate
        // cascade-persists them in the same flush.  A freshly-constructed entity
        // holds a plain ArrayList that is never replaced by a PersistentBag
        // until the session closes, so mutations after persist are invisible to
        // the flush.
        //
        // Base required-tools junctions are attached DIRECTLY to the base row
        // (not a separate BUILD_REQUIRED_TOOLS marker). EntityMappers.toBuildParameters
        // falls back to `base.requiredToolJunctions` when no per-range marker
        // matches, which is the correct semantic for tools inherited via
        // `Defaults.groovy`. The previous design wrote a marker row at
        // `savedBase.versionRange`, but for synthetic-base components that range
        // is the DSL's first explicit range (e.g. `(,1.0.107)`) and would not
        // match version queries against any other range — collapsing
        // `buildParameters.tools` to `[]` for those versions. Per-range tool
        // overrides still get a dedicated marker via `emitMarkerOverrides`.
        val baseRow = buildBaseConfigRow(saved, baseConfig, isSyntheticBase)
        attachVcsEntries(baseRow, baseConfig.vcsSettings)
        attachDistribution(baseRow, baseConfig.distribution)
        val savedBase = configurationRepository.save(baseRow)
        // The Groovy loader's per-range merge drops `requiredTools` inherited
        // from `Defaults.groovy` whenever the component declares its OWN
        // `build { ... }` block (the block REPLACES Defaults' build, so an
        // inherited `requiredTools` is lost from the per-range merged config —
        // observed reproducibly on multi-range components with a top-level
        // `build {}` block). The legacy Git resolver compensates with a
        // Defaults fallback at request time; restore parity here by reading
        // common-defaults tools when the per-config merge came back empty.
        val baseTools =
            baseConfig.buildConfiguration?.tools.takeUnless { it.isNullOrEmpty() }
                ?: commonDefaultsToolsCache
        if (baseTools.isNotEmpty()) {
            attachRequiredTools(savedBase, baseTools)
        }
        val baseBuildTools = baseConfig.buildConfiguration?.buildTools?.toList() ?: emptyList()
        if (baseBuildTools.isNotEmpty()) {
            attachBuildToolBeans(savedBase, baseBuildTools)
        }

        // For synthetic-base components, the base row's versionRange is the
        // DSL's first range (e.g. `(,1.0.107)` for TEST_COMPONENT3) and
        // `toEscrowModule` suppresses it from enumeration whenever overrides
        // exist (MIG-029). Emit a RANGE_PRESENCE row at that same range so
        // the resolver re-enumerates it (RES-001 family). Skip when the base
        // is a real `(,)`/ALL_VERSIONS placeholder — non-synthetic bases are
        // enumerated as their own view.
        if (isSyntheticBase) {
            val syntheticBaseRange = baseConfig.versionRangeString
            if (syntheticBaseRange != null) {
                emitRangePresenceRow(saved, syntheticBaseRange)
            }
        }

        // §6.5 Override rows: diff all non-base configs against the base.
        // For ALL_VERSIONS base: nonBaseConfigs = configs with explicit version ranges.
        // For synthetic base (isSyntheticBase): baseConfig = configs.first(), nonBaseConfigs = rest.
        // In both cases, `filter { it != baseConfig }` is the correct set.
        //
        // If neither scalar nor marker emission produced any row for a given
        // override config, emit a RANGE_PRESENCE row so the resolver still
        // enumerates this DSL range (RES-001 family fix).
        val nonBaseConfigs = configs.filter { it !== baseConfig }
        for (override in nonBaseConfigs) {
            val scalarRows = emitScalarOverrides(saved, baseConfig, override)
            val markerRows = emitMarkerOverrides(saved, savedBase, baseConfig, override)
            val overrideRange = override.versionRangeString
            if (scalarRows.isEmpty() && markerRows.isEmpty() && overrideRange != null) {
                emitRangePresenceRow(saved, overrideRange)
            }
        }

        LOG.debug("Imported component '{}' with {} config rows", componentKey, configs.size)
    }

    /**
     * Build a `ComponentEntity` from the given `EscrowModuleConfig`.
     * Only sets scalar/per-component fields. `parentComponent` and
     * `componentGroup` are resolved in later passes.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun buildComponentEntity(
        componentKey: String,
        cfg: EscrowModuleConfig,
    ): ComponentEntity {
        val entity = ComponentEntity(componentKey = componentKey)
        entity.componentOwner = cfg.componentOwner
        entity.displayName = cfg.componentDisplayName
        entity.productType = cfg.productType?.name
        entity.clientCode = cfg.clientCode
        entity.archived = cfg.archived
        entity.solution = cfg.solution
        entity.releaseManager = cfg.releaseManager
        entity.securityChampion = cfg.securityChampion
        entity.copyright = cfg.copyright
        entity.releasesInDefaultBranch = cfg.releasesInDefaultBranch

        // jira.displayName and jira.hotfixVersionFormat go to component-level columns
        cfg.jiraConfiguration?.let { jira ->
            entity.jiraDisplayName = jira.displayName?.takeIf { it.isNotBlank() }
            entity.jiraHotfixVersionFormat =
                jira.componentVersionFormat?.hotfixVersionFormat?.takeIf { it.isNotBlank() }
        }

        // vcs.externalRegistry is per-component
        entity.vcsExternalRegistry = cfg.vcsSettings?.externalRegistry

        // distribution.explicit / distribution.external are per-component
        cfg.distribution?.let { dist ->
            entity.distributionExplicit = dist.explicit()
            entity.distributionExternal = dist.external()
        }

        // Artifact IDs: parse groupId:artifactId pattern from first config.
        // `sortOrder` records the position of each token in the original CSV so
        // that `/maven-artifacts` re-joins them in the same order V1 returns — V1
        // reads `EscrowModuleConfig.artifactIdPattern` (the raw DSL string),
        // V2 reads these rows and CSV-joins by `sortOrder` ASC.
        val groupId = cfg.groupIdPattern
        val artifactIdCsv = cfg.artifactIdPattern
        if (!groupId.isNullOrBlank() && !artifactIdCsv.isNullOrBlank()) {
            artifactIdCsv.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { index, artId ->
                    entity.artifactIds.add(
                        ComponentArtifactIdEntity(
                            component = entity,
                            groupPattern = groupId,
                            artifactPattern = artId,
                            sortOrder = index,
                        ),
                    )
                }
        }

        // Distribution security groups (never per-version)
        cfg.distribution?.securityGroups?.read?.takeIf { it.isNotBlank() }?.let { readGroups ->
            entity.securityGroups.add(
                DistributionSecurityGroupEntity(component = entity, groupType = "read", groupName = readGroups),
            )
        }

        // Doc links (per-component; "never varies per version" per audit)
        cfg.doc?.let { doc ->
            val docKey = doc.component()
            if (!docKey.isNullOrBlank()) {
                entity.docLinks.add(
                    ComponentDocLinkEntity(
                        component = entity,
                        docComponentKey = docKey,
                        majorVersion = doc.majorVersion()?.takeIf { it.isNotBlank() },
                        sortOrder = 0,
                    ),
                )
            }
        }

        return entity
    }

    private fun linkSystems(
        component: ComponentEntity,
        cfg: EscrowModuleConfig,
    ) {
        val systemStr = cfg.system ?: return
        for (code in systemStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            upsertSystem(code) // ensure dictionary exists
            val junction =
                ComponentSystemEntity(
                    componentId = component.id!!,
                    systemCode = code,
                )
            componentSystemRepository.save(junction)
        }
    }

    private fun linkLabels(
        component: ComponentEntity,
        cfg: EscrowModuleConfig,
    ) {
        val labels = cfg.labels ?: return
        for (code in labels) {
            upsertLabel(code) // ensure dictionary exists
            val junction =
                ComponentLabelEntity(
                    componentId = component.id!!,
                    labelCode = code,
                )
            componentLabelRepository.save(junction)
        }
    }

    // =========================================================================
    // §6.4 Base row
    // =========================================================================

    @Suppress("CyclomaticComplexMethod")
    private fun buildBaseConfigRow(
        component: ComponentEntity,
        cfg: EscrowModuleConfig,
        isSyntheticBase: Boolean,
    ): ComponentConfigurationEntity {
        val row =
            ComponentConfigurationEntity(
                component = component,
                versionRange = cfg.versionRangeString ?: ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                isSyntheticBase = isSyntheticBase,
            )
        populateScalarsFromConfig(row, cfg)
        return row
    }

    /**
     * Emit a RANGE_PRESENCE row for a DSL-declared version range whose
     * scalars/markers all match base (so neither `emitScalarOverrides` nor
     * `emitMarkerOverrides` produced any row). Without this, the range is
     * invisible to the resolver and disappears from
     * `/jira-component-version-ranges` and `/{component}/maven-artifacts`
     * responses — RES-001 family.
     */
    private fun emitRangePresenceRow(
        component: ComponentEntity,
        versionRange: String,
    ) {
        // Idempotent: skip if a presence row for this (component, range)
        // already exists (re-import). The partial unique index
        // `uq_component_configurations_one_range_presence` would also reject
        // duplicates; this is a defence-in-depth pre-check.
        val existing =
            configurationRepository.findByComponentIdAndVersionRangeAndRowType(
                component.id!!,
                versionRange,
                "RANGE_PRESENCE",
            )
        if (existing != null) return
        configurationRepository.save(
            ComponentConfigurationEntity(
                component = component,
                versionRange = versionRange,
                overriddenAttribute = null,
                rowType = "RANGE_PRESENCE",
                isSyntheticBase = false,
            ),
        )
    }

    /** Write all scalar aspect fields from DSL config onto an entity row. */
    @Suppress("CyclomaticComplexMethod")
    private fun populateScalarsFromConfig(
        row: ComponentConfigurationEntity,
        cfg: EscrowModuleConfig,
    ) {
        // build aspect
        row.buildSystem = cfg.buildSystem?.name
        row.buildFilePath = cfg.buildFilePath
        // Store false explicitly (not null) so EntityMappers.setField can assign
        // it to the primitive-boolean EscrowModuleConfig.deprecated without NPE.
        row.deprecated = cfg.isDeprecated

        cfg.buildConfiguration?.let { bp ->
            row.javaVersion = bp.javaVersion
            row.mavenVersion = bp.mavenVersion
            row.gradleVersion = bp.gradleVersion
            row.requiredProject = bp.requiredProject.takeIf { it }
            row.projectVersion = bp.projectVersion
            row.systemProperties = bp.systemProperties
            row.buildTasks = bp.buildTasks
        }
        // escrow.buildTask is stored in its own column (separate from build.buildTasks).
        cfg.escrow?.buildTask?.let { row.escrowBuildTask = it }

        // escrow aspect
        cfg.escrow?.let { escrow ->
            escrow.generation.orElse(null)?.let { row.escrowGeneration = it.name }
            row.escrowReusable = escrow.isReusable.takeIf { it }
            escrow.diskSpaceRequirement.orElse(null)?.let { row.escrowDiskSpace = it.toString() }
            row.escrowProvidedDependencies =
                escrow.providedDependencies.joinToString(",").takeIf { it.isNotEmpty() }
            row.escrowAdditionalSources =
                escrow.additionalSources.joinToString(",").takeIf { it.isNotEmpty() }
            // Note: escrowGradleInclude/Exclude/IncludeTest not in DSL API; left null
        }

        // jira aspect
        cfg.jiraConfiguration?.let { jira ->
            row.jiraProjectKey = jira.projectKey
            row.jiraTechnical = jira.isTechnical.takeIf { it }
            jira.componentVersionFormat?.let { cvf ->
                row.jiraMajorVersionFormat = cvf.majorVersionFormat
                row.jiraReleaseVersionFormat = cvf.releaseVersionFormat
                row.jiraBuildVersionFormat = cvf.buildVersionFormat
                row.jiraLineVersionFormat = cvf.lineVersionFormat
                // hotfixVersionFormat goes to component level; skip here
            }
            jira.componentInfo?.let { info ->
                // Do NOT collapse empty string to null for versionPrefix/versionFormat:
                // bug-G-component DSL sets versionPrefix="" (override range clears "wallet") and baseline
                // preserves "". Collapsing "" → null would prevent the null-clear diff from being
                // emitted and the value would bleed from the base range (bug G).
                row.jiraVersionPrefix = info.versionPrefix
                row.jiraVersionFormat = info.versionFormat
            }
        }
    }

    // =========================================================================
    // §6.5 Override row generation
    // =========================================================================

    /**
     * Emit scalar override rows for each scalar attribute that differs
     * between [base] config and [override] config.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun emitScalarOverrides(
        component: ComponentEntity,
        base: EscrowModuleConfig,
        override: EscrowModuleConfig,
    ): List<ComponentConfigurationEntity> {
        // Transient throwaway entities used only as carriers for the diff
        // computation; never persisted. They still need a `rowType` because
        // the column is NOT NULL on the entity definition — set to BASE for
        // both since they mirror config-shape, not on-disk row-shape.
        val baseRow =
            ComponentConfigurationEntity(
                component = component,
                versionRange = "",
                rowType = "BASE",
            )
        populateScalarsFromConfig(baseRow, base)

        val overRow =
            ComponentConfigurationEntity(
                component = component,
                versionRange = "",
                rowType = "BASE",
            )
        populateScalarsFromConfig(overRow, override)

        val versionRange = override.versionRangeString ?: return emptyList()

        val saved = mutableListOf<ComponentConfigurationEntity>()
        // Collect changed scalar attribute → value pairs
        val changed = collectScalarDiffs(baseRow, overRow)
        for ((attrPath, newValue) in changed) {
            // Avoid duplicate rows for same (component, range, attribute)
            val existing =
                configurationRepository.findByComponentIdAndVersionRangeAndOverriddenAttribute(
                    component.id!!,
                    versionRange,
                    attrPath,
                )
            if (existing != null) {
                saved += existing
                continue // idempotent
            }

            val scalarRow =
                ComponentConfigurationEntity(
                    component = component,
                    versionRange = versionRange,
                    overriddenAttribute = attrPath,
                    rowType = "SCALAR_OVERRIDE",
                    isSyntheticBase = false,
                )
            applyScalarValueToRow(scalarRow, attrPath, newValue)
            saved += configurationRepository.save(scalarRow)
        }
        return saved
    }

    /**
     * Emit marker override rows for each child collection that differs
     * between [base] and [override] configs.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun emitMarkerOverrides(
        component: ComponentEntity,
        @Suppress("UNUSED_PARAMETER") baseConfigRow: ComponentConfigurationEntity,
        base: EscrowModuleConfig,
        override: EscrowModuleConfig,
    ): List<ComponentConfigurationEntity> {
        val versionRange = override.versionRangeString ?: return emptyList()
        val saved = mutableListOf<ComponentConfigurationEntity>()

        // VCS override
        if (vcsSettingsDiffer(base.vcsSettings, override.vcsSettings)) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.VCS_SETTINGS) { row ->
                attachVcsEntries(row, override.vcsSettings)
            }?.let { saved += it }
        }

        // Distribution overrides — check each family
        val baseDist = base.distribution
        val overDist = override.distribution

        if (mavenArtifactsDiffer(baseDist, overDist)) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.DISTRIBUTION_MAVEN) { row ->
                attachMavenArtifacts(row, overDist)
            }?.let { saved += it }
        }
        if (fileUrlArtifactsDiffer(baseDist, overDist)) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.DISTRIBUTION_FILE_URL) { row ->
                attachFileUrlArtifacts(row, overDist)
            }?.let { saved += it }
        }
        if (dockerImagesDiffer(baseDist, overDist)) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.DISTRIBUTION_DOCKER) { row ->
                attachDockerImages(row, overDist)
            }?.let { saved += it }
        }
        if (packagesDiffer(baseDist, overDist)) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.DISTRIBUTION_PACKAGES) { row ->
                attachPackages(row, overDist)
            }?.let { saved += it }
        }

        // Required tools override (junction rows need the config ID; handled inside).
        // Use the same effective base tools that were attached to the BASE row in
        // `importModule` (loader merge + common-defaults fallback) so that an
        // override range whose tools match the effective base does NOT produce a
        // redundant marker row.
        val effectiveBaseToolNames =
            (base.buildConfiguration?.tools.takeUnless { it.isNullOrEmpty() } ?: commonDefaultsToolsCache)
                .mapNotNull { it.name }
                .toSet()
        val overTools = override.buildConfiguration?.tools?.map { it.name }?.toSet() ?: emptySet()
        if (effectiveBaseToolNames != overTools) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.BUILD_REQUIRED_TOOLS) { row ->
                // Required tool junctions use the config ID explicitly, so we must
                // persist the marker row first (to get the ID), then attach tools.
                // saveMarkerRowWithChildren detects that row.id is non-null after this
                // save and skips the redundant second save in its own body.
                configurationRepository.save(row)
                attachRequiredTools(row, override.buildConfiguration?.tools)
            }?.let { saved += it }
        }

        // Build-tool beans override — emit a marker row when the override's buildTools
        // differ from the base's buildTools (comparing by serialised beanType+version pairs).
        val baseBuildToolKeys = buildToolKeys(base.buildConfiguration?.buildTools)
        val overBuildToolKeys = buildToolKeys(override.buildConfiguration?.buildTools)
        if (overBuildToolKeys.isNotEmpty() && baseBuildToolKeys != overBuildToolKeys) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.BUILD_TOOLS) { row ->
                attachBuildToolBeans(row, override.buildConfiguration?.buildTools?.toList() ?: emptyList())
            }?.let { saved += it }
        }

        return saved
    }

    /**
     * Idempotent helper: if a marker row for (component, versionRange, marker) does not
     * yet exist, builds a transient [ComponentConfigurationEntity], passes it to [populate]
     * so children can be added BEFORE the first persist (ensuring cascade), then saves.
     *
     * Children that rely on the generated ID (e.g. required-tool junctions) must be
     * handled separately inside [populate] after they call `repository.save(row)` themselves.
     */
    private fun saveMarkerRowWithChildren(
        component: ComponentEntity,
        versionRange: String,
        marker: String,
        populate: (ComponentConfigurationEntity) -> Unit,
    ): ComponentConfigurationEntity? {
        // Idempotent: if the row already exists (re-run), return it so callers
        // can still count it toward the "did anything land?" check.
        val existing =
            configurationRepository.findByComponentIdAndVersionRangeAndOverriddenAttribute(
                component.id!!,
                versionRange,
                marker,
            )
        if (existing != null) return existing

        val row =
            ComponentConfigurationEntity(
                component = component,
                versionRange = versionRange,
                overriddenAttribute = marker,
                rowType = "MARKER",
                isSyntheticBase = false,
            )
        // Attach children BEFORE save so cascade-persist picks them up
        populate(row)
        // Only save if the lambda didn't already do so
        if (row.id == null) {
            configurationRepository.save(row)
        }
        return row
    }

    // =========================================================================
    // §6.6 Distribution parsing
    // =========================================================================

    private fun attachDistribution(
        row: ComponentConfigurationEntity,
        dist: Distribution?,
    ) {
        dist ?: return
        attachMavenArtifacts(row, dist)
        attachFileUrlArtifacts(row, dist)
        attachDockerImages(row, dist)
        attachPackages(row, dist)
    }

    private fun attachMavenArtifacts(
        row: ComponentConfigurationEntity,
        dist: Distribution?,
    ) {
        val gavCsv = dist?.GAV() ?: return
        var sortOrder = 0
        for (entry in splitCsv(gavCsv)) {
            if (entry.startsWith("file://") || entry.startsWith("http://") || entry.startsWith("https://")) {
                continue // file-URL entries handled separately
            }
            val coords = parseMavenGavEntry(entry) ?: continue
            row.mavenArtifacts.add(
                DistributionMavenArtifactEntity(
                    componentConfiguration = row,
                    groupPattern = coords.groupId,
                    artifactPattern = coords.artifactId,
                    extension = coords.extension,
                    classifier = coords.classifier,
                    sortOrder = sortOrder++,
                ),
            )
        }
    }

    private fun attachFileUrlArtifacts(
        row: ComponentConfigurationEntity,
        dist: Distribution?,
    ) {
        val gavCsv = dist?.GAV() ?: return
        var sortOrder = 0
        for (entry in splitCsv(gavCsv)) {
            if (!entry.startsWith("file://") && !entry.startsWith("http://") && !entry.startsWith("https://")) {
                continue // Maven entries handled separately
            }
            parseFileUrl(entry)?.let { (url, artifactId, classifier) ->
                row.fileUrlArtifacts.add(
                    DistributionFileUrlArtifactEntity(
                        componentConfiguration = row,
                        url = url,
                        artifactId = artifactId,
                        classifier = classifier,
                        sortOrder = sortOrder++,
                    ),
                )
            }
        }
    }

    private fun attachDockerImages(
        row: ComponentConfigurationEntity,
        dist: Distribution?,
    ) {
        val dockerCsv = dist?.docker() ?: return
        var sortOrder = 0
        for (entry in splitCsv(dockerCsv)) {
            parseDockerImage(entry)?.let { (imageName, flavor) ->
                row.dockerImages.add(
                    DistributionDockerImageEntity(
                        componentConfiguration = row,
                        imageName = imageName,
                        flavor = flavor,
                        sortOrder = sortOrder++,
                    ),
                )
            }
        }
    }

    private fun attachPackages(
        row: ComponentConfigurationEntity,
        dist: Distribution?,
    ) {
        dist ?: return
        var sortOrder = 0
        dist.DEB()?.let { debCsv ->
            for (name in splitCsv(debCsv)) {
                row.packages.add(
                    DistributionPackageEntity(
                        componentConfiguration = row,
                        packageType = "DEB",
                        packageName = name,
                        sortOrder = sortOrder++,
                    ),
                )
            }
        }
        sortOrder = 0
        dist.RPM()?.let { rpmCsv ->
            for (name in splitCsv(rpmCsv)) {
                row.packages.add(
                    DistributionPackageEntity(
                        componentConfiguration = row,
                        packageType = "RPM",
                        packageName = name,
                        sortOrder = sortOrder++,
                    ),
                )
            }
        }
    }

    // =========================================================================
    // §6.7 VCS and required tools
    // =========================================================================

    private fun attachVcsEntries(
        row: ComponentConfigurationEntity,
        vcsSettings: VCSSettings?,
    ) {
        vcsSettings ?: return
        val roots = vcsSettings.versionControlSystemRoots ?: return
        var sortOrder = 0
        for (root in roots) {
            val path = root.vcsPath ?: continue // skip roots with no path
            val name = root.name
            row.vcsEntries.add(
                VcsSettingsEntryEntity(
                    componentConfiguration = row,
                    name = name,
                    vcsPath = path,
                    branch = root.rawBranch,
                    tag = root.tag,
                    hotfixBranch = root.hotfixBranch,
                    repositoryType = root.repositoryType?.name,
                    sortOrder = sortOrder++,
                ),
            )
        }
    }

    private fun attachRequiredTools(
        row: ComponentConfigurationEntity,
        tools: List<org.octopusden.octopus.escrow.model.Tool>?,
    ) {
        tools ?: return
        val configId = row.id ?: return
        for (tool in tools) {
            val toolName = tool.name ?: continue
            // Ensure tool exists in dictionary
            upsertTool(toolName, tool.escrowEnvironmentVariable, tool.sourceLocation, tool.targetLocation, tool.installScript)
            val toolExists = toolRepository.findByName(toolName) != null
            if (!toolExists) {
                LOG.warn("Required tool '{}' not found in tools registry; skipping junction", toolName)
                continue
            }
            val junction =
                ComponentRequiredToolEntity(
                    componentConfigurationId = configId,
                    toolName = toolName,
                )
            componentRequiredToolRepository.save(junction)
        }
    }

    /**
     * Persist `buildTools` as `ComponentBuildToolBeanEntity` rows attached to [row].
     *
     * Unknown `BuildTool` subtypes (forward-compat) are skipped with a WARN.
     * Rows are assigned `sortOrder` from their index in the list so that the
     * original DSL order is preserved on retrieval.
     */
    private fun attachBuildToolBeans(
        row: ComponentConfigurationEntity,
        buildTools: List<BuildTool>,
    ) {
        buildTools.forEachIndexed { index, tool ->
            val (beanType, toolType, settingsProperty, versionPattern, edition) = when (tool) {
                is OracleDatabaseToolBean ->
                    BeanFields(
                        "oracleDatabase",
                        "ORACLE",
                        tool.settingsProperty,
                        tool.version,
                        tool.edition?.name,
                    )
                is PTCProductToolBean ->
                    BeanFields("cProduct", null, tool.settingsProperty, tool.version, null)
                is PTKProductToolBean ->
                    BeanFields("kProduct", null, tool.settingsProperty, tool.version, null)
                is PTDProductToolBean ->
                    BeanFields("dProduct", null, tool.settingsProperty, tool.version, null)
                is PTDDbProductToolBean ->
                    BeanFields("dDbProduct", null, tool.settingsProperty, tool.version, null)
                is OdbcToolBean ->
                    BeanFields("odbc", null, null, tool.version, null)
                else -> {
                    LOG.warn("attachBuildToolBeans: unknown BuildTool type {}; skipping", tool::class.simpleName)
                    null
                }
            } ?: return@forEachIndexed

            val entity = ComponentBuildToolBeanEntity(
                componentConfiguration = row,
                beanType = beanType,
                toolType = toolType,
                settingsProperty = settingsProperty,
                versionPattern = versionPattern,
                edition = edition,
                sortOrder = index,
            )
            componentBuildToolBeanRepository.save(entity)
        }
    }

    /** Stable key set used to diff build-tool lists across base and override configs. */
    private fun buildToolKeys(tools: Collection<BuildTool>?): Set<String> =
        buildBuildToolKeys(tools)

    /** Scratch holder for `attachBuildToolBeans` destructuring. */
    private data class BeanFields(
        val beanType: String,
        val toolType: String?,
        val settingsProperty: String?,
        val versionPattern: String?,
        val edition: String?,
    )

    // =========================================================================
    // Diff helpers
    // =========================================================================

    /**
     * Collect (attributePath → newValue) for all scalar columns that differ
     * between [base] and [override] rows.
     *
     * A null [overVal] is a legal "clear" value: it means the override range
     * explicitly clears the inherited base scalar. Previously the predicate
     * `if (overVal != null && overVal != baseVal)` dropped all null overrides,
     * causing the base value to bleed into ranges that should show null (bugs F/G).
     */
    @Suppress("CyclomaticComplexMethod")
    private fun collectScalarDiffs(
        base: ComponentConfigurationEntity,
        override: ComponentConfigurationEntity,
    ): Map<String, Any?> {
        val diffs = mutableMapOf<String, Any?>()

        fun <T> diffScalar(
            attrPath: String,
            baseVal: T?,
            overVal: T?,
        ) {
            // Emit whenever values differ, including when overVal is null (null-clear override).
            if (overVal != baseVal) {
                diffs[attrPath] = overVal
            }
        }

        diffScalar("build.buildSystem", base.buildSystem, override.buildSystem)
        diffScalar("build.buildSystemVersion", base.buildSystemVersion, override.buildSystemVersion)
        diffScalar("build.javaVersion", base.javaVersion, override.javaVersion)
        diffScalar("build.mavenVersion", base.mavenVersion, override.mavenVersion)
        diffScalar("build.gradleVersion", base.gradleVersion, override.gradleVersion)
        diffScalar("build.buildFilePath", base.buildFilePath, override.buildFilePath)
        diffScalar("build.deprecated", base.deprecated, override.deprecated)
        diffScalar("build.requiredProject", base.requiredProject, override.requiredProject)
        diffScalar("build.projectVersion", base.projectVersion, override.projectVersion)
        diffScalar("build.systemProperties", base.systemProperties, override.systemProperties)
        diffScalar("build.buildTasks", base.buildTasks, override.buildTasks)

        diffScalar("escrow.buildTask", base.escrowBuildTask, override.escrowBuildTask)
        diffScalar("escrow.providedDependencies", base.escrowProvidedDependencies, override.escrowProvidedDependencies)
        diffScalar("escrow.reusable", base.escrowReusable, override.escrowReusable)
        diffScalar("escrow.generation", base.escrowGeneration, override.escrowGeneration)
        diffScalar("escrow.diskSpace", base.escrowDiskSpace, override.escrowDiskSpace)
        diffScalar("escrow.additionalSources", base.escrowAdditionalSources, override.escrowAdditionalSources)

        diffScalar("jira.projectKey", base.jiraProjectKey, override.jiraProjectKey)
        diffScalar("jira.technical", base.jiraTechnical, override.jiraTechnical)
        diffScalar("jira.majorVersionFormat", base.jiraMajorVersionFormat, override.jiraMajorVersionFormat)
        diffScalar("jira.releaseVersionFormat", base.jiraReleaseVersionFormat, override.jiraReleaseVersionFormat)
        diffScalar("jira.buildVersionFormat", base.jiraBuildVersionFormat, override.jiraBuildVersionFormat)
        diffScalar("jira.lineVersionFormat", base.jiraLineVersionFormat, override.jiraLineVersionFormat)
        diffScalar("jira.versionPrefix", base.jiraVersionPrefix, override.jiraVersionPrefix)
        diffScalar("jira.versionFormat", base.jiraVersionFormat, override.jiraVersionFormat)

        return diffs
    }

    /**
     * Apply a single typed value to the appropriate column on [row].
     *
     * [value] may be null for import-originated null-clear override rows (the import pipeline
     * represents "this range clears the inherited base scalar" by emitting a SCALAR_OVERRIDE row
     * with the discriminator column set and the typed column left null). The `overriddenAttribute`
     * discriminator is the source of truth; null typed column = explicit clear, not absent override.
     *
     * **V4 POST path (`ConfigurationRowAccessors.applyScalarValue`) rejects null with "use DELETE"
     * — that contract is unchanged. This function is import-only.**
     */
    @Suppress("CyclomaticComplexMethod")
    private fun applyScalarValueToRow(
        row: ComponentConfigurationEntity,
        attrPath: String,
        value: Any?,
    ) {
        when (attrPath) {
            "build.buildSystem" -> row.buildSystem = value?.toString()
            "build.buildSystemVersion" -> row.buildSystemVersion = value?.toString()
            "build.javaVersion" -> row.javaVersion = value?.toString()
            "build.mavenVersion" -> row.mavenVersion = value?.toString()
            "build.gradleVersion" -> row.gradleVersion = value?.toString()
            "build.buildFilePath" -> row.buildFilePath = value?.toString()
            "build.deprecated" -> row.deprecated = value as? Boolean ?: value?.toString()?.toBooleanStrictOrNull()
            "build.requiredProject" -> row.requiredProject = value as? Boolean ?: value?.toString()?.toBooleanStrictOrNull()
            "build.projectVersion" -> row.projectVersion = value?.toString()
            "build.systemProperties" -> row.systemProperties = value?.toString()
            "build.buildTasks" -> row.buildTasks = value?.toString()
            "escrow.buildTask" -> row.escrowBuildTask = value?.toString()
            "escrow.providedDependencies" -> row.escrowProvidedDependencies = value?.toString()
            "escrow.reusable" -> row.escrowReusable = value as? Boolean ?: value?.toString()?.toBooleanStrictOrNull()
            "escrow.generation" -> row.escrowGeneration = value?.toString()
            "escrow.diskSpace" -> row.escrowDiskSpace = value?.toString()
            "escrow.additionalSources" -> row.escrowAdditionalSources = value?.toString()
            "jira.projectKey" -> row.jiraProjectKey = value?.toString()
            "jira.technical" -> row.jiraTechnical = value as? Boolean ?: value?.toString()?.toBooleanStrictOrNull()
            "jira.majorVersionFormat" -> row.jiraMajorVersionFormat = value?.toString()
            "jira.releaseVersionFormat" -> row.jiraReleaseVersionFormat = value?.toString()
            "jira.buildVersionFormat" -> row.jiraBuildVersionFormat = value?.toString()
            "jira.lineVersionFormat" -> row.jiraLineVersionFormat = value?.toString()
            "jira.versionPrefix" -> row.jiraVersionPrefix = value?.toString()
            "jira.versionFormat" -> row.jiraVersionFormat = value?.toString()
            else -> LOG.warn("Unknown scalar attribute path: '{}'", attrPath)
        }
    }

    private fun vcsSettingsDiffer(
        base: VCSSettings?,
        override: VCSSettings?,
    ): Boolean {
        if (base == null && override == null) return false
        if (base == null || override == null) return true
        val baseRoots = base.versionControlSystemRoots ?: emptyList<Any>()
        val overRoots = override.versionControlSystemRoots ?: emptyList<Any>()
        return baseRoots != overRoots || base.externalRegistry != override.externalRegistry
    }

    private fun mavenArtifactsDiffer(
        base: Distribution?,
        override: Distribution?,
    ): Boolean = extractMavenGavs(base?.GAV()) != extractMavenGavs(override?.GAV())

    private fun fileUrlArtifactsDiffer(
        base: Distribution?,
        override: Distribution?,
    ): Boolean = extractFileUrls(base?.GAV()) != extractFileUrls(override?.GAV())

    private fun dockerImagesDiffer(
        base: Distribution?,
        override: Distribution?,
    ): Boolean = base?.docker() != override?.docker()

    private fun packagesDiffer(
        base: Distribution?,
        override: Distribution?,
    ): Boolean = base?.DEB() != override?.DEB() || base?.RPM() != override?.RPM()

    private fun extractMavenGavs(gavCsv: String?): List<String> {
        gavCsv ?: return emptyList()
        return splitCsv(gavCsv).filter {
            !it.startsWith("file://") && !it.startsWith("http://") && !it.startsWith("https://")
        }
    }

    private fun extractFileUrls(gavCsv: String?): List<String> {
        gavCsv ?: return emptyList()
        return splitCsv(gavCsv).filter {
            it.startsWith("file://") || it.startsWith("http://") || it.startsWith("https://")
        }
    }

    // =========================================================================
    // §6.3 Aggregator handling — Pass 3
    // =========================================================================

    /**
     * Pass 3 (§6.3): create `component_groups` rows and set `component_group_id` FKs.
     *
     * Strategy: build a reverse map parentKey → [childKeys] from [pendingParentByKey], then
     * for each aggregator parent:
     *  1. Classify REAL vs FAKE via [isFakeAggregator] against the parent's first DSL config.
     *  2. Upsert a [ComponentGroupEntity] (idempotent — re-runs skip existing rows).
     *  3. Set `componentGroup` on every child component — and update it when the existing
     *     link points at a *different* group (DSL parent changed since the last migration).
     *  4. For a REAL aggregator: same self-link update rule.
     *
     * @return per-parent failures: list of `(parentKey, errorMessage)`. Empty list on full
     *         success. Callers should fold these into their own result aggregation so a
     *         partial Pass 3 failure does not silently look like a fully-successful migration.
     */
    private fun linkAggregatorGroups(
        allModules: Map<String, EscrowModule>,
        pendingParentByKey: Map<String, String>,
    ): List<Pair<String, String>> {
        // Reverse the child→parent map to parent→[children]
        val childrenByParent = mutableMapOf<String, MutableList<String>>()
        for ((childKey, parentKey) in pendingParentByKey) {
            childrenByParent.getOrPut(parentKey) { mutableListOf() }.add(childKey)
        }

        // Aggregate per-parent failures and log a single summary at the end
        // instead of letting individual WARN lines hide a systemic issue. A
        // migration step is a one-shot batch — silent partial-success is the
        // wrong default. Callers that need stricter behaviour can inspect the
        // summary log or wire this list into BatchMigrationResult later.
        val failures = mutableListOf<Pair<String, String>>()
        for ((parentKey, childKeys) in childrenByParent) {
            try {
                val parentModule = allModules[parentKey]
                if (parentModule == null) {
                    LOG.warn("§6.3 Pass 3: aggregator parent '{}' not found in DSL; skipping group creation", parentKey)
                    continue
                }
                val parentFirstConfig = parentModule.moduleConfigurations.firstOrNull()
                if (parentFirstConfig == null) {
                    LOG.warn("§6.3 Pass 3: aggregator parent '{}' has no DSL configs; skipping", parentKey)
                    continue
                }
                val fake = isFakeAggregator(parentFirstConfig)

                // Upsert the group (idempotent)
                val group = upsertComponentGroup(parentKey, fake)

                // Link every sub-component to the group
                for (childKey in childKeys) {
                    val child = componentRepository.findByComponentKey(childKey)
                    if (child == null) {
                        LOG.warn("§6.3 Pass 3: child '{}' not found in DB; skipping group link", childKey)
                        continue
                    }
                    if (child.componentGroup?.id != group.id) {
                        child.componentGroup = group
                        componentRepository.save(child)
                        LOG.debug("§6.3 Pass 3: linked child '{}' → group '{}'", childKey, parentKey)
                    }
                }

                // For a REAL aggregator, also link the parent itself to its own group
                if (!fake) {
                    val parent = componentRepository.findByComponentKey(parentKey)
                    if (parent != null && parent.componentGroup?.id != group.id) {
                        parent.componentGroup = group
                        componentRepository.save(parent)
                        LOG.debug("§6.3 Pass 3: linked REAL aggregator '{}' → its own group", parentKey)
                    }
                }

                LOG.info(
                    "§6.3 Pass 3: group '{}' (isFake={}) created; {} sub-component(s) linked",
                    parentKey,
                    fake,
                    childKeys.size,
                )
            } catch (e: Exception) {
                LOG.error("§6.3 Pass 3: failed to create group for aggregator '{}': {}", parentKey, e.message, e)
                failures += parentKey to (e.message ?: e.javaClass.simpleName)
            }
        }
        if (failures.isNotEmpty()) {
            LOG.error(
                "§6.3 Pass 3 finished with {} group-creation failure(s): {}",
                failures.size,
                failures.joinToString { "${it.first}=${it.second}" },
            )
        }
        return failures
    }

    /** Upsert a [ComponentGroupEntity] by [groupKey]. Idempotent. */
    private fun upsertComponentGroup(
        groupKey: String,
        isFake: Boolean,
    ): ComponentGroupEntity {
        val existing = componentGroupRepository.findByGroupKey(groupKey)
        if (existing != null) return existing
        return componentGroupRepository.save(ComponentGroupEntity(groupKey = groupKey, isFake = isFake))
    }

    // =========================================================================
    // §6.3 Aggregator detection helpers (per schema-spec.md §4.3)
    // =========================================================================

    internal fun isFakeAggregator(cfg: EscrowModuleConfig): Boolean {
        val vcsUrl = cfg.vcsSettings?.versionControlSystemRoots?.firstOrNull()?.vcsPath
        val artifactId = cfg.artifactIdPattern ?: ""
        return vcsUrl.isNullOrBlank() || isFakeVcsUrl(vcsUrl) || isFakeArtifactId(artifactId)
    }

    internal fun isFakeVcsUrl(url: String): Boolean =
        "/fake/" in url ||
            "/dummy/" in url ||
            url.endsWith("fake.git") ||
            url.endsWith("dummy.git") ||
            url.endsWith("stub.git")

    internal fun isFakeArtifactId(aid: String): Boolean {
        val lower = aid.lowercase().trim()
        if (lower in FAKE_ARTIFACT_ID_LITERALS) return true
        return FAKE_ARTIFACT_ID_TOKEN.containsMatchIn(lower)
    }

    // =========================================================================
    // Distribution parsing helpers (splitCsv / MavenCoords / parseMavenGavEntry
    // are now in util/GavParsing.kt and imported at the top of this file)
    // =========================================================================

    private data class FileUrlCoords(
        val url: String,
        val artifactId: String?,
        val classifier: String?,
    )

    private fun parseFileUrl(entry: String): FileUrlCoords? {
        if (entry.isBlank()) return null
        val questionIdx = entry.indexOf('?')
        val url = if (questionIdx >= 0) entry.substring(0, questionIdx) else entry
        val queryStr = if (questionIdx >= 0) entry.substring(questionIdx + 1) else ""
        val params =
            queryStr.split("&").mapNotNull {
                val eqIdx = it.indexOf('=')
                if (eqIdx > 0) it.substring(0, eqIdx) to it.substring(eqIdx + 1) else null
            }.toMap()
        return FileUrlCoords(
            url = url,
            artifactId = params["artifactId"]?.takeIf { it.isNotEmpty() },
            classifier = params["classifier"]?.takeIf { it.isNotEmpty() },
        )
    }

    private data class DockerImageCoords(
        val imageName: String,
        val flavor: String?,
    )

    /**
     * Parse `image[:flavor]`. Split on last `:` to get the flavor.
     * Flavor is the build variant (NOT a version tag like `1.2.3`).
     */
    private fun parseDockerImage(entry: String): DockerImageCoords? {
        if (entry.isBlank()) return null
        val lastColon = entry.lastIndexOf(':')
        if (lastColon < 0) return DockerImageCoords(imageName = entry, flavor = null)
        val imageName = entry.substring(0, lastColon)
        val flavor = entry.substring(lastColon + 1).takeIf { it.isNotEmpty() }
        return DockerImageCoords(imageName = imageName, flavor = flavor)
    }

    // =========================================================================
    // Companion / constants
    // =========================================================================

    companion object {
        private val LOG = LoggerFactory.getLogger(ImportServiceImpl::class.java)

        /** Exact-string FAKE-aggregator artifactId markers. */
        private val FAKE_ARTIFACT_ID_LITERALS: Set<String> = setOf("fake", "dummy", "stub")

        /**
         * Token-based FAKE-aggregator artifactId marker: matches `fake`/`dummy`/`stub`
         * as a hyphen- or comma-delimited token (e.g. `aggregator-core-stub`,
         * `dummy-tool`). Compiled once at class-init.
         */
        private val FAKE_ARTIFACT_ID_TOKEN: Regex = Regex("(^|-)(fake|dummy|stub)(-|$|,)")
    }
}

/**
 * Stable per-bean key set used to diff build-tool lists across base and override configs.
 * Extracted from `ImportServiceImpl` as a top-level `internal fun` so it can be unit-tested
 * directly without spinning up a Spring context.
 *
 * Key shape: `<beanType>:<settingsProperty>:<version>` (plus `:<edition>` for
 * `OracleDatabaseToolBean`). `settingsProperty` is part of the discriminator because two
 * beans of the same type/version that differ only in `settingsProperty` are semantically
 * distinct — without it, `emitMarkerOverrides` silently drops the override and the base
 * `settingsProperty` bleeds into the override range. `edition` is meaningful only for
 * Oracle (always null for the others).
 */
internal fun buildBuildToolKeys(tools: Collection<BuildTool>?): Set<String> =
    tools?.mapNotNull { tool ->
        when (tool) {
            is OracleDatabaseToolBean ->
                "oracleDatabase:${tool.getSettingsProperty()}:${tool.version}:${tool.edition?.name}"
            is PTCProductToolBean ->
                "cProduct:${tool.getSettingsProperty()}:${tool.version}"
            is PTKProductToolBean ->
                "kProduct:${tool.getSettingsProperty()}:${tool.version}"
            is PTDProductToolBean ->
                "dProduct:${tool.getSettingsProperty()}:${tool.version}"
            is PTDDbProductToolBean ->
                "dDbProduct:${tool.getSettingsProperty()}:${tool.version}"
            is OdbcToolBean ->
                // OdbcToolBean has no settingsProperty — `<version>` is the only distinguishing field.
                "odbc:${tool.version}"
            else -> null
        }
    }?.toSet() ?: emptySet()
