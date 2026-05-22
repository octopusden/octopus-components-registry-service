package org.octopusden.octopus.components.registry.server.mapper

/**
 * Single source of truth for the sets of valid enum names used by write-side
 * validation. Consumed by `ConfigurationRowAccessors.applyScalarValue`
 * (file-level top-level helpers in this package) and by the per-axis
 * `validate*` helpers on [ComponentManagementServiceImpl][org.octopusden.octopus.components.registry.server.service.impl.ComponentManagementServiceImpl].
 *
 * Each `val` is initialised once at class-load time (equivalent to a
 * companion-object or top-level `object` initialiser). Callers should reference
 * the symbol directly — no caching needed on the call site.
 */

internal val BUILD_SYSTEM_NAMES: Set<String> =
    org.octopusden.octopus.components.registry.core.dto.BuildSystem
        .values()
        .map { it.name }
        .toSet()

// Derive from the SAME enum the resolver uses (`api.enums.EscrowGenerationMode.valueOf`,
// silent-null catch — see `EntityMappers.toEscrowApi`). Using the sibling `core.dto`
// variant would introduce dual-enum drift risk: if either enum gains a member
// out-of-band, the validator and the resolver would disagree.
internal val ESCROW_GENERATION_MODE_NAMES: Set<String> =
    org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
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
 * Hand-listed: no `PackageType` enum exists in the API layer. Replace with
 * `PackageType.values().map { it.name }.toSet()` once the enum is extracted —
 * see TD-004 (`docs/registry/tech-debt/004-extract-package-type-enum.md`).
 * Until then any DSL change that introduces a new package type must update
 * this set in lockstep.
 */
internal val PACKAGE_TYPE_NAMES: Set<String> = setOf("DEB", "RPM")

/**
 * Hand-listed: valid `beanType` values for `component_build_tool_beans`.
 * Matches the DB CHECK constraint in `V1__schema.sql`. Update both the
 * constraint and this set whenever a new bean type is introduced.
 */
internal val BEAN_TYPE_NAMES: Set<String> =
    setOf("oracleDatabase", "cProduct", "kProduct", "dProduct", "dDbProduct", "odbc")
