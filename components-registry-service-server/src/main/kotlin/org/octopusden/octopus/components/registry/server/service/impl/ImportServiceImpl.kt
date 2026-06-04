@file:Suppress("TooManyFunctions", "LargeClass", "LongMethod")

package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.octopusden.octopus.components.registry.api.beans.OdbcToolBean
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTDDbProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTDProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
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
@ConditionalOnDatabaseEnabled
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
    private val componentRequiredToolRepository: ComponentRequiredToolRepository,
    private val componentBuildToolBeanRepository: ComponentBuildToolBeanRepository,
) : ImportService {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * In-migration dictionary caches, scoped via [ThreadLocal] to the
     * migration thread. Populated at the top of Pass 1 from a full
     * `findAll()` of the dictionary tables; reset in the matching `finally`
     * block. While set, `upsertSystem/Label/Tool` short-circuit the per-call
     * `findByCode`/`findByName` lookup via Set membership — important once
     * `flush() + clear()` starts evicting the JPA L1 cache mid-pass.
     *
     * Thread-confinement is load-bearing for correctness across transactions.
     * A previous design held these on the singleton service with `@Volatile`
     * and a [java.util.concurrent.ConcurrentHashMap]-backed Set; that made
     * the Set itself race-free but did not solve the cross-transaction
     * visibility issue: a parallel `migrateComponent` (its own transaction)
     * would see a code as "in cache" — claiming the row exists — when the
     * `migrateAllComponents` transaction that inserted it has not yet
     * committed. The subsequent junction insert in the single-component
     * transaction then trips an FK constraint. With [ThreadLocal] the
     * single-component thread sees `null`, falls back to the per-call
     * `findBy*` against its own transaction's snapshot, and stays
     * consistent.
     *
     * A nested call on the same thread (e.g. `migrate()` →
     * `migrateAllComponents()`) shares the cache as intended.
     */
    private val knownSystemCodes: ThreadLocal<MutableSet<String>?> = ThreadLocal.withInitial { null }
    private val knownLabelCodes: ThreadLocal<MutableSet<String>?> = ThreadLocal.withInitial { null }
    private val knownToolNames: ThreadLocal<MutableSet<String>?> = ThreadLocal.withInitial { null }

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

                // R1 §4.3/§6.3 — group membership is driven by the DSL `components { }`
                // aggregator membership (fullConfig.aggregatorSubComponents), NOT the flat
                // parentComponent field. `name` is an aggregator iff it owns a components{} block.
                // NOTE: a FAKE aggregator STILL gets a ComponentEntity row (so the v1–v3
                // resolver keeps serving it = compat parity with V1); it is only excluded from
                // the v4 regular-components list (ComponentManagementServiceImpl.buildSpecification).
                val subComponentsOfThis: Set<String> = fullConfig.aggregatorSubComponents[name] ?: emptySet()
                val isAggregator = subComponentsOfThis.isNotEmpty()

                importModule(name, module.moduleConfigurations)
                sourceRegistry.setComponentSource(name, "db")

                // §6.3 single-path grouping, keyed off `components { }` membership:
                //   (b) aggregator (owns components{}, fake OR real) → create its group,
                //       self-link, and link any children already in DB;
                //   (c) sub-component of some aggregator → link it to that aggregator's group.
                val pass3Input: Map<String, Set<String>> =
                    if (isAggregator) {
                        mapOf(name to subComponentsOfThis)
                    } else {
                        // case (c): `name` is a sub-component of one or more aggregators →
                        // link it to each owning aggregator's group.
                        fullConfig.aggregatorSubComponents.filter { (_, subKeys) -> name in subKeys }
                    }
                val pass3Failures: List<Pair<String, String>> =
                    if (pass3Input.isNotEmpty()) {
                        linkAggregatorGroups(fullConfig.escrowModules, pass3Input)
                    } else {
                        emptyList()
                    }
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
    @Suppress("LongMethod")
    override fun migrateAllComponents(progress: MigrationProgressListener): BatchMigrationResult {
        val totalStart = System.nanoTime()
        LOG.info("Starting full DSL → DB component migration (schema v2 §6 pipeline)")
        // Use lenient loader so migration tolerates DSL entries that have semantic
        // validation warnings (labels-not-available, hotfix-format, missing VCS roots,
        // etc.). The git-backed resolver validates at request time; the import pipeline
        // should import what is there, not refuse to run due to pre-existing warnings.
        val loadStart = System.nanoTime()
        val fullConfig = configurationLoader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap())
        val allModules = fullConfig.escrowModules
        LOG.info("§6 DSL load: {} ms ({} modules)", (System.nanoTime() - loadStart) / 1_000_000, allModules.size)

        // §6.1 Pre-pass dictionary discovery
        LOG.info("§6.1 Pre-pass: upserting systems, tools, labels")
        val dictStart = System.nanoTime()
        val sysStart = System.nanoTime()
        preupsertSystemsFromConfig(allModules.values.flatMap { it.moduleConfigurations })
        val sysMs = (System.nanoTime() - sysStart) / 1_000_000
        val toolStart = System.nanoTime()
        preupsertToolsFromLoader(fullConfig)
        val toolMs = (System.nanoTime() - toolStart) / 1_000_000
        val labelStart = System.nanoTime()
        preupsertLabelsFromLoader()
        val labelMs = (System.nanoTime() - labelStart) / 1_000_000
        LOG.info(
            "§6.1 dictionary preupsert complete: {} ms (systems={} ms, tools={} ms, labels={} ms)",
            (System.nanoTime() - dictStart) / 1_000_000, sysMs, toolMs, labelMs,
        )

        // Prime in-migration dictionary caches so per-component upserts
        // (linkSystems / linkLabels / attachRequiredTools) skip the redundant
        // `findByCode` / `findByName` round-trips that JPA's L1 cache would
        // normally absorb. Combined with the `flush() + clear()` below, the
        // L1 cache is evicted every 50 components; without these explicit
        // Sets the find calls re-hit the DB on every flush boundary.
        //
        // The try/finally below resets these fields even when the migration
        // throws midway. Without the reset a failed run would leave the Sets
        // claiming dictionary codes were inserted; the rollback rolled back
        // the inserts but the in-memory cache outlives the transaction. A
        // subsequent `migrateComponent` call would then skip a needed insert
        // and the next junction save would trip the FK. Assignments happen
        // *inside* the try so a partial assignment (one field set, the next
        // `findAll` throws) is still cleaned up by the same finally.
        try {
            knownSystemCodes.set(systemRepository.findAll().mapTo(HashSet()) { it.code })
            knownLabelCodes.set(labelRepository.findAll().mapTo(HashSet()) { it.code })
            knownToolNames.set(toolRepository.findAll().mapTo(HashSet()) { it.name })

            // §6.2 Two-pass component import
            LOG.info("§6.2 Two-pass component import for {} modules", allModules.size)

            // Pre-compute parentComponent references from DSL up front, independent of Pass 1's
            // insert/skip decisions. This keeps Passes 2 and 3 idempotent on re-runs: even when
            // a component is already in the DB and Pass 1 skips it, its parent FK and aggregator-
            // group membership are still resolved against the current DSL.
            val deriveStart = System.nanoTime()
            val pendingParentByKey: Map<String, String> =
                allModules.mapNotNull { (componentKey, escrowModule) ->
                    val firstConfig = escrowModule.moduleConfigurations.firstOrNull() ?: return@mapNotNull null
                    firstConfig.parentComponent?.takeIf { it.isNotBlank() }?.let { parentKey ->
                        componentKey to parentKey
                    }
                }.toMap()

            // R1: an aggregator is a component that OWNS a `components { }` block (a key in
            // `aggregatorSubComponents`), NOT merely a flat-`parentComponent` target. Among
            // aggregators the FAKE ones (stub VCS / artifactId) used to be group-only (no
            // ComponentEntity row); they now get a normal row too — so the v1–v3 resolver keeps
            // serving them (compat parity with V1) — and are excluded from the v4 list instead
            // (ComponentManagementServiceImpl.buildSpecification). This set is kept only for the
            // derivation-summary log below.
            val fakeAggregatorKeys: Set<String> =
                fullConfig.aggregatorSubComponents.keys.filter { parentKey ->
                    val firstConfig = allModules[parentKey]?.moduleConfigurations?.firstOrNull()
                    firstConfig != null && isFakeAggregator(firstConfig)
                }.toSet()
            LOG.info(
                "§6 DSL derivation: {} ms ({} parent refs, {} aggregators, {} FAKE)",
                (System.nanoTime() - deriveStart) / 1_000_000, pendingParentByKey.size,
                fullConfig.aggregatorSubComponents.size, fakeAggregatorKeys.size,
            )

            var total = 0
            var migrated = 0
            var failed = 0
            var skipped = 0
            val results = mutableListOf<MigrationResult>()

            // Pass 1: create all components (parentComponent = null)
            val pass1Start = System.nanoTime()
            // Batch-load existing component keys so the per-iteration
            // findByComponentKey round-trip drops out. Targeted query (`IN (...)`
            // on the DSL key set) rather than `findAll()` so the cost stays
            // bounded by DSL size, not total DB size.
            val moduleKeys = allModules.keys
            val existingComponentKeys: Set<String> =
                componentRepository.findByComponentKeyIn(moduleKeys).mapTo(HashSet()) { it.componentKey }

            // Source-flag staging is plain data (key → migratedAt timestamp) so
            // that the `entityManager.clear()` below cannot detach managed
            // entities we still need to write. Materialise fresh entities at
            // end-of-Pass-1; that avoids the `merge`-induced SELECT-before-INSERT
            // per row that would otherwise eat back the JDBC batch savings.
            val stagedSourceUpdates = LinkedHashMap<String, Instant>()
            fun stageSource(name: String) {
                stagedSourceUpdates[name] = Instant.now()
            }
            // After importing this many components, flush pending INSERTs and
            // clear the JPA persistence context. Without this the session grows
            // to thousands of managed entities (ComponentEntity + base config +
            // junctions + override rows) and dirty-checking cost grows
            // super-linearly. 50 matches `hibernate.jdbc.batch_size` so each
            // flush boundary aligns with one full JDBC batch.
            //
            // The modulus below is on `total`, which is incremented
            // unconditionally for every iteration — including skipped
            // (already-in-DB, no-configurations) and failed
            // entries. This is by design: the session may have grown via
            // partial cascades even on the skip paths, so the eviction
            // cadence is driven by iteration count, not persisted-row count.
            val flushEvery = 50
            for ((componentKey, escrowModule) in allModules) {
                if (total > 0 && total % flushEvery == 0) {
                    entityManager.flush()
                    entityManager.clear()
                }
                total++
                try {
                    // NOTE: FAKE aggregators are NO LONGER skipped here. They get a normal
                    // ComponentEntity row (inserted below) so the v1–v3 resolver keeps serving
                    // them (compat parity with V1); Pass 3 still creates + self-links their
                    // ComponentGroupEntity, and the v4 list excludes them via buildSpecification.

                    if (componentKey in existingComponentKeys) {
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
                    stageSource(componentKey)
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

            if (stagedSourceUpdates.isNotEmpty()) {
                // Materialise fresh / re-fetched entities now so saveAll sees
                // either managed (from this fetch) or genuinely new entities —
                // no detached merges, no SELECT-before-INSERT round-trips.
                val existingSources =
                    componentSourceRepository.findAllById(stagedSourceUpdates.keys).associateBy { it.componentKey }
                val toSave = stagedSourceUpdates.map { (key, ts) ->
                    val entity = existingSources[key] ?: ComponentSourceEntity(componentKey = key)
                    entity.source = "db"
                    entity.migratedAt = ts
                    entity
                }
                componentSourceRepository.saveAll(toSave)
            }

            val pass1Ms = (System.nanoTime() - pass1Start) / 1_000_000
            LOG.info(
                "§6.2 Pass 1 complete: {} ms ({} migrated, {} skipped, {} failed of {} total)",
                pass1Ms, migrated, skipped, failed, total,
            )

            // Pass 2: resolve parentComponent FK references + seed canBeParent
            LOG.info("§6.2 Pass 2: resolving {} parentComponent references", pendingParentByKey.size)
            val pass2Start = System.nanoTime()
            // Grandfathered parent-of-parent detection: a key that is BOTH referenced
            // as a parent AND references a parent itself. The schema-v2 contract
            // forbids NEW parent-of-parent assignments (enforced by the v4 API), but
            // legacy DSL may contain them — warn, never drop the FK (no silent data loss).
            val multiLevelKeys = pendingParentByKey.values.toSet().intersect(pendingParentByKey.keys)
            if (multiLevelKeys.isNotEmpty()) {
                LOG.warn(
                    "§6.2 multi-level hierarchy (parent-of-a-parent) for {} component(s): {} — FKs preserved; " +
                        "the v4 API rejects new/changed parent-of-parent assignments (grandfathered rows are tolerated)",
                    multiLevelKeys.size,
                    multiLevelKeys.take(20),
                )
            }
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
                    // Seed canBeParent on the referenced parent: any component referenced
                    // as a `parentComponent` becomes can-be-parent (eligible in the parent
                    // picker). This is INDEPENDENT of aggregator status — an aggregator owns
                    // a `components { }` block (§6.3 / aggregatorSubComponents), a separate
                    // concept. Idempotent — set true once, never false. A FAKE aggregator now
                    // HAS a row, so it may be seeded can-be-parent here, but it stays excluded
                    // from every v4 list query (buildSpecification) and so never enters the picker.
                    if (!parent.canBeParent) {
                        parent.canBeParent = true
                        componentRepository.save(parent)
                    }
                    val child = componentRepository.findByComponentKey(childKey) ?: continue
                    child.parentComponent = parent
                    componentRepository.save(child)
                } catch (e: Exception) {
                    LOG.warn("Failed to link parentComponent '{}' → '{}': {}", childKey, parentKey, e.message)
                }
            }

            LOG.info("§6.2 Pass 2 complete: {} ms ({} parent refs)", (System.nanoTime() - pass2Start) / 1_000_000, pendingParentByKey.size)

            // §6.3 Pass 3: upsert component_groups rows and link component_group_id FKs from the
            // DSL `components { }` aggregator membership (R1 — NOT the flat parentComponent graph).
            // Runs after Pass 2 so all ComponentEntity rows are present.
            LOG.info("§6.3 Pass 3: linking {} aggregator group(s)", fullConfig.aggregatorSubComponents.size)
            val pass3Start = System.nanoTime()
            val pass3Failures = linkAggregatorGroups(allModules, fullConfig.aggregatorSubComponents)
            LOG.info(
                "§6.3 Pass 3 complete: {} ms ({} failures)",
                (System.nanoTime() - pass3Start) / 1_000_000, pass3Failures.size,
            )
            // §6.3 cleanup (re-run safety): the OLD logic grouped by flat parentComponent and could
            // have created groups for non-aggregators (a plain parentComponent target with no
            // components{} block). Remove any group whose key is
            // not a current true aggregator — unlink its members and delete the orphaned row.
            cleanupStaleGroups(fullConfig.aggregatorSubComponents.keys)
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
                "Migration complete in {} ms: total={}, migrated={}, failed={}, skipped={}",
                (System.nanoTime() - totalStart) / 1_000_000,
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
        } finally {
            // Reset the in-migration dictionary caches unconditionally. The
            // try around the body ensures this runs even if Pass 1/2/3
            // throws; the next entrant primes the fields fresh from the DB.
            knownSystemCodes.remove()
            knownLabelCodes.remove()
            knownToolNames.remove()
        }
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

    @Transactional
    override fun migrate(progress: MigrationProgressListener): FullMigrationResult {
        // @Transactional here exists for the self-call transaction context,
        // NOT atomicity. `migrate()` calls `migrateDefaults()` and
        // `migrateAllComponents()` directly on `this`; both inner methods
        // carry their own @Transactional, but Spring's proxy advice does
        // not fire on intra-class calls. Without an outer @Transactional
        // the chain runs with no active transaction and
        // `entityManager.flush()` inside Pass 1 throws
        // `TransactionRequiredException` (Spring Data's per-save auto-tx
        // covered every plain `save()` before but does not cover a bare
        // `flush()` call).
        //
        // Atomicity disclaimer: per-component failures in
        // `migrateAllComponents` are caught, counted into
        // `BatchMigrationResult.failed`, and returned normally. The outer
        // transaction therefore COMMITS even when some components failed.
        // The `requireMigrationSucceeded` check in `cloneVcsData` raises
        // after the commit, which fails pod startup but does NOT roll back
        // the partial DB state — by design: keep what migrated so the next
        // pod restart only retries the gaps. If a future requirement is
        // true atomicity, this method needs to call
        // `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`
        // when `components.failed > 0`.
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
                // componentOwner / releaseManager / securityChampion are people
                // fields that the real Defaults.groovy never sets; they do not
                // belong in the global component-defaults surface (matches the
                // portal defaults-form removal). The `?.let` above was dead code.
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
        if (distinctSystems.isEmpty()) return
        // Batch upsert: one findAll() to learn what already exists, then a single
        // saveAll() for the missing rows. Replaces N round-trips with at most 2.
        val existing = systemRepository.findAll().mapTo(HashSet<String>(distinctSystems.size)) { it.code }
        val toSave = distinctSystems.filter { it !in existing }.map { SystemEntity(code = it) }
        if (toSave.isNotEmpty()) systemRepository.saveAll(toSave)
    }

    private fun upsertSystem(code: String) {
        val cache = knownSystemCodes.get()
        if (cache != null) {
            if (cache.add(code)) {
                // First time we see this code in this migration; system isn't in
                // the DB yet (cache was primed from a complete findAll).
                systemRepository.save(SystemEntity(code = code))
            }
            return
        }
        if (systemRepository.findByCode(code) == null) {
            systemRepository.save(SystemEntity(code = code))
        }
    }

    /**
     * Shared cache of `loadCommonDefaults(emptyMap())`. The call evaluates the
     * Groovy `Defaults.groovy` script and is heavy: measured ~25 s in QA on a
     * CPU-throttled pod. Without sharing it fires twice per migration — once
     * for the tools fallback ([commonDefaultsTools]) and once inside
     * [preupsertLabelsFromLoader] for label discovery. `by lazy` so resolution
     * is deferred to the first read inside a migration call (the bean is
     * constructed before the loader's git clone is ready).
     *
     * Staleness note: the lazy is JVM-lifetime (the service is a Spring
     * singleton). If `Defaults.groovy` or the validation-config labels file
     * is updated via a `git pull` between migrations on a long-running pod,
     * the second migration silently observes the stale snapshot — a newly
     * added label will not be preupserted. Pre-PR #236 the equivalent
     * `commonDefaultsTools` lazy had the same JVM-lifetime caching for
     * tools; this extends that pattern to labels. If the DSL is expected to
     * change at runtime, invalidate at the top of `migrateAllComponents`.
     */
    private val commonDefaultsCache: org.octopusden.octopus.escrow.configuration.model.DefaultConfigParameters by lazy {
        configurationLoader.loadCommonDefaults(emptyMap())
    }

    /**
     * Common-defaults tools (`Defaults.groovy` → `build { requiredTools = "..." }`),
     * derived on each read from the shared lazy [commonDefaultsCache]. Used as
     * a fallback when a component's own merged `buildConfiguration.tools` came
     * back empty — the Groovy loader drops Defaults-inherited tools whenever
     * the component declares its own `build { ... }` block (see [importModule]).
     *
     * Each access allocates a fresh `toList()` copy from the cached defaults;
     * the underlying `loadCommonDefaults` Groovy eval is paid once via the
     * lazy. The list re-copy is cheap relative to the eval and isolates
     * callers from accidental mutation.
     *
     * Limitation: this fallback cannot distinguish "loader merge dropped tools"
     * from "component explicitly cleared tools" — both surface as an empty list
     * on `EscrowModuleConfig.buildConfiguration.tools`. A future opt-out for
     * components that want NO tools would require loader changes (preserve the
     * null-vs-empty distinction). No current DSL component exercises that case.
     */
    private val commonDefaultsTools: List<org.octopusden.octopus.escrow.model.Tool>
        get() = commonDefaultsCache.buildParameters?.tools?.toList() ?: emptyList()

    private fun preupsertToolsFromLoader(
        fullConfig: org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration,
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
        data class ToolSpec(val name: String, val env: String?, val src: String?, val tgt: String?, val script: String?)
        val seen = LinkedHashMap<String, ToolSpec>()
        for ((_, module) in fullConfig.escrowModules) {
            for (cfg in module.moduleConfigurations) {
                val buildCfg = cfg.buildConfiguration ?: continue
                for (tool in buildCfg.tools ?: emptyList()) {
                    val toolName = tool.name ?: continue
                    seen.putIfAbsent(
                        toolName,
                        ToolSpec(
                            name = toolName,
                            env = tool.escrowEnvironmentVariable,
                            src = tool.sourceLocation,
                            tgt = tool.targetLocation,
                            script = tool.installScript,
                        ),
                    )
                }
            }
        }
        if (seen.isEmpty()) return
        // Batch: one findAll() to learn existing tool names, then saveAll() for missing.
        val existing = toolRepository.findAll().mapTo(HashSet<String>(seen.size)) { it.name }
        val toSave = seen.values.filter { it.name !in existing }.map {
            ToolEntity(
                name = it.name,
                escrowEnvVariable = it.env,
                sourceLocation = it.src,
                targetLocation = it.tgt,
                installScript = it.script,
            )
        }
        if (toSave.isNotEmpty()) toolRepository.saveAll(toSave)
    }

    private fun upsertTool(
        name: String,
        escrowEnvVariable: String?,
        sourceLocation: String?,
        targetLocation: String?,
        installScript: String?,
    ) {
        val cache = knownToolNames.get()
        if (cache != null) {
            if (cache.add(name)) {
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
            return
        }
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
            // Share the lazy [commonDefaultsCache] instead of calling
            // `loadCommonDefaults` a second time (Groovy script eval — ~25 s
            // in QA per call). The tools-fallback in `importModule` reads
            // from the same lazy field; whichever fires first pays the cost,
            // the other gets the cached result.
            //
            // Follow-up MIG-039 (see `docs/registry/requirements-migration.md`): expose
            // ValidationConfig via EscrowConfigurationLoader (or inject IConfigLoader to access
            // loadAndParseValidationConfigFile()) for label seeding. Current direct
            // commonDefaultsCache.labels access works but couples seeding to the lazy field.
            val labels = commonDefaultsCache.labels?.toSet() ?: return
            if (labels.isEmpty()) return
            // Batch upsert: one findAll() + one saveAll(). The prod DSL has hundreds of
            // labels — the per-label `findByCode + save` pattern was the single biggest
            // dictionary cost (measured ~3.3 s in prod-scale local runs).
            val existing = labelRepository.findAll().mapTo(HashSet<String>(labels.size)) { it.code }
            val toSave = labels.filter { it !in existing }.map { LabelEntity(code = it) }
            if (toSave.isNotEmpty()) labelRepository.saveAll(toSave)
        } catch (e: Exception) {
            LOG.warn("Could not seed labels from defaults: {}", e.message)
        }
    }

    private fun upsertLabel(code: String) {
        val cache = knownLabelCodes.get()
        if (cache != null) {
            if (cache.add(code)) {
                labelRepository.save(LabelEntity(code = code))
            }
            return
        }
        if (labelRepository.findByCode(code) == null) {
            labelRepository.save(LabelEntity(code = code))
        }
    }

    // =========================================================================
    // §6.2–6.7 Per-module import
    // =========================================================================

    /**
     * Import a single DSL module: create its `components` row plus the
     * `component_configurations` rows (BASE + per-version + markers). Sets only
     * scalar / per-component fields on the entity.
     *
     * Relationships are resolved in LATER passes, not here:
     *  - `parent_component_id` FK and `can_be_parent` seeding → Pass 2 (§6.2),
     *    from the flat `parentComponent` field;
     *  - `component_group_id` (aggregator membership) → Pass 3 (§6.3), keyed off
     *    the DSL `components { }` block (EscrowConfiguration.aggregatorSubComponents),
     *    NEVER the flat `parentComponent` field.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
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
        val tEntityStart = System.nanoTime()
        val componentEntity = buildComponentEntity(componentKey, baseConfig)
        val saved = componentRepository.save(componentEntity)
        val tEntityMs = (System.nanoTime() - tEntityStart) / 1_000_000

        // Wire M:N junctions (systems, labels)
        val tJunctionsStart = System.nanoTime()
        linkSystems(saved, baseConfig)
        linkLabels(saved, baseConfig)
        val tJunctionsMs = (System.nanoTime() - tJunctionsStart) / 1_000_000

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
        val tBaseStart = System.nanoTime()
        val baseRow = buildBaseConfigRow(saved, baseConfig, isSyntheticBase)
        attachVcsEntries(baseRow, baseConfig.vcsSettings)
        attachDistribution(baseRow, baseConfig.distribution)
        val savedBase = configurationRepository.save(baseRow)
        val tBaseMs = (System.nanoTime() - tBaseStart) / 1_000_000
        // The Groovy loader's per-range merge drops `requiredTools` inherited
        // from `Defaults.groovy` whenever the component declares its OWN
        // `build { ... }` block (the block REPLACES Defaults' build, so an
        // inherited `requiredTools` is lost from the per-range merged config —
        // observed reproducibly on multi-range components with a top-level
        // `build {}` block). The legacy Git resolver compensates with a
        // Defaults fallback at request time; restore parity here by reading
        // common-defaults tools when the per-config merge came back empty.
        val tToolsStart = System.nanoTime()
        val baseTools =
            baseConfig.buildConfiguration?.tools.takeUnless { it.isNullOrEmpty() }
                ?: commonDefaultsTools
        if (baseTools.isNotEmpty()) {
            attachRequiredTools(savedBase, baseTools)
        }
        val baseBuildTools = baseConfig.buildConfiguration?.buildTools?.toList() ?: emptyList()
        if (baseBuildTools.isNotEmpty()) {
            attachBuildToolBeans(savedBase, baseBuildTools)
        }
        val tToolsMs = (System.nanoTime() - tToolsStart) / 1_000_000

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
        val tOverridesStart = System.nanoTime()
        val nonBaseConfigs = configs.filter { it !== baseConfig }
        for (override in nonBaseConfigs) {
            val scalarRows = emitScalarOverrides(saved, baseConfig, override)
            val markerRows = emitMarkerOverrides(saved, savedBase, baseConfig, override)
            val overrideRange = override.versionRangeString
            if (scalarRows.isEmpty() && markerRows.isEmpty() && overrideRange != null) {
                emitRangePresenceRow(saved, overrideRange)
            }
        }
        val tOverridesMs = (System.nanoTime() - tOverridesStart) / 1_000_000

        if (LOG.isDebugEnabled) {
            LOG.debug(
                "importModule[{}] ms: entity={} junctions={} base={} tools={} overrides={} (configs={}, nonBase={})",
                componentKey, tEntityMs, tJunctionsMs, tBaseMs, tToolsMs, tOverridesMs, configs.size, nonBaseConfigs.size,
            )
        }
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
        // CSV → ordered list happens HERE (the single split site). The accessor
        // does trim → drop blank → keep-first dedupe, so import only needs the
        // raw split. DSL validates the field as `\w+(,\w+)*` (EscrowConfigValidator).
        entity.replaceReleaseManagerUsernames(cfg.releaseManager?.split(",") ?: emptyList())
        entity.replaceSecurityChampionUsernames(cfg.securityChampion?.split(",") ?: emptyList())
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
        // Single-value collapse: the DSL field is historically a CSV that
        // could carry multiple codes per component. The new schema models
        // exactly one system per component, so we take the FIRST non-blank
        // entry and drop the rest. Components whose DSL declares multiple
        // systems are flagged at WARN level so an operator can decide
        // whether to split the component or keep the first.
        val codes = systemStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val firstCode = codes.firstOrNull() ?: return
        if (codes.size > 1) {
            LOG.warn(
                "linkSystems: component '{}' DSL declares multiple systems ({}); " +
                    "keeping first ('{}') and dropping the rest under the single-value contract.",
                component.componentKey,
                codes,
                firstCode,
            )
        }
        upsertSystem(firstCode) // ensure dictionary exists
        component.systemCode = firstCode
        // Defence-in-depth: explicit save on the managed entity. The
        // outer batch loop (`flushAndClearComponentsBatch`) flushes +
        // clears the persistence context every N components; an
        // explicit save() here pins the dirty `systemCode` field
        // through dirty-checking on the *next* flush rather than
        // relying on the managed-entity invariant alone. Cheap (the
        // entity is already managed, so save() is a no-op merge) and
        // makes the field unambiguous against future refactors of the
        // import loop's flush cadence.
        componentRepository.save(component)
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
            // V1-contract: when a component config's escrow.generation is empty,
            // fall back to the common Defaults.groovy generation (= AUTO per the
            // shipped DSL). Without this fallback, components whose DSL omits an
            // explicit `escrow { generation = … }` block (e.g. wscardsmodel) land
            // with a NULL `escrow_generation` column, the read-path returns
            // Optional.empty, and the downstream Component view diverges from V1
            // (which always returns AUTO via Defaults inheritance). Reproduced
            // today (2026-05-24) on the local candidate v3 schema-v2 DB-mode
            // stand vs prod + QA + local baseline V1.
            val genFromCfg = escrow.generation.orElse(null)
            val genFromDefaults = commonDefaultsCache.escrow?.generation?.orElse(null)
            (genFromCfg ?: genFromDefaults)?.let { row.escrowGeneration = it.name }
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
                // hotfixVersionFormat is also stored per-range so per-range DSL
                // overrides survive into component_configurations. The
                // per-component base value (Defaults / top-level DSL) is
                // additionally captured on components.jira_hotfix_version_format
                // in buildComponentEntity; the resolver layers per-range over base.
                row.jiraHotfixVersionFormat = cvf.hotfixVersionFormat
            }
            jira.componentInfo?.let { info ->
                // Do NOT collapse empty string to null for versionPrefix/versionFormat:
                // bug-G-component DSL sets versionPrefix="" (override range clears a non-empty
                // base value) and baseline preserves "". Collapsing "" → null would prevent the
                // null-clear diff from being emitted and the value would bleed from the base
                // range (bug G).
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

        // DISTRIBUTION_MAVEN and GROUP_ARTIFACT_PATTERN are orthogonal markers consumed by
        // DIFFERENT read-paths (per the comment at DatabaseComponentRegistryResolver.kt:329-343):
        // - DISTRIBUTION_MAVEN feeds buildEscrowModuleConfig and V4 distribution endpoints
        //   (`getResolvedComponentDefinition`, `/distribution`); its `mavenArtifacts` rows are
        //   parsed from `distribution.GAV()` tokens.
        // - GROUP_ARTIFACT_PATTERN is the SOLE source of per-range overrides for
        //   `/maven-artifacts` (see resolver fix 95bc74e8); its rows carry the per-range
        //   DSL `groupId`/`artifactId` verbatim.
        //
        // When a DSL range sets BOTH `artifactId` AND `distribution { gav = … }`
        // simultaneously, both diff conditions hold and BOTH markers must be
        // emitted. The previous `if … else if …` shape emitted only
        // DISTRIBUTION_MAVEN; the read-path then fell back to the inherited
        // component-level CSV instead of the per-range V1 artifactId.
        if (mavenArtifactsDiffer(baseDist, overDist)) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.DISTRIBUTION_MAVEN) { row ->
                attachMavenArtifacts(row, overDist)
            }?.let { saved += it }
        }
        if (groupArtifactPatternsDiffer(base, override)) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.GROUP_ARTIFACT_PATTERN) { row ->
                attachMavenArtifactsFromGroupArtifact(row, override.groupIdPattern, override.artifactIdPattern)
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
        //
        // VAL-011: only emit a BUILD_REQUIRED_TOOLS marker when the override EXPLICITLY
        // declares its own requiredTools (non-null, non-empty). An override range that
        // doesn't set requiredTools in the DSL inherits from the base (including
        // Defaults-fallback tools on the base row). Emitting an empty marker here would
        // shadow the base row's tools and return `buildParameters.tools=[]` for versions
        // in that override range — producing a Git vs DB divergence.
        val effectiveBaseToolNames =
            (base.buildConfiguration?.tools.takeUnless { it.isNullOrEmpty() } ?: commonDefaultsTools)
                .mapNotNull { it.name }
                .toSet()
        val overExplicitTools = override.buildConfiguration?.tools.takeUnless { it.isNullOrEmpty() }
        val overTools = overExplicitTools?.map { it.name }?.toSet() ?: emptySet()
        if (overExplicitTools != null && effectiveBaseToolNames != overTools) {
            saveMarkerRowWithChildren(component, versionRange, MarkerAttributes.BUILD_REQUIRED_TOOLS) { row ->
                // Required tool junctions use the config ID explicitly, so we must
                // persist the marker row first (to get the ID), then attach tools.
                // saveMarkerRowWithChildren detects that row.id is non-null after this
                // save and skips the redundant second save in its own body.
                //
                // Safe because Spring Data's SimpleJpaRepository.save(...) returns the
                // same in-memory reference for new entities (calls em.persist on the
                // arg), so `row.id` is observable on the same `row` after the call.
                // With UUID-strategy the id is assigned during persist; with IDENTITY/
                // SEQUENCE strategies the id is also written back to the same instance.
                // The latent regression is anything that breaks reference identity (an
                // entity-listener returning a fresh DTO-mapped instance, a custom
                // BeforeExecutionGenerator returning a new entity, a @Version-merge
                // path on the audit-aware repository). `attachRequiredTools` then does
                // `row.id ?: return` and the override's required tools are silently
                // dropped. Tracked as Group 6-J: capture `val saved = save(row)` and
                // pass `saved` if/when the repository contract ever changes.
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

    /**
     * MIG-047: populate a GROUP_ARTIFACT_PATTERN MARKER row with synthetic
     * [DistributionMavenArtifactEntity] rows built from DSL-level `groupId`/`artifactId`
     * fields when neither range carries an explicit `distribution { gav = … }` block.
     *
     * [artifactIdCsv] is treated as a comma-separated list (each token becomes
     * one [DistributionMavenArtifactEntity] row), matching the V1 behaviour in
     * `JiraParametersResolver.getMavenArtifactParameters` which uses
     * `artifactIdPattern` directly.
     */
    private fun attachMavenArtifactsFromGroupArtifact(
        row: ComponentConfigurationEntity,
        groupId: String?,
        artifactIdCsv: String?,
    ) {
        val group = groupId ?: return
        var sortOrder = 0
        for (artifactToken in splitCsv(artifactIdCsv ?: return)) {
            row.mavenArtifacts.add(
                DistributionMavenArtifactEntity(
                    componentConfiguration = row,
                    groupPattern = group,
                    artifactPattern = artifactToken,
                    extension = null,
                    classifier = null,
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
            // Ensure tool exists in dictionary. After `upsertTool` returns
            // the tool is either already in the DB (cache hit), in the
            // in-migration dictionary cache (cache add returned true →
            // save was issued), or was inserted by the cache-null fallback.
            // For the cache-null path a niche edge case remains: if a row
            // with a slightly different name normalisation (case/whitespace)
            // already exists, `upsertTool.findByName(toolName)` misses it
            // and `save()` then trips a unique-constraint exception. The
            // explicit follow-up `findByName` only on the cache-null path
            // restores the graceful "log + skip junction" behaviour that
            // existed before the dictionary cache was introduced. On the
            // cache path the check is unnecessary (the cache is
            // authoritative for the in-flight migration).
            upsertTool(toolName, tool.escrowEnvironmentVariable, tool.sourceLocation, tool.targetLocation, tool.installScript)
            if (knownToolNames.get() == null && toolRepository.findByName(toolName) == null) {
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
        diffScalar("jira.hotfixVersionFormat", base.jiraHotfixVersionFormat, override.jiraHotfixVersionFormat)

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
            "jira.hotfixVersionFormat" -> row.jiraHotfixVersionFormat = value?.toString()
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

    /**
     * MIG-047: returns true when the override range's DSL-level `groupId`/`artifactId`
     * differ from the base range — independently of `distribution.GAV()`.
     *
     * This supplements [mavenArtifactsDiffer] for the common DSL pattern where
     * component ranges declare `groupId`/`artifactId` directly (not via a
     * `distribution { gav = … }` block).  In that pattern both
     * `distribution.GAV()` values are null so [mavenArtifactsDiffer] returns
     * false, yet the effective maven co-ordinates differ per range.
     */
    private fun groupArtifactPatternsDiffer(
        base: EscrowModuleConfig,
        override: EscrowModuleConfig,
    ): Boolean =
        base.groupIdPattern != override.groupIdPattern ||
            splitCsv(base.artifactIdPattern ?: "") != splitCsv(override.artifactIdPattern ?: "")

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
     * Input is [aggregatorSubComponents] (aggregatorKey → its sub-component keys), derived by
     * the loader from DSL `components { }` blocks — NOT the flat `parentComponent` field. For
     * each aggregator key:
     *  1. Classify REAL vs FAKE via [isFakeAggregator] against the aggregator's first DSL config.
     *  2. Upsert a [ComponentGroupEntity] keyed by the aggregator key (idempotent — re-runs skip
     *     existing rows).
     *  3. Set `componentGroup` on every sub-component — and update it when the existing link
     *     points at a *different* group (DSL membership changed since the last migration).
     *  4. Self-link the aggregator to its own group too — for BOTH real and fake aggregators.
     *     A fake aggregator now keeps its ComponentEntity row (so v1–v3 still serves it for
     *     compat parity); self-linking it to its own fake group is exactly the marker the v4
     *     list uses to EXCLUDE it (group.isFake && group.groupKey == the row's componentKey).
     *
     * @return per-aggregator failures: list of `(aggregatorKey, errorMessage)`. Empty list on
     *         full success. Callers should fold these into their own result aggregation so a
     *         partial Pass 3 failure does not silently look like a fully-successful migration.
     */
    private fun linkAggregatorGroups(
        allModules: Map<String, EscrowModule>,
        aggregatorSubComponents: Map<String, Set<String>>,
    ): List<Pair<String, String>> {
        // R1: group membership is driven by the DSL `components { }` block
        // (aggregatorKey → its sub-component keys), NOT the flat `parentComponent`
        // field. `aggregatorSubComponents` is already parent→children, so no
        // reversal is needed.
        //
        // Aggregate per-parent failures and log a single summary at the end
        // instead of letting individual WARN lines hide a systemic issue. A
        // migration step is a one-shot batch — silent partial-success is the
        // wrong default.
        val failures = mutableListOf<Pair<String, String>>()
        for ((parentKey, childKeys) in aggregatorSubComponents) {
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

                // Link the aggregator itself to its own group — for BOTH real and fake
                // aggregators. A fake aggregator now has a ComponentEntity row (so the v1–v3
                // resolver serves it = compat parity); self-linking it to its own fake group is
                // exactly the marker the v4 list uses to EXCLUDE it (group.isFake &&
                // group.groupKey == the row's componentKey). For a real aggregator this is its
                // normal group membership.
                val aggregatorRow = componentRepository.findByComponentKey(parentKey)
                if (aggregatorRow != null && aggregatorRow.componentGroup?.id != group.id) {
                    aggregatorRow.componentGroup = group
                    componentRepository.save(aggregatorRow)
                    LOG.debug("§6.3 Pass 3: linked aggregator '{}' (isFake={}) → its own group", parentKey, fake)
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

    /**
     * Upsert a [ComponentGroupEntity] by [groupKey]. Idempotent. On a re-run the
     * `isFake` flag is refreshed when the DSL classification flips (REAL↔FAKE), so the
     * stored flag never goes stale. (NB: a REAL→FAKE flip also leaves the aggregator's
     * old ComponentEntity row behind; that row-level transition is out of scope here.)
     */
    private fun upsertComponentGroup(
        groupKey: String,
        isFake: Boolean,
    ): ComponentGroupEntity {
        val existing = componentGroupRepository.findByGroupKey(groupKey)
        if (existing != null) {
            if (existing.isFake != isFake) {
                existing.isFake = isFake
                return componentGroupRepository.save(existing)
            }
            return existing
        }
        return componentGroupRepository.save(ComponentGroupEntity(groupKey = groupKey, isFake = isFake))
    }

    /**
     * Re-run cleanup (R1): the previous logic created a ComponentGroup for every flat
     * `parentComponent` target, including non-aggregators (a plain parentComponent target that
     * owns no `components { }` block). Group membership
     * is now driven solely by `components { }` aggregators, so any EXISTING group whose key is
     * NOT a current true aggregator ([validAggregatorKeys]) is stale: unlink its members
     * (`componentGroup = null`) and delete the orphaned `component_groups` row. Real-aggregator
     * groups are preserved. Operates on existing DB rows, so it runs correctly even when Pass 1
     * skipped already-present components.
     */
    private fun cleanupStaleGroups(validAggregatorKeys: Set<String>) {
        val stale = componentGroupRepository.findAll().filter { it.groupKey !in validAggregatorKeys }
        if (stale.isEmpty()) return
        for (group in stale) {
            val members = group.id?.let { componentRepository.findByComponentGroupId(it) } ?: emptyList()
            for (member in members) {
                member.componentGroup = null
                componentRepository.save(member)
            }
            componentGroupRepository.delete(group)
            LOG.info(
                "§6.3 cleanup: removed stale (non-aggregator) group '{}' — unlinked {} member(s)",
                group.groupKey, members.size,
            )
        }
        LOG.info("§6.3 cleanup: removed {} stale group(s)", stale.size)
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
