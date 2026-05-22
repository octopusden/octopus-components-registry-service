# TD-007: Compat-test framework self-tests + serious review protocol

## Status

In progress · P1 · framework correctness · belongs to the addendum of
`~/.claude/plans/async-stirring-koala.md` (TASK-D). Layers 1-4 landed.

## Context

First end-to-end use of the verify gate (PR #210/#215 merged) and the
prod-vs-QA compat run revealed five silent-failure modes in the compat-test
infrastructure, plus one critical environment-mode confusion:

1. `kill_port` lsof syntax wrong on macOS → candidate JVM survived "kill",
   compat tested stale code.
2. `wait_port_free` warn-and-continue on timeout → race against still-bound
   port produced garbage diffs.
3. `PASS_ARGS[@]: unbound variable` under `set -u` → gate died before
   `compat.sh`.
4. `JsonShape` position-sensitive on Set-typed responses → 1781 spurious
   STRUCTURAL_DIFFs on `/jira-component-version-ranges`.
5. No preflight on candidate `serviceMode` → both stands were running in
   `default-source=git` (V1 in-memory), so the entire 1895-diff measurement
   was V1-vs-V1, NOT schema-v2-vs-V1.

Bug #5 was the largest: the four cluster-fix PRs (#208/#209/#211/#212) were
merged but their `DatabaseComponentRegistryResolver` code was dormant at
runtime on the deployed stands.

The fix is **four layers of regression guards** (each as its own PR) plus a
stricter review protocol for any future change touching this code.

## Four layers

| Layer | What | Files | PR | Status |
|---|---|---|---|---|
| L1 — Comparator-logic unit tests | `ComparatorLogicTest` (13 cases) pinning `Comparators.compareRaw` — status divergence, transport failure on EITHER side, key permutation, additive/removed field, array size mismatch, header allow-list with case-insensitive keys, context-threading. Required extracting `compareRaw` out of `CompatibilityTestBase` into an `object Comparators` so it's testable without HTTP scaffolding. | `Comparators.kt` (NEW), `ComparatorLogicTest.kt` (NEW), `CompatibilityTestBase.kt` (delegate) | #230 | Merged |
| L2 — Shell self-tests | `scripts/local-stands/test/test-verify-lib.sh` — 14 pure-bash cases exercising `kill_port` (incl. PR #220 argv-shape regression guard), `wait_port_free`, `check_migration_health` (incl. greedy-left sed bug regression guard). Mocks `lsof` / `kill` / `sleep` via shell function redefinition. | `scripts/local-stands/test/test-verify-lib.sh` (NEW), `scripts/local-stands/verify.sh` (sourcing guard), `.gitignore` | #229 | Merged |
| L3 — Preflight assertions | Extend `SnapshotPreconditionTest` to record `CANDIDATE_NOT_DB_MODE` env-level diff when candidate `/service/status` reports `defaultSource != "db"` OR `dbComponentCount` below the `0.9 × baselineComponentCount` threshold. Required adding `defaultSource: String?` + `dbComponentCount: Long?` to `ServiceStatusDTO` (nullable, default-null for backward-compat with old baselines). Activated the dormant `CANDIDATE_NOT_DB_MODE` category in `DiffClassifier`; the env-categories filter in `build.gradle` makes it non-suppressible via `known-deltas.json`. | `ServiceStatusDTO.kt`, `ApplicationConfig.kt`, `SnapshotPreconditionTest.kt`, `EnvironmentPreflightEvaluator.kt` + test (NEW), `DiffClassifier.kt`, `build.gradle` | #223 | Merged |
| L4 — CI binding | Split the compat-test module into two Gradle tasks: `:test` (URL-gated, runs every `CompatibilityTestBase` subclass via `@Tag("http")` inherited from the base) and `:unitTest` (pure-Kotlin, no URL config, runs everything not tagged `http`). PR-time CI binds to `:unitTest`; full HTTP integration stays on TC manual-run via `:test`. The `excludeTags 'http'` polarity protects against new untagged tests silently disappearing. | `components-registry-compat-test/build.gradle` (split), `CompatibilityTestBase.kt` (`@Tag("http")`) | #228 | Merged |

Plus, separately:

| Cluster I (subsumed under L1) | Pre-sort known Set-shape arrays by a stable composite key before `JsonShape.diff`. Closes the 1781-diff noise on `/jira-component-version-ranges`. | `RawArraySorters.kt` + test (NEW) | #222 | Merged |

## Serious-review protocol

Any PR touching `components-registry-compat-test/` OR
`scripts/local-stands/` follows this two-stage review:

### Stage 1 — Sonnet (default)

Code-correctness, edge cases, idioms. Standard PR review questions:

- Are tests pinning what they claim to pin?
- Edge cases on inputs?
- Idiomatic Kotlin / Groovy / bash?

### Stage 2 — Opus, adversarial

A single question: **could this test pass when the production code is
silently wrong?** The reviewer hunts specifically for:

- Tests that pass vacuously (assertions wouldn't fail on a buggy
  implementation).
- Tests that assert on the wrong field / wrong path.
- Tests that silently skip the case they claim to cover.
- Mocking patterns that mask real checks.

### Mandatory RED-then-GREEN demo

For every new regression guard:

1. The PR contains two commits: `test: RED reproducing X` (test fails
   against current main) + `fix: GREEN` (test passes after fix).
2. The PR body pastes both outputs.
3. The Stage-2 reviewer checks out the RED commit locally to confirm it
   actually fails before approving.

Examples landed under this protocol:

- L2's `case_kill_port_passes_correct_argv_to_lsof` — Stage-2 caught that
  the original test mocked lsof without inspecting argv. Fix added argv
  recording + a RED demo against the broken pre-PR-#220 syntax (lsof argv
  was checked, test went RED).
- L4's polarity inversion — Stage-2 caught the silent-skip trap where new
  untagged tests would land in URL-gated `:test`. Fix inverted to
  `excludeTags 'http'`; a new untagged unit test now lands in `:unitTest`
  by default.

### Reviewer-driven end-to-end smoke

Stage-2 reviewer (not author) runs `verify.sh --restart --tests
"*ServiceStatusV2CompatTest*"` and confirms `exit 0 + summary.md generated`.
For schema-touching PRs, also `--reset-db`.

## Outcomes after Phase 2

- `:unitTest` runs in ~8s on every PR (40 tests today: JsonShape,
  RawArraySorters, EnvironmentPreflightEvaluator, ComparatorLogic).
- `:test` runs against deployed stands when URLs are configured; records
  `CANDIDATE_NOT_DB_MODE` env-level diff if the candidate is in V1 mode.
- `scripts/local-stands/test/test-verify-lib.sh` (14 cases) runs in <1s
  and pins the macOS / sed / source-guard regressions.
- Future PRs touching this code follow the two-stage review.

## Related

- `~/.claude/plans/async-stirring-koala.md` — TASK-D details, including
  the 5 sub-bug analysis.
- `docs/registry/tech-debt/006-compat-test-coverage.md` — TD-006, the
  endpoint-coverage gate; depends on the `Comparators` extraction from L1.
- `docs/registry/tech-debt/008-compat-test-trace-replay.md` — TD-008.
- `docs/registry/tech-debt/009-compat-test-load.md` — TD-009.
