# TD-005: IMP-003 v2 — In-Memory Regression Guard for KTS-Only Build-Block Shape

## Status

Open · nice-to-have (defense-in-depth atop compat-test) · cluster-D follow-up

## Context

PR-D+E (#208) closed cluster D by adding the narrow `component_build_tool_beans`
schema extension plus the `attachBuildToolBeans` import-side helper. The 8
production database-bean carriers (Oracle / kProduct / dProduct / dDbProduct /
cProduct components — sanitised here as a group) now have their structured
build-tool beans correctly persisted on the BASE row, and `GET .../build-tools`
matches baseline. Validated end-to-end via `verify.sh --reset-db` against the
full 475-component compat smoke (zero `VALUE_DIFF` on `/build-tools` / `.versions/{v}`)
and via the prod-vs-QA compat run.

The cluster-D fix path relies on a non-obvious shape interaction:

- DSL components carry the `build { tools { database { oracle { ... } } } }`
  block at the **range level** (KTS side), with **no component-level `build {}`
  block** on the Groovy side.
- The defaults-merge wiring + `attachBuildToolBeans` together must still fire on
  this shape — otherwise the bean attach is silently skipped and the resulting
  DB row carries no `component_build_tool_beans` entries.
- If a future refactor of the import pipeline (defaults clone, marker emission,
  scalar diff, etc.) accidentally drops the attach for this specific shape, the
  bug would be invisible until the next manual compat run — there is no unit-level
  guard in `:components-registry-service-server:test` today.

## What was tried (PR #216) and why it was closed

PR #216 (branch `fix/buildtools-kts-only-merge`, closed 2026-05-16 without merge)
attempted this regression guard by:

1. Adding a new component to the **global**
   `test-common/src/test/resources/components-registry/common/TestComponents.kts`
   (full KTS DSL with `build { tools { database { oracle { version = "12.0" } } } }`).
2. Adding the corresponding Groovy counterpart in `TestComponents.groovy` with NO
   component-level `build` block + two version ranges + per-range `build {
   requiredTools }` + escrow.generation override.
3. Adding an empty `build { }` block to `Defaults.groovy` so test components
   without their own component-level `build` block inherit a non-null
   `buildConfiguration` clone-base (matching the production Defaults shape).
4. New `BuildToolBeansImportTest.imp003_*` asserting
   `component_build_tool_beans` has exactly one `oracleDatabase` row on the
   BASE row of the new fixture.

The new fixture worked: clean reset + automigrate persisted the expected row.
But adding a single component to the global `TestComponents.{kts,groovy}` set
**changed the component count** observed by every client test that asserts on
the total — three of them broke:

- `ComponentsRegistryServiceClientTest.testGetAllComponents` (hard-coded count).
- `…RES-001 jira-component-version-ranges` (count-derived assertion).
- `…RES-003 product-mapping` (count-derived assertion).

Updating those three would have meant touching ~10 assertions across unrelated
test surfaces just to host one regression guard for behaviour that already
works in compat-test. The author closed the PR rather than take that cascade
and explicitly recommended the alternative shape:

> If we want regression coverage later, the right shape is a focused unit test
> that constructs the relevant config objects in-memory and calls
> `attachBuildToolBeans` directly — no global fixture changes.

## What is still wanted

A **focused unit test** at
`components-registry-service-server/src/test/kotlin/.../migration/BuildToolBeansImportTest.kt`
that:

1. Constructs a `BaseConfigurationRequest` in-memory with the KTS-only shape:
   - no component-level `build` block,
   - each range-level config carries the structured
     `build.tools.database.oracle(version=...)` (or PT*/ODBC) block.
2. Either injects a stub `Defaults` that exposes an empty `build { }` block
   (matching the production-Defaults clone-base shape), OR drives the import
   helper directly without going through the defaults-merge stage.
3. Invokes `importService.importComponentConfiguration(...)` (or the lower-level
   `attachBuildToolBeans` helper if the defaults-merge can be sidestepped).
4. Asserts the persisted `component_build_tool_beans` row has the expected
   `bean_type`, `version_pattern`, `tool_type`, and `settings_property`.
5. Does NOT touch:
   - `test-common/src/test/resources/components-registry/common/TestComponents.kts`
   - `test-common/src/test/resources/components-registry/common/TestComponents.groovy`
   - `test-common/src/test/resources/components-registry/common/Defaults.groovy`
   - Any other shared fixture file that other tests count entries from.

## Sketch (informal)

```kotlin
@Test
fun `IMP-003 v2: KTS-only build block — attachBuildToolBeans fires through Defaults clone-base`() {
    val baseCfg = baseConfigurationRequestFor(
        // no top-level build{} on the request (mirrors Groovy side),
        // range-level build{tools{database{oracle{version="[12,)"}}}} on each range
        ranges = listOf(rangeCfgWithOracleBean("(,2.0)"), rangeCfgWithOracleBean("[2.0,)")),
    )
    val row = importService.importComponentConfiguration("appAlpha", baseCfg)
    val beans = buildToolBeanRepository.findByComponentConfigurationId(row.id)
    assertThat(beans).hasSize(1)
    assertThat(beans.first().beanType).isEqualTo("oracleDatabase")
    assertThat(beans.first().versionPattern).isEqualTo("[12,)")
}
```

No fixture-file edits. One test class, one test method. Other variants
(`kProduct`, `dProduct`, `dDbProduct`, `cProduct`) can be added as additional
parameterised methods in the same class.

## Acceptance criteria

- [ ] New test method in `BuildToolBeansImportTest` (no other file changes
  beyond that test class).
- [ ] Test is RED if `attachBuildToolBeans` is replaced with a no-op (verify
  the guard works before relying on it).
- [ ] Test is GREEN on current `feat/schema-v2-sql` HEAD without further fixes
  (cluster-D code path is correct — this is regression coverage only).
- [ ] No changes to global `TestComponents.{kts,groovy}` / `Defaults.groovy` —
  the existing `testGetAllComponents` and RES-00x assertions continue to pass
  with their current hard-coded counts.

## Risk classification

Nice-to-have. Cluster-D behaviour is already exercised by compat-test on every
manual run. Without TD-005 the worst case is "a future refactor silently
removes attach for the KTS-only shape, regression caught at the next compat
cycle instead of at unit-test time." Acceptable to defer; tracked so it isn't
forgotten.

## See also

- PR #208 — the cluster-D fix (`feat(schema-v2): close RES-014 — narrow schema
  extension for build-tool beans`).
- PR #216 (closed) — the global-fixture attempt; closing comment captures the
  trade-off in detail.
- Memory `feedback-regression-guards-avoid-global-fixtures` — the rule
  generalised from this incident.
