# TD-026: A configuration reload is not atomic across subtrees

## Status

Open · P3 · accepted by the add-component-profiles change · the existing subtrees already behaved
this way.

## Context

`POST /rest/api/4/admin/reload-config` calls `ContextRefresher.refresh()`, which rebinds
`AdminConfigProperties` in place and re-syncs `field-config` and `component-defaults` into the
`registry_config` cache (`ConfigRefreshListener` → `ConfigSyncService`). It then reloads the
component profiles through `ComponentProfileCatalog.reload()`.

Each part decides on its own:

- the catalog keeps the profiles in use when the new ones are not usable;
- `ConfigSyncService` refuses an invalid `field-config` (`ConfigValidationException`), but the bound
  `AdminConfigProperties` bean is already rebound by then;
- a profile failure does not undo the `field-config` / `component-defaults` sync.

## The limit

One service-config change that touches several subtrees can be applied in part. A reload whose
`field-config` is valid and whose profiles are not leaves the new field configuration live next to
the old profiles. The response says which part failed (`componentProfiles.status`, or
`error: config-validation`), but nothing rolls the rest back.

## Removal options

- **Validate everything before applying anything:** read the refreshed environment, parse the
  profiles and serialize `field-config` / `component-defaults` without writing, and apply all three
  only when all pass. Needs the sync to be split into validate and write steps, and the rebind of
  `AdminConfigProperties` to be undone, or replaced by a snapshot like the profiles', when the
  validation fails.
- **Document the order and tell administrators to reload one subtree at a time.** No code; leaves
  the gap.

## References

- `AdminControllerV4.reloadConfig`, `ComponentProfileCatalog`, `ConfigSyncService`,
  `ConfigRefreshListener`
- [ADR-016](../adr/016-admin-config-as-code.md) — "Component profiles (third subtree)"
- `openspec/changes/add-component-profiles/design.md` (Risks)
