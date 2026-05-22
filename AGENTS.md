# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, Cursor, Copilot, etc.) when working with code in this repository.

## Project Overview

Components Registry Service — a Spring Boot microservice that manages component metadata (versions, build configs, VCS settings, escrow configs). Currently reads from Git-based Groovy/Kotlin DSL files; actively migrating to PostgreSQL with a new CRUD API (v4) and React UI.

**Key architectural concept:** Component-source routing — each component is individually sourced from either Git or DB via the `component_source` table. No global mode flag. See `docs/db-migration/adr/007-dual-read-migration.md`.

## Build & Test Commands

```bash
./gradlew build                    # Full build with tests and quality checks
./gradlew test                     # Unit tests (JUnit 5)
./gradlew integrationTest          # Integration tests (fat JAR startup validation)
./gradlew qualityStatic            # Static analysis: Checkstyle, PMD, detekt, ktlint
./gradlew qualityCoverage          # Tests + JaCoCo coverage reports

# Single module test
./gradlew :components-registry-service-server:test

# Single test class
./gradlew :components-registry-service-server:test --tests "*.FooTest"

# Docker
./gradlew dockerBuildImage
```

Quality reports index: `build/reports/quality/index.html`

## Shell Command Safety

- **NEVER** use pipes (`|`), `&&`, `||`, `;`, `2>&1`, `>`, or `$()` in Bash commands. This is a hard rule, no exceptions.
- **NEVER** redirect output to files (`> file.txt`) — Claude Code captures stdout/stderr and exit code automatically.
- **NEVER** append `echo "Exit: $?"` — Claude Code already sees the exit code.
- **NEVER** write polling/wait loops in bash (for/while loops waiting for a server). Instead: run `sleep N` as a separate call, then `curl` as a separate call, repeat if needed.
- **ALWAYS** wrap URLs in double quotes in curl commands to prevent `&` in query strings being interpreted as shell operators.
  - ❌ `curl -s http://localhost:4567/api?page=0&size=1`
  - ✅ `curl -s "http://localhost:4567/api?page=0&size=1"`
- Always run one command at a time as a separate tool call.
  - ❌ `./gradlew dependencyInsight 2>&1 | head -40`
  - ✅ `./gradlew dependencyInsight` — output is captured automatically
  - ❌ `cd /some/dir && npm install`
  - ✅ `cd /some/dir` — then `npm install` as a separate call
- **NEVER** combine `cd` with `git` commands — use `git -C <path>` instead.
  - ❌ `cd /some/dir && git status`
  - ✅ `git -C /some/dir status`
- **NEVER** pipe `curl` output into `python3` or any other command. Save curl output to a temp file, then process it separately.
  - ❌ `curl -s "http://..." | python3 -c "import json; ..."`
  - ✅ `curl -s "http://..." -o /tmp/response.json` — then `python3 -c "import json; f=open('/tmp/response.json'); ..."`

## Modules

| Module | Purpose |
|--------|---------|
| `component-resolver-api` | Resolver interface definitions |
| `component-resolver-core` | Resolver implementations |
| `components-registry-api` | API payload data beans |
| `components-registry-dsl` | Groovy/Kotlin DSL parsing |
| `components-registry-automation` | CLI automation tools |
| `components-registry-service-core` | Core DTOs and exceptions |
| `components-registry-service-client` | Feign HTTP client |
| `components-registry-service-server` | Spring Boot REST API (main application) |
| `test-common` | Shared test utilities |

## Tech Stack

- **Language:** Kotlin 1.9.25 + Java 21
- **Framework:** Spring Boot 3.2.2, Spring Cloud 2023.0.1
- **Database:** PostgreSQL 16+ with JPA/Hibernate
- **Build:** Gradle (wrapper)
- **API docs:** Springdoc OpenAPI 2.3.0
- **Security:** Keycloak + octopus-security-common

## Architecture

Layered architecture within `components-registry-service-server`:

- **Controllers** — REST endpoints: v1/v2/v3 (read-only, backward-compatible), v4 (CRUD, new)
- **Service layer** — `ComponentsRegistryService`, `VcsService`, `CopyrightService`
- **Repository layer** — Spring Data JPA
- **Component routing** — `ComponentRoutingResolver` dispatches per-component to Git or DB source

All existing v1/v2/v3 endpoints (34 endpoints, 28 Feign client methods) must remain backward-compatible.

## Code Style

- **Kotlin:** 4-space indent, 140-char line length, trailing commas allowed. Enforced by detekt + ktlint (blocking). Module-level baselines: `detekt-baseline.xml`, `ktlint-baseline.xml`.
- **Java:** Checkstyle (no wildcard imports) + PMD (both blocking).
- **Groovy:** CodeNarc (report-only, not blocking).
- Config files in `config/checkstyle/`, `config/pmd/`, `config/detekt/`, `config/codenarc/`.

## Quality Gates

- Java checks (Checkstyle, PMD) are **blocking**.
- Kotlin checks (detekt, ktlint) are **blocking** with module-level baselines.
- Groovy CodeNarc is **report-only** — do not make blocking without explicit decision.
- **SpotBugs intentionally disabled** in `build.gradle` via `tasks.matching { it.name.startsWith('spotbugs') }.configureEach { enabled = false }` (the plugin itself stays applied because it's owned by the `octopus-quality` convention plugin). Bytecode-flow analysis gave high false-positive rate on Kotlin lateinit / DSL getter / test code; the per-language tools above already cover the same ground. Do not re-enable without an explicit policy decision.
- JaCoCo coverage: per-module minimum 10%, overall weighted minimum 70%.

## CI Workflows

- Reuse shared GitHub workflows from `octopus-base` for `build`, `quality`, and `security` instead of copying repository-local workflow logic.
- For PR checks, avoid duplicate runs: prefer `pull_request` and restrict `push` triggers to `main` or release branches.

## Coverage

- Treat unit-test coverage and FT/integration coverage as separate concerns.
- Generic GitHub quality checks should enforce only coverage that GitHub actually measures reliably.
- FT or OKD coverage should be enforced only in the environment that really runs those scenarios.
- The repository uses both a low per-module coverage floor and an overall weighted coverage threshold.

## Reports

- Publish machine-readable reports for CI and keep `build/reports/quality/index.html` as the unified human-readable entry point when multiple reports exist.
- If the unified index should be available from a normal `build`, wire it to `build` or `check` and ensure it depends on the report-producing tasks.
- TeamCity XML watcher paths and artifact paths are separate concerns; configure both explicitly.

## Running Locally

IntelliJ run configurations in `.run/`:
- `ComponentRegistryService (dev-fs)` — filesystem-based
- `ComponentRegistryService (dev-vcs-local)` — local Git
- `ComponentRegistryService (dev-vcs-remote)` — remote Git

Required env vars: `LDAP_USERNAME`, `LDAP_PASSWORD`, `COMPONENTS_REGISTRY_VCS_ROOT`

## Compatibility Verification (after every read-path / schema change)

**Entry point:** `scripts/local-stands/verify.sh`. This is the single gate any agent working on schema-v2 bug-cluster PRs (B / C / D+E / F+G) MUST run before declaring a fix complete.

What it does, per flag:

| Flag | When | Behaviour |
|---|---|---|
| `--restart` | code-only change (import / mapper / resolver) | port-scoped kill of the candidate JVM, respawn from `$CANDIDATE_WORKTREE` via `candidate.sh`, wait `/actuator/health` UP, run `:components-registry-compat-test:test`. DSL→DB automigrate re-runs through the new code on every restart. |
| `--reset-db` | edit to `V1__schema.sql` | implies `--restart`, plus `docker compose down -v` so Flyway re-applies the schema from scratch before automigrate. |
| `--allow-partial-migration` | targeted smoke knowingly excluding failed components | after restart, the gate parses the auto-migrate summary; without this flag, any `failed > 0` makes verify exit `4` (POLLUTED RUN). With it, the gate prints the warning + failed-component list but proceeds. Only use when your test filter skips the failed set. |
| _(no flag)_ | re-read state | runs compat against the existing stands without touching them. |

**Exit codes:** `0` clean / `2` baseline down or env missing / `3` candidate failed to come up / `4` polluted run / `*` gradle exit code from compat.

**Polluted run — how to recognize one from the diff signature alone:**

If you have a `summary.md` and don't know whether the run was polluted (e.g. you didn't see the verify banner):

- `STATUS_CODE_DIFF` dominated by `200 → 500` (not `200 → 404`) — endpoints crash on missing-component refs.
- `NULL_VS_EMPTY` on `GET /rest/api/3/components` for many distinct `componentId=` — those components weren't imported.
- `VALUE_DIFF` count is small or zero while `STRUCTURAL_DIFF` and `STATUS_CODE_DIFF` are huge.

This combination is the characteristic signature of a partially-migrated DB. **Do not classify these diffs as your cluster's regressions** without first ruling out the polluted-state hypothesis — re-run with `--restart` (which always invokes the migration health-check) or grep the candidate log for `Failed to migrate component '`. The no-flag path of `verify.sh` does NOT invoke the health-check (it has no fresh log to look at), so a polluted candidate started by an earlier `--restart` could keep serving stale state until the next restart-flagged run.

**When `--reset-db` surfaces an upstream import regression:** if the gate exits `4` and the failed components are not in *your* cluster (B / C / D+E / F+G), the regression belongs to an earlier merged PR — file it separately, don't fold the fix into your cluster PR. To continue validating *your* cluster while the upstream fix is in flight, use `--allow-partial-migration` together with a smoke filter (`COMPAT_SMOKE_COMPONENTS=` or `--tests`) that excludes the failed components.

**Env contract** (verify.sh fails fast with a clear message if any required var is missing when a restart-flag is used):

```bash
export CANDIDATE_WORKTREE="$(pwd)"            # subagent's own worktree, so the rebuild uses its code
export LOCAL_VCS_ROOT="<your DSL clone>"      # path on your machine, never committed
export SERVICE_CONFIG_DIR="<service-config clone>"  # carries production-only keys absent from dev/ overlays
export COMPAT_SMOKE_COMPONENTS="<csv>"        # comma-separated real component names, from session/user
export COMPAT_RMS_URL="<RMS URL>"             # optional but recommended for real-version sampling
```

All four `COMPAT_*` values are confidential per the open-source rule — they live in env (or a local `/tmp/compat-*-env.sh`), never in committed files or commit messages. `scripts/local-stands/README.md` has the full operator-facing detail.

**One-shot bootstrap** (first time on a clean host, or after `stop-all.sh`): `postgres-up.sh` → `baseline.sh` (one-time `bootJar`, ~2 min) → `candidate.sh` → then `verify.sh` for subsequent iterations. Baseline is `origin/main` (or whatever fat JAR is in `_wt/local-baseline/components-registry-service-server/build/libs/`); `baseline.sh` rebuilds the JAR automatically when any `*.kt`/`*.java`/`*.gradle` in the baseline worktree is newer.

**Reading `build/reports/compat/summary.md`:**
- `Total recorded / Suppressed / Active` counts at the top — exit code is `0` iff active == 0.
- Diffs are grouped under `### STATUS_CODE_DIFF`, `### STRUCTURAL_DIFF`, `### VALUE_DIFF` headings (plus `### TIMESTAMP_DRIFT` etc. when present).
- For a typed-layer (`Feign` recursive comparison) diff, the assertion direction is `assertThat(candidate).isEqualTo(baseline)` — `actual` = candidate, `expected` = baseline.
- An agent owning one cluster (B / C / D+E / F+G) cares only that diffs scoped to their cluster's endpoint+field signature go to zero. Diffs from sibling PRs not yet landed are expected and tracked separately.

The skill `/crs-compat verify` (user-local, not in this repo) wraps `verify.sh` with the same flag semantics; if invoked from the user's session, prefer the skill — it also surfaces `summary.md` cluster digest. If invoked directly (subagent / CI), call `verify.sh`.

## Design Documentation

**Start with [`DOCS.md`](DOCS.md)** — the wayfinding map showing what lives in this repo vs the Portal repo, with the "owns vs delegates" rules.

Architecture Decision Records and design docs in this repo live in `docs/db-migration/`:
- `technical-design.md` — primary working document
- `prd.md` — product requirements
- `functional-spec.md` — functional specification
- `non-functional-spec.md` — performance, availability, async-job SLAs
- `requirements-{common,migration,resolver}.md` — numbered requirements (`SYS-NNN`, `MIG-NNN`, `RES-NNN` for resolver parity)
- `adr/` — 15 ADRs (000–014), including ADR-012 (Portal architecture, canonical), ADR-013 (Cutover strategy, Proposed), ADR-014 (Schema v2)
- `tech-debt/` — numbered tech-debt entries (`TD-NNN`)

## Tech Debt Tracking

Tech debt is tracked as numbered entries in `docs/db-migration/tech-debt/`; the directory listing is the index. Format: `TD-NNN`.

Rules:
- No `TODO` comments in code. Use `// see TD-NNN` to reference the backlog entry.
- Every workaround or deferred fix must have a corresponding `TD-NNN` file before the code is
  committed.
- The `TD-NNN` file must describe: context, workaround applied, and what to do when removing it.

## Technical Requirements Tracking

Два файла с нумерованными требованиями:
- `docs/db-migration/requirements-migration.md` — требования к миграции (MIG-xxx)
- `docs/db-migration/requirements-common.md` — общие требования (SYS-xxx)

Rules:
1. Every bug → new requirement with acceptance criteria
2. Every new feature → requirements first, then implementation
3. After writing a test → update status to ✅ and fill in "Test method"
4. System verifier checks requirements with status ✅ Tested
5. All requirement content must be written in English

### Test-to-Requirement Traceability

Every test method that covers a numbered requirement (MIG-xxx, SYS-xxx) MUST:
1. Include the requirement ID in the method name: `` `MIG-001 migration preserves buildSystem from Defaults`() ``
2. Use `@DisplayName("MIG-001: ...")` annotation for readable test reports
3. After passing — update requirement status to ✅ and fill in "Test method" field
