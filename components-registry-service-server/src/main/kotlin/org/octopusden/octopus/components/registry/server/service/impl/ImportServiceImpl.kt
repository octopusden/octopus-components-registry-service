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
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingTokenEntity
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
import org.octopusden.octopus.components.registry.server.entity.SystemEntity
import org.octopusden.octopus.components.registry.server.entity.ToolEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.mapper.numericVersionComparator
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
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
import org.octopusden.octopus.components.registry.server.util.ArtifactOwnershipModeClassifier
import org.octopusden.octopus.components.registry.server.util.JiraRowView
import org.octopusden.octopus.components.registry.server.util.MavenGavCollision
import org.octopusden.octopus.components.registry.server.util.VersionRangePartition
import org.octopusden.octopus.components.registry.server.util.computeEffectiveJiraPairs
import org.octopusden.octopus.components.registry.server.util.parseMavenGavEntry
import org.octopusden.octopus.components.registry.server.util.splitCsv
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory
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
 *  §6.4  Base row (always ALL_VERSIONS effective default) + coverage layer
 *        (RANGE_PRESENCE rows per declared bounded block) — ADR-018.
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
    private val configSyncService: ConfigSyncService,
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
    private val mavenArtifactRepository: DistributionMavenArtifactRepository,
    private val componentArtifactMappingRepository: ComponentArtifactMappingRepository,
    private val dockerImageRepository: DistributionDockerImageRepository,
    private val versionRangeFactory: VersionRangeFactory,
    private val numericVersionFactory: NumericVersionFactory,
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
                // Order is load-bearing: skip-check → uniqueness pre-pass → dictionary
                // upserts → importModule. A uniqueness failure must leave ZERO rows
                // behind, dictionary rows included — so the §6.1-style preupserts may
                // only run after the pre-pass clears.
                val existing = componentRepository.findByComponentKey(name)
                if (existing != null) {
                    return MigrationResult(
                        componentName = name,
                        success = true,
                        dryRun = false,
                        message = "Skipped (already in DB)",
                    )
                }

                // §6.0 single-component uniqueness pre-pass vs the persisted state
                // (same invariants the v4 API enforces on save).
                val uniquenessViolations = findUniquenessViolations(name, module.moduleConfigurations)
                if (uniquenessViolations.isNotEmpty()) {
                    val msg = uniquenessViolations.joinToString("; ")
                    LOG.error("Migration of '{}' rejected by uniqueness pre-pass: {}", name, msg)
                    return MigrationResult(
                        componentName = name,
                        success = false,
                        dryRun = false,
                        message = msg.take(500),
                    )
                }

                // Pre-pass dictionary discovery for this single component
                preupsertSystemsForModule(module.moduleConfigurations)
                preupsertToolsFromLoader(fullConfig)
                preupsertLabelsFromLoader()

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

        // §6.0 uniqueness pre-pass over EVERY invariant the v4 API enforces (displayName,
        // distribution GAV, jira projectKey+versionPrefix, docker image name). Violations in
        // the old CR must fail the migration HERE — after the DSL load but BEFORE the §6.1
        // dictionary pre-upserts below (the first writes in this method) — so a failed run
        // leaves zero rows behind, the DB never holds data the API would reject on the next
        // save, and the error names every offender across all invariants at once. (Throwing
        // from the per-module loop would not abort: it's caught & recorded per component, and
        // migrate() permits partial commits. migrate() upserts the idempotent
        // component-defaults config blob before this method; that is unaffected.)
        detectUniquenessViolations(allModules)

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
            (System.nanoTime() - dictStart) / 1_000_000,
            sysMs,
            toolMs,
            labelMs,
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
                allModules
                    .mapNotNull { (componentKey, escrowModule) ->
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
                fullConfig.aggregatorSubComponents.keys
                    .filter { parentKey ->
                        val firstConfig = allModules[parentKey]?.moduleConfigurations?.firstOrNull()
                        firstConfig != null && isFakeAggregator(firstConfig)
                    }.toSet()
            LOG.info(
                "§6 DSL derivation: {} ms ({} parent refs, {} aggregators, {} FAKE)",
                (System.nanoTime() - deriveStart) / 1_000_000,
                pendingParentByKey.size,
                fullConfig.aggregatorSubComponents.size,
                fakeAggregatorKeys.size,
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
                pass1Ms,
                migrated,
                skipped,
                failed,
                total,
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
                (System.nanoTime() - pass3Start) / 1_000_000,
                pass3Failures.size,
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
        // `git` is "how many DSL (git-sourced) components are NOT yet in the DB".
        // It MUST be a set difference (git keys minus db-sourced keys), not
        // `gitResolver.size - countBySource("db")`: the subtraction goes negative the
        // moment the DB holds a component the DSL does not (e.g. one created via the
        // v4 write API after migration), which showed `Git -1` in the admin panel and
        // broke every `git == 0` "fully migrated" check (updateCache 410-retirement,
        // the Portal Run gate). The set difference is >= 0 by construction and still
        // counts a genuinely-unmigrated git component even when an unrelated db-only
        // row keeps the totals equal.
        val dbKeys = componentSourceRepository.findComponentKeysBySource("db").toSet()
        val gitKeys =
            try {
                gitResolver.getComponents().mapTo(mutableSetOf()) { it.moduleName }
            } catch (_: Exception) {
                emptySet()
            }
        return MigrationStatus(
            git = gitKeys.count { it !in dbKeys }.toLong(),
            db = dbKeys.size.toLong(),
            total = gitKeys.size.toLong(),
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

    /**
     * Component defaults are code-as-config (service-config → AdminConfigProperties),
     * no longer read from the Git DSL. Delegates to [ConfigSyncService] so the
     * `/admin/migrate-defaults` endpoint and the migration-job DEFAULTS phase produce
     * the same `registry_config['component-defaults']` blob without the Groovy loader.
     * (Full `component-resolver-core` removal is a separate follow-up — see plan.)
     */
    @Transactional
    override fun migrateDefaults(): Map<String, Any?> {
        LOG.info("Syncing component defaults from service-config")
        val map = configSyncService.syncComponentDefaults()
        LOG.info("Synced component defaults: {} keys", map.size)
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

    private fun preupsertToolsFromLoader(fullConfig: org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration) {
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
        data class ToolSpec(
            val name: String,
            val env: String?,
            val src: String?,
            val tgt: String?,
            val script: String?,
        )
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

        // CRS #387: reject per-range distribution.explicit / .external /
        // .securityGroups.read that vary across ranges — they are per-component
        // only and the divergent value was silently dropped before. Runs before
        // this component's own rows are written (the per-component try/catch in
        // migrateAllComponents fails just this component, not the batch).
        validatePerComponentDistributionInvariants(componentKey, configs)

        // TD-011 / CRS #349: reject malformed distribution.GAV Maven coordinates
        // (groupId-only / blank segment) that the import previously silently
        // dropped. Fail-loud with component + raw entry + reason; symmetric with
        // the v4 write-path check. Same per-component try/catch scope as above.
        validateDistributionCoordinates(componentKey, configs)

        // Decoupled version model (ADR-018): the base row is ALWAYS the effective
        // default at ALL_VERSIONS, and coverage is a separate layer expressed as
        // RANGE_PRESENCE rows. `supported = ALL` exactly when the DSL declares no
        // bounded blocks — i.e. the loader emitted an all-versions config (a
        // top-level-only component, or an explicit "(,)" / ALL_VERSIONS block).
        // Otherwise (only bounded blocks) supported = ∪ of the declared ranges and
        // the base scalars come from the NEWEST (open-upper) block's merged config —
        // see [selectBaseConfig]: the base default must equal the value effective for
        // the open-upper `[X,)` range (what current versions resolve to). Per-block
        // differences (including OLDER blocks) are emitted as overrides. No synthetic-bounded base.
        val baseConfig = selectBaseConfig(configs)
        val supportedIsAll = configs.any { isAllVersionsRange(it.versionRangeString) }

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
        val baseRow = buildBaseConfigRow(saved, baseConfig)
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

        // §6.4b Coverage layer (ADR-018 redesign): store supported as the MERGED union of the declared
        // bounded blocks — maximal contiguous segments, gaps preserved — independent of overrides.
        // Enumeration partitions `supported` by value-change edges at READ time, so there are NO
        // per-block presence rows: adjacent identical legacy blocks collapse into one segment, and
        // blocks that differ keep their boundary via their own override/ownership/doc rows.
        // `supported = ALL` emits none. emitRangePresenceRow is idempotent on (component, range).
        if (!supportedIsAll) {
            val declaredRanges = configs.mapNotNull { it.versionRangeString }.filterNot { isAllVersionsRange(it) }
            // If the declared blocks together cover everything (e.g. `(,1.0.107)` ∪ `[1.0.107,)`),
            // mergeUnion collapses to the all-versions sentinel — that means supported = ALL, so emit
            // NO presence rows (the ALL_VERSIONS base stands for everything; supported = ALL ⟺ no rows).
            for (segment in VersionRangePartition
                .mergeUnion(declaredRanges, numericVersionComparator(numericVersionFactory))
                .filterNot { isAllVersionsRange(it) }) {
                emitRangePresenceRow(saved, segment)
            }
        }

        // §6.5 Override rows: diff every non-base config against the base. The base scalar source
        // (selectBaseConfig: the open-upper/newest block, or the all-versions config) carries no override
        // of its own — it equals the base by construction; the OLDER blocks become the overrides. No
        // RES-001 fallback presence row: coverage is the merged union above, and an empty block (no
        // value change vs base) simply contributes no edge.
        //
        // Scalar overrides are emitted MERGED per attribute (MIG-048): with base = the newest block,
        // an attribute set only there makes EVERY older block diff identically (they all inherit the
        // same top-level/Defaults value), which would emit one identical row per block. Adjacent
        // same-value ranges collapse into their union (spec: "adjacent same-value MAY be merged").
        // Markers stay per declared block — their per-block boundaries also anchor enumeration edges.
        val tOverridesStart = System.nanoTime()
        val nonBaseConfigs = configs.filter { it !== baseConfig }
        emitMergedScalarOverrides(saved, baseConfig, nonBaseConfigs)
        for (override in nonBaseConfigs) {
            emitMarkerOverrides(saved, savedBase, baseConfig, override)
        }
        val tOverridesMs = (System.nanoTime() - tOverridesStart) / 1_000_000

        if (LOG.isDebugEnabled) {
            LOG.debug(
                "importModule[{}] ms: entity={} junctions={} base={} tools={} overrides={} (configs={}, nonBase={})",
                componentKey,
                tEntityMs,
                tJunctionsMs,
                tBaseMs,
                tToolsMs,
                tOverridesMs,
                configs.size,
                nonBaseConfigs.size,
            )
        }
    }

    /**
     * §6.0 aggregated uniqueness pre-pass: every invariant the v4 API enforces at save time
     * (displayName, distribution GAV with full (group, artifact, extension, classifier)
     * identity, jira projectKey+versionPrefix, docker image name) is checked up front against
     * BOTH the incoming DSL and the already-persisted DB state, and the migration fails ONCE
     * with the complete report — operators fix everything in one round instead of peeling
     * violations one migration attempt at a time. Throwing from the per-module loop would not
     * abort (it's caught & recorded per component, and migrate() permits partial commits), so
     * this must run as an up-front pre-pass before the first write.
     *
     * Idempotent reruns: incoming rows are built ONLY from modules NOT yet in the DB (Pass 1
     * skips the rest), and same-componentKey pairs never collide — so a rerun over an
     * already-imported DSL reports nothing. DB-vs-DB pairs are deliberately NOT flagged:
     * pre-existing DB conflicts are fixed via the API and must not brick the migration.
     * That applies to displayName too: DSL-vs-DSL pairs are built from TO-IMPORT modules
     * only (an already-imported component's authoritative name lives in the DB — it may
     * have been renamed via the API — so its DSL name must not be paired against new
     * modules); new-vs-DB is covered by [computeDisplayNameDbCollisions].
     *
     * Best-effort, not a durable invariant: the DB-side rows are read once at the top of
     * the migration transaction, and (except display_name and docker image, which are
     * DB-UNIQUE) there are no DB constraints behind the GAV/jira rules — a concurrent v4
     * API write during a running migration can still slip a duplicate past both this
     * pre-pass and the API's own read-before-flush check. Same window as the API check;
     * migration is normally startup-exclusive.
     *
     * Pure logic lives in top-level `compute*` functions (and the companion's
     * [computeDisplayNameCollisions]) so it is unit-testable without Spring fixtures.
     * Empty-body modules (`"FOO" { }`, no moduleConfigurations) are excluded — Pass 1 skips
     * them, so they must not contribute phantom rows; the base-config selection mirrors
     * importModule's `selectBaseConfig` exactly (both call it via `baseConfigOf`).
     */
    private fun detectUniquenessViolations(allModules: Map<String, EscrowModule>) {
        val nonEmpty = allModules.filterValues { it.moduleConfigurations.isNotEmpty() }
        if (nonEmpty.isEmpty()) return
        val existingKeys =
            componentRepository.findByComponentKeyIn(nonEmpty.keys).mapTo(HashSet()) { it.componentKey }
        val toImport = nonEmpty.filterKeys { it !in existingKeys }

        val violations = mutableListOf<String>()

        // DSL-vs-DSL display names compare TO-IMPORT modules only: an already-imported
        // component's authoritative name lives in the DB (it may have been renamed via the
        // API since import), so pairing its DSL name against a new module would false-positive
        // on partial-migration reruns. New-vs-DB conflicts are covered by
        // computeDisplayNameDbCollisions below.
        val displayPairs =
            toImport.map { (key, m) -> key to baseConfigOf(m.moduleConfigurations)?.componentDisplayName }
        violations +=
            computeDisplayNameCollisions(displayPairs)
                .entries
                .sortedBy { it.key }
                .map { (name, keys) ->
                    "uniqueness violation: display name \"$name\" is claimed by multiple components " +
                        "${keys.joinToString(", ")} (display_name is unique)"
                }

        if (toImport.isNotEmpty()) {
            val dbNames = componentRepository.findAllDisplayNamePairs().map { it.componentKey to it.displayName }
            violations += computeDisplayNameDbCollisions(displayPairs, dbNames)

            val newGavRows = toImport.flatMap { (key, m) -> collectDslGavRows(key, m.moduleConfigurations) }
            if (newGavRows.isNotEmpty()) {
                val dbRows =
                    mavenArtifactRepository.findAllRows().map {
                        UniquenessGavRow(
                            it.componentKey,
                            it.versionRange,
                            it.groupPattern,
                            it.artifactPattern,
                            it.extension,
                            it.classifier,
                            origin = MavenGavCollision.originLabel(it.overriddenAttribute),
                        )
                    }
                violations += computeDistributionGavCollisions(newGavRows, dbRows, ::versionRangesIntersect)
            }

            val newJiraPairs = toImport.flatMap { (key, m) -> collectDslJiraPairs(key, m.moduleConfigurations) }
            if (newJiraPairs.isNotEmpty()) {
                violations += computeJiraPairCollisions(newJiraPairs, persistedEffectiveJiraPairs())
            }

            val newDockerRows = toImport.flatMap { (key, m) -> collectDslDockerRows(key, m.moduleConfigurations) }
            if (newDockerRows.isNotEmpty()) {
                val dbImages =
                    dockerImageRepository.findAllImageRows().map {
                        UniquenessDockerRow(it.componentKey, it.imageName)
                    }
                violations += computeDockerImageCollisions(newDockerRows, dbImages)
            }
        }

        if (violations.isEmpty()) return
        val report = violations.joinToString("; ")
        LOG.error("§6.0 uniqueness pre-pass FAILED — {} violation(s): {}", violations.size, report)
        throw IllegalStateException(
            "Cannot migrate: ${violations.size} uniqueness violation(s) found. " +
                "Fix the conflicting values in the DSL before migrating: $report",
        )
    }

    /**
     * Single-component flavour of [detectUniquenessViolations]: this component's DSL-derived
     * rows against the DB rows of OTHER components (same-componentKey pairs are skipped inside
     * the `compute*` functions, so an already-imported rival named like this component cannot
     * self-collide). Returns the violation list instead of throwing — `migrateComponent` maps
     * it to a failed [MigrationResult] BEFORE any write (dictionary upserts included).
     */
    private fun findUniquenessViolations(
        componentKey: String,
        configs: List<EscrowModuleConfig>,
    ): List<String> {
        val violations = mutableListOf<String>()
        baseConfigOf(configs)?.componentDisplayName?.takeIf { it.isNotBlank() }?.let { name ->
            val dbNames = componentRepository.findAllDisplayNamePairs().map { it.componentKey to it.displayName }
            violations += computeDisplayNameDbCollisions(listOf(componentKey to name), dbNames)
        }
        val gavCandidates = collectDslGavRows(componentKey, configs)
        if (gavCandidates.isNotEmpty()) {
            val dbRows =
                mavenArtifactRepository.findAllRows().map {
                    UniquenessGavRow(
                        it.componentKey,
                        it.versionRange,
                        it.groupPattern,
                        it.artifactPattern,
                        it.extension,
                        it.classifier,
                        origin = MavenGavCollision.originLabel(it.overriddenAttribute),
                    )
                }
            violations += computeDistributionGavCollisions(gavCandidates, dbRows, ::versionRangesIntersect)
        }
        val jiraCandidates = collectDslJiraPairs(componentKey, configs)
        if (jiraCandidates.isNotEmpty()) {
            violations += computeJiraPairCollisions(jiraCandidates, persistedEffectiveJiraPairs())
        }
        val dockerCandidates = collectDslDockerRows(componentKey, configs)
        if (dockerCandidates.isNotEmpty()) {
            val dbImages =
                dockerImageRepository.findAllImageRows().map { UniquenessDockerRow(it.componentKey, it.imageName) }
            violations += computeDockerImageCollisions(dockerCandidates, dbImages)
        }
        return violations
    }

    /**
     * Base-row config selection for the decoupled model. The base row carries the effective value at
     * ALL_VERSIONS — it stands for every version no override covers, INCLUDING the newest band — so its
     * scalars come from the OPEN-UPPER (newest) block. NOTE: this SUPERSEDES ADR-018's original base-row
     * rule (`base = top-level ⊕ Defaults`, open-upper = override); see the ADR-018 §2 amendment (2026-07)
     * — the base row is the portal's editable default and must reflect what current versions use.
     * Precedence:
     *   1. an explicit all-versions block (top-level-only component, or a `(,)` / ALL_VERSIONS block):
     *      it already covers everything, so it IS the base. (The loader does not emit a separate
     *      all-versions config alongside bounded blocks — a top-level default is merged INTO each
     *      range config — so this branch and step 2 are mutually exclusive in practice.)
     *   2. otherwise the newest block by [VersionRangePartition.baseCandidateComparator]: the open-upper
     *      `[X,)` block, or — when NO block is open-upper (all bounded/historical) — the highest declared
     *      range. That last case is a behaviour change from the previous `configs.first()` (the
     *      OLDEST/lowest block); "newest" is a judgement call there, but such components are
     *      deprecated/historical and the previous oldest-first choice was itself arbitrary.
     * Fixes the bug where the base got the OLDEST block's stale value and the newest block's value was
     * pushed into a redundant open-upper override (e.g. a release format only the oldest range used, or
     * a version prefix that newest versions no longer set).
     */
    private fun selectBaseConfig(configs: List<EscrowModuleConfig>): EscrowModuleConfig {
        configs.firstOrNull { isAllVersionsRange(it.versionRangeString) }?.let { return it }
        val rank = VersionRangePartition.baseCandidateComparator(numericVersionComparator(numericVersionFactory))
        return configs
            .filter { it.versionRangeString != null }
            .maxWithOrNull { x, y -> rank.compare(x.versionRangeString!!, y.versionRangeString!!) }
            ?: configs.first()
    }

    /** Base-config selection shared with the uniqueness pre-pass; null only for an empty config list. */
    private fun baseConfigOf(configs: List<EscrowModuleConfig>): EscrowModuleConfig? =
        if (configs.isEmpty()) null else selectBaseConfig(configs)

    /**
     * True when [range] denotes the all-versions coverage set: null, the canonical
     * ALL_VERSIONS sentinel "(,0),[0,)", or the fully-unbounded "(,)" (the loader stores
     * both bracket forms verbatim, without normalization). Such a config means the DSL
     * declared no bounded block; per ADR-018 it maps to a single ALL_VERSIONS base with no
     * RANGE_PRESENCE rows, so the two legacy "all versions" shapes converge to one
     * stored representation (schema-spec / spec §7).
     */
    private fun isAllVersionsRange(range: String?): Boolean = range == null || range == ALL_VERSIONS || range.replace(" ", "") == "(,)"

    /**
     * EFFECTIVE jira claims of every non-archived persisted component — per-range
     * scalar overrides layered over the BASE row ([computeEffectiveJiraPairs]),
     * matching both the legacy merged-config bucketing and the API-side check.
     */
    private fun persistedEffectiveJiraPairs(): List<UniquenessJiraPair> {
        val rows =
            configurationRepository.findAllNonArchivedJiraRows().map {
                JiraRowView(
                    it.componentKey,
                    it.versionRange,
                    it.rowType,
                    it.overriddenAttribute,
                    it.projectKey,
                    it.versionPrefix,
                )
            }
        return computeEffectiveJiraPairs(rows).flatMap { (key, pairs) ->
            pairs.map { (pk, prefix) -> UniquenessJiraPair(key, pk, prefix) }
        }
    }

    /** Conservative range intersection: unparseable ranges count as overlapping (same as the API check). */
    private fun versionRangesIntersect(
        range1: String,
        range2: String,
    ): Boolean =
        runCatching {
            versionRangeFactory.create(range1).isIntersect(versionRangeFactory.create(range2))
        }.getOrDefault(true)

    /**
     * DSL-derived rows that [importModule] would persist into
     * `distribution_maven_artifacts` for this module — the SAME row set the API-side
     * `validateMavenArtifactCollisions` later checks:
     *  - the base config's explicit `distribution.GAV()` coordinates (URL entries excluded);
     *  - per override range: its GAV coordinates when [mavenArtifactsDiffer], and the
     *    MIG-047 synthetic (groupIdPattern × artifactId token, extension=null) rows when
     *    [groupArtifactPatternsDiffer].
     */
    private fun collectDslGavRows(
        componentKey: String,
        configs: List<EscrowModuleConfig>,
    ): List<UniquenessGavRow> {
        val baseConfig = baseConfigOf(configs) ?: return emptyList()
        val rows = mutableListOf<UniquenessGavRow>()

        fun addGavRows(
            versionRange: String,
            dist: Distribution?,
        ) {
            val gavCsv = dist?.GAV() ?: return
            for (entry in extractMavenGavs(gavCsv)) {
                val coords = parseMavenGavEntry(entry) ?: continue
                rows +=
                    UniquenessGavRow(
                        componentKey,
                        versionRange,
                        coords.groupId,
                        coords.artifactId,
                        coords.extension,
                        coords.classifier,
                        origin = "distribution GAV",
                    )
            }
        }

        // NOTE: only explicit `distribution { GAV }` coordinates feed this distribution-GAV
        // uniqueness pre-pass. The groupId/artifactId OWNERSHIP mapping is no longer folded in
        // here — its cross-component uniqueness is the mode-aware matrix
        // (validateArtifactOwnershipCollisions), enforced on the v4 write path ONLY (create +
        // update-with-artifactIds), NOT in this migration pre-pass. This is sufficient, not a gap:
        // the migration imports only legacy-VALID DSL (it passed the daily [2.0] validation), and an
        // ownership collision (two configs owning the same artifact in overlapping ranges) is exactly
        // what the legacy single-match `ModuleByArtifactResolver` THROWS on — so it cannot exist in
        // migrated data. A collision can only be introduced by a NEW v4 write, which the matrix guards.
        // Migration itself enforces correctness via strict artifactId→mode classification (no escape
        // hatch; unclassifiable patterns hard-fail). See ADR-017 / SYS-058.
        // The base distribution is persisted on the BASE row at ALL_VERSIONS (buildBaseConfigRow /
        // attachDistribution), regardless of which block seeded the base (selectBaseConfig). Record the
        // base GAV at ALL_VERSIONS — NOT baseConfig.versionRangeString — so this pre-pass matches the
        // physical row the API-side validateMavenArtifactCollisions reads back. Using the base block's
        // declared range (e.g. the open-upper `[X,)`) would make the pre-pass miss a cross-component
        // collision outside that range that the API would later reject (pre-pass contract: reject here
        // exactly what the API would reject).
        addGavRows(ALL_VERSIONS, baseConfig.distribution)
        for (override in configs.filter { it !== baseConfig }) {
            val overrideRange = override.versionRangeString ?: continue
            if (mavenArtifactsDiffer(baseConfig.distribution, override.distribution)) {
                addGavRows(overrideRange, override.distribution)
            }
        }
        return rows
    }

    /**
     * EFFECTIVE jira (projectKey, versionPrefix) claims of this module — one pair per
     * MERGED per-range config (the Groovy loader has already layered each range's jira
     * block over the base, including the inherited `component{versionPrefix}`). This is
     * exactly what the legacy `validateJiraProjectKeyAndVersionPrefixIntersections`
     * bucketed and what the resolver serves: the prod shape where one component
     * legitimately owns `(project, null)` while others claim that project only
     * WITH their explicit/inherited prefixes via projectKey-only range overrides
     * is LEGAL.
     * Bucketing raw SCALAR_OVERRIDE rows instead fabricated `(key, null)` claims and
     * bricked the migration on legacy-valid data. The API-side check mirrors this via
     * [computeEffectiveJiraPairs] over the persisted rows.
     * An archived component claims no pair (same exemption as the API check).
     */
    private fun collectDslJiraPairs(
        componentKey: String,
        configs: List<EscrowModuleConfig>,
    ): List<UniquenessJiraPair> {
        val baseConfig = baseConfigOf(configs) ?: return emptyList()
        if (baseConfig.archived) return emptyList()
        return configs
            .mapNotNull { cfg ->
                cfg.jiraConfiguration?.let { jira ->
                    jira.projectKey?.takeIf { it.isNotBlank() }?.let { pk ->
                        UniquenessJiraPair(componentKey, pk, jira.componentInfo?.versionPrefix)
                    }
                }
            }.distinct()
    }

    /**
     * Distinct docker image names across this module's configs (base `attachDockerImages`
     * rows plus DISTRIBUTION_DOCKER marker rows collapse to the same distinct name set,
     * which is what the API-side `validateDockerImageUniqueness` reads back).
     */
    private fun collectDslDockerRows(
        componentKey: String,
        configs: List<EscrowModuleConfig>,
    ): List<UniquenessDockerRow> =
        configs
            .flatMap { cfg ->
                val dockerCsv = cfg.distribution?.docker() ?: return@flatMap emptyList<String>()
                splitCsv(dockerCsv).mapNotNull { entry -> parseDockerImage(entry)?.imageName?.takeIf { it.isNotBlank() } }
            }.distinct()
            .map { UniquenessDockerRow(componentKey, it) }

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
        // Stored verbatim (nullable) — NO key backfill: prod 2.0.87 served the legacy `$.name`
        // as null for components without componentDisplayName, so we must keep it null to stay
        // wire-compatible. The loader already resolves DSL inheritance, so a blank value maps to
        // null here. Uniqueness is enforced by the DB + the collision pre-pass on non-null names.
        entity.displayName = cfg.componentDisplayName?.takeIf { it.isNotBlank() }
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

        // vcs.externalRegistry is per-component. CRS-C bridge: the legacy
        // `externalRegistry = "NOT_AVAILABLE"` sentinel is NEVER stored — it becomes the
        // dedicated skipCommitCheck flag with a NULL registry; any real registry name imports
        // as-is with the flag off. The helper sets BOTH fields authoritatively so a re-import
        // that changed/dropped the sentinel can never leave a stale flag.
        val importedExternalRegistry = cfg.vcsSettings?.externalRegistry
        applyImportedExternalRegistry(entity, importedExternalRegistry)
        // Q13: WHISKEY + NOT_AVAILABLE is a data contradiction (a WHISKEY component should carry a
        // real registry, not the sentinel). Warn but still import — the v4 write path rejects the
        // combination; existing DSL data is the domain owner's to reconcile.
        if (entity.skipCommitCheck && cfg.buildSystem == org.octopusden.octopus.escrow.BuildSystem.WHISKEY) {
            LOG.warn(
                "Component '{}': externalRegistry=NOT_AVAILABLE combined with buildSystem=WHISKEY — " +
                    "imported as skipCommitCheck=true; this pairing is rejected on v4 write and should be reviewed.",
                componentKey,
            )
        }

        // distribution.explicit / distribution.external are per-component; a
        // per-range value that differs from the base is rejected at import
        // (validatePerComponentDistributionInvariants, CRS #387), so taking the
        // base value here can no longer silently drop a divergent per-range one.
        cfg.distribution?.let { dist ->
            entity.distributionExplicit = dist.explicit()
            entity.distributionExternal = dist.external()
        }

        // Artifact ownership: the base (all-versions) mapping. Classify the DSL
        // groupId/artifactId into a mode (EXPLICIT / ALL_EXCEPT_CLAIMED / ALL) and
        // store it as ComponentArtifactMappingEntity rows. Per-range overrides are
        // added by emitMarkerOverrides with the override's range. sort_order=0 (the
        // first mapping added) is the PRIMARY mapping rendered into the legacy
        // v1-v3 single (groupIdPattern, artifactIdPattern) pair.
        addOwnershipMappings(entity, ALL_VERSIONS, cfg.groupIdPattern, cfg.artifactIdPattern)

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

    /**
     * Build artifact-ownership mappings for [versionRange] from a DSL groupId/artifactId
     * pair. Classifies the artifactId into a mode (EXPLICIT / ALL_EXCEPT_CLAIMED / ALL —
     * [ArtifactOwnershipModeClassifier]) and returns [ComponentArtifactMappingEntity] rows
     * (EXPLICIT adds literal token children). ALL_EXCEPT_CLAIMED is single-group, so a
     * comma group-list is split into one mapping per group for EVERY mode (canonicalization:
     * exactly one Maven groupId per stored row). The split rows share mode/tokens/range and are
     * re-composed into the legacy single pair by the forward render (ArtifactOwnershipGrouping).
     * `sortOrder` runs from [startSortOrder] (0 = primary). Empty list when there is no group.
     */
    private fun buildOwnershipMappings(
        component: ComponentEntity,
        versionRange: String,
        groupIdPattern: String?,
        artifactIdPattern: String?,
        startSortOrder: Int,
    ): List<ComponentArtifactMappingEntity> {
        val group = groupIdPattern?.trim()
        if (group.isNullOrBlank()) return emptyList()
        val mode = ArtifactOwnershipModeClassifier.classify(artifactIdPattern)
        // Canonicalize ownership to exactly ONE Maven groupId per stored row (for EVERY mode, not just
        // ALL_EXCEPT_CLAIMED): a comma group-list "a,b" becomes one mapping per group sharing the same
        // mode/tokens/range. Semantically safe — resolution matches a single real groupId against each
        // token, and the forward wire re-composes the split rows (ArtifactOwnershipGrouping).
        // Fail-loud on a malformed list (empty segment) rather than silently dropping a group.
        val groups = ArtifactOwnershipModeClassifier.splitGroups(group)
        val tokens =
            if (mode == ArtifactIdMode.EXPLICIT) {
                ArtifactOwnershipModeClassifier.splitTokens(artifactIdPattern.orEmpty())
            } else {
                emptyList()
            }
        return groups.mapIndexed { gi, g ->
            val mapping = ComponentArtifactMappingEntity(
                component = component,
                versionRange = versionRange,
                groupPattern = g,
                artifactIdMode = mode.name,
                sortOrder = startSortOrder + gi,
            )
            tokens.forEachIndexed { i, tok ->
                mapping.tokens.add(
                    ComponentArtifactMappingTokenEntity(mapping = mapping, artifactPattern = tok, sortOrder = i),
                )
            }
            mapping
        }
    }

    /** Base-mapping convenience: build + attach to the not-yet-saved component (cascade persists). */
    private fun addOwnershipMappings(
        entity: ComponentEntity,
        versionRange: String,
        groupIdPattern: String?,
        artifactIdPattern: String?,
    ) {
        entity.artifactMappings.addAll(
            buildOwnershipMappings(entity, versionRange, groupIdPattern, artifactIdPattern, entity.artifactMappings.size),
        )
    }

    private fun linkSystems(
        component: ComponentEntity,
        cfg: EscrowModuleConfig,
    ) {
        val systemStr = cfg.system ?: return
        // A component may be classified under several system codes at once; the
        // DSL field is a CSV that carries every one. Persist ALL non-blank
        // entries as `component_systems` junction rows (dedup on the way in) so
        // multi-system components round-trip through DB-mode read/filter exactly
        // as they do through git-mode. The old single-value collapse (keep-first,
        // drop-rest) is what dropped a component from a report filtered by one
        // of its OTHER systems.
        val codes = systemStr
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        for (code in codes) {
            upsertSystem(code) // ensure dictionary exists
            componentSystemRepository.save(
                ComponentSystemEntity(componentId = component.id!!, systemCode = code),
            )
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
    ): ComponentConfigurationEntity {
        // Decoupled version model (ADR-018): the base row is ALWAYS the effective
        // default at ALL_VERSIONS — never a synthetic bounded range. Coverage is a
        // separate layer (RANGE_PRESENCE rows). `is_synthetic_base` is retained on
        // the schema/DTO for v4-contract stability but is always false now.
        val row =
            ComponentConfigurationEntity(
                component = component,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                isSyntheticBase = false,
            )
        populateScalarsFromConfig(row, cfg)
        return row
    }

    /**
     * Emit a RANGE_PRESENCE row for a DSL-declared version range whose
     * scalars/markers all match base (so neither `emitMergedScalarOverrides` nor
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
                row.jiraMinorVersionFormat = cvf.majorVersionFormat
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
     * Emit SCALAR_OVERRIDE rows for every attribute where a non-base config differs from the base,
     * MERGING adjacent/overlapping ranges that carry the same value into one row (MIG-048).
     *
     * With the base = the OPEN-UPPER (newest) block (ADR-018 amendment), an attribute declared only
     * there makes every OLDER block diff identically against it — they all resolve the same inherited
     * top-level/Defaults value — which would emit one identical row per declared block (e.g. two
     * adjacent `javaVersion=1.8` rows). The spec allows merging ("adjacent same-value MAY be merged"),
     * so ranges are grouped per (attribute, value) and collapsed via [VersionRangePartition.mergeUnion]
     * with the component's scheme-aware ordering. Non-adjacent same-value ranges stay separate rows.
     *
     * Defensive guard: if a merged union collapses to the all-versions shape, the group's VERBATIM
     * per-block ranges are emitted instead — a SCALAR_OVERRIDE at ALL_VERSIONS would shadow the base
     * row for the whole version space. The shape is unreachable today ONLY because of a loader
     * invariant, not structure: the Groovy loader merges top-level defaults INTO each range config
     * rather than emitting a separate all-versions config (see [isAllVersionsRange] KDoc), so
     * `selectBaseConfig` branch 1 (explicit all-versions base) never coexists with bounded siblings
     * whose union tiles everything. If it ever fires, the fallback deliberately reintroduces the
     * duplicated-adjacent-rows shape this merge removes (fail-safe over fail-wrong), and the verbatim
     * strings are unnormalized DSL text — the idempotency lookup below then depends on DSL string
     * stability.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun emitMergedScalarOverrides(
        component: ComponentEntity,
        base: EscrowModuleConfig,
        nonBaseConfigs: List<EscrowModuleConfig>,
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

        // (attribute, value) -> the declared ranges carrying that diff. The value is part of the
        // grouping key (nullable — an explicit null-clear groups like any other value), so only
        // SAME-value ranges ever merge; a range with a different value keeps its own group.
        val rangesByAttrValue = LinkedHashMap<Pair<String, Any?>, MutableList<String>>()
        for (override in nonBaseConfigs) {
            val versionRange = override.versionRangeString ?: continue
            val overRow =
                ComponentConfigurationEntity(
                    component = component,
                    versionRange = "",
                    rowType = "BASE",
                )
            populateScalarsFromConfig(overRow, override)
            for ((attrPath, newValue) in collectScalarDiffs(baseRow, overRow)) {
                rangesByAttrValue.getOrPut(attrPath to newValue) { mutableListOf() }.add(versionRange)
            }
        }

        val saved = mutableListOf<ComponentConfigurationEntity>()
        val compare = numericVersionComparator(numericVersionFactory)
        for ((attrValue, ranges) in rangesByAttrValue) {
            val (attrPath, newValue) = attrValue
            // The jira uniqueness pair `(projectKey, versionPrefix)` is reconstructed from PERSISTED
            // rows by grouping on the EXACT versionRange (computeEffectiveJiraPairs: pkRow+prefixRow
            // must sit in the SAME range group; mirrored by the API-side validator). Merging one of
            // the two attributes independently of the other would tear companion rows apart —
            // e.g. a widened projectKey row paired with the BASE prefix instead of its range's
            // prefix — corrupting collision detection. Keep these two attributes on their VERBATIM
            // per-block ranges; every other scalar merges.
            val merged =
                if (attrPath in JIRA_UNIQUENESS_PAIR_ATTRS) {
                    ranges
                } else {
                    VersionRangePartition.mergeUnion(ranges, compare)
                }
            val effectiveRanges = if (merged.any { isAllVersionsRange(it) }) ranges else merged
            for (versionRange in effectiveRanges) {
                // Avoid duplicate rows for same (component, range, attribute). NOTE: this exact-range
                // lookup assumes import never re-runs over a component that already has override rows
                // (both importModule call sites hard-skip components present in the DB). If a
                // re-migration/refresh path is ever added, rows persisted by an OLDER import at
                // unmerged per-block ranges would NOT be found under the merged range and the same
                // attribute would end up with overlapping rows — there is no import-side disjointness
                // validator (validateFieldOverrideRange guards only the v4 API write path).
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
            // Per-range ownership override: write ComponentArtifactMappingEntity rows with this
            // range (REPLACES the base mapping for the range). Ownership no longer rides the
            // GROUP_ARTIFACT_PATTERN marker. `component` is already persisted, so save directly.
            // sort_order starts at 0: these rows live in their own `versionRange` bucket (distinct
            // from the base ALL_VERSIONS rows and from every other override range), and both the
            // UNIQUE(component_id, version_range, sort_order) constraint and the resolver's
            // per-range primary selection (`minByOrNull { sortOrder }` within a range) only require
            // ordering WITHIN this range — no global offset is needed.
            val overrideMappings =
                buildOwnershipMappings(component, versionRange, override.groupIdPattern, override.artifactIdPattern, 0)
            componentArtifactMappingRepository.saveAll(overrideMappings)
            // ownership mappings are persisted directly (not config-row markers), so `saved`
            // (the marker-config-row list) is unchanged.
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
            (base.buildConfiguration?.tools.takeUnless { it.isNullOrEmpty() } ?: commonDefaultsTools)
                .mapNotNull { it.name }
                .toSet()
        val overTools = override.buildConfiguration
            ?.tools
            ?.map { it.name }
            ?.toSet() ?: emptySet()
        if (effectiveBaseToolNames != overTools) {
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
    private fun buildToolKeys(tools: Collection<BuildTool>?): Set<String> = buildBuildToolKeys(tools)

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
        diffScalar("jira.minorVersionFormat", base.jiraMinorVersionFormat, override.jiraMinorVersionFormat)
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
            "jira.minorVersionFormat" -> row.jiraMinorVersionFormat = value?.toString()
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
                group.groupKey,
                members.size,
            )
        }
        LOG.info("§6.3 cleanup: removed {} stale group(s)", stale.size)
    }

    // =========================================================================
    // §6.3 Aggregator detection helpers (per schema-spec.md §4.3)
    // =========================================================================

    internal fun isFakeAggregator(cfg: EscrowModuleConfig): Boolean {
        val vcsUrl = cfg.vcsSettings
            ?.versionControlSystemRoots
            ?.firstOrNull()
            ?.vcsPath
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
            queryStr
                .split("&")
                .mapNotNull {
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

        /**
         * Attributes EXCLUDED from adjacent same-value override merging (MIG-048): the jira
         * uniqueness pair is reconstructed from persisted rows by exact-range grouping
         * (`computeEffectiveJiraPairs`), so `jira.projectKey` / `jira.versionPrefix` rows must keep
         * their verbatim per-block ranges — see [emitMergedScalarOverrides].
         */
        private val JIRA_UNIQUENESS_PAIR_ATTRS = setOf("jira.projectKey", "jira.versionPrefix")

        /** Exact-string FAKE-aggregator artifactId markers. */
        private val FAKE_ARTIFACT_ID_LITERALS: Set<String> = setOf("fake", "dummy", "stub")

        /**
         * Groups (componentKey → cfgDisplayName) module pairs by their verbatim, non-blank display
         * name and returns only the names claimed by more than one DISTINCT component (name → all
         * claiming keys, each list deduped-and-sorted). Components with a null/blank
         * `componentDisplayName` are skipped (they store NULL — many NULLs don't collide under the
         * UNIQUE constraint). Empty result ⇒ no collisions. Pure — unit-tested.
         */
        internal fun computeDisplayNameCollisions(modules: List<Pair<String, String?>>): Map<String, List<String>> {
            val byDisplayName = mutableMapOf<String, MutableSet<String>>()
            for ((componentKey, cfgDisplayName) in modules) {
                val name = cfgDisplayName?.takeIf { it.isNotBlank() } ?: continue
                byDisplayName.getOrPut(name) { mutableSetOf() }.add(componentKey)
            }
            return byDisplayName
                .filterValues { it.size > 1 }
                .mapValues { (_, keys) -> keys.sorted() }
        }

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

/**
 * One would-be `distribution_maven_artifacts` row derived from the DSL, for the §6.0
 * uniqueness pre-pass. [origin] names the DSL source for the conflict message:
 * an explicit `distribution { GAV }` coordinate vs the component-level
 * `groupId`/`artifactId` mapping (MIG-047 synthetic rows) — see [MavenGavCollision.originLabel].
 */
internal data class UniquenessGavRow(
    val componentKey: String,
    val versionRange: String,
    val groupPattern: String,
    val artifactPattern: String,
    val extension: String?,
    val classifier: String?,
    val origin: String = "distribution GAV",
)

/** One jira (projectKey, versionPrefix) claim, for the §6.0 uniqueness pre-pass. */
internal data class UniquenessJiraPair(
    val componentKey: String,
    val projectKey: String,
    val versionPrefix: String?,
)

/** One docker image-name claim, for the §6.0 uniqueness pre-pass. */
internal data class UniquenessDockerRow(
    val componentKey: String,
    val imageName: String,
)

/**
 * Distribution-GAV collisions for the migration pre-pass, with the same FULL identity
 * the API check uses ([MavenGavCollision]: group/artifact pattern overlap + equal
 * extension + equal classifier) and the same conservative range handling (the caller's
 * [rangesIntersect] decides; unparseable → treat as intersecting).
 *
 * Only new-vs-new and new-vs-existing pairs are reported; existing-vs-existing is the
 * API's to fix, and same-componentKey pairs never collide (idempotent reruns, multi-range
 * components). Pure — unit-tested without Spring.
 */
internal fun computeDistributionGavCollisions(
    newRows: List<UniquenessGavRow>,
    existingRows: List<UniquenessGavRow>,
    rangesIntersect: (String, String) -> Boolean,
): List<String> {
    val violations = mutableListOf<String>()

    fun check(
        row: UniquenessGavRow,
        rival: UniquenessGavRow,
    ) {
        if (row.componentKey == rival.componentKey) return
        if (!MavenGavCollision.identityCollides(
                row.groupPattern,
                row.artifactPattern,
                row.extension,
                row.classifier,
                rival.groupPattern,
                rival.artifactPattern,
                rival.extension,
                rival.classifier,
            )
        ) {
            return
        }
        if (!rangesIntersect(row.versionRange, rival.versionRange)) return
        val gav = MavenGavCollision.gavLabel(row.groupPattern, row.artifactPattern, row.extension, row.classifier)
        violations +=
            "uniqueness violation: ${row.origin} '$gav' of component '${row.componentKey}' duplicates " +
            "the ${rival.origin} of component '${rival.componentKey}' in intersecting version ranges " +
            "'${row.versionRange}' ∩ '${rival.versionRange}'"
    }

    for (i in newRows.indices) {
        for (j in i + 1 until newRows.size) check(newRows[i], newRows[j])
        for (existing in existingRows) check(newRows[i], existing)
    }
    return violations
}

/**
 * Jira (projectKey, versionPrefix) buckets claimed by more than one distinct component,
 * where at least one claimant is incoming — same invariant as the API-side
 * `validateJiraProjectKeyVersionPrefixUniqueness` (null prefix is its own bucket;
 * callers already excluded archived components). Self-collision safety: claimants are
 * collected into a SET of componentKeys, so the same component appearing multiple times
 * in `newPairs` (multi-range module) or on both sides (idempotent rerun against its own
 * persisted rows) never trips the `size >= 2` threshold. Pure — unit-tested without Spring.
 */
internal fun computeJiraPairCollisions(
    newPairs: List<UniquenessJiraPair>,
    existingPairs: List<UniquenessJiraPair>,
): List<String> {
    val newByBucket = newPairs.groupBy({ it.projectKey to it.versionPrefix }, { it.componentKey })
    val existingByBucket = existingPairs.groupBy({ it.projectKey to it.versionPrefix }, { it.componentKey })
    return newByBucket.entries
        .mapNotNull { (bucket, newKeys) ->
            val claimants = (newKeys + existingByBucket[bucket].orEmpty()).toSortedSet()
            if (claimants.size < 2) return@mapNotNull null
            val (projectKey, versionPrefix) = bucket
            val prefixText = if (versionPrefix == null) "no version prefix" else "version prefix '$versionPrefix'"
            "uniqueness violation: jira project '$projectKey' with $prefixText is claimed by " +
                "multiple components: ${claimants.joinToString(", ")}"
        }.sorted()
}

/**
 * Docker image names claimed by more than one distinct component, where at least one
 * claimant is incoming — same global-uniqueness invariant as the API-side
 * `validateDockerImageUniqueness`. Claimants dedupe into a componentKey SET, so
 * multi-range modules and idempotent reruns never self-collide. Pure — unit-tested
 * without Spring.
 */
internal fun computeDockerImageCollisions(
    newRows: List<UniquenessDockerRow>,
    existingRows: List<UniquenessDockerRow>,
): List<String> {
    val newByImage = newRows.groupBy({ it.imageName }, { it.componentKey })
    val existingByImage = existingRows.groupBy({ it.imageName }, { it.componentKey })
    return newByImage.entries
        .mapNotNull { (imageName, newKeys) ->
            val claimants = (newKeys + existingByImage[imageName].orEmpty()).toSortedSet()
            if (claimants.size < 2) return@mapNotNull null
            "uniqueness violation: docker image name '$imageName' is claimed by " +
                "multiple components: ${claimants.joinToString(", ")} — image names must be globally unique"
        }.sorted()
}

/**
 * Incoming display names that an ALREADY-PERSISTED different component holds (the DSL-vs-DSL
 * half stays in [ImportServiceImpl.computeDisplayNameCollisions]). Null/blank incoming names
 * never collide (display_name is nullable-unique). Pure — unit-tested without Spring.
 */
internal fun computeDisplayNameDbCollisions(
    newPairs: List<Pair<String, String?>>,
    existingPairs: List<Pair<String, String>>,
): List<String> {
    val existingByName = existingPairs.groupBy({ it.second }, { it.first })
    return newPairs
        .mapNotNull { (componentKey, rawName) ->
            val name = rawName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val holders = existingByName[name].orEmpty().filter { it != componentKey }
            if (holders.isEmpty()) return@mapNotNull null
            "uniqueness violation: display name \"$name\" of component '$componentKey' is already " +
                "used by component(s) ${holders.sorted().joinToString(", ")} (display_name is unique)"
        }.sorted()
}

internal fun buildBuildToolKeys(tools: Collection<BuildTool>?): Set<String> =
    tools
        ?.mapNotNull { tool ->
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
