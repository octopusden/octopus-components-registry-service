package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.mapper.toComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.MigrationResult
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.octopusden.octopus.components.registry.server.service.ValidationResult
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ImportServiceImpl(
    private val gitResolver: ComponentRegistryResolverImpl,
    @Qualifier("databaseComponentRegistryResolver")
    private val dbResolver: DatabaseComponentRegistryResolver,
    private val componentRepository: ComponentRepository,
    private val componentSourceRepository: ComponentSourceRepository,
    private val sourceRegistry: ComponentSourceRegistry,
    private val configurationLoader: EscrowConfigurationLoader,
    private val registryConfigRepository: RegistryConfigRepository,
) : ImportService {
    @Suppress("TooGenericExceptionCaught")
    @Transactional
    override fun migrateComponent(
        name: String,
        dryRun: Boolean,
    ): MigrationResult {
        LOG.info("Migrating component '{}' (dryRun={})", name, dryRun)

        // 1. Get from Git resolver
        val escrowModule =
            gitResolver.getComponentById(name)
                ?: return MigrationResult(name, false, dryRun, "Component '$name' not found in Git DSL")

        // 2. Check if already migrated
        if (sourceRegistry.isDbComponent(name) && !dryRun) {
            return MigrationResult(name, false, dryRun, "Component '$name' is already migrated to DB")
        }

        // 3. Convert to entity
        val entity =
            try {
                escrowModule.toComponentEntity()
            } catch (e: Exception) {
                LOG.error("Failed to convert component '{}' to entity", name, e)
                return MigrationResult(name, false, dryRun, "Conversion error: ${e.message}")
            }

        if (dryRun) {
            return MigrationResult(name, true, true, "Dry run: component can be migrated")
        }

        // 4. Save to DB (delete existing if re-migrating)
        val existing = componentRepository.findByName(name)
        if (existing != null) {
            componentRepository.delete(existing)
            componentRepository.flush()
        }

        try {
            componentRepository.save(entity)
            componentRepository.flush()
        } catch (e: Exception) {
            LOG.error("Failed to save component '{}' to DB", name, e)
            return MigrationResult(name, false, false, "Save error: ${e.message}")
        }

        // 5. Validate: compare git vs db output
        val discrepancies = validateComponentData(name, escrowModule)

        // 6. Switch source
        if (discrepancies.isEmpty()) {
            sourceRegistry.setComponentSource(name, "db")
            return MigrationResult(name, true, false, "Successfully migrated")
        } else {
            // Still switch to DB but report discrepancies
            sourceRegistry.setComponentSource(name, "db")
            return MigrationResult(name, true, false, "Migrated with ${discrepancies.size} discrepancies", discrepancies)
        }
    }

    override fun migrateAllComponents(): BatchMigrationResult {
        val allComponents = gitResolver.getComponents()
        val results = mutableListOf<MigrationResult>()
        var migrated = 0
        var failed = 0
        var skipped = 0

        for (module in allComponents) {
            if (sourceRegistry.isDbComponent(module.moduleName)) {
                skipped++
                results.add(MigrationResult(module.moduleName, true, false, "Already migrated, skipped"))
                continue
            }
            val result = migrateComponent(module.moduleName, false)
            results.add(result)
            if (result.success) migrated++ else failed++
        }

        return BatchMigrationResult(
            total = allComponents.size,
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
        val gitModule =
            gitResolver.getComponentById(name)
                ?: return ValidationResult(name, false, listOf("Component not found in Git DSL"))

        val discrepancies = validateComponentData(name, gitModule)
        return ValidationResult(name, discrepancies.isEmpty(), discrepancies)
    }

    override fun migrate(): FullMigrationResult {
        val defaults = migrateDefaults()
        val components = migrateAllComponents()
        return FullMigrationResult(defaults = defaults, components = components)
    }

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    override fun migrateDefaults(): Map<String, Any?> {
        LOG.info("Migrating component defaults from Git DSL")
        val defaults = configurationLoader.loadCommonDefaults(emptyMap())
        val map =
            buildMap<String, Any?> {
                // Existing scalar fields
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

                // deprecated
                defaults.deprecated?.let { put("deprecated", it) }

                // octopusVersion
                defaults.octopusVersion?.let { put("octopusVersion", it) }

                // build parameters (nested map)
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

                // jira component (nested map)
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

                // distribution (nested map)
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

                // VCS settings wrapper (nested map)
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

                // escrow (nested map)
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

                // doc (nested map)
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

    @Suppress("TooGenericExceptionCaught")
    private fun validateComponentData(
        name: String,
        gitModule: EscrowModule,
    ): List<String> {
        val discrepancies = mutableListOf<String>()

        val dbModule =
            try {
                dbResolver.getComponentById(name)
            } catch (e: Exception) {
                return listOf("DB resolver error: ${e.message}")
            }

        if (dbModule == null) {
            return listOf("Component not found in DB after migration")
        }

        // Compare module names
        if (gitModule.moduleName != dbModule.moduleName) {
            discrepancies.add("Module name mismatch: git='${gitModule.moduleName}', db='${dbModule.moduleName}'")
        }

        // Compare configuration count
        if (gitModule.moduleConfigurations.size != dbModule.moduleConfigurations.size) {
            discrepancies.add(
                "Configuration count mismatch: git=${gitModule.moduleConfigurations.size}, " +
                    "db=${dbModule.moduleConfigurations.size}",
            )
        }

        // Compare default config fields
        val gitDefault = gitModule.moduleConfigurations.firstOrNull()
        val dbDefault = dbModule.moduleConfigurations.firstOrNull()
        if (gitDefault != null && dbDefault != null) {
            if (gitDefault.componentOwner != dbDefault.componentOwner) {
                discrepancies.add("componentOwner mismatch: git='${gitDefault.componentOwner}', db='${dbDefault.componentOwner}'")
            }
            if (gitDefault.componentDisplayName != dbDefault.componentDisplayName) {
                discrepancies.add("displayName mismatch: git='${gitDefault.componentDisplayName}', db='${dbDefault.componentDisplayName}'")
            }
            if (gitDefault.buildSystem != dbDefault.buildSystem) {
                discrepancies.add("buildSystem mismatch: git='${gitDefault.buildSystem}', db='${dbDefault.buildSystem}'")
            }
        }

        return discrepancies
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ImportServiceImpl::class.java)
    }
}
