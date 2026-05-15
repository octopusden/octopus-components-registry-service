# components-registry-compat-test

API v1/v2/v3 compatibility tests comparing two running deployments:

- **baseline** — production (main branch)
- **candidate** — test stand (v3 branch, post-migration)

## Why this module exists

The v3 branch migrates the Components Registry from a Git-DSL store to PostgreSQL and introduces a new v4 CRUD API. By contract, the existing `/rest/api/1`, `/rest/api/2`, `/rest/api/3` endpoints must remain backward-compatible. This module enumerates every legacy endpoint, calls it against both deployments, and reports any divergence in HTTP status, response body shape, or DTO values.

## Configuration

This module deliberately ships **no** corporate hostnames. URLs are passed at run time:

```bash
./gradlew :components-registry-compat-test:test \
  -Pcompat.baseline.url=https://baseline.example.org/components-registry-service/ \
  -Pcompat.candidate.url=https://candidate.example.org/components-registry-service/ \
  -Pcompat.rms.url=https://rms.example.org/release-management-service/
```

Available properties (also accepted as `COMPAT_*` env variables, dots/dashes → underscores):

| Property | Default | Purpose |
|---|---|---|
| `compat.baseline.url` | _required_ | Old (main) API URL |
| `compat.candidate.url` | _required_ | New (v3) API URL |
| `compat.rms.url` | _required_ | Release Management Service URL — source of real component versions |
| `compat.full` | `false` | If `true`, run against the full component listing; otherwise smoke set |
| `compat.parallelism` | `8` | Concurrent test method execution |
| `compat.malformed` | `false` | Enable `MalformedInputCompatTest` (status-only, body ignored) |
| `compat.versions-fallback` | `false` | Allow `versions-fallback.json` fixture to count as real version coverage |
| `compat.smoke-components` | (file) | Comma-separated component name list; overrides `/smoke-components.txt`. Provide real names via env (`COMPAT_SMOKE_COMPONENTS`) or TC parameter — do not commit them. |

## Run only on demand

This module deliberately does **not** participate in the regular `gradlew build` lifecycle:

- The module's `check` task is disabled.
- The `test` task has `onlyIf { at least one of compat.baseline.url / compat.candidate.url is set }` — if **both** are missing (or their `COMPAT_BASELINE_URL` / `COMPAT_CANDIDATE_URL` env equivalents), the task is **skipped at the Gradle level** (no JVM fork, no JUnit launcher). `gradlew build` therefore costs nothing extra for contributors who don't pass compat URLs.
- Setting exactly one of `compat.baseline.url` / `compat.candidate.url` lets the test JVM start so `CompatConfig.explainInvalid()` can surface a fail-fast configuration error at `@BeforeAll`. Gating on "both" at the Gradle layer would silently skip the partial-config case as BUILD SUCCESSFUL, which would hide misconfigured TC / manual runs.

These tests are intended to be triggered from a TeamCity manual-run build configuration (or explicit local `./gradlew :components-registry-compat-test:test -P...`), not from any automated PR/build trigger.

## Failure model

The test task itself does not throw on a single divergence — every diff is _recorded_ via `DiffCollector` and the run continues, so a full picture is collected. After the test task finishes, the Gradle `compatibilityReporter` task aggregates the per-worker ndjson files, writes `summary.md`, and **fails the build** if any recorded diffs exist.

Two diff categories are surfaced as environment warnings at the top of `summary.md`:

- **`SNAPSHOT_MISMATCH`** — baseline and candidate `/service/status .versionControlRevision` differ. The compat run is non-authoritative: data drift between snapshots cannot be distinguished from migration regression. Resolve by re-syncing the candidate stand to the baseline VCS revision.
- **`CANDIDATE_NOT_DB_MODE`** — reserved env category, not currently emitted. The public `ServiceStatusDTO.serviceMode` enum on this branch is `{FS, VCS}` only; there is no DB value to compare against. The candidate's source mode is an internal selector via `ComponentSourceRegistry` / profile settings and must be verified out-of-band. Slot kept for the day a DB-mode signal is exposed.

Both env categories cause the build to fail. They are **not** suppressible via `known-deltas.json` — the reporter explicitly excludes env categories from known-delta matching, since they signal that the comparison itself is unsound (different snapshots, wrong service mode) rather than an intentional v3 delta. Resolve at the operator level.

## Reports

After a run, two artifacts under `build/reports/compat/`:

1. **`summary.md`** — only divergences, grouped by `DiffClassifier` category. Empty file = no unclassified diffs (clean run).
2. **`execution-log.md`** + **`execution-log.ndjson`** — full record of every test case (endpoint × component × params), including clean ones, with status codes and timings. Useful for verifying that a particular component-endpoint-version combination was actually exercised.

## Running locally

A single component smoke run:

```bash
./gradlew :components-registry-compat-test:test \
  -Pcompat.baseline.url=... -Pcompat.candidate.url=... -Pcompat.rms.url=...
```

Full run (slow, ~10–30 min):

```bash
./gradlew :components-registry-compat-test:test \
  -Pcompat.baseline.url=... -Pcompat.candidate.url=... -Pcompat.rms.url=... \
  -Pcompat.full=true
```

## Open-source rule

Real corporate URLs (internal domains, gateway hostnames, etc.) must not appear in this directory or in commit messages. Pass them via local `~/.gradle/gradle.properties` or TeamCity parameters. Pre-commit guard: keep the disallowed-literals regex out of every file under `components-registry-compat-test/` (the canonical list lives in the team's redaction policy doc, not in this repo).
