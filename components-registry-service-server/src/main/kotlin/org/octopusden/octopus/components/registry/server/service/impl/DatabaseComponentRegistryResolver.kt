package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.util.ArtifactOwnershipRendering
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.mapper.escrowModuleConfigField
import org.octopusden.octopus.components.registry.server.mapper.toEscrowModule
import org.octopusden.octopus.components.registry.server.mapper.toResolvedEscrowModuleConfig
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.util.formatVersion
import org.octopusden.octopus.escrow.MavenArtifactMatcher
import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.config.JiraComponentVersionRangeFactory
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.components.registry.api.escrow.Escrow
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.resolvers.ComponentHotfixSupportResolver
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.EnumMap

/**
 * Read-side resolver for the v1-v3 API surface, backed by the schema v2
 * `components` + `component_configurations` tables. All resolve calls go through
 * the new `EntityMappers` merge algorithm (schema-spec.md §3.4): base row +
 * matching override rows are combined per request into a single
 * `EscrowModuleConfig` (single-version path) or one config per distinct version
 * range (enumeration path).
 *
 * Synthetic-base handling lives in the mapper (MIG-029 fix); the resolver does
 * not special-case it here.
 */
@ConditionalOnDatabaseEnabled
@Suppress("TooManyFunctions")
@Service("databaseComponentRegistryResolver")
@Transactional(readOnly = true)
class DatabaseComponentRegistryResolver(
    private val componentRepository: ComponentRepository,
    private val dependencyMappingRepository: DependencyMappingRepository,
    private val numericVersionFactory: NumericVersionFactory,
    private val versionRangeFactory: VersionRangeFactory,
    private val versionNames: VersionNames,
) : ComponentRegistryResolver {
    private val jiraComponentVersionFormatter = JiraComponentVersionFormatter(versionNames)
    private val jiraComponentVersionRangeFactory = JiraComponentVersionRangeFactory(versionNames)
    private val componentHotfixSupportResolver = ComponentHotfixSupportResolver()

    override fun updateCache() {
        log.debug("updateCache() called on DatabaseComponentRegistryResolver — no-op")
    }

    override fun getComponents(): MutableCollection<EscrowModule> =
        componentRepository
            .findAll()
            .map { it.toEscrowModule(versionRangeFactory, numericVersionFactory).nullifyEmptyEscrow() }
            .toMutableList()

    override fun getComponentById(id: String): EscrowModule? =
        componentRepository
            .findByComponentKey(id)
            ?.toEscrowModule(versionRangeFactory, numericVersionFactory)
            ?.nullifyEmptyEscrow()

    override fun getResolvedComponentDefinition(
        id: String,
        version: String,
    ): EscrowModuleConfig? {
        val component = componentRepository.findByComponentKey(id) ?: return null
        return getResolvedComponentDefinition(component, version)
    }

    /**
     * Entity-accepting variant of [getResolvedComponentDefinition] — resolves off an
     * already-loaded [ComponentEntity] so callers that hold the entity (e.g. the
     * docker-image path) avoid a redundant `findByComponentKey` reload. Semantics are
     * identical to the `(id, version)` override: null when the version resolves to no
     * config, distribution-normalisation failures fall back to the raw version string.
     */
    private fun getResolvedComponentDefinition(
        component: ComponentEntity,
        version: String,
    ): EscrowModuleConfig? {
        val config =
            component.toResolvedEscrowModuleConfig(
                version = version,
                versionRangeFactory = versionRangeFactory,
                numericVersionFactory = numericVersionFactory,
            ) ?: return null

        // Null out escrow that has no meaningful data (matches Git-path behaviour
        // for sub-components without an explicit escrow block).
        config.nullifyEmptyEscrow()

        // Mirror the Git resolver: when distribution is present, calculate
        // dist-time substitutions against the jira-normalised version, falling
        // back to the raw version string if normalisation fails.
        if (config.distribution != null) {
            val normalizedVersion =
                try {
                    getJiraComponentVersionToRangeByComponentAndVersion(component, version).first.version
                } catch (_: Exception) {
                    version
                }
            val resolved = EscrowConfigurationLoader.calculateDistribution(config.distribution, normalizedVersion)
            // Reuse the memoized field lookup (GH #365) instead of a per-call getDeclaredField.
            // requireNotNull keeps the old fail-loud semantics (getDeclaredField threw
            // NoSuchFieldException) for this runtime-resolved write, rather than silently no-op'ing.
            requireNotNull(escrowModuleConfigField("distribution")) {
                "EscrowModuleConfig.distribution field missing from memoized lookup"
            }.set(config, resolved)
        }
        return config
    }

    override fun getJiraComponentVersion(
        component: String,
        version: String,
    ): JiraComponentVersion {
        val jiraVersionRanges = getJiraComponentVersionRangesByComponent(component)
        val results = getJiraComponentVersionsToRanges(version, jiraVersionRanges, false)
        return when (results.size) {
            1 -> results.first().first
            0 -> throw NotFoundException("Version '$version' for component '$component' is not found")
            else -> error("Found ${results.size} configurations for version '$version' of component '$component'")
        }
    }

    /**
     * Entity-accepting variant of [getJiraComponentVersion] — builds the ranges from an
     * already-loaded [ComponentEntity] (no `findByComponentKey` reload). Used by the
     * docker-image path, which holds the entity from [buildImageToComponentMap].
     */
    private fun getJiraComponentVersion(
        component: ComponentEntity,
        version: String,
    ): JiraComponentVersion {
        val jiraVersionRanges = buildJiraVersionRangesForComponent(component)
        val results = getJiraComponentVersionsToRanges(version, jiraVersionRanges, false)
        return when (results.size) {
            1 -> results.first().first
            0 -> throw NotFoundException("Version '$version' for component '${component.componentKey}' is not found")
            else ->
                error(
                    "Found ${results.size} configurations for version '$version' of component '${component.componentKey}'",
                )
        }
    }

    override fun getJiraComponentVersions(
        component: String,
        versions: List<String>,
    ): Map<String, JiraComponentVersion> =
        try {
            val jiraVersionRanges = getJiraComponentVersionRangesByComponent(component)
            versions
                .associateWith { version ->
                    getJiraComponentVersionsToRanges(version, jiraVersionRanges, false)
                        .map { it.first }
                }.filterValues { it.isNotEmpty() }
                .mapValues { it.value.first() }
        } catch (_: NotFoundException) {
            emptyMap()
        }

    override fun getVCSSettings(
        component: String,
        version: String,
    ): VCSSettings {
        val (jiraComponentVersion, jiraComponentVersionRange) =
            getJiraComponentVersionToRangeByComponentAndVersion(component, version)
        val versionFormat = jiraComponentVersion.component.componentVersionFormat
        val defaultBuildVersion =
            versionFormat.buildVersionFormat.formatVersion(
                numericVersionFactory,
                jiraComponentVersion.version,
            )
        val buildVersion =
            if (jiraComponentVersionRange.component.isHotfixEnabled) {
                val hotfixVersion =
                    versionFormat.hotfixVersionFormat.formatVersion(
                        numericVersionFactory,
                        jiraComponentVersion.version,
                    )
                if (hotfixVersion == jiraComponentVersion.version) hotfixVersion else defaultBuildVersion
            } else {
                defaultBuildVersion
            }
        return ModelConfigPostProcessor(ComponentVersion.create(component, buildVersion), versionNames)
            .resolveVariables(jiraComponentVersionRange.vcsSettings)
    }

    override fun getBuildTools(
        component: String,
        version: String,
        ignoreRequired: Boolean?,
    ): List<BuildTool> {
        val buildConfiguration =
            getResolvedComponentDefinition(component, version)?.buildConfiguration
                ?: return emptyList()
        val resolvedBuildTools = mutableListOf<BuildTool>()

        if (ignoreRequired != true && buildConfiguration.requiredProject) {
            resolvedBuildTools +=
                PTCProductToolBean().apply {
                    setVersion(buildConfiguration.projectVersion)
                }
        }

        buildConfiguration.buildTools?.let(resolvedBuildTools::addAll)
        return resolvedBuildTools
    }

    override fun getJiraComponentByProjectAndVersion(
        projectKey: String,
        version: String,
    ): JiraComponentVersion = getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version).first

    override fun getJiraComponentsByProject(projectKey: String): Set<String> =
        getJiraComponentVersionRangesByProject(projectKey)
            .map { it.componentName }
            .toSet()

    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRange> {
        val byProject = getAllJiraComponentVersionRanges().filter { it.component.projectKey == projectKey }
        if (byProject.isEmpty()) {
            throw NotFoundException("Project '$projectKey' is not found")
        }
        return byProject.toSet()
    }

    override fun getComponentsDistributionByJiraProject(projectKey: String): Map<String, Distribution> =
        getJiraComponentVersionRangesByProject(projectKey)
            .groupBy { it.componentName }
            .mapValues { (_, versionRanges) ->
                Distribution(
                    versionRanges.any { it.distribution?.explicit() ?: true },
                    versionRanges.any { it.distribution?.external() ?: false },
                    versionRanges.find { it.distribution != null }?.distribution?.GAV(),
                    versionRanges.find { it.distribution != null }?.distribution?.DEB(),
                    versionRanges.find { it.distribution != null }?.distribution?.RPM(),
                    versionRanges.find { it.distribution != null }?.distribution?.docker(),
                    versionRanges.find { it.distribution != null }?.distribution?.securityGroups ?: SecurityGroups(null),
                )
            }

    override fun getVCSSettingForProject(
        projectKey: String,
        version: String,
    ): VCSSettings {
        val (_, jiraComponentVersionRange) =
            getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version)
        return ModelConfigPostProcessor(
            ComponentVersion.create(jiraComponentVersionRange.componentName, version),
            versionNames,
        ).resolveVariables(jiraComponentVersionRange.vcsSettings)
    }

    override fun getDistributionForProject(
        projectKey: String,
        version: String,
    ): Distribution {
        log.info("Get distribution for project: {} version: {}", projectKey, version)
        val found = getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version)
        return EscrowConfigurationLoader.calculateDistribution(found.second.distribution, found.first.version)
    }

    override fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRange> =
        componentRepository
            .findAll()
            .flatMap { component -> buildJiraVersionRangesForComponent(component) }
            .toSet()

    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent =
        findComponentByArtifactOrNull(artifact)
            ?: throw NotFoundException("No component found for artifact=$artifact")

    override fun findComponentsByArtifact(artifacts: Set<ArtifactDependency>): Map<ArtifactDependency, VersionedComponent?> {
        log.debug("Find components by artifacts: {}", artifacts)
        // Build per-component per-range effective (group, artifact) ONCE, then match each artifact
        // — avoids re-reading all components and reconstructing their escrow view per artifact.
        val perComponent = loadPerComponentArtifactParameters()
        return artifacts.associateWith { artifact -> matchArtifact(artifact, perComponent) }
    }

    override fun getMavenArtifactParameters(component: String): Map<String, ComponentArtifactConfiguration> {
        val componentEntity =
            componentRepository.findByComponentKey(component)
                ?: throw NotFoundException("Component '$component' is not found")
        return mavenArtifactParametersFor(componentEntity)
    }

    /**
     * Per-range effective ownership `(groupPattern, artifactPattern)` for a component,
     * keyed by version-range string, rendered for the legacy v1–v3 wire from the
     * `component_artifact_mappings` model:
     *  - For each config range R, the EFFECTIVE mappings are the per-range override
     *    mappings whose `versionRange == R` if any, else the base (`ALL_VERSIONS`)
     *    mappings (override REPLACES base for that range — most-specific wins).
     *  - The legacy single pair renders the PRIMARY mapping (lowest `sortOrder`) of R.
     *  - Mode → pattern: ALL / ALL_EXCEPT_CLAIMED → the catch-all `[\w-\.]+` (the v3 DB
     *    resolver's specificity makes ALL_EXCEPT yield to a rival's EXPLICIT, and ALL is
     *    sole-owner-by-validation); EXPLICIT → its literal tokens with regex metacharacters
     *    escaped (so the legacy regex matcher matches them literally), joined by `,`.
     */
    private fun mavenArtifactParametersFor(
        componentEntity: ComponentEntity,
    ): Map<String, ComponentArtifactConfiguration> {
        // #357 Option A: the FORWARD /maven-artifacts wire renders an ALL_EXCEPT_CLAIMED mapping as the
        // sibling-aware anchored negative-lookahead (mirroring the legacy DSL's exact-token exclusion),
        // so the v1-v3 wire matches the legacy baseline: byte-identical for a single excluded sibling
        // (the only ALL_EXCEPT shape in prod today), and order-insensitively equal for multi-sibling
        // (v3 sorts the siblings; the compat comparator compares the exclusion as a set). Siblings =
        // the EXPLICIT tokens claimed under the same group in an intersecting range by OTHER components.
        // Gated on the component actually having an ALL_EXCEPT_CLAIMED mapping so the common path does
        // NO cross-component scan; the scan itself uses findAll() like the existing reverse path
        // (loadPerComponentArtifactParameters) and only runs for the rare ALL_EXCEPT read. (Reverse
        // find-by-artifact keeps the plain catch-all + specificity via [renderMapping] — the resolution
        // outcome is identical and unchanged.)
        val siblings =
            if (componentEntity.artifactMappings.any { it.artifactIdMode == ArtifactIdMode.ALL_EXCEPT_CLAIMED.name }) {
                loadSiblingContext(excludingComponentKey = componentEntity.componentKey)
            } else {
                SiblingContext(emptyList(), emptyMap())
            }
        return effectiveMappingsByRange(componentEntity)
            .mapValues { (_, mappings) -> renderForwardMapping(mappings.minByOrNull { it.sortOrder }!!, siblings) }
            .filterValues { it.groupPattern.isNotEmpty() || it.artifactPattern.isNotEmpty() }
    }

    /**
     * Reverse `find-by-artifact` view: ALL effective mappings of each config range (not just
     * the primary), flattened to `(rangeKey, config)` pairs, so an artifact owned by a
     * non-primary mapping still resolves.
     */
    private fun ownershipConfigsByRange(
        componentEntity: ComponentEntity,
    ): List<Pair<String, ComponentArtifactConfiguration>> =
        effectiveMappingsByRange(componentEntity).flatMap { (rangeKey, mappings) ->
            mappings.map { rangeKey to renderMapping(it) }
        }

    /**
     * For each of the component's declared config ranges, the in-force ownership mappings:
     * the override mappings keyed to that exact range if present, else the base mappings.
     */
    private fun effectiveMappingsByRange(
        componentEntity: ComponentEntity,
    ): Map<String, List<ComponentArtifactMappingEntity>> {
        val byRange = componentEntity.artifactMappings.groupBy { it.versionRange }
        val baseMappings = byRange[ALL_VERSIONS].orEmpty()
        val module = componentEntity.toEscrowModule(versionRangeFactory, numericVersionFactory)
        val configRanges = module.moduleConfigurations.map { it.versionRangeString ?: ALL_VERSIONS }
        // Consider every declared config range PLUS every override range (an override range must
        // equal a config range by invariant, but include it explicitly so a per-range override is
        // never dropped); each range → its override mappings if any, else the base mappings.
        val overrideRanges = byRange.keys.filter { it != ALL_VERSIONS }
        val ranges = (configRanges + overrideRanges).distinct()
        if (ranges.isEmpty()) return emptyMap()
        // Drop ranges with NO in-force mappings (e.g. a component created with a config row but an
        // empty artifactIds list): otherwise an empty list reaches the forward render's
        // `minByOrNull { … }!!` and NPEs → GET /maven-artifacts 500. An empty list = no ownership.
        return ranges
            .associateWith { rangeKey -> byRange[rangeKey] ?: baseMappings }
            .filterValues { it.isNotEmpty() }
    }

    /** Render one ownership mapping to the legacy `(groupPattern, artifactPattern)` wire pair. */
    private fun renderMapping(mapping: ComponentArtifactMappingEntity): ComponentArtifactConfiguration =
        ComponentArtifactConfiguration(
            mapping.groupPattern,
            ArtifactOwnershipRendering.renderArtifactPattern(
                ArtifactIdMode.valueOf(mapping.artifactIdMode),
                mapping.tokens.sortedBy { it.sortOrder }.map { it.artifactPattern },
            ),
        )

    /**
     * An EXPLICIT ownership mapping of another component, reduced to what the sibling lookup needs:
     * the owning [componentKey], its group token(s), its version range, and its literal artifact tokens.
     */
    private data class ExplicitMappingRef(
        val componentKey: String,
        val groups: List<String>,
        val versionRange: String,
        val tokens: List<String>,
    )

    /**
     * Cross-component context for ALL_EXCEPT_CLAIMED sibling rendering: every OTHER component's EXPLICIT
     * mappings, plus [shadowRangesByComponent] = each component's own override (non-base) range strings.
     * The shadow map lets a rival's BASE EXPLICIT claim be skipped in a range that rival itself overrides
     * (override REPLACES base → the base token is not in force there), mirroring `computeOwnershipCollisions`.
     */
    private data class SiblingContext(
        val explicit: List<ExplicitMappingRef>,
        val shadowRangesByComponent: Map<String, Set<String>>,
    )

    /** Split a comma-separated group pattern into trimmed, non-empty group tokens. */
    private fun groupTokensOf(groupPattern: String): List<String> =
        groupPattern.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Two version ranges intersect (unparseable → treat as intersecting, the conservative choice). */
    private fun rangesIntersect(a: String, b: String): Boolean =
        runCatching {
            versionRangeFactory.create(a).isIntersect(versionRangeFactory.create(b))
        }.getOrDefault(true)

    /**
     * Snapshot the [SiblingContext] for ALL_EXCEPT sibling rendering: every OTHER component's EXPLICIT
     * mappings + each component's own override ranges (for the shadow skip). One `findAll()`; mirrors the
     * cross-component sibling scan in `ComponentManagementServiceImpl.ownershipExportPatterns`.
     */
    private fun loadSiblingContext(excludingComponentKey: String): SiblingContext {
        val all = componentRepository.findAll()
        val explicit =
            all.asSequence()
                .filter { it.componentKey != excludingComponentKey }
                .flatMap { component -> component.artifactMappings.asSequence().map { component.componentKey to it } }
                .filter { (_, mapping) -> mapping.artifactIdMode == ArtifactIdMode.EXPLICIT.name }
                .map { (key, mapping) ->
                    ExplicitMappingRef(
                        componentKey = key,
                        groups = groupTokensOf(mapping.groupPattern),
                        versionRange = mapping.versionRange,
                        tokens = mapping.tokens.sortedBy { it.sortOrder }.map { it.artifactPattern },
                    )
                }.toList()
        val shadowRanges =
            all.associate { component ->
                component.componentKey to
                    component.artifactMappings.map { it.versionRange }.filterTo(mutableSetOf()) { it != ALL_VERSIONS }
            }
        return SiblingContext(explicit, shadowRanges)
    }

    /**
     * Forward `/maven-artifacts` render: an ALL_EXCEPT_CLAIMED mapping becomes the anchored
     * negative-lookahead over its in-force EXPLICIT siblings (other components claiming a shared group
     * token in an intersecting range); every other mode renders as the plain wire form via [renderMapping].
     * A rival's BASE (ALL_VERSIONS) EXPLICIT claim is SKIPPED in a range that rival itself overrides
     * (override REPLACES base → not in force there), mirroring the shadow skip in `computeOwnershipCollisions`.
     * With no in-force siblings the lookahead degrades to the plain catch-all.
     */
    private fun renderForwardMapping(
        mapping: ComponentArtifactMappingEntity,
        ctx: SiblingContext,
    ): ComponentArtifactConfiguration {
        val mode = ArtifactIdMode.valueOf(mapping.artifactIdMode)
        if (mode != ArtifactIdMode.ALL_EXCEPT_CLAIMED) return renderMapping(mapping)
        val groups = groupTokensOf(mapping.groupPattern)
        val siblings =
            ctx.explicit
                .filter { ref -> ref.groups.any { it in groups } }
                .filter { ref -> rangesIntersect(ref.versionRange, mapping.versionRange) }
                .filterNot { ref ->
                    ref.versionRange == ALL_VERSIONS &&
                        mapping.versionRange in ctx.shadowRangesByComponent[ref.componentKey].orEmpty()
                }
                .flatMap { it.tokens }
                .distinct()
                .sorted()
        return ComponentArtifactConfiguration(
            mapping.groupPattern,
            ArtifactOwnershipRendering.renderExportPattern(mode, emptyList(), siblings),
        )
    }

    override fun getDependencyMapping(): Map<String, String> =
        dependencyMappingRepository.findAll().associate { it.alias to it.componentKey }

    override fun getComponentsCountByBuildSystem(): EnumMap<BuildSystem, Int> {
        log.debug("Get components count by build system")
        val result =
            getComponents()
                .filterNot { it.isArchived() }
                .fold(EnumMap<BuildSystem, Int>(BuildSystem::class.java)) { acc, component ->
                    val buildSystem = component.getBuildSystem()
                    acc[buildSystem] = acc.getOrDefault(buildSystem, 0) + 1
                    acc
                }
        log.debug("Components count by build system: {}", result)
        return result
    }

    override fun getComponentProductMapping(): Map<String, ProductTypes> =
        componentRepository
            .findAll()
            .filter { it.productType != null }
            .mapNotNull { component ->
                val productType =
                    try {
                        ProductTypes.valueOf(component.productType!!)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                productType?.let { component.componentKey to it }
            }.toMap()

    override fun findComponentsByDockerImages(images: Set<Image>): Set<ComponentImage> {
        val imageNames = images.map { it.name }.toSet()
        return buildImageToComponentMap()
            .filterKeys(imageNames::contains)
            .mapNotNull { (imgName, component) ->
                images.find { it.name == imgName }?.let { requiredImage ->
                    findConfigurationByDockerImage(imgName, requiredImage.tag, component)
                }
            }.toSet()
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private fun getJiraComponentVersionRangesByComponent(component: String): Set<JiraComponentVersionRange> {
        val componentEntity =
            componentRepository.findByComponentKey(component)
                ?: throw NotFoundException("Component '$component' is not found")
        return buildJiraVersionRangesForComponent(componentEntity)
    }

    /**
     * Enumerate `JiraComponentVersionRange` entries for one component by walking
     * the EscrowModule view: each `EscrowModuleConfig` with a populated jira
     * project key becomes one range.
     */
    private fun buildJiraVersionRangesForComponent(component: ComponentEntity): Set<JiraComponentVersionRange> {
        val module = component.toEscrowModule(versionRangeFactory, numericVersionFactory)
        val ranges = mutableSetOf<JiraComponentVersionRange>()
        for (config in module.moduleConfigurations) {
            val jiraComponent = config.jiraConfiguration ?: continue
            if (jiraComponent.projectKey.isNullOrBlank()) continue

            val hotfixEnabled = componentHotfixSupportResolver.isHotFixEnabled(config.vcsSettings)
            val jiraWithHotfix =
                org.octopusden.octopus.releng.dto.JiraComponent(
                    jiraComponent.projectKey,
                    jiraComponent.displayName,
                    jiraComponent.componentVersionFormat,
                    jiraComponent.componentInfo,
                    jiraComponent.isTechnical,
                    hotfixEnabled,
                )
            ranges.add(
                jiraComponentVersionRangeFactory.create(
                    component.componentKey,
                    config.versionRangeString ?: ALL_VERSIONS,
                    jiraWithHotfix,
                    config.distribution,
                    config.vcsSettings ?: VCSSettings.create(null, emptyList()),
                ),
            )
        }
        return ranges
    }

    private fun getJiraComponentVersionsToRanges(
        version: String,
        versionRanges: Set<JiraComponentVersionRange>,
        strict: Boolean = true,
    ): List<Pair<JiraComponentVersion, JiraComponentVersionRange>> {
        val numericVersion = numericVersionFactory.create(version)
        return versionRanges
            .filter { versionRangeFactory.create(it.versionRange).containsVersion(numericVersion) }
            .mapNotNull { versionRange ->
                val component = versionRange.jiraComponentVersion.component
                val vcsSettings = versionRange.vcsSettings
                jiraComponentVersionFormatter
                    .normalizeVersion(
                        component,
                        version,
                        strict,
                        componentHotfixSupportResolver.isHotFixEnabled(vcsSettings),
                    )?.let { cleanVersion ->
                        JiraComponentVersion(
                            ComponentVersion.create(versionRange.componentName, cleanVersion),
                            versionRange.component,
                            jiraComponentVersionFormatter,
                        ) to versionRange
                    }
            }
    }

    private fun getJiraComponentVersionToRangeByComponentAndVersion(
        component: String,
        version: String,
    ): Pair<JiraComponentVersion, JiraComponentVersionRange> {
        val jiraVersionRanges = getJiraComponentVersionRangesByComponent(component)
        val results = getJiraComponentVersionsToRanges(version, jiraVersionRanges, false)
        return when (results.size) {
            1 -> results.first()
            0 -> throw NotFoundException("Version '$version' for component '$component' is not found")
            else -> error("Found ${results.size} configurations for version '$version' of component '$component'")
        }
    }

    /**
     * Entity-accepting variant of [getJiraComponentVersionToRangeByComponentAndVersion] —
     * builds the ranges from an already-loaded [ComponentEntity] (no `findByComponentKey`
     * reload).
     */
    private fun getJiraComponentVersionToRangeByComponentAndVersion(
        component: ComponentEntity,
        version: String,
    ): Pair<JiraComponentVersion, JiraComponentVersionRange> {
        val jiraVersionRanges = buildJiraVersionRangesForComponent(component)
        val results = getJiraComponentVersionsToRanges(version, jiraVersionRanges, false)
        return when (results.size) {
            1 -> results.first()
            0 -> throw NotFoundException("Version '$version' for component '${component.componentKey}' is not found")
            else ->
                error(
                    "Found ${results.size} configurations for version '$version' of component '${component.componentKey}'",
                )
        }
    }

    private fun getJiraComponentVersionToRangeByProjectAndVersion(
        projectKey: String,
        version: String,
    ): Pair<JiraComponentVersion, JiraComponentVersionRange> {
        val jiraVersionRanges = getJiraComponentVersionRangesByProject(projectKey)
        val results = getJiraComponentVersionsToRanges(version, jiraVersionRanges)
        return when (results.size) {
            1 -> results.first()
            0 -> throw NotFoundException("Version '$version' for project '$projectKey' is not found")
            else -> error("Found ${results.size} configurations for version '$version' of project '$projectKey'")
        }
    }

    private fun findComponentByArtifactOrNull(artifact: ArtifactDependency): VersionedComponent? =
        matchArtifact(artifact, loadPerComponentArtifactParameters())

    /** Snapshot every component's per-range ownership configs (ALL mappings) for reverse lookup. */
    private fun loadPerComponentArtifactParameters(): List<Pair<String, List<Pair<String, ComponentArtifactConfiguration>>>> =
        componentRepository.findAll().map { it.componentKey to ownershipConfigsByRange(it) }

    /**
     * MIG-039 + MIG-023: resolve an artifact to a component by mirroring V1's
     * `EscrowModuleConfigMatcher` per config — for each component, a version range whose EFFECTIVE
     * `(groupPattern, artifactPattern)` matches the artifact AND that contains the artifact
     * version (reusing the same marker-aware per-range patterns as `/maven-artifacts`,
     * [mavenArtifactParametersFor]), so per-range `artifactId` overrides resolve correctly and an
     * archived/old out-of-range range no longer pollutes resolution.
     *
     * All in-range matches are then ranked by artifact-pattern specificity so a more specific
     * (exact / version-aware) mapping beats a generic wildcard/`|`-union one regardless of
     * `findAll()` order — MIG-023 requires the specific match to win when both apply. (V1 treats
     * >1 as a conflict; a batch endpoint instead picks the most specific rather than failing the
     * whole request.)
     */
    private fun matchArtifact(
        artifact: ArtifactDependency,
        perComponent: List<Pair<String, List<Pair<String, ComponentArtifactConfiguration>>>>,
    ): VersionedComponent? {
        val best =
            perComponent
                .flatMap { (componentKey, perRange) ->
                    perRange.mapNotNull { (rangeKey, cfg) ->
                        if (cfg.groupPattern.isNotBlank() &&
                            cfg.artifactPattern.isNotBlank() &&
                            MavenArtifactMatcher.groupIdMatches(artifact.group, cfg.groupPattern) &&
                            MavenArtifactMatcher.artifactIdMatches(artifact.name, cfg.artifactPattern) &&
                            versionInRange(rangeKey, artifact.version)
                        ) {
                            componentKey to cfg.artifactPattern
                        } else {
                            null
                        }
                    }
                }
                .maxWithOrNull(
                    compareBy(
                        { artifactSpecificity(it.second, artifact.name) },
                        { it.second.length },
                    ),
                ) ?: return null
        return VersionedComponent(best.first, null, artifact.version, "")
    }

    /**
     * MIG-023: higher = more specific. An exact artifactId beats a multi-token (`|`/`,`) union,
     * which beats a catch-all. Used to prefer a specific mapping over a generic one when both match
     * the same artifact in range.
     */
    private fun artifactSpecificity(artifactPattern: String, artifactName: String): Int =
        when {
            artifactPattern == artifactName -> 3
            isCatchAllArtifactPattern(artifactPattern) -> 0
            artifactPattern.contains("|") || artifactPattern.contains(",") -> 1
            else -> 2
        }

    /**
     * MIG-023: a "catch-all" artifact pattern — literal `*`, or the inherited default ANY_ARTIFACT
     * regex (`.*`, `[\w-\.]+`, `[\w-]+`) the importer writes verbatim for components without an
     * explicit `artifactId` — must rank BELOW any concrete mapping. Detected behaviourally: a
     * pattern that matches an arbitrary probe id matches essentially anything, so it is a catch-all.
     *
     * The probe is intentionally pure lowercase letters — NO dot, dash, digit or underscore — so it
     * is matched by every `[\w…]`-family default form (`[\w-]+`, `[\w-\.]+`, `.*`, `\w+`, `[a-z]+`).
     * A probe containing a dot would miss the dot-less `[\w-]+` default; a probe with a dash would
     * miss a `\w+` default. Concrete names and `|`/`,` unions never match it.
     */
    private fun isCatchAllArtifactPattern(artifactPattern: String): Boolean =
        artifactPattern == "*" ||
            MavenArtifactMatcher.artifactIdMatches("xanyartifactprobe", artifactPattern)

    /**
     * MIG-039: true iff [versionString] is contained in [rangeString], using the same
     * version-range semantics V1's [org.octopusden.octopus.escrow.configuration.validation.EscrowModuleConfigMatcher]
     * applies. Unparseable range/version → false (no match) rather than throwing.
     */
    private fun versionInRange(rangeString: String, versionString: String): Boolean =
        runCatching {
            versionRangeFactory.create(rangeString)
                .containsVersion(numericVersionFactory.create(versionString))
        }.getOrDefault(false)

    /**
     * Map docker image name → the already-loaded [ComponentEntity] that declares it.
     * Carrying the entity (not just its key) lets [findConfigurationByDockerImage]
     * resolve without a per-image `findByComponentKey` reload; with `@BatchSize` on the
     * walked collections, the single `findAll()` here plus the batched child loads cover
     * the whole `find-by-docker-images` request in a bounded number of queries.
     */
    private fun buildImageToComponentMap(): Map<String, ComponentEntity> {
        val result = mutableMapOf<String, ComponentEntity>()
        for (component in componentRepository.findAll()) {
            for (configuration in component.configurations) {
                for (image in configuration.dockerImages) {
                    result[image.imageName] = component
                }
            }
        }
        return result
    }

    private fun findConfigurationByDockerImage(
        imageName: String,
        imageTag: String,
        component: ComponentEntity,
    ): ComponentImage? {
        val versionString =
            try {
                getJiraComponentVersion(component, imageTag).version
            } catch (_: NotFoundException) {
                return null
            }
        // MIG-040: resolve via getResolvedComponentDefinition so the distribution version
        // substitution (EscrowConfigurationLoader.calculateDistribution) is applied — exactly as
        // the /versions/{v}/distribution endpoint does. A plain toResolvedEscrowModuleConfig leaves
        // docker() WITHOUT the version tag (e.g. "img" instead of "img:4.0.427"), so the
        // "$imageName:$imageTag" membership check never matched and find-by-docker-images returned
        // empty where the V1 baseline (whose escrow model is version-substituted) returns the image.
        val config = getResolvedComponentDefinition(component, versionString) ?: return null
        return config.distribution?.let { dist ->
            if (dist.docker()?.split(',')?.contains("$imageName:$imageTag") == true) {
                ComponentImage(component.componentKey, versionString, Image(imageName, imageTag))
            } else {
                null
            }
        }
    }

    private fun EscrowModule.getBuildSystem(): BuildSystem =
        moduleConfigurations.firstOrNull()?.buildSystem?.toDTO() ?: BuildSystem.NOT_SUPPORTED

    private fun EscrowModule.isArchived(): Boolean {
        val moduleConfig = moduleConfigurations.firstOrNull() ?: return false
        return moduleConfig.archived ||
            (moduleConfig.componentDisplayName?.endsWith("ARCHIVED", ignoreCase = true) ?: false)
    }

    private fun org.octopusden.octopus.escrow.BuildSystem.toDTO(): BuildSystem =
        when (this) {
            org.octopusden.octopus.escrow.BuildSystem.BS2_0 -> BuildSystem.BS2_0
            org.octopusden.octopus.escrow.BuildSystem.MAVEN -> BuildSystem.MAVEN
            org.octopusden.octopus.escrow.BuildSystem.ECLIPSE_MAVEN -> BuildSystem.ECLIPSE_MAVEN
            org.octopusden.octopus.escrow.BuildSystem.GRADLE -> BuildSystem.GRADLE
            org.octopusden.octopus.escrow.BuildSystem.WHISKEY -> BuildSystem.WHISKEY
            org.octopusden.octopus.escrow.BuildSystem.PROVIDED -> BuildSystem.PROVIDED
            org.octopusden.octopus.escrow.BuildSystem.ESCROW_NOT_SUPPORTED -> BuildSystem.NOT_SUPPORTED
            org.octopusden.octopus.escrow.BuildSystem.ESCROW_PROVIDED_MANUALLY -> BuildSystem.PROVIDED
            org.octopusden.octopus.escrow.BuildSystem.GOLANG -> BuildSystem.GOLANG
            org.octopusden.octopus.escrow.BuildSystem.IN_CONTAINER -> BuildSystem.IN_CONTAINER
        }

    /**
     * Null out `config.escrow` when ALL meaningful escrow fields are absent.
     *
     * `EntityMappers.buildEscrowModuleConfig` always sets `config.escrow` to a
     * non-null anonymous `Escrow` object (because `toEscrowApi()` is
     * unconditional). For components that have no escrow data in the DB
     * (all escrow columns are null), this produces an empty EscrowDTO that
     * the controller serializes as `escrow=EscrowDTO(buildTask=null, ...)`.
     *
     * The Git-path resolver returns `null` for such components, so we match
     * that behaviour here: if none of the meaningful fields carry a real value
     * the escrow object is treated as absent.
     *
     * NOTE: `isReusable()` is intentionally excluded from the emptiness check
     * because the DB column is nullable (null means "not specified") but the
     * `Escrow` interface method returns a primitive `boolean` (defaults to
     * `false`), making `false` indistinguishable from "not specified" at the
     * model level.  All other fields can be checked for meaningful content.
     */
    private fun EscrowModuleConfig.nullifyEmptyEscrow(): EscrowModuleConfig {
        val e: Escrow = escrow ?: return this
        val meaningfulEscrow =
            e.buildTask != null ||
                e.providedDependencies.isNotEmpty() ||
                e.diskSpaceRequirement.isPresent ||
                e.additionalSources.isNotEmpty() ||
                e.generation.isPresent
        if (!meaningfulEscrow) {
            escrow = null
        }
        return this
    }

    /** Apply [nullifyEmptyEscrow] to every config in the module. */
    private fun EscrowModule.nullifyEmptyEscrow(): EscrowModule {
        moduleConfigurations.forEach { it.nullifyEmptyEscrow() }
        return this
    }

    companion object {
        private val log = LoggerFactory.getLogger(DatabaseComponentRegistryResolver::class.java)

        /** Must match EscrowConfigurationLoader.ALL_VERSIONS = "(,0),[0,)" */
        @Suppress("VariableNaming")
        private const val ALL_VERSIONS = "(,0),[0,)"

        /**
         * SoT literal for `ComponentConfigurationEntity.rowType`. Matches the
         * String constants used throughout `EntityMappers`. The entity field is
         * a `String` — there is no shared enum to reference.
         */
        private const val ROW_TYPE_MARKER = "MARKER"
    }
}
