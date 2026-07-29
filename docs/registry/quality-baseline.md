# Quality Gate Baseline

Snapshot of every automated check and its measured value, taken **before** the planned quality-gate
work (mutation testing, BRANCH coverage gate, OpenAPI breaking-change diff, TD-017 authorization
policy test, property-based version-model tests, `src/main`→`src/test` PR gate).

Purpose: a "before / after" reference. Add a new dated section below rather than editing an old one —
each snapshot must stay reproducible from the recipe in [§7](#7-how-to-re-measure).

## Gate changes since the baseline

Measured values move only when the code changes; this table records when a **gate** changed, so a later
snapshot can be read against the right contract.

| Date | Change | Gate before | Gate after |
|---|---|---|---|
| 2026-07-27 | Aggregate coverage floor | LINE ≥ 70%, no BRANCH rule | LINE ≥ 86%, BRANCH ≥ 65% |
| 2026-07-27 | Per-module coverage floor (`jacocoCoverageFloor`, wired into `qualityCoverage`) | plugin-owned 10% floor only | strict per-module LINE/BRANCH minimums (measured − ~2 p.p.), 10% floor kept underneath |
| 2026-07-28 | `component-validation` gained a floor (module added by #443, measured on `main` @`82d76d26`: LINE 99.7% = 343/344, BRANCH 85.9% = 128/149) | not gated | LINE ≥ 97%, BRANCH ≥ 83% |
| 2026-07-28 | Floors-map policy checks moved out of configuration into `verifyCoverageFloorsPolicy` | a map problem failed **every** Gradle invocation | it fails the coverage gate only |
| 2026-07-29 | Coverage execution data restricted to the tasks the invocation runs (`test`, `dbTest`) | a leftover `integrationTest.exec` could fold in locally, raising the numbers a clean CI run does not have | the gate reads the same data locally and on a fresh agent |
| 2026-07-29 | Floors-map contract validated (`line` required, `branch` optional, values in (0, 1], no unknown keys, exemptions need a reason, no module both floored and exempt) | only `> 0` was checked | a malformed entry fails the gate with a precise message instead of failing late in JaCoCo or being silently ignored |

---

## Snapshot 2026-07-27 (baseline)

**Code under measurement:** `main` @ `1ecab7a2` (`fix(labels): preserve pre-existing
labels/systems/tools on edit (set-diff sync) [SYS-067] (#454)`) — `origin/main` tip at snapshot time.

**Sources:** TeamCity chain `3.0.9-4407` (2026-07-24, the last `main` chain), GitHub Actions runs on
`main` @ `1ecab7a2`, and the report artifacts those runs published.

**Tool versions at this snapshot** (they own the thresholds, so a bump can move the numbers):
`octopus-quality` 2.4.1, shared workflows `octopus-base@v2.4.1`, detekt 1.23.8, ktlint-gradle 14.0.1,
Gradle 8.6, Java 21. Dependabot PRs proposing `octopus-base` 2.5.1 were open at snapshot time — if the
plugin-owned per-module coverage floor changes there, re-measure before comparing.

### 1. TeamCity — `main` chain 3.0.9-4407

| Config | Gradle task | Result | Tests | Passed | Ignored | Failed | Duration |
|---|---|---|---|---|---|---|---|
| `[1.0] Compile & UT [AUTO]` | `clean build publish dockerPushImage` | SUCCESS | 1893 | 1890 | 3 | 0 | 11m32s |
| `[2.1] Integration & DB Tests [AUTO]` | `clean :…-server:dbTest :…-client:dbTest :…-light-client:dbTest` | SUCCESS | 1491 | 1485 | 6 | 0 | 8m52s |

Notes:

- TeamCity reports **no** `CodeCoverage*` statistics for either config — coverage is enforced only in
  the GitHub `quality` job (see §3). Do not expect a coverage delta to show up in TC.
- `[1.0]` test time is 715s wall across workers vs 692s build duration (parallel execution).
- `[2.1]` spent 583s in the queue waiting on `[1.0]`; that is queueing, not check cost.

Ignored tests (the baseline set — any change here is a signal):

| Config | Test | Reason class |
|---|---|---|
| `[1.0]` | `cli.auth.KeycloakIntegrationTest.deviceFlowLoginAgainstRealKeycloak` | gated on Keycloak device flow |
| `[1.0]` | `server.service.impl.RealDslUniquenessAcceptanceTest` (§6.0 uniqueness pre-pass) | needs the real production DSL |
| `[2.1]` | `server.migration.FtDbProfileWriteTest` — SYS-027 POST + PATCH round-trips (2) | H2 `jsonb` column under `ft-db` |
| `[2.1]` | `server.migration.GitVsDbValidationTest` — VAL-010 bulk canary | full-corpus validation, on demand |

Compat / validation configs do **not** run on `main` — they are PR-triggered. Last runs (chain
`3.0.9-4413`, 2026-07-27, dependabot branches), recorded here as the reference scale of the gate:

| Config | Result | Assertions | Ignored | Duration |
|---|---|---|---|---|
| `[2.2] Compat — Local Stand (baseline + candidate JARs)` | SUCCESS | 21812 | 1 | 30m12s |
| `[2.3] Compat — Local Stand (git-mode, no DB migration)` | SUCCESS | 21812 | 2 | 25m32s |
| `[2.0] Validate Git-based Components Registry` | SUCCESS | n/a (`validateConfig`) | — | 2m01s |
| `[1.5] Compat — HTTP (two pre-deployed URLs)` | last run 2026-05-25 on `v3` (`2.0.84-108`) | — | — | — |
| `[1.6] Compat — Trace Replay (prod traffic)` | last run 2026-05-25 on `v3` (`2.0.84-86`) | — | — | — |

### 2. GitHub Actions — `main` @ 1ecab7a2

| Workflow / job | Result | Duration |
|---|---|---|
| `Gradle Compile & UT` → `run-build-and-deploy / build` | success | 141s |
| `Quality Gates` → `quality/wrapper-validation` | success | 8s |
| `Quality Gates` → `quality/tests-coverage` | success | 624s |
| `Quality Gates` → `quality/static` | success | 142s |
| `Security Reports` (CodeQL + Trivy; dependency-check disabled) | success | — |
| `Performance SLA (non-gating)` → `perfTest` | success | 8.09s (single test) |

**Perf SLA measurement** (`GetComponentsListPerformanceTest`, run 2026-07-27):

| Metric | Value |
|---|---|
| Median wall time (the asserted metric) | **261 ms** |
| Ceiling (`MEDIAN_CEILING_MS`) | 2000 ms |
| Components seeded (`COMPONENT_COUNT`) | 1000 |
| Iterations | 2 warmup (discarded) + 5 measured |
| Measured iterations (ms) | 348, 305, 250, 261, 254 |
| Prepared statements per call | 31 |

The median is emitted through the test's logger, which lands in the **`system-err`** element of
`build/test-results/perfTest/TEST-*.xml` (published in the `perf-test-report` artifact) — not in the
job log and not in `system-out`. Read it from the artifact XML, not from the run log.

One observation that is not a quality metric but is true of this snapshot: the `Gradle Release` run on
`main` (2026-07-24, job `prepare-build-publish-release`) **failed**. Out of scope for this baseline;
tracked separately.

### 3. Coverage — JaCoCo (`coverage-reports` artifact of the Quality Gates run)

Execution data: `test` + `dbTest`. `integrationTest` boots a separate JVM without the JaCoCo agent
and is **not** part of the GitHub gate, so these numbers exclude it.

**Aggregate (gate: LINE ≥ 70%, no BRANCH rule):**

| Counter | Covered / total | % | Gate |
|---|---|---|---|
| LINE | 12960 / 14701 | **88.2%** | ≥ 70% |
| BRANCH | 6068 / 9021 | **67.3%** | none |
| INSTRUCTION | 101192 / 124584 | 81.2% | none |
| METHOD | 3761 / 4473 | 84.1% | none |
| CLASS | 746 / 870 | 85.7% | none |

**Per module (plugin-owned floor: 10%):**

| Module | LINE | BRANCH |
|---|---|---|
| `components-registry-service-server` | 89.5% (9765/10906) | 70.7% (4505/6368) |
| `components-registry-service-client` | 96.9% (95/98) | 65.4% (17/26) |
| `component-resolver-core` | 80.9% (1680/2076) | 62.6% (1182/1889) |
| `components-registry-dsl` | 81.0% (277/342) | 47.1% (32/68) |
| `components-registry-service-light-client` | 79.2% (99/125) | 45.0% (27/60) |
| `component-resolver-api` | 70.2% (351/500) | 30.4% (133/438) |
| `components-registry-api` | 16.6% (57/343) | 9.6% (10/104) |
| `components-registry-service-core` | no per-module report | no per-module report |

Two facts to carry into the BRANCH-gate change:

- `components-registry-api` sits at 16.6% LINE — the only module close to the 10% floor, so it sets
  the ceiling for how far the per-module floor can be raised repo-wide.
- `components-registry-service-core` (46 main sources) produces no per-module JaCoCo report: it has
  no tests of its own, so its classes are covered only through the aggregate. A per-module floor
  cannot be enforced there as-is.

Test counts from the same artifact, by module (source sets `test` = 1069, `dbTest` = 794):

| Module | Tests |
|---|---|
| `components-registry-service-server` | 1508 |
| `components-registry-service-client` | 93 |
| `component-resolver-core` | 212 |
| `components-registry-dsl` | 24 |
| `component-resolver-api` | 20 |
| `components-registry-service-light-client` | 4 |
| `components-registry-api` | 2 |

### 4. Static analysis (`static-analysis-reports` artifact of the same run)

**Reported violations (after baselines):**

| Tool | Scope | Blocking | Violations |
|---|---|---|---|
| Checkstyle | Java | yes | 0 |
| PMD | Java | yes | 0 |
| detekt | Kotlin | yes | 0 |
| ktlint | Kotlin | yes | 0 |
| CodeNarc | Groovy | **report-only** | **143** (`component-resolver-core` 138, `component-resolver-api` 5) |
| SpotBugs | — | disabled by policy | n/a |

**Suppressed as tracked debt (baseline file entries) — this is the burn-down number:**

| Module | detekt | ktlint |
|---|---|---|
| `components-registry-service-server` | 111 | 19 |
| `components-registry-compat-test` | 38 | 5 |
| `components-registry-cli` | 18 | 3 |
| `components-registry-service-client` | 8 | — |
| `components-registry-dsl` | 7 | 1 |
| `components-registry-api` | 4 | 2 |
| `components-registry-service-core` | 4 | 3 |
| `components-registry-automation` | 3 | — |
| **Total** | **193** | **33** |

### 5. Architecture fitness (ArchUnit)

| Item | Value |
|---|---|
| Active rules | 5 (4 package-placement + 1 frozen layering) |
| Frozen rule | controllers must not use Spring Data repositories directly |
| Baselined violations in `archunit_violation_store/` | 15 |
| Store policy | `allowStoreCreation=false`, `allowStoreUpdate=false`, `refreeze=false` |
| Deferred rules | TD-016 (package cycles), TD-017 (v4 authorization policy), TD-019 (DB-source boundary) |

### 6. Checks with no baseline value (absent today)

| Check | State |
|---|---|
| Mutation testing | not present anywhere |
| BRANCH coverage gate | no rule (measured value 67.3%) |
| Gherkin / BDD executable specs | not present (0 `.feature` files) |
| Consumer-driven contracts (Pact / Spring Cloud Contract) | not present; only client compilation + the compat gate |
| OpenAPI breaking-change diff vs `main` | not present. Spec **drift** *is* gated — `OpenApiV4SpecTest` asserts byte-equality against the committed `v4.json` |
| `src/main` change requires a `src/test` change | no gate |

### 7. How to re-measure

Every command below is a **single standalone invocation** — no pipes, no `&&`/`;`, no command
substitution, no redirects, no loops. That is the repository's mandatory shell form (see `AGENTS.md`
§Shell Command Safety), and an agent following this recipe must be able to copy each line as-is. Where a
value from one step feeds the next, substitute it by hand into the `<PLACEHOLDER>`.

### TeamCity

Anonymous access is disabled, so every request needs the personal token from the macOS Keychain. Go through
[`scripts/quality-baseline/tc-get.sh`](../../scripts/quality-baseline/tc-get.sh) — **never** read the token
out and paste it into a command. `security find-generic-password -w` prints the secret to stdout, which puts
it in terminal scrollback, shell history and (in an agent session) the tool output that gets recorded;
pasting it into a `curl` command then puts it in the command text as well. The helper does the Keychain
lookup and the request in one process and passes the header to curl on stdin, so the credential never
reaches argv, a log, or a transcript.

`TC_URL` is passed **inline on every call**, not exported once: each command here is a separate invocation,
and in an agent session each one is a fresh shell, so an `export` from a previous step is already gone and
the helper would exit 2. The base URL is internal and deliberately not committed anywhere in this
repository — substitute it from the team's CI bookmark.

Build-configuration IDs are `<TC_PROJECT_ID>_<CONFIG>`, where `<CONFIG>` is `10CompileUtAuto`,
`12IntegrationDbTestsAuto`, `17CompatLocalStandManual`, … as declared in `.teamcity/settings.kts`, and
`<TC_PROJECT_ID>` is the project id from that same file.

Last `main` builds of a configuration:

```bash
TC_URL=<teamcity-base-url> scripts/quality-baseline/tc-get.sh "app/rest/builds?locator=buildType:<TC_PROJECT_ID>_10CompileUtAuto,branch:main,count:3&fields=build(id,number,status,revisions(revision(version)))"
```

Statistics of one build — `TotalTestCount`, `PassedTestCount`, `IgnoredTestCount`, `BuildDuration`:

```bash
TC_URL=<teamcity-base-url> scripts/quality-baseline/tc-get.sh "app/rest/builds/id:<BUILD_ID>/statistics"
```

Ignored tests of one build:

```bash
TC_URL=<teamcity-base-url> scripts/quality-baseline/tc-get.sh "app/rest/testOccurrences?locator=build:(id:<BUILD_ID>),status:UNKNOWN,count:20&fields=testOccurrence(name,status)"
```

### GitHub Actions

Coverage and static-analysis numbers come from the Quality Gates **artifacts**, not from the job logs.
Find the run:

```bash
gh run list --branch main --limit 12 --json workflowName,conclusion,createdAt,databaseId,headSha
```

Download its artifacts. Use a `<TMPDIR>` **outside** the repository — `gh run download` unpacks
module-shaped directories that otherwise land in the working tree:

```bash
gh run download <QUALITY_RUN_ID> -R octopusden/octopus-components-registry-service --dir <TMPDIR>
```

Then read these files:

| Metric | Path under `<TMPDIR>` |
|---|---|
| Aggregate JaCoCo counters | `coverage-reports/build/reports/jacoco/overallCoverage/jacocoOverallCoverageReport.xml` |
| Per-module JaCoCo counters | `coverage-reports/<module>/build/reports/jacoco/test/jacocoTestReport.xml` |
| Per-module test counts | `coverage-reports/<module>/build/test-results/<sourceSet>/TEST-*.xml` |
| Static-analysis violations | `static-analysis-reports/<module>/build/reports/<tool>/*.xml` for `checkstyle`, `pmd`, `detekt`, `ktlint` |

Read only the **top-level** `<counter>` elements of a JaCoCo report (the direct children of `<report>`);
the nested per-package and per-class counters must not be summed.

### Perf median

From the `perf-test-report` artifact of the `Performance SLA` run, in `system-err` — not `system-out`, and
not the job log:

```bash
gh run download <PERF_RUN_ID> -R octopusden/octopus-components-registry-service --dir <TMPDIR>
```

```bash
grep -roE "getComponents\(\) perf[^&<]{0,200}" <TMPDIR>/perf-test-report/test-results/perfTest
```

### Baseline debt counts

These come from the working tree, not from CI. One command per file — the counts are the burn-down
numbers in §4:

```bash
grep -rc "<ID>" --include=detekt-baseline.xml --exclude-dir=build .
```

```bash
grep -rc "<error " --include=ktlint-baseline.xml --exclude-dir=build .
```

```bash
wc -l components-registry-service-server/archunit_violation_store/3e61e124-fe37-40db-9ea6-91a90f6afc18
```
