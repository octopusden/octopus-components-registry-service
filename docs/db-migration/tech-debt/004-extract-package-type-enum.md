# TD-004: Extract `PackageType` Enum to the API Module

## Status

Open

## Context

Write-side enum validation in `ComponentManagementServiceImpl` and the scalar-column write
guards in `ConfigurationRowAccessors` are backed by `Set<String>` snapshots in
`org.octopusden.octopus.components.registry.server.mapper.EnumValidValues` derived from real
API-layer enums:

- `BUILD_SYSTEM_NAMES` ← `core.dto.BuildSystem`
- `ESCROW_GENERATION_MODE_NAMES` ← `core.dto.EscrowGenerationMode`
- `REPOSITORY_TYPE_NAMES` ← `org.octopusden.octopus.escrow.RepositoryType`
- `PRODUCT_TYPE_NAMES` ← `api.enums.ProductTypes`

`PACKAGE_TYPE_NAMES` is the odd one out — there is no `PackageType` enum in any of the API
modules, so the set is hand-listed:

```kotlin
internal val PACKAGE_TYPE_NAMES: Set<String> = setOf("DEB", "RPM")
```

This is the only divergent member of the validation set in this file.

## Workaround Applied

Hand-listed literal set, with `// see TD-004` referencing this entry. Any DSL change that
introduces a new package type must update the literal set in lockstep — there is no compile-time
guarantee that the set matches what the resolver / dependency-mapping code understands.

## What to Do

When the package-type enum is consolidated into the API layer:

1. Extract `PackageType` (or equivalent enum) into one of the `components-registry-*` API
   modules — colocate with `RepositoryType` / `ProductTypes`.
2. Replace the `PACKAGE_TYPE_NAMES` literal set with
   `PackageType.values().map { it.name }.toSet()` (same pattern as the four sibling sets).
3. Remove this TD entry and the `// see TD-004` reference.

## Out of Scope

- Changing the persisted DB column shape — `distribution_packages.package_type` stays a
  string with the existing CHECK constraint.
- Introducing new package types — the literal set must be kept in sync until the enum exists.

## Acceptance Criteria

- A first-class `PackageType` enum exists in an API module.
- `PACKAGE_TYPE_NAMES` is derived from the enum, not hand-listed.
- The four other sets in `EnumValidValues.kt` and this one all use the same derivation pattern.
