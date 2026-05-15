package org.octopusden.octopus.components.registry.server.mapper

/**
 * Single source of truth for the sets of valid enum names used by write-side
 * validation in [ConfigurationRowAccessors] and [ComponentManagementServiceImpl].
 *
 * Each `val` is initialised once at class-load time (equivalent to a
 * companion-object or top-level `object` initialiser).  Callers should reference
 * the symbol directly — no caching needed on the call site.
 */

internal val BUILD_SYSTEM_NAMES: Set<String> =
    org.octopusden.octopus.components.registry.core.dto.BuildSystem
        .values()
        .map { it.name }
        .toSet()

// The resolver reads `escrow.generation` via
// `org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.valueOf`
// (silent-null catch). The set below is computed from the sibling
// `core.dto.EscrowGenerationMode` whose members are kept in lockstep with the
// `api.enums` variant — if either enum is ever extended out-of-band, this set
// would diverge and either reject valid values or accept invalid ones; keep
// the two enums identical, OR replace this set with a derivation from the
// `api.enums` variant directly.
internal val ESCROW_GENERATION_MODE_NAMES: Set<String> =
    org.octopusden.octopus.components.registry.core.dto.EscrowGenerationMode
        .values()
        .map { it.name }
        .toSet()

internal val REPOSITORY_TYPE_NAMES: Set<String> =
    org.octopusden.octopus.escrow.RepositoryType
        .values()
        .map { it.name }
        .toSet()

internal val PRODUCT_TYPE_NAMES: Set<String> =
    org.octopusden.octopus.components.registry.api.enums.ProductTypes
        .values()
        .map { it.name }
        .toSet()

/**
 * Hand-listed: no enum class exists in the API layer yet for package types.
 * TODO: replace with `PackageType.values().map { it.name }.toSet()` if a
 * `PackageType` enum is ever extracted to the API module; until then any DSL
 * change that introduces a new package type must update this set in lockstep.
 */
internal val PACKAGE_TYPE_NAMES: Set<String> = setOf("DEB", "RPM")
