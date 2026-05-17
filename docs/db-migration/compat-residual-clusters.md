# Compat Residual Clusters — schema-v2 vs V1 (post-sort-fix)

## Status

Open · P1 · captures the **real backward-compat regressions** surfaced by the
prod-vs-test compat sweep after positional false-positives were removed.

## Context

After PR #232 extended `RawArraySorters` to cover `/rest/api/{1,2}/components`,
the prod-vs-test compat run collapsed from **5157 raw records** to **19**:

| Bucket | Count | What |
|---|---|---|
| `CANDIDATE_NOT_DB_MODE` env-warning | 1 | candidate `/service/status` reports `defaultSource=git` (operationally expected during the hybrid-mode migration) |
| `STATUS_CODE_DIFF` (suppressed) | 1 | `PUT /service/updateCache` 200 → 410 — documented known delta |
| `STRUCTURAL_DIFF` | **0** | (was 5138 positional artefacts; sort-fix removed all) |
| `VALUE_DIFF` | 17 | typed-layer recursive comparison; deduplicates to **4 distinct clusters** below (× 5 `/components` query-matrix variants and the v3 list endpoint produce the apparent multiplicity) |

The 4 clusters are the actionable backward-compat issues that schema-v2 must
fix before its measurement reaches the "0 unclassified diffs" exit criterion
in `~/.claude/plans/async-stirring-koala.md` §Workflow.

## Hard rule: TDD on synthetic data

Every cluster fix MUST follow the mandatory TDD process (codified by user
memory `feedback_tdd_mandatory` + `feedback_regression_guards_avoid_global_fixtures`):

1. **Test-first.** Write a failing in-memory unit test that reproduces the
   cluster's divergence using **synthetic fixtures only**:
   - Build the input via `BaseConfigurationRequest` / `EscrowModule` /
     `EscrowConfigurationImpl` constructors directly in the test body.
   - Or exercise `ImportServiceImpl.importComponentConfiguration(...)` /
     `migrateComponent(...)` with a hand-built `EscrowModule` against a
     focused `@DataJpaTest`-slice (or equivalent) that loads only the
     import + repository beans.
   - **Do NOT** extend the global `TestComponents.{kts,groovy}` or
     `Defaults.groovy` shared fixtures — PR #216 was closed precisely
     because such mutations cascade into broken component-count
     assertions in unrelated tests.

2. **No prod identifiers in the test or doc.** Component names, GAV
   coordinates, VCS paths, and project keys from the live stands are
   confidential per `feedback_redacted_identifiers`. Tests use synthetic
   names (`alpha-fixture`, `beta-aggregator`, etc.) and `com.example.*`
   GAVs. Each cluster below states the bug shape, not the prod component
   that exhibits it.

3. **RED → GREEN demo.** Two commits per bug:
   - `test(schema-v2): MIG-NNN-001 RED reproducing <cluster>` — the new
     unit test fails against current main.
   - `fix(schema-v2): MIG-NNN-001 <cluster> GREEN` — production code
     change makes the test pass; no other test regresses.
   - PR body pastes both `./gradlew :<module>:test --tests <new>`
     outputs.

4. **Compat-loop close-out.** After the fix merges, re-run prod-vs-test
   compat and confirm the cluster's records drop to 0. Update this doc
   with a ✅ Closed marker.

5. **Add the MIG-NNN row** to `docs/db-migration/requirements-migration.md`
   (Summary Table + body section with acceptance criteria) as the
   conventional registry of completed requirements.

---

## Cluster #1 — MIG-041: trailing comma in `distribution.gav` CSV

**Symptom.** On the typed layer, AssertJ reports the candidate's
`Component.distribution.gav` string differs from baseline only by a **single
trailing `,` character**:

- baseline (V1): `"com.example.foo:art-a:zip,com.example.foo:art-b:zip,com.example.foo:art-c:zip"`
- candidate (schema-v2): `"com.example.foo:art-a:zip,com.example.foo:art-b:zip,com.example.foo:art-c:zip,"` ← extra `,`

A `String.split(",")` on the candidate value yields **N + 1** elements with an
empty-string at the end; on the baseline, **N** elements. Same multiset of GAVs;
trailing-empty is an artefact of how schema-v2 builds the CSV (one component
in the residual exhibits exactly this).

**Hypothesis.** The CSV joiner in the schema-v2 entity → DTO mapper appends a
delimiter per element instead of `joinToString(",")`. Likely sites:
`EntityMappers.toEscrowModule` or `ComponentEntity.toComponentV2DTO`
(grep for `StringBuilder.*append.*","` or unconditional `+= ","`).

**Synthetic reproduction fixture.**

```kotlin
// components-registry-service-server/src/test/kotlin/.../MIG_041_TrailingCommaTest.kt
@Tag("unit")
class MIG_041_TrailingCommaTest {
    @Test
    fun `MIG-041-001 distribution-gav CSV has no trailing comma for multi-artifact component`() {
        // Build a synthetic component with three explicit per-config GAV rows,
        // mirroring the production shape (no per-range markers, all on BASE
        // config). All three GAVs share the same group, vary by artifactId.
        val component = ComponentEntity().apply {
            componentKey = "alpha-fixture"
            // ... configurations: BASE row with three component_artifacts entries
            // groupPattern = "com.example.foo"
            // artifactId in {"art-a", "art-b", "art-c"}; packaging "zip"
        }
        val dto = component.toEscrowModule(versionRangeFactory, numericVersionFactory)
        val v2 = dto.toComponentV2()
        assertEquals(
            "com.example.foo:art-a:zip,com.example.foo:art-b:zip,com.example.foo:art-c:zip",
            v2.distribution.gav,
            "distribution.gav must be a plain CSV with NO trailing delimiter",
        )
    }
}
```

**Acceptance.**

- RED: `MIG-041-001` fails on `feat/schema-v2-sql` HEAD.
- GREEN: after fix, the trailing `,` is gone; `MIG-041-001` passes.
- Compat residual: 5 records on `/v2/components` collapse to 0.
- No regression on `DatabaseComponentRegistryResolverMavenArtifactsRangeTest`,
  `VAL-006`, or any RES-C tests.

---

## Cluster #2 — MIG-042: `escrow.generation` default not inherited (UNSUPPORTED ← AUTO)

**Symptom.** Candidate emits `escrow.generation = UNSUPPORTED` where baseline
emits `AUTO` for at least one component that has **no explicit
`escrow.generation` in its DSL**. Same divergence shows on both `/v2/components`
(×5 query variants) and `/v3/components` (×1).

**Hypothesis.** V1's `Defaults.groovy` ships `generation = AUTO` and the
in-memory resolver applies that default when the component-level DSL omits the
field. Schema-v2's import (`ImportServiceImpl` or the entity-level default in
`escrow_configuration`) does not propagate `Defaults.escrow.generation`, so
the column lands at its enum-null value which the V2 DTO mapper renders as
`UNSUPPORTED`.

**Synthetic reproduction fixture.**

```kotlin
// components-registry-service-server/src/test/kotlin/.../MIG_042_EscrowGenerationDefaultTest.kt
@Tag("unit")
class MIG_042_EscrowGenerationDefaultTest {
    @Test
    fun `MIG-042-001 escrow generation falls back to AUTO when DSL omits it`() {
        // Build a synthetic EscrowModule with no explicit `escrow.generation`
        // set and a Defaults containing `generation = AUTO`.
        val defaults = EscrowConfigurationDefaults().apply {
            escrow = EscrowConfiguration().apply { generation = EscrowGenerationMode.AUTO }
        }
        val componentDsl = EscrowModule().apply {
            moduleName = "beta-fixture"
            // explicit Escrow block is null / has generation = null
        }

        // Round-trip through the import pipeline (focused slice — no Spring context)
        val entity = ImportPipeline.toEntity(componentDsl, defaults)
        val backOut = entity.toEscrowModule(versionRangeFactory, numericVersionFactory)

        assertEquals(
            EscrowGenerationMode.AUTO,
            backOut.escrowGeneration,
            "absent component-level generation must inherit from Defaults (AUTO)",
        )
    }

    @Test
    fun `MIG-042-002 escrow generation stays UNSUPPORTED when DSL says UNSUPPORTED`() {
        // Anti-regression: explicit UNSUPPORTED must not be silently rewritten.
        val componentDsl = EscrowModule().apply {
            moduleName = "gamma-fixture"
            escrow = EscrowConfiguration().apply { generation = EscrowGenerationMode.UNSUPPORTED }
        }
        val entity = ImportPipeline.toEntity(componentDsl, defaultsWithAuto())
        val backOut = entity.toEscrowModule(versionRangeFactory, numericVersionFactory)
        assertEquals(EscrowGenerationMode.UNSUPPORTED, backOut.escrowGeneration)
    }
}
```

**Acceptance.**

- RED: `MIG-042-001` fails (candidate emits UNSUPPORTED); `MIG-042-002` passes
  (anti-regression — must stay green throughout).
- GREEN: after fix, `MIG-042-001` passes; the explicit-UNSUPPORTED case is
  still respected.
- Compat residual: 6 records (5 on v2 + 1 on v3) collapse to 0.
- The fix must NOT change V1's behaviour — both resolvers should agree on
  `AUTO` for the synthetic fixture.

---

## Cluster #3 — MIG-043: wrong `distribution.gav` selected for multi-distribution component

**Symptom.** For one component, candidate's `distribution.gav` is
`"com.example.foo:product-alpha:zip"` where baseline returns
`"com.example.foo:product-beta:zip"` — **completely different artifactId**, not
a formatting issue. Both `alpha` and `beta` belong to the same component's
distribution set in the DSL; schema-v2 picks the wrong row.

**Hypothesis.** The DSL declares multiple distribution rows (e.g. per-range,
or multiple Maven artifacts under one component). PR #225 (RES-C-prime)
changed the row-selection logic in `DatabaseComponentRegistryResolver` and
introduced a stable `ORDER BY id` (UUID) on `component_artifacts`. UUID
ordering is **not** the same as DSL declaration order — the resolver picks
the row whose UUID happens to sort first, not the row the DSL author
intended. This is the "DSL-order preservation" follow-up explicitly deferred
in PR #225's Copilot review reply.

**Synthetic reproduction fixture.**

```kotlin
// components-registry-service-server/src/test/kotlin/.../MIG_043_DistributionRowSelectionTest.kt
@Tag("unit")
class MIG_043_DistributionRowSelectionTest {
    @Test
    fun `MIG-043-001 distribution-gav preserves DSL declaration order for multi-distribution component`() {
        // Synthetic component with two distribution.gav rows declared in DSL:
        //   first: com.example.foo:product-beta:zip
        //   second: com.example.foo:product-alpha:zip
        // V1 contract: the first DSL row wins for the `/components` summary view.
        // (Note: alpha < beta lexicographically, so a UUID-sorted resolver would
        // typically surface alpha — making this fixture a deterministic RED.)
        val component = ComponentEntity().apply {
            componentKey = "multi-dist-fixture"
            // configurations BASE with two component_artifacts rows inserted
            // in DSL declaration order. ImportServiceImpl writes rows one-by-one
            // from the CSV split — the test must seed rows in that order with
            // matching `created_at` timestamps OR an explicit `dsl_idx` column
            // (see MIG-043 fix design below).
        }
        val v2 = component.toEscrowModule(...).toComponentV2()
        assertEquals(
            "com.example.foo:product-beta:zip",
            v2.distribution.gav.split(",").first(),
            "first GAV must preserve DSL declaration order, not UUID order",
        )
    }

    @Test
    fun `MIG-043-002 distribution-gav CSV preserves DSL order across all rows`() {
        // Same fixture but assert the full CSV reflects DSL order.
        val v2 = build("multi-dist-fixture", gavs = listOf("beta", "alpha", "gamma"))
        assertEquals(
            "com.example.foo:product-beta:zip,com.example.foo:product-alpha:zip,com.example.foo:product-gamma:zip",
            v2.distribution.gav,
        )
    }
}
```

**Fix design (non-prescriptive — investigate before committing).**

Options:

- **A.** Add an explicit `idx INTEGER NOT NULL` column to `component_artifacts`
  populated 0, 1, 2, … in `ImportServiceImpl` at import time. Resolver
  queries with `ORDER BY idx`. Migration: `V_XX__add_artifacts_idx.sql`
  backfills via `ROW_NUMBER() OVER (PARTITION BY component_id ORDER BY id)`
  (acceptable approximation given the current state is already
  UUID-sorted; new imports get true DSL order).
- **B.** Store the entire CSV as-is in a single column on
  `component_configurations.artifact_ids` and stop normalising to
  `OneToMany`. Simpler but loses query-ability.

Option A is more invasive but aligns with the existing migration policy
"edit `V1__schema.sql` in place — schema-v2 not in prod" (memory
`project_crs_schema_v2_migration_policy`). Apply the column to
`V1__schema.sql` directly; no incremental migration needed.

**Acceptance.**

- RED: `MIG-043-001` fails (resolver returns alpha-first instead of beta-first).
- GREEN: after fix, DSL order preserved; `MIG-043-001` + `MIG-043-002` pass.
- Compat residual: 5 records collapse to 0.
- VAL-006 cluster (`platform-commons`-class multi-artifact ordering on
  `/maven-artifacts`) — verify whether MIG-043's fix also resolves it; if
  so, mark VAL-006 follow-up closed.

---

## Cluster #4 — MIG-044: `/common/jira-component-version-ranges` set residual

**Symptom.** One `VALUE_DIFF` on `GET /rest/api/2/common/jira-component-version-ranges`.
AssertJ recursive comparison with `ignoringCollectionOrder` fails — meaning
the two SETS of `JiraComponentVersionRangeDTO` are NOT element-wise equal.

**Diagnosis (2026-05-17).** The single `VALUE_DIFF` that originally prompted this cluster came from the 01:22 compat run logged in `_wt/schema-v2-sql` — a run that fired the `CANDIDATE_NOT_DB_MODE` pre-flight warning (candidate was running `defaultSource=git`, not `defaultSource=db`). Inspecting `diff-worker-40123-…ndjson`, AssertJ reported "The following expected elements were not matched in the actual ArrayList" for exactly one element: a `JiraComponentVersionRangeDTO` whose `componentName` is a multi-artifact distribution component and whose `distribution.gav` on the **candidate** (V1 QA-stand) ended with a trailing comma. Because `ignoringCollectionOrder` compares by field equality, the candidate's trailing-comma element could not be matched against the baseline's clean element, producing the 1-record VALUE_DIFF. The candidate side was a stale V1 build on the QA stand — the same trailing-comma regression that MIG-041/MIG-045 tracks — not a schema-v2 code defect.

**Outcome: not a schema-v2 regression.** The authoritative DB-mode compat run (executed 2026-05-17 08:26 from `feat/schema-v2-sql` tip `1ac4bc6`) shows `diffCount=0` for `GET /rest/api/2/common/jira-component-version-ranges`. The schema-v2 path for this endpoint (`DatabaseComponentRegistryResolver.buildJiraVersionRangesForComponent` → `ComponentEntity.toEscrowModule` → `EntityMappers.composeGavCsv`) uses `joinToString(",")` and never produces a trailing comma. The 01:22 diff was V1-vs-V1 drift between prod and the (then-undeployed) QA stand; once the QA stand is redeployed off the current `feat/schema-v2-sql` tip, this record will remain at 0.

**State: ✅ Closed-as-non-issue (2026-05-17).** Not reproducible against current
`feat/schema-v2-sql` tip with proper local-vs-local compat (v2.0.86 baseline +
DB-mode candidate, smoke-list active). The cluster's original VALUE_DIFF was
V1-vs-V1 drift from the 00:52 QA-stand run where the preflight
`CANDIDATE_NOT_DB_MODE` warning fired. No code change required. If a new diff
surfaces for this endpoint after the QA-stand redeploy, it should be filed as
a fresh cluster with a proper DB-mode diff report.

**Acceptance.**

- Compat residual: 0 records on `/jira-component-version-ranges` in DB-mode
  run (confirmed 2026-05-17 08:26, tip `1ac4bc6`).

---

## Sequencing

Recommended order (smallest blast radius first):

1. **MIG-041** (trailing comma) — pure formatting in mapper; <50 LoC.
2. **MIG-042** (escrow.generation default) — focused import / mapper bug,
   defaults-inheritance pattern already exists in the codebase elsewhere.
3. **MIG-043** (DSL-order preservation) — schema-touching, requires
   `V1__schema.sql` edit; do AFTER #41-42 close so the next compat re-run
   isolates only this cluster's records.
4. **MIG-044** (jira-component-version-ranges) — diagnose first; may
   reduce to a one-line fix or may expose a deeper missing-emit path.

Each PR is independent and merges to `feat/schema-v2-sql` directly. Each
follows the standard schema-v2 PR review pattern (Sonnet correctness +
adversarial check + `gradlew build` green).

## Acceptance gate (end-to-end)

After all four MIG-NNN PRs merge:

- `gradlew :components-registry-compat-test:test -Pcompat.full=true` against
  prod (baseline) ↔ test stand (candidate, in hybrid-or-DB mode) records:
  - 0 active `VALUE_DIFF`.
  - 0 active `STRUCTURAL_DIFF`.
  - `CANDIDATE_NOT_DB_MODE` env-warning may remain until the operator
    switches candidate to `defaultSource=db` — non-blocking for the
    cluster-fix verdict.
- The 4 new MIG-NNN unit tests stay GREEN on every subsequent PR.
- VAL-010 in `GitVsDbValidationTest` is re-enabled (or the residual entry
  in the @Disabled note is updated to reflect that 0 of the 3 originally
  listed divergences remain).

## Related

- `~/.claude/plans/async-stirring-koala.md` §Diff classification — original
  TDD policy for MIG-NNN bugs.
- `docs/db-migration/requirements-migration.md` — registry of completed
  MIG-NNN requirements; MIG-041..MIG-044 land here as each PR closes.
- `docs/db-migration/tech-debt/006-compat-test-coverage.md` — endpoint
  coverage gate that this compat run depends on.
- PR #232 (`feat/raw-sorters-v1-v2-components`) — the prerequisite
  positional-noise fix; without it the residual clusters were buried in 5138
  records of artefacts.
