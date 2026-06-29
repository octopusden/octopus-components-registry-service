@file:Suppress("TooManyFunctions")

package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.api.beans.OdbcToolBean
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTDDbProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTDProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.OracleDatabaseEditions
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionFileUrlArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionPackageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.ComponentVersionFormat
import org.octopusden.releng.versions.IVersionInfo
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRange
import org.octopusden.releng.versions.VersionRangeFactory

/** Must match EscrowConfigurationLoader.ALL_VERSIONS = "(,0),[0,)" */
internal const val ALL_VERSIONS: String = "(,0),[0,)"

/**
 * Marker names for child-collection replacement overrides (see schema-spec.md §3.3).
 */
internal object MarkerAttributes {
    const val VCS_SETTINGS: String = "vcs.settings"
    const val DISTRIBUTION_MAVEN: String = "distribution.maven"
    const val DISTRIBUTION_FILE_URL: String = "distribution.fileUrl"
    const val DISTRIBUTION_DOCKER: String = "distribution.docker"
    const val DISTRIBUTION_PACKAGES: String = "distribution.packages"
    const val BUILD_REQUIRED_TOOLS: String = "build.requiredTools"
    const val BUILD_TOOLS: String = "build.buildTools"

    /**
     * MIG-047: synthetic per-range `groupId`/`artifactId` marker.
     *
     * Used when the DSL sets `groupId`/`artifactId` per range WITHOUT an
     * explicit `distribution { gav = … }` block.  Unlike [DISTRIBUTION_MAVEN]
     * (which feeds into `config.distribution.GAV()` and therefore affects ALL
     * callers of the `EscrowModuleConfig` distribution field including
     * `getAllJiraComponentVersionRanges`), this marker is intentionally NOT
     * registered in [ALL] and is NOT picked by [pickMarkerChildren] inside
     * `buildEscrowModuleConfig`.  Only `getMavenArtifactParameters` reads it.
     */
    const val GROUP_ARTIFACT_PATTERN: String = "group-artifact-pattern"

    val ALL: Set<String> =
        setOf(
            VCS_SETTINGS,
            DISTRIBUTION_MAVEN,
            DISTRIBUTION_FILE_URL,
            DISTRIBUTION_DOCKER,
            DISTRIBUTION_PACKAGES,
            BUILD_REQUIRED_TOOLS,
            BUILD_TOOLS,
        )
}

// ============================================================
// Public API: DB → Domain
// ============================================================

/**
 * Enumerate `EscrowModule` view from a fully-loaded `ComponentEntity` (with
 * `configurations` and all child collections accessible within the Hibernate
 * session). Produces one `EscrowModuleConfig` per distinct version range that
 * appears across base + override rows.
 *
 * The base row's version range is always emitted first, followed by any override
 * range strings that are not already included. For version-range-only components
 * (`isSyntheticBase = true`), the base row holds the first explicit version range
 * from the DSL — this range must be emitted so that version resolution finds it.
 * Downstream consumers always see at least one `EscrowModuleConfig`.
 */
fun ComponentEntity.toEscrowModule(
    versionRangeFactory: VersionRangeFactory,
    numericVersionFactory: NumericVersionFactory,
): EscrowModule {
    val module = EscrowModule()
    module.moduleName = this.componentKey

    // Sort configurations deterministically before enumeration. `@OneToMany`
    // collections have no order guarantee; on Postgres they come back in heap-
    // scan order, which is non-deterministic and not aligned with DSL declaration
    // order. For a synthetic-base component with multiple overrides (BASE range
    // is suppressed below), `enumeratedRanges` builds from
    // `overrides.map { versionRange }.distinct()` in iteration order — and a
    // wrong first range silently leaks into `moduleConfigurations[0]`, which
    // `BaseComponentController.createComponent` uses to populate the wire DTO.
    //
    // V1 reference iterates the loader's parse list in DSL declaration order;
    // align v3 to that by sorting on `(rowType != "BASE", createdAt, id)`. The
    // `@CreationTimestamp` column is monotonically populated by `ImportServiceImpl`
    // as it persists rows top-to-bottom through the DSL, so `createdAt` is an
    // accurate proxy for DSL position. The `rowType != "BASE"` leading key keeps
    // BASE at index 0 of the sorted list (the subsequent `firstOrNull { rowType
    // == "BASE" }` still works, but the consistent ordering makes the override
    // enumeration deterministic regardless of heap-scan or test-fixture insertion
    // order). `id` is the deterministic tiebreaker when createdAt collides.
    val configs = this.configurations.sortedWith(
        compareBy(
            { it.rowType != "BASE" },
            { it.createdAt ?: java.time.Instant.MIN },
            { it.id?.toString() ?: "" },
        ),
    )
    val base =
        configs.firstOrNull { it.rowType == "BASE" }
            ?: return module
    // "non-base" here covers SCALAR_OVERRIDE, MARKER, and RANGE_PRESENCE. The
    // presence rows participate in enumeration (their `versionRange` adds an
    // entry to the distinct list) but `resolveForRange` filters them out
    // before applying overrides.
    val overrides = configs.filter { it.rowType != "BASE" }

    // Per schema-spec.md §3.4 (MIG-029): a synthetic-base row exists only as a
    // schema-required anchor for a version-range-only component (one with no
    // shared scalars across its ranges). When overrides exist, the base range
    // (which is `(,)` by convention for synthetic bases) is a placeholder and
    // must NOT be enumerated as a view of its own. For non-synthetic bases the
    // base range IS the default view and must be enumerated.
    val enumeratedRanges = mutableListOf<String>()
    if (!(base.isSyntheticBase && overrides.isNotEmpty())) {
        enumeratedRanges += base.versionRange
    }
    overrides
        .map { it.versionRange }
        .distinct()
        .filter { it !in enumeratedRanges }
        .forEach { enumeratedRanges += it }

    for (range in enumeratedRanges) {
        val resolved =
            this.resolveForRange(
                range = range,
                base = base,
                overrides = overrides,
                versionRangeFactory = versionRangeFactory,
                numericVersionFactory = numericVersionFactory,
            )
        module.moduleConfigurations.add(resolved)
    }

    return module
}

/**
 * Resolve a single `EscrowModuleConfig` for the given version. Returns `null`
 * if `version` cannot be parsed or if no override matches AND the only base is
 * synthetic with no fallback semantics required by caller — current callers
 * always want the synthetic base as fallback, so this returns it.
 *
 * The algorithm follows schema-spec.md §3.4:
 *   1. Start with base scalars.
 *   2. For each scalar override whose range contains `version`, overwrite the
 *      matching scalar field on the result.
 *   3. For each marker override whose range contains `version`, replace the
 *      corresponding child collection (full replacement).
 */
fun ComponentEntity.toResolvedEscrowModuleConfig(
    version: String,
    versionRangeFactory: VersionRangeFactory,
    numericVersionFactory: NumericVersionFactory,
): EscrowModuleConfig? {
    val configs = this.configurations.toList()
    val base = configs.firstOrNull { it.rowType == "BASE" } ?: return null
    val overrides = configs.filter { it.rowType == "SCALAR_OVERRIDE" || it.rowType == "MARKER" }

    val numericVersion =
        try {
            numericVersionFactory.create(version)
        } catch (_: Exception) {
            return null
        }

    // MIG-042: mirror V1 EscrowConfigurationLoader.resolveComponentConfiguration —
    // a version outside EVERY configured range resolves to NO configuration (the
    // controller renders 404). The component's effective range is:
    //  • ALL_VERSIONS base — everything; skip the gate (the compound "(,0),[0,)"
    //    union is also not parseable by VersionRangeFactory.containsVersion);
    //  • otherwise — the UNION of the base block's range and every override
    //    row's range, REGARDLESS of the synthetic flag: a NON-synthetic
    //    component (one with top-level scalars) can still declare many DSL
    //    range blocks, and the BASE row carries only the FIRST block — gating
    //    on it alone 404'd every version covered by a later block (59 NEW on
    //    the first gate iteration). A version in a GAP between blocks (e.g.
    //    [11,12.1) + [12.2,) queried with 12.1.x) is out of the union, exactly
    //    like V1 (compat cluster A: 404→200 over-resolution). Unparseable or
    //    blank ranges count as containing — conservative, never a false 404.
    if (base.versionRange != ALL_VERSIONS) {
        val containsVersion = { range: String? ->
            range.isNullOrBlank() ||
                range == ALL_VERSIONS ||
                try {
                    versionRangeFactory.create(range).containsVersion(numericVersion)
                } catch (_: Exception) {
                    true
                }
        }
        // Every non-BASE row participates in the union — including RANGE_PRESENCE
        // rows: an EMPTY DSL block ("[1.1,2.0)" {}) persists as a presence row
        // that carries no overrides but still proves the version is configured
        // (V1 serves base-inherited data there; gating presence-covered versions
        // out produced the second wave of 59 NEW on the full gate).
        val inEffectiveRange =
            containsVersion(base.versionRange) ||
                configs.any { it.rowType != "BASE" && containsVersion(it.versionRange) }
        if (!inEffectiveRange) return null
    }

    val matchingOverrides =
        overrides.filter { override ->
            try {
                versionRangeFactory.create(override.versionRange).containsVersion(numericVersion)
            } catch (_: Exception) {
                false
            }
        }

    // Effective ownership range for this version: the narrowest per-range ownership override whose
    // range contains the version REPLACES the base; else the base ALL_VERSIONS mappings. Without this
    // the resolved DTO returned base ownership at a version an override owns (disagreeing with
    // ComponentCodeRenderer.renderResolved). Ranges are disjoint by invariant; sorted for determinism.
    val ownershipRange =
        this.artifactMappings.map { it.versionRange }.filter { it != ALL_VERSIONS }.distinct().sorted()
            .firstOrNull { range ->
                try {
                    versionRangeFactory.create(range).containsVersion(numericVersion)
                } catch (_: Exception) {
                    false
                }
            } ?: base.versionRange

    return buildEscrowModuleConfig(
        component = this,
        base = base,
        scalarOverrides = matchingOverrides.filter { it.rowType == "SCALAR_OVERRIDE" },
        markerOverrides = matchingOverrides.filter { it.rowType == "MARKER" },
        versionRange = base.versionRange,
        ownershipRange = ownershipRange,
    )
}

// ============================================================
// Internal: resolve a single range view
// ============================================================

private fun ComponentEntity.resolveForRange(
    range: String,
    base: ComponentConfigurationEntity,
    overrides: List<ComponentConfigurationEntity>,
    versionRangeFactory: VersionRangeFactory,
    numericVersionFactory: NumericVersionFactory,
): EscrowModuleConfig {
    // For enumeration purposes, an override applies to `range` when its own
    // range CONTAINS `range` (child is a subset of parent). Equality is the
    // common case (overrides are keyed by their own range); strict containment
    // matters when a broader override range covers a narrower enumeration view
    // (e.g. an override on [1.0,3.0) projected onto an enumerated [1.0,2.0)).
    val scalarOverrides =
        overrides.filter {
            it.rowType == "SCALAR_OVERRIDE" &&
                rangeApplies(
                    parentRange = it.versionRange,
                    childRange = range,
                    versionRangeFactory = versionRangeFactory,
                    numericVersionFactory = numericVersionFactory,
                )
        }
    val markerOverrides =
        overrides.filter {
            it.rowType == "MARKER" &&
                rangeApplies(
                    parentRange = it.versionRange,
                    childRange = range,
                    versionRangeFactory = versionRangeFactory,
                    numericVersionFactory = numericVersionFactory,
                )
        }

    return buildEscrowModuleConfig(
        component = this,
        base = base,
        scalarOverrides = scalarOverrides,
        markerOverrides = markerOverrides,
        versionRange = range,
    )
}

/**
 * True when an override row keyed by [parentRange] should apply to the enumeration view
 * [childRange] — i.e. when child is a subset of parent (range containment).
 *
 * TD-010 (docs/registry/tech-debt/010-range-applies-containment.md). The version-range library
 * exposes only point-in-range [VersionRange.containsVersion], not containsRange, so containment is
 * approximated by a sample-points heuristic: sample the child's endpoints (closed bound inclusive;
 * open finite bound epsilon-shifted just inside the interval) plus a fixed number of interior probes
 * across the child interval, and return true iff EVERY sample lies in the parent. Equality
 * short-circuits to true.
 *
 * One-sided-unbounded children ("[2.0,)" open-upper, "(,5.0)" open-lower — the everyday
 * "from this version onward" / "up to this version" shapes) ARE supported: the open-ended side is
 * probed via a sentinel (a huge tail version for open-upper, the zero version for open-lower), so an
 * open-upper child is contained iff its floor is in the parent AND the parent is itself open-upper.
 * A parent that fails to parse (the fully-unbounded "(,)" — both-sides-open is rejected by the
 * factory, deferred to TD-010-b) falls back to string equality, keeping the result conservative
 * (a missed override, never a wrong one).
 *
 * The heuristic is exact for the bounded and one-sided-unbounded cases in the TD-010 matrix against a
 * single-interval or open-left-union parent. The only theoretical false positive is a parent gap
 * narrower than the probe spacing sitting between two samples (e.g. a multi-segment override row with
 * a far-out internal gap); this does not occur for the integer-component, single-range overrides this
 * registry uses, and is noted as a deferred approximation in the TD-010 doc.
 */
internal fun rangeApplies(
    parentRange: String,
    childRange: String,
    versionRangeFactory: VersionRangeFactory,
    numericVersionFactory: NumericVersionFactory,
): Boolean {
    if (parentRange == childRange) return true

    val parent =
        try {
            versionRangeFactory.create(parentRange)
        } catch (_: Exception) {
            // Unparseable parent (e.g. unbounded "(,)", TD-010-b): fall back to the conservative
            // equality test handled above — reaching here means it was not equal, so no match.
            return false
        }

    val bounds =
        try {
            parseSingleInterval(childRange)
        } catch (_: Exception) {
            // Unparseable / fully-unbounded child: cannot enumerate samples, stay conservative.
            return false
        }

    // Structural guard: a child that runs to +inf (open upper) can only be contained in a parent that
    // ALSO runs to +inf. A bounded parent never contains it, however high its finite upper bound is —
    // so this must be decided structurally, not by probing a finite +inf sentinel against the parent
    // (a finite sentinel below the parent's finite upper would otherwise yield a false positive).
    if (bounds.upper == null && !isOpenUpper(parentRange)) return false

    val samples = childRangeSamples(bounds, numericVersionFactory)
    if (samples.isEmpty()) return false

    return samples.all { sample ->
        try {
            parent.containsVersion(sample)
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * True iff [range]'s trailing segment has an empty (open-ended) upper bound, i.e. the range runs to
 * +inf — "[1.0,)", "(,0),[1.0,)". False for any finite or closed upper bound ("[1.0,2.0)",
 * "[1.0,10000000.0)") and for a hard single version ("[1.0]"). Used by [rangeApplies] so an open-upper
 * child is only ever matched against an open-upper parent.
 */
private fun isOpenUpper(range: String): Boolean {
    val trimmed = range.trim()
    if (trimmed.isEmpty() || trimmed.last() != ')') return false
    val lastComma = trimmed.lastIndexOf(',')
    return lastComma in 0 until trimmed.length - 1 &&
        trimmed.substring(lastComma + 1, trimmed.length - 1).isBlank()
}

/** Number of interior probe points sampled strictly inside the child interval. */
private const val INTERIOR_PROBE_COUNT = 8

/** Smallest representable version, standing in for a child's unbounded LOWER side ("(,X)"). */
private const val ZERO_VERSION = "0.0"

/**
 * A high probe version standing in for a point well inside a child's open UPPER tail ("[X,)"). It is
 * only ever evaluated against a parent that [rangeApplies] has ALREADY confirmed is itself open-upper
 * (the bounded-parent case is rejected by the structural guard before sampling), so this value never
 * has to out-rank a finite parent upper bound — it just confirms the open-upper parent's floor sits
 * at or below the child's tail. It is not an encoding of +inf.
 */
private const val HUGE_TAIL_VERSION = "9999999.0"

/**
 * Build the set of probe versions for the already-parsed child [bounds] used by the containment
 * heuristic: the lower and upper endpoints (closed bound inclusive; open finite bound shifted just
 * inside) plus interior probes across the interval. One-sided-unbounded children are supported by
 * substituting a sentinel for the open-ended side — [ZERO_VERSION] for an unbounded lower,
 * [HUGE_TAIL_VERSION] for an unbounded upper — so a child like "[2.0,)" probes its 2.0 floor AND a
 * point inside the tail. An open-upper child is only sampled at all once [rangeApplies] has confirmed
 * the parent is open-upper (see the structural guard there); a bounded parent never reaches here with
 * an open-upper child.
 */
private fun childRangeSamples(
    bounds: IntervalBounds,
    numericVersionFactory: NumericVersionFactory,
): List<IVersionInfo> {
    val samples = mutableListOf<IVersionInfo>()

    // Low edge for interior probing + the lower endpoint sample.
    val lowerEdge: IVersionInfo
    if (bounds.lower == null) {
        // Unbounded below: the child reaches down to the smallest representable version.
        lowerEdge = numericVersionFactory.create(ZERO_VERSION)
        samples += lowerEdge
    } else {
        lowerEdge = numericVersionFactory.create(bounds.lower)
        // Closed bound is part of the interval; open bound is excluded, so probe just above it
        // (epsilon-shift) to represent the smallest point that IS in the child.
        samples += if (bounds.includeLower) lowerEdge else epsilonAbove(bounds.lower, numericVersionFactory)
    }

    // High edge for interior probing + (when in the interval) the upper endpoint sample.
    val upperEdge: IVersionInfo
    if (bounds.upper == null) {
        // Unbounded above: a high probe stands in for a point inside the tail and IS sampled directly.
        // We only reach here for an open-upper PARENT (the bounded-parent case is rejected by the
        // structural guard in rangeApplies), so this probe just confirms the open-upper parent's floor
        // is at/below the tail — it is not racing a finite parent upper bound.
        upperEdge = numericVersionFactory.create(HUGE_TAIL_VERSION)
        samples += upperEdge
    } else {
        upperEdge = numericVersionFactory.create(bounds.upper)
        // Only a closed finite upper bound is part of the interval; an open finite upper bound's
        // supremum is excluded — the near-upper interior probe covers the approach to it.
        if (bounds.includeUpper) {
            samples += upperEdge
        }
    }

    // Interior probes: spread across [lowerEdge, upperEdge] so that a child reaching past the parent
    // (or sitting in a union-parent gap) is caught even when both endpoints individually land inside.
    samples += interiorProbes(lowerEdge, upperEdge, numericVersionFactory)
    return samples
}

/**
 * A single version interval parsed from a range string such as "[1.0,2.0)". A null [lower]/[upper]
 * means that side is unbounded (open-ended), e.g. "[1.0,)" → upper == null. An unbounded side is by
 * definition exclusive, so its include-flag is always false.
 */
private data class IntervalBounds(
    val lower: String?,
    val includeLower: Boolean,
    val upper: String?,
    val includeUpper: Boolean,
)

/**
 * Parse a single-interval range string into its bounds: "[1.0,2.0)", "(1.0,3.0)", "[1.0]", and the
 * one-sided-unbounded forms "[1.0,)" / "(,2.0]". Throws for the fully-unbounded "(,)" (both sides
 * empty — deferred to TD-010-b, cannot be sampled), a multi-segment union, or otherwise malformed
 * input, so the caller can fall back to the conservative path.
 */
private fun parseSingleInterval(range: String): IntervalBounds {
    val trimmed = range.trim()
    require(trimmed.length >= 3) { "range too short: $range" }
    val open = trimmed.first()
    val close = trimmed.last()
    require(open == '[' || open == '(') { "bad opening bracket: $range" }
    require(close == ']' || close == ')') { "bad closing bracket: $range" }

    val inner = trimmed.substring(1, trimmed.length - 1)
    if (',' !in inner) {
        // Hard single version "[1.0]" — degenerate closed interval [v, v]. Only the fully-closed
        // bracket form is valid here (matches VersionRangeFactory, which rejects "(1.0)").
        require(open == '[' && close == ']') { "hard version must be fully closed: $range" }
        val v = inner.trim()
        require(v.isNotEmpty()) { "empty hard version: $range" }
        return IntervalBounds(v, true, v, true)
    }
    val parts = inner.split(',')
    require(parts.size == 2) { "multi-segment or malformed range: $range" }
    val lower = parts[0].trim().ifEmpty { null }
    val upper = parts[1].trim().ifEmpty { null }
    // Fully-unbounded "(,)" cannot be sampled (no endpoint to anchor probes) — deferred to TD-010-b.
    require(lower != null || upper != null) { "fully-unbounded range not supported: $range" }
    return IntervalBounds(
        lower = lower,
        includeLower = open == '[' && lower != null,
        upper = upper,
        includeUpper = close == ']' && upper != null,
    )
}

/**
 * A version strictly greater than [version] by the smallest representable amount, used to represent
 * the point just inside an open lower bound. Appends a low-order ".0.0.0.1" tail: trailing zero
 * components compare equal under the factory's padding, so the final "1" makes the value sort
 * immediately above the original.
 */
private fun epsilonAbove(
    version: String,
    numericVersionFactory: NumericVersionFactory,
): IVersionInfo = numericVersionFactory.create("$version.0.0.0.1")

/**
 * A version strictly less than [upper] by the smallest representable amount, used as a near-upper
 * interior probe for open upper bounds. Takes the upper's major and minor and, when minor > 0,
 * drops one minor and appends a large low-order tail ("2.9" → "2.8.999999"); when minor == 0 but
 * major > 0, probes just below the major ("3.0" → "2.999999"). Integer-component versions make this
 * exact. When upper is "0.0" there is no representable version below it in this non-negative domain,
 * so [upper] itself is returned — `offerIfInside`'s strict `< upper` check then drops it (rather than
 * fabricating a "-1.999999" string, which the factory would misread via the `-` separator).
 */
private fun justBelow(
    upper: IVersionInfo,
    numericVersionFactory: NumericVersionFactory,
): IVersionInfo {
    val major = upper.getMajor()
    val minor = upper.getMinor()
    return when {
        minor > 0 -> numericVersionFactory.create("$major.${minor - 1}.$NEAR_BOUNDARY_MINOR")
        major > 0 -> numericVersionFactory.create("${major - 1}.$NEAR_BOUNDARY_MINOR")
        else -> upper
    }
}

/** Large minor index used to probe just below an integer-major boundary (e.g. 2.999999 < 3.0). */
private const val NEAR_BOUNDARY_MINOR = 999_999

/**
 * Interior probe versions strictly between [lower] and [upper]. Combines three families so that any
 * sub-interval where the child escapes the parent contains at least one sample:
 *  - whole-version grid points (2.0, 3.0, …) lying strictly inside the interval;
 *  - sub-major minor steps under each major in the span (covers narrow same-major intervals like
 *    [1.0,2.0) that contain no whole-version grid point);
 *  - a near-upper probe just below the upper bound's major (covers a child that overshoots an open
 *    upper bound between the coarser samples, e.g. [1.5,2.9) escaping parent [1.0,2.5)).
 * Versions in this registry are integer-component, so this set fully covers the interval; the
 * INTERIOR_PROBE_COUNT cap bounds the TOTAL number of probes (across all three families, enforced by
 * `offerIfInside` and every loop guard), not a per-family quota, keeping work bounded for very wide
 * ranges without weakening the matrix cases.
 */
private fun interiorProbes(
    lower: IVersionInfo,
    upper: IVersionInfo,
    numericVersionFactory: NumericVersionFactory,
): List<IVersionInfo> {
    val probes = mutableListOf<IVersionInfo>()
    val lowMajor = lower.getMajor()
    val highMajor = upper.getMajor()

    fun offerIfInside(candidate: IVersionInfo) {
        if (probes.size < INTERIOR_PROBE_COUNT &&
            candidate.compareTo(lower) > 0 &&
            candidate.compareTo(upper) < 0
        ) {
            probes += candidate
        }
    }

    // Near-upper probe FIRST: a version just below the upper bound, catching an open-upper overshoot
    // that sits above every whole-version grid point (e.g. the 2.5..2.9 tail of child [1.5,2.9)
    // against a parent that stops at 2.5). It is the load-bearing sample for that family, so it is
    // offered before the grid loop — otherwise a child spanning many majors could fill the probe cap
    // with grid points and silently drop it, opening a false-positive for a wide overshooting child.
    offerIfInside(justBelow(upper, numericVersionFactory))
    // Whole-version grid points strictly inside the interval (e.g. 2.0 inside [1.5,2.5)).
    var major = lowMajor + 1
    while (major <= highMajor && probes.size < INTERIOR_PROBE_COUNT) {
        offerIfInside(numericVersionFactory.create("$major.0"))
        major++
    }
    // Sub-major minor steps across the span, covering narrow same-major intervals such as [1.0,2.0).
    major = lowMajor
    while (major <= highMajor && probes.size < INTERIOR_PROBE_COUNT) {
        var minor = 1
        while (minor <= INTERIOR_PROBE_COUNT && probes.size < INTERIOR_PROBE_COUNT) {
            offerIfInside(numericVersionFactory.create("$major.$minor"))
            minor++
        }
        major++
    }
    return probes
}

// ============================================================
// Internal: build EscrowModuleConfig from base + overrides
// ============================================================

@Suppress("CyclomaticComplexMethod", "LongParameterList", "LongMethod")
private fun buildEscrowModuleConfig(
    component: ComponentEntity,
    base: ComponentConfigurationEntity,
    scalarOverrides: List<ComponentConfigurationEntity>,
    markerOverrides: List<ComponentConfigurationEntity>,
    versionRange: String,
    // Range used to select the effective ownership mapping. Equals [versionRange] for the per-range
    // enumeration path, but the resolved-single-version path passes the override range that CONTAINS
    // the version (override REPLACES base) — base.versionRange there would ignore an ownership override.
    ownershipRange: String = versionRange,
): EscrowModuleConfig {
    val config = EscrowModuleConfig()
    setField(config, "versionRange", versionRange)

    // Effective scalars: start from base typed columns; for each scalar override
    // row, find its single non-NULL typed column and overlay it.
    val merged = ComponentConfigurationView.from(base)
    for (override in scalarOverrides) {
        merged.applyScalarOverride(override)
    }

    // Build aspect
    setField(config, "buildSystem", merged.buildSystem?.let { safeParseBuildSystem(it) })
    setField(config, "buildFilePath", merged.buildFilePath)
    // EscrowModuleConfig.deprecated is a primitive `boolean` — Java reflection
    // rejects null for primitive fields. A configuration with no override on
    // `build.deprecated` and a NULL base column must collapse to `false`
    // instead of crashing the resolver.
    setField(config, "deprecated", merged.deprecated ?: false)
    val buildParameters = merged.toBuildParameters(base, markerOverrides)
    if (buildParameters != null) {
        setField(config, "buildConfiguration", buildParameters)
    }

    // Escrow aspect
    config.escrow = merged.toEscrowApi()

    // VCS — child collection. Marker override "vcs.settings" replaces base
    // children; otherwise base.vcsEntries is used.
    //
    // `externalRegistry` is per-component scalar — a sibling of the VCS roots,
    // not a child entry. Emit `vcsSettings` whenever either is present so a
    // component declared in DSL as `vcsSettings { externalRegistry = "..." }`
    // with no VCS roots round-trips correctly (the Groovy resolver kept the
    // VCSSettings instance with externalRegistry set and roots empty; v2 was
    // silently dropping it).
    val vcsEntries =
        pickMarkerChildren(
            attribute = MarkerAttributes.VCS_SETTINGS,
            markerOverrides = markerOverrides,
            baseChildren = base.vcsEntries.toList(),
        ) { it.vcsEntries.toList() }
    if (vcsEntries.isNotEmpty() || component.vcsExternalRegistry != null) {
        setField(config, "vcsSettings", vcsEntries.toVCSSettings(component.vcsExternalRegistry))
    }

    // Distribution — composed from four family child collections, each
    // replaceable via its own marker.
    val mavenArtifacts =
        pickMarkerChildren(
            attribute = MarkerAttributes.DISTRIBUTION_MAVEN,
            markerOverrides = markerOverrides,
            baseChildren = base.mavenArtifacts.toList(),
        ) { it.mavenArtifacts.toList() }
    val fileUrlArtifacts =
        pickMarkerChildren(
            attribute = MarkerAttributes.DISTRIBUTION_FILE_URL,
            markerOverrides = markerOverrides,
            baseChildren = base.fileUrlArtifacts.toList(),
        ) { it.fileUrlArtifacts.toList() }
    val dockerImages =
        pickMarkerChildren(
            attribute = MarkerAttributes.DISTRIBUTION_DOCKER,
            markerOverrides = markerOverrides,
            baseChildren = base.dockerImages.toList(),
        ) { it.dockerImages.toList() }
    val packages =
        pickMarkerChildren(
            attribute = MarkerAttributes.DISTRIBUTION_PACKAGES,
            markerOverrides = markerOverrides,
            baseChildren = base.packages.toList(),
        ) { it.packages.toList() }

    val distribution =
        buildDistribution(
            explicit = component.distributionExplicit,
            external = component.distributionExternal,
            mavenArtifacts = mavenArtifacts,
            fileUrlArtifacts = fileUrlArtifacts,
            dockerImages = dockerImages,
            packages = packages,
            securityGroups = component.securityGroups.toList(),
        )
    if (distribution != null) {
        setField(config, "distribution", distribution)
    }

    // Jira aspect — composed from merged config scalars + component-level fields
    val jira = buildJiraComponent(component = component, merged = merged)
    if (jira != null) {
        setField(config, "jiraConfiguration", jira)
    }

    // Component-level (per-component, never per-version). displayName is nullable and stored
    // verbatim from the DSL (no key backfill), so a straight passthrough reproduces the legacy
    // v1/v2/v3 `$.name` byte-for-byte (null stays null).
    setField(config, "componentDisplayName", component.displayName)
    setField(config, "componentOwner", component.componentOwner)
    // Single-value system: write the scalar code (or empty string when null).
    // The legacy DSL field was a CSV; keeping the field name shape but
    // emitting at most one comma-free token preserves backward compat for
    // any v1-v3 consumer that still treats `system` as "the first / only
    // entry of a CSV".
    setField(config, "system", component.systemCode ?: "")
    setField(config, "clientCode", component.clientCode)
    setField(config, "solution", component.solution)
    setField(config, "parentComponent", component.parentComponent?.componentKey)
    setField(config, "archived", component.archived)
    // Legacy/compat join — the SINGLE point that keeps v1/v2/v3 non-breaking:
    // collapse the ordered list back into a comma-string (empty list → null to
    // preserve the previous nullable-scalar behaviour).
    setField(config, "releaseManager", component.releaseManagerUsernames().joinToString(",").ifEmpty { null })
    setField(config, "securityChampion", component.securityChampionUsernames().joinToString(",").ifEmpty { null })
    setField(config, "copyright", component.copyright)
    setField(config, "releasesInDefaultBranch", component.releasesInDefaultBranch)

    val labels = component.labelJunctions.map { it.labelCode }.toSet()
    if (labels.isNotEmpty()) {
        setField(config, "labels", labels)
    }

    config.productType = component.productType?.let { safeParseProductType(it) }

    // Doc — prefer per-major link matching the resolved view's leading version;
    // fall back to the major_version = NULL link, then null.
    val docLink = pickDocLink(component.docLinks.toList(), versionRange)
    if (docLink != null) {
        config.doc =
            org.octopusden.octopus.escrow.model
                .Doc(docLink.docComponentKey, docLink.majorVersion)
    }

    // Artifact ownership — the PRIMARY (lowest sortOrder) effective mapping for this range
    // (override mappings keyed to `versionRange` if present, else the base ALL_VERSIONS
    // mappings) rendered to the legacy (groupIdPattern, artifactIdPattern) pair.
    val mappingsByRange = component.artifactMappings.groupBy { it.versionRange }
    val effectiveMappings = mappingsByRange[ownershipRange] ?: mappingsByRange[ALL_VERSIONS].orEmpty()
    effectiveMappings.minByOrNull { it.sortOrder }?.let { primary ->
        setField(config, "groupIdPattern", primary.groupPattern)
        setField(
            config,
            "artifactIdPattern",
            org.octopusden.octopus.components.registry.server.util.ArtifactOwnershipRendering.renderArtifactPattern(
                org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode.valueOf(primary.artifactIdMode),
                primary.tokens.sortedBy { it.sortOrder }.map { it.artifactPattern },
            ),
        )
    }

    return config
}

private fun <T> pickMarkerChildren(
    attribute: String,
    markerOverrides: List<ComponentConfigurationEntity>,
    baseChildren: List<T>,
    childExtractor: (ComponentConfigurationEntity) -> List<T>,
): List<T> {
    val marker = markerOverrides.firstOrNull { it.overriddenAttribute == attribute }
    return if (marker != null) childExtractor(marker) else baseChildren
}

// ============================================================
// Internal: scalar merge view (a mutable copy of base scalars)
// ============================================================

/**
 * Mutable scratch buffer holding the merged scalar values that will populate
 * the resulting `EscrowModuleConfig`. Lifecycle is scoped to one resolve call.
 */
internal class ComponentConfigurationView {
    var buildSystem: String? = null
    var javaVersion: String? = null
    var mavenVersion: String? = null
    var gradleVersion: String? = null
    var buildFilePath: String? = null
    var deprecated: Boolean? = null
    var requiredProject: Boolean? = null
    var projectVersion: String? = null
    var systemProperties: String? = null
    var buildTasks: String? = null
    var escrowBuildTask: String? = null

    var escrowProvidedDependencies: String? = null
    var escrowReusable: Boolean? = null
    var escrowGeneration: String? = null
    var escrowDiskSpace: String? = null
    var escrowAdditionalSources: String? = null
    var escrowGradleIncludeConfigurations: String? = null
    var escrowGradleExcludeConfigurations: String? = null
    var escrowGradleIncludeTestConfigurations: Boolean? = null

    var jiraProjectKey: String? = null
    var jiraTechnical: Boolean? = null
    var jiraMajorVersionFormat: String? = null
    var jiraReleaseVersionFormat: String? = null
    var jiraBuildVersionFormat: String? = null
    var jiraLineVersionFormat: String? = null
    var jiraVersionPrefix: String? = null
    var jiraVersionFormat: String? = null
    var jiraHotfixVersionFormat: String? = null

    /**
     * Tracks presence (not value) of a per-range SCALAR_OVERRIDE for
     * `jira.hotfixVersionFormat`. Set to `true` only when
     * [applyScalarOverride] processes a row whose `overriddenAttribute ==
     * "jira.hotfixVersionFormat"`. Required because this field has a
     * per-component fallback layer (`components.jira_hotfix_version_format`)
     * that the resolver consults when no per-range override is present —
     * without the presence flag, an explicit null-clear (override row with
     * NULL value) is silently undone by the Kotlin `?:` fallback chain in
     * [buildJiraComponent], defeating the import-only null-clear contract.
     *
     * Sibling per-range scalars (e.g. `jiraReleaseVersionFormat`) do not
     * need this flag — their resolver fallback is a hardcoded sentinel
     * (`"$major.$minor"` etc.), not a per-component column, so a null
     * value on the merged view is itself the null-clear result.
     */
    var jiraHotfixVersionFormatOverridden: Boolean = false

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun applyScalarOverride(override: ComponentConfigurationEntity) {
        // Unconditional assignment: the `overriddenAttribute` discriminator is the source of truth.
        // A null column value means "explicitly clear this scalar for this range" (import-only
        // null-clear pattern). Using `?.let` would silently skip null and let the base value bleed
        // through — that was the root cause of bugs F (bug-F-component.buildFilePath) and G (bug-G-component.versionPrefix).
        when (override.overriddenAttribute) {
            "build.buildSystem" -> buildSystem = override.buildSystem
            "build.javaVersion" -> javaVersion = override.javaVersion
            "build.mavenVersion" -> mavenVersion = override.mavenVersion
            "build.gradleVersion" -> gradleVersion = override.gradleVersion
            "build.buildFilePath" -> buildFilePath = override.buildFilePath
            "build.deprecated" -> deprecated = override.deprecated
            "build.requiredProject" -> requiredProject = override.requiredProject
            "build.projectVersion" -> projectVersion = override.projectVersion
            "build.systemProperties" -> systemProperties = override.systemProperties
            "build.buildTasks" -> buildTasks = override.buildTasks

            "escrow.buildTask" -> escrowBuildTask = override.escrowBuildTask
            "escrow.providedDependencies" -> escrowProvidedDependencies = override.escrowProvidedDependencies
            "escrow.reusable" -> escrowReusable = override.escrowReusable
            "escrow.generation" -> escrowGeneration = override.escrowGeneration
            "escrow.diskSpace" -> escrowDiskSpace = override.escrowDiskSpace
            "escrow.additionalSources" -> escrowAdditionalSources = override.escrowAdditionalSources
            "escrow.gradleIncludeConfigurations" -> escrowGradleIncludeConfigurations = override.escrowGradleIncludeConfigurations
            "escrow.gradleExcludeConfigurations" -> escrowGradleExcludeConfigurations = override.escrowGradleExcludeConfigurations
            "escrow.gradleIncludeTestConfigurations" -> escrowGradleIncludeTestConfigurations = override.escrowGradleIncludeTestConfigurations

            "jira.projectKey" -> jiraProjectKey = override.jiraProjectKey
            "jira.technical" -> jiraTechnical = override.jiraTechnical
            "jira.majorVersionFormat" -> jiraMajorVersionFormat = override.jiraMajorVersionFormat
            "jira.releaseVersionFormat" -> jiraReleaseVersionFormat = override.jiraReleaseVersionFormat
            "jira.buildVersionFormat" -> jiraBuildVersionFormat = override.jiraBuildVersionFormat
            "jira.lineVersionFormat" -> jiraLineVersionFormat = override.jiraLineVersionFormat
            "jira.versionPrefix" -> jiraVersionPrefix = override.jiraVersionPrefix
            "jira.versionFormat" -> jiraVersionFormat = override.jiraVersionFormat
            "jira.hotfixVersionFormat" -> {
                jiraHotfixVersionFormat = override.jiraHotfixVersionFormat
                // Set presence regardless of value so a null-clear row
                // (NULL on the typed column) is preserved by the resolver
                // instead of being swallowed by the per-component fallback.
                jiraHotfixVersionFormatOverridden = true
            }

            else -> Unit // unknown attribute path; ignore for forward-compat
        }
    }

    fun toBuildParameters(
        base: ComponentConfigurationEntity,
        markerOverrides: List<ComponentConfigurationEntity>,
    ): BuildParameters? {
        // Tools: a `build.requiredTools` marker REPLACES the base junctions for
        // its version range; otherwise the base row's tools propagate. The
        // legacy implementation only consulted the marker and dropped base
        // tools entirely — components whose tools live on the base row would
        // resolve as if no tools were required.
        val toolMarker = markerOverrides.firstOrNull { it.overriddenAttribute == MarkerAttributes.BUILD_REQUIRED_TOOLS }
        val sourceJunctions = toolMarker?.requiredToolJunctions?.toList() ?: base.requiredToolJunctions.toList()
        val tools =
            sourceJunctions.mapNotNull { junction ->
                val tool = junction.tool ?: return@mapNotNull null
                org.octopusden.octopus.escrow.model.Tool(
                    tool.name,
                    tool.escrowEnvVariable,
                    tool.sourceLocation,
                    tool.targetLocation,
                    tool.installScript,
                )
            }
        // Build-tool beans (OracleDatabaseToolBean, PTKProductToolBean, etc.):
        // a `build.buildTools` marker row REPLACES the base row's beans for its
        // version range; otherwise the base row's beans propagate.
        val buildToolBeanEntities =
            pickMarkerChildren(MarkerAttributes.BUILD_TOOLS, markerOverrides, base.buildToolBeans.toList()) {
                it.buildToolBeans.sortedBy { b -> b.sortOrder }
            }
        val buildTools: List<BuildTool> = buildToolBeanEntities.mapNotNull { it.toBuildToolBean() }

        // Early-return must now consider tools and buildTools too: a build aspect
        // with ONLY requiredTools (no java/maven/gradle/buildTasks/etc.) was
        // previously dropped entirely.
        if (javaVersion == null &&
            mavenVersion == null &&
            gradleVersion == null &&
            buildTasks == null &&
            !requiredProject.orFalse() &&
            projectVersion == null &&
            systemProperties == null &&
            tools.isEmpty() &&
            buildTools.isEmpty()
        ) {
            return null
        }
        return BuildParameters.create(
            javaVersion,
            mavenVersion,
            gradleVersion,
            requiredProject.orFalse(),
            projectVersion,
            systemProperties,
            buildTasks,
            tools,
            buildTools,
        )
    }

    fun toEscrowApi(): org.octopusden.octopus.components.registry.api.escrow.Escrow {
        val captured = this
        return object : org.octopusden.octopus.components.registry.api.escrow.Escrow {
            override fun getGradle() = null

            override fun getBuildTask() = captured.escrowBuildTask

            override fun getProvidedDependencies(): Collection<String> =
                captured.escrowProvidedDependencies
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() } ?: emptyList()

            override fun getDiskSpaceRequirement() = java.util.Optional.ofNullable(captured.escrowDiskSpace?.toLongOrNull())

            override fun getAdditionalSources(): Collection<String> =
                captured.escrowAdditionalSources
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() } ?: emptyList()

            override fun isReusable() = captured.escrowReusable ?: false

            override fun getGeneration() =
                java.util.Optional.ofNullable(
                    captured.escrowGeneration?.let {
                        try {
                            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
                                .valueOf(it)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    },
                )
        }
    }

    companion object {
        fun from(base: ComponentConfigurationEntity): ComponentConfigurationView =
            ComponentConfigurationView().apply {
                buildSystem = base.buildSystem
                javaVersion = base.javaVersion
                mavenVersion = base.mavenVersion
                gradleVersion = base.gradleVersion
                buildFilePath = base.buildFilePath
                deprecated = base.deprecated
                requiredProject = base.requiredProject
                projectVersion = base.projectVersion
                systemProperties = base.systemProperties
                buildTasks = base.buildTasks
                escrowBuildTask = base.escrowBuildTask

                escrowProvidedDependencies = base.escrowProvidedDependencies
                escrowReusable = base.escrowReusable
                escrowGeneration = base.escrowGeneration
                escrowDiskSpace = base.escrowDiskSpace
                escrowAdditionalSources = base.escrowAdditionalSources
                escrowGradleIncludeConfigurations = base.escrowGradleIncludeConfigurations
                escrowGradleExcludeConfigurations = base.escrowGradleExcludeConfigurations
                escrowGradleIncludeTestConfigurations = base.escrowGradleIncludeTestConfigurations

                jiraProjectKey = base.jiraProjectKey
                jiraTechnical = base.jiraTechnical
                jiraMajorVersionFormat = base.jiraMajorVersionFormat
                jiraReleaseVersionFormat = base.jiraReleaseVersionFormat
                jiraBuildVersionFormat = base.jiraBuildVersionFormat
                jiraLineVersionFormat = base.jiraLineVersionFormat
                jiraVersionPrefix = base.jiraVersionPrefix
                jiraVersionFormat = base.jiraVersionFormat
                jiraHotfixVersionFormat = base.jiraHotfixVersionFormat
            }
    }
}

private fun Boolean?.orFalse(): Boolean = this == true

// ============================================================
// Internal: VCS / Distribution / Jira builders
// ============================================================

internal fun List<VcsSettingsEntryEntity>.toVCSSettings(externalRegistry: String?): VCSSettings {
    val sorted = this.sortedBy { it.sortOrder }
    val roots =
        sorted.map { entry ->
            VersionControlSystemRoot.create(
                entry.name,
                RepositoryType.valueOf(entry.repositoryType ?: "GIT"),
                entry.vcsPath,
                entry.tag,
                entry.branch,
                entry.hotfixBranch,
            )
        }
    return VCSSettings.create(externalRegistry, roots)
}

@Suppress("LongParameterList")
internal fun buildDistribution(
    explicit: Boolean?,
    external: Boolean?,
    mavenArtifacts: List<DistributionMavenArtifactEntity>,
    fileUrlArtifacts: List<DistributionFileUrlArtifactEntity>,
    dockerImages: List<DistributionDockerImageEntity>,
    packages: List<DistributionPackageEntity>,
    securityGroups: List<DistributionSecurityGroupEntity>,
): Distribution? {
    val gavStr = composeGavCsv(mavenArtifacts, fileUrlArtifacts)
    val dockerStr = composeDockerCsv(dockerImages)
    val debStr = packages.filter { it.packageType == "DEB" }.sortedBy { it.sortOrder }.joinToString(",") { it.packageName }.ifEmpty { null }
    val rpmStr = packages.filter { it.packageType == "RPM" }.sortedBy { it.sortOrder }.joinToString(",") { it.packageName }.ifEmpty { null }

    val secReadGroups =
        securityGroups
            .filter { it.groupType == "read" }
            .joinToString(",") { it.groupName }
    val secGroups = if (secReadGroups.isNotEmpty()) SecurityGroups(secReadGroups) else null

    val anyContent =
        explicit != null ||
            external != null ||
            gavStr != null ||
            dockerStr != null ||
            debStr != null ||
            rpmStr != null ||
            secGroups != null

    if (!anyContent) return null

    return Distribution(explicit ?: true, external ?: false, gavStr, debStr, rpmStr, dockerStr, secGroups)
}

private fun composeGavCsv(
    maven: List<DistributionMavenArtifactEntity>,
    fileUrl: List<DistributionFileUrlArtifactEntity>,
): String? {
    val mavenStr =
        maven.sortedBy { it.sortOrder }.joinToString(",") { e ->
            buildString {
                append(e.groupPattern)
                append(':')
                append(e.artifactPattern)
                if (!e.extension.isNullOrBlank()) {
                    append(':').append(e.extension)
                }
                if (!e.classifier.isNullOrBlank()) {
                    if (e.extension.isNullOrBlank()) append(':') // hold extension slot
                    append(':').append(e.classifier)
                }
            }
        }
    val fileUrlStr =
        fileUrl.sortedBy { it.sortOrder }.joinToString(",") { e ->
            buildString {
                append(e.url)
                if (!e.artifactId.isNullOrBlank()) append("?artifactId=").append(e.artifactId)
                if (!e.classifier.isNullOrBlank()) {
                    append(if (e.artifactId.isNullOrBlank()) "?" else "&")
                    append("classifier=").append(e.classifier)
                }
            }
        }
    val combined = listOf(mavenStr, fileUrlStr).filter { it.isNotEmpty() }.joinToString(",")
    return combined.ifEmpty { null }
}

private fun composeDockerCsv(images: List<DistributionDockerImageEntity>): String? {
    val sorted = images.sortedBy { it.sortOrder }
    val csv =
        sorted.joinToString(",") { e ->
            if (e.flavor.isNullOrBlank()) e.imageName else "${e.imageName}:${e.flavor}"
        }
    return csv.ifEmpty { null }
}

@Suppress("LongMethod")
private fun buildJiraComponent(
    component: ComponentEntity,
    merged: ComponentConfigurationView,
): JiraComponent? {
    val projectKey = merged.jiraProjectKey ?: return null

    val majorFmt = merged.jiraMajorVersionFormat ?: "\$major"
    val releaseFmt = merged.jiraReleaseVersionFormat ?: "\$major.\$minor"
    val buildFmt = merged.jiraBuildVersionFormat ?: releaseFmt
    val lineFmt = merged.jiraLineVersionFormat ?: majorFmt
    // Per-range override (from a SCALAR_OVERRIDE row on component_configurations
    // for the matching range) takes precedence; falls back to the per-component
    // base value stored on `components.jira_hotfix_version_format` (Defaults /
    // top-level DSL). Empty string preserves the pre-existing "no format string"
    // contract for components that declare no hotfixVersionFormat anywhere.
    //
    // When [merged.jiraHotfixVersionFormatOverridden] is true the per-range
    // SCALAR_OVERRIDE row took effect for this range — including a null-clear
    // override (NULL typed column = "explicitly clear inherited value"). In
    // that case the per-component fallback MUST NOT be consulted, otherwise
    // the null-clear is silently undone. See the KDoc on
    // [ComponentConfigurationView.jiraHotfixVersionFormatOverridden].
    val hotfixFmt =
        if (merged.jiraHotfixVersionFormatOverridden) {
            merged.jiraHotfixVersionFormat ?: ""
        } else {
            merged.jiraHotfixVersionFormat ?: component.jiraHotfixVersionFormat ?: ""
        }

    val format =
        ComponentVersionFormat.create(majorFmt, releaseFmt, buildFmt, lineFmt, hotfixFmt)
    val info = if (merged.jiraVersionPrefix != null || merged.jiraVersionFormat != null) {
        ComponentInfo(merged.jiraVersionPrefix, merged.jiraVersionFormat)
    } else {
        null
    }

    return JiraComponent(
        projectKey,
        component.jiraDisplayName,
        format,
        info,
        merged.jiraTechnical ?: false,
        false,
    )
}

private fun pickDocLink(
    links: List<ComponentDocLinkEntity>,
    versionRange: String,
): ComponentDocLinkEntity? {
    if (links.isEmpty()) return null
    // Caller resolves to a specific major version when needed; for now, prefer
    // the link whose `majorVersion` is null (fallback) or matches the leading
    // numeric prefix of `versionRange` when both can be parsed. Detailed
    // semantics are pinned by the mapper-level spec note in schema-spec.md §5.
    val nullFallback = links.firstOrNull { it.majorVersion == null }
    val leadingMajor =
        Regex("""(\d+)""").find(versionRange)?.groupValues?.getOrNull(1)
    val matchByMajor = leadingMajor?.let { major -> links.firstOrNull { it.majorVersion == major } }
    return matchByMajor ?: nullFallback ?: links.firstOrNull()
}

// ============================================================
// Internal: utilities
// ============================================================

private fun safeParseBuildSystem(value: String): BuildSystem? =
    try {
        BuildSystem.valueOf(value)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun safeParseProductType(value: String): ProductTypes? =
    try {
        ProductTypes.valueOf(value)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * One-time immutable lookup of `EscrowModuleConfig`'s own declared fields by name, each already
 * `isAccessible = true`. Built once at class load and reused for every resolve — this replaces the
 * per-call `getDeclaredField` that [setField] used to make (~22 per resolved config × thousands of
 * configs per full unpaged-list request ≈ tens of thousands of reflective lookups per request; see
 * GH #365).
 *
 * `declaredFields` is class-only (no inherited fields), matching the previous `getDeclaredField`
 * semantics for the supported configuration fields. Synthetic (Groovy-injected) fields
 * (`$staticClassInfo`, etc.) are filtered out — they are never targets of [setField] and forcing
 * `isAccessible` on them is pointless.
 */
private val escrowModuleConfigFields: Map<String, java.lang.reflect.Field> =
    EscrowModuleConfig::class.java.declaredFields
        .filter { !it.isSynthetic }
        .onEach { it.isAccessible = true }
        .associateBy { it.name }

/**
 * Memoized declared-field lookup on `EscrowModuleConfig`. Returns `null` for unknown or synthetic
 * names so callers preserve the silent-ignore (forward-compat) contract. `internal` so the
 * reflection regression test can assert "discovered once per name".
 */
internal fun escrowModuleConfigField(name: String): java.lang.reflect.Field? = escrowModuleConfigFields[name]

/**
 * Set a private field on `EscrowModuleConfig` via the memoized [escrowModuleConfigField] lookup.
 * Unknown fields are silently ignored (forward-compat with domain-model changes).
 */
private fun setField(
    config: EscrowModuleConfig,
    name: String,
    value: Any?,
) {
    escrowModuleConfigField(name)?.set(config, value)
}

/**
 * Convert a persisted `ComponentBuildToolBeanEntity` row into the corresponding
 * `BuildTool` subtype. Returns `null` for unknown `beanType` values so callers
 * can use `mapNotNull` to silently skip forward-compat unknowns.
 */
internal fun ComponentBuildToolBeanEntity.toBuildToolBean(): BuildTool? {
    // Capture entity fields before entering bean apply-blocks, where `this`
    // changes to the bean type and shadows the entity's property names.
    val entityVersionPattern = versionPattern
    val entitySettingsProperty = settingsProperty
    val entityEdition = edition

    return when (beanType) {
        "oracleDatabase" -> {
            val bean = OracleDatabaseToolBean()
            entityVersionPattern?.let { bean.setVersion(it) }
            entitySettingsProperty?.let { bean.setSettingsProperty(it) }
            entityEdition?.let { edStr ->
                OracleDatabaseEditions.values().firstOrNull { it.name == edStr }
                    ?.let { bean.setEdition(it) }
            }
            bean
        }
        "cProduct" -> {
            val bean = PTCProductToolBean()
            entityVersionPattern?.let { bean.setVersion(it) }
            entitySettingsProperty?.let { bean.setSettingsProperty(it) }
            bean
        }
        "kProduct" -> {
            val bean = PTKProductToolBean()
            entityVersionPattern?.let { bean.setVersion(it) }
            entitySettingsProperty?.let { bean.setSettingsProperty(it) }
            bean
        }
        "dProduct" -> {
            val bean = PTDProductToolBean()
            entityVersionPattern?.let { bean.setVersion(it) }
            entitySettingsProperty?.let { bean.setSettingsProperty(it) }
            bean
        }
        "dDbProduct" -> {
            val bean = PTDDbProductToolBean()
            entityVersionPattern?.let { bean.setVersion(it) }
            entitySettingsProperty?.let { bean.setSettingsProperty(it) }
            bean
        }
        "odbc" -> {
            val bean = OdbcToolBean()
            entityVersionPattern?.let { bean.setVersion(it) }
            bean
        }
        else -> null
    }
}
