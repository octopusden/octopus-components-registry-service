# API Compat Deltas — Compat Surface, Known Deltas, Pre-flight

This doc explains how the `components-registry-compat-test` module decides what counts
as a compatibility regression between the v2 prod baseline and the v3 schema-v2 candidate,
and how to document intentional behavioral changes so they stop failing the build.

## Compat surface scope

The compat-test exercises **API contracts**:

- `GET /rest/api/2/components/{component}` and per-version detail endpoints
- `GET /rest/api/2/components/{component}/versions/{version}/{detailed-version,jira-component,build-tools}`
- `GET /rest/api/2/components/{component}/maven-artifacts`
- `GET /rest/api/2/projects/{project}/{distribution,component-distributions,jira-components,jira-component-version-ranges,vcs-settings}` (per-project + per-version)
- `GET /rest/api/2/common/jira-component-version-ranges`
- `POST /rest/api/2/components/find-by-artifact` and the batch detailed-versions endpoint
- `GET /rest/api/2/components-registry/service/ping` — plain-text behavioral contract
- `PUT /rest/api/2/components-registry/service/updateCache` — **phase-aware** contract: returns **200** + the Git-refresh duration (ms) while any component is still served from Git (migration-status `git > 0`), and **410 Gone** only once every component is migrated to the DB (`git <= 0`). A fully-migrated db-mode candidate returns 410 → suppressed via `known-deltas-db.json`; a git-mode (no-migration) candidate returns 200 and matches baseline → no delta. See "Per-mode known-deltas" below.

**Operational metadata endpoints are explicitly excluded** from the compat surface:

- `GET /rest/api/2/components-registry/service/status` — read **only** by
  `SnapshotPreconditionTest` for two fields (`versionControlRevision`, `serviceMode`)
  via a local `ServiceStatusSnapshot` projection. The `cacheUpdatedAt` field is
  transient (per-run timestamp) and is not part of any contract.

Rationale: keeping transient runtime fields in the typed-DTO compare produces
per-run noise that future readers mistake for regressions, and fixing it inside
the comparator (field skip / sentinel value) is fragile.

## Pre-flight checklist before running compat-test

`./gradlew :components-registry-compat-test:test` is gated on the
`compat.baseline.url` Gradle property (or `COMPAT_BASELINE_URL` env var). Before
running, confirm:

1. **Both stands report the same `versionControlRevision`** on
   `GET /service/status`. Auto-checked by `SnapshotPreconditionTest`; mismatch
   records a `SNAPSHOT_MISMATCH` env-warning (fail-causing, not suppressible).
   Mechanism: each stand checks out the max-numeric tag matching its
   env-specific `components-registry.vcs.tagVersionPrefix`
   (`GitTagResolver.resolve()`); different prefix per env → different tag →
   different revision, **by design**. Align by configuring matching prefixes
   in `f1/service-config` or by waiting for both stands to pick up the same
   release tag.

2. **Candidate `cacheUpdatedAt` has been stable for at least a few minutes.**
   The candidate runs a periodic VCS clone+resolve cycle; running compat-test
   during that cycle produces a flood of transient `STRUCTURAL_DIFF` records as
   different requests see mid-refresh snapshots. Verify with two curls of
   `/service/status` ~30s apart — `cacheUpdatedAt` should not advance.

3. **Candidate's read path** (Git source vs DB source) must be verified
   out-of-band — there is currently no signal exposed in `ServiceStatusDTO`
   indicating which the candidate is using (the public `serviceMode` enum is
   `{FS, VCS}` only, the schema-v2 DB/Git selector is an internal switch via
   `ComponentSourceRegistry` / profile). **Critical**: if the candidate is
   serving from the legacy VCS code path, compat-test validates only that the
   candidate binary still reads VCS correctly — it does NOT validate the
   actual schema-v2 DB read-path. Any DB-read-path regressions are invisible
   in this configuration. Operator must confirm the candidate is in the
   intended mode before the run.

4. **Wipe `build/reports/compat/`** before re-runs. Per-worker ndjson files
   contain a UUID in the filename; old files would otherwise be merged into
   the new `summary.md` and double-count diffs. The `test` task's
   `doFirst` block now handles this automatically, and `outputs.upToDateWhen { false }`
   forces re-run.

## Per-mode known-deltas (db vs git)

The candidate can run in two modes, and the sanctioned deltas differ, so the
deltas live in **two files** selected by `-Pcompat.known-deltas` (or
`COMPAT_KNOWN_DELTAS`), default `known-deltas-db.json`:

| File | Candidate mode | Selected by | Contents |
|---|---|---|---|
| `known-deltas-db.json` | migrated, `default-source=db` (`git==0`) — id17 / `[1.7]` | default | the `updateCache` 410 delta (db-mode is fully migrated, so the endpoint is retired) |
| `known-deltas-git.json` | no-migration, `default-source=git` (`git>0`) — id18 / `[1.8]` | `-Pcompat.known-deltas=known-deltas-git.json` (set by `teamcity-run.sh` when `CANDIDATE_MODE=git`) | **intentionally empty** |

`known-deltas-git.json` is empty by design: in git-mode every v1/v2/v3 response
is served by the Git resolver — the same code path as the 2.0.87 baseline — and
phase-aware `updateCache` returns 200, so there are **no** sanctioned deviations.
This encodes the **deploy-without-migration no-op invariant**: any active diff in
git-mode is a real regression and must be fixed, never suppressed.

## Known-delta entry format

Each entry in the active known-deltas file (see "Per-mode known-deltas" above)
under `deltas[]`:

| Field | Required | Semantics |
|---|---|---|
| `endpoint` | yes | Must match `DiffRecord.endpoint` exactly. Format: `"<METHOD> <path>"`, e.g. `"PUT /rest/api/2/components-registry/service/updateCache"`. The HTTP method is part of this string; there is no separate `method` field. |
| `category` | yes | One of `STATUS_CODE_DIFF`, `HEADER_DIFF`, `STRUCTURAL_DIFF`, `VALUE_DIFF`, `NULL_VS_EMPTY` (see `DiffClassifier`). **Env categories (`SNAPSHOT_MISMATCH`, `CANDIDATE_NOT_DB_MODE` — the latter currently dormant) are not suppressible.** |
| `baselineValue` | no | Exact-match (string compare); `null` means "any". |
| `candidateValue` | no | Exact-match; `null` means "any". |
| `pathParams` | no | Map exact-match; `null` means "any". |
| `queryParams` | no | Map exact-match; `null` means "any". Required to distinguish e.g. `?ignore-required=true` from `?ignore-required=false` when only one of the two variants is intentional. |
| `reason` | yes | Free-text justification. Should reference the relevant code path and/or PR. |
| `regressionTest` | yes | UT method name in `ClassName.testMethodName` form (simple class name, no package prefix — e.g. `ComponentsRegistryServiceControllerUpdateCacheTest.git equal to zero retires the endpoint with 410 and does not re-read`) that pins the intended behavior. This is the contract that prevents the entry from going stale. |

## Categories of resolution

When a diff appears that wasn't there before, classify it as one of:

### 1. Intentional v3 change

Example: `C.1` — `PUT /service/updateCache` returns `410 Gone` on a **fully-migrated**
v3 candidate (`git<=0`), where it was `200` on v2. This is the end-state of the
phase-aware endpoint: once every component lives in the DB, a manual Git re-read is
meaningless. (Note: this is a db-mode-only delta — a git-mode/no-migration candidate
still returns 200, matching v2, so it is NOT a delta there.)

**Procedure**:
1. Ship a regression UT that pins the intended behavior on the candidate side.
2. Add an entry to `known-deltas.json` referencing that UT in `regressionTest`.
3. PR description justifies why this is intentional (link to ADR / spec / ticket).

### 2. Data drift after tag-revision divergence

Example: baseline returns one extra docker image in a project's
`distribution.docker` field that candidate omits — because each stand resolves
to a different `tagVersionPrefix` and catches different DSL revisions.

**Procedure**: NOT entered as a known-delta. Surfaces via `SNAPSHOT_MISMATCH`
precondition record (`SnapshotPreconditionTest`). Resolve at the operator level
by aligning the stands' configured prefixes or waiting for both to pick up the
same release tag. Drift is by design; do not paper over.

### 3. Operational metadata

Example: `/service/status` carries `cacheUpdatedAt` (per-stand timestamp).

**Procedure**: NOT entered as a known-delta. Endpoint is excluded from the
compat surface entirely; only `SnapshotPreconditionTest` reads it via a
projection of the two contract-bearing fields. Do not add the endpoint back
to a compat test.

### 4. Real regression

Example: candidate's `/build-tools` returns an empty list for components that
inherit `BuildEnv` from `Defaults.groovy`, while baseline returns `[BuildEnv]`.

**Procedure**: this is a bug. Add a regression UT (e.g., `GitVsDbValidationTest`
VAL-XXX) that reproduces it deterministically against the candidate code path,
then fix the code. Do NOT enter as known-delta; the entry would mask the bug.

## Workflow for adding a known-delta entry

1. Confirm the diff is in category 1 (intentional v3 change). If it is in
   category 2/3/4, follow that category's procedure instead.
2. Ship the regression UT first; entry's `regressionTest` field is mandatory
   and must reference an existing test method.
3. Add the entry to `known-deltas.json`.
4. PR description justifies the intentional behavior change with a link to
   the relevant ADR / spec section / ticket.

## What to do when compat-test fails

`./gradlew :components-registry-compat-test:test` fails iff `activeTotal > 0`,
where `activeTotal = env-warnings + unsuppressed endpoint diffs`. Suppressed
records (matched known-deltas) never count.

Failure modes:

- **`SNAPSHOT_MISMATCH` active** → stands resolved to different VCS tags / revisions; not a code issue. Align by configuring matching `tagVersionPrefix` per env in `f1/service-config` or by waiting for both to pick up the same release tag.
- **(Historical) `CANDIDATE_NOT_DB_MODE`** → category is currently dormant on this branch (no DB value in `ServiceMode`). Operator must verify the candidate's read path (Git vs DB source) out-of-band before assuming a clean run means the schema-v2 DB read-path is validated.
- **`STRUCTURAL_DIFF` with hundreds/thousands of records on one endpoint** →
  almost certainly a mid-refresh race on candidate. Wait for `cacheUpdatedAt`
  stability and re-run.
- **A handful of `VALUE_DIFF` or `STATUS_CODE_DIFF` records on specific
  endpoints** → real regressions. Investigate, classify into category 1 or
  category 4, follow the corresponding procedure.
