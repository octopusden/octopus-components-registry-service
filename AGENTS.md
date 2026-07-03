# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, Cursor, Copilot, etc.) when working with code in this repository.

## Project Overview

Components Registry Service — a Spring Boot microservice that manages component metadata (versions, build configs, VCS settings, escrow configs). Currently reads from Git-based Groovy/Kotlin DSL files; actively migrating to PostgreSQL with a new CRUD API (v4) and React UI.

**Key architectural concept:** Component-source routing — each component is individually sourced from either Git or DB via the `component_source` table. No global mode flag. See `docs/registry/adr/007-dual-read-migration.md`.

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

## Search & Context Efficiency

- Before a broad search, identify the target **module** (see the table below) and scope the search to its path — don't scan the whole tree.
- Do **not** read or grep generated/build directories (`build/`, `.gradle/`, `.kotlin/`, `BOOT-INF/`, `.idea/`, `.idea-copy/`, `.teamcity/target/`). All are gitignored, so `rg`/Grep skip them already. Direct `Read` is additionally denied for all of these **except `build/`** in `.claude/settings.json` — `build/` stays readable on purpose because `build/reports/**` (quality + compat summaries) is consulted.
- Prefer `rg` with an explicit module path over unscoped searches.

## Modules

| Module | Purpose |
|--------|---------|
| `component-resolver-api` | Resolver interface definitions |
| `component-resolver-core` | Resolver implementations |
| `components-registry-api` | API payload data beans |
| `components-registry-dsl` | Groovy/Kotlin DSL parsing |
| `components-registry-automation` | CLI automation tools |
| `components-registry-service-core` | Core DTOs and exceptions |
| `components-registry-service-light-client` | Lightweight Feign client (Java 8, legacy Jackson) for consumers on older Gradle-plugin toolchains |
| `components-registry-service-client` | Feign HTTP client |
| `components-registry-service-server` | Spring Boot REST API (main application) |
| `components-registry-compat-test` | API v1/v2/v3 wire-compatibility tests: baseline (prod/`main`) vs candidate (v3). On-demand only — gated on `compat.*.url`; contributes nothing to `build`/`check` |
| `test-common` | Shared test utilities |
| `components-registry-cli` | `crsctl` — read-only command-line client for the v4 API (Kotlin/JVM fat jar) |

### crsctl CLI

`crsctl` (module `components-registry-cli`) is a read-only command-line client for the CRS **v4 API**,
shipped as a self-contained fat jar (`./gradlew :components-registry-cli:shadowJar`, then
`java -jar components-registry-cli/build/libs/components-registry-cli-1.0-SNAPSHOT.jar ...`). Anonymous
read commands (`components`, `component`, `meta`, `whoami`) work without a login; `login` / `audit` /
`meta employees` need a credential and are currently gated on a pending Keycloak public device-flow
client. With `-o json` it emits stable JSON (a top-level array for list-shaped commands), structured
JSON errors on stderr, and pinned exit codes (0 OK, 2 USAGE, 3 NOT_FOUND, 4 AUTH_REQUIRED, 5 SERVER).

Global options (`--env`, `--crs-url`, `--token`, `-o`) precede the subcommand:

```
crsctl --env dev -o json components list --owner alice --system FOO | jq -r '.[].name'
crsctl --env dev -o json component get my-component | jq '.componentOwner'
crsctl --env dev -o json meta owners | jq -r '.[]'
```

See `components-registry-cli/README.md` for the full command surface and `components-registry-cli/skill/SKILL.md`
for the agent-facing Skill with copy-pasteable `jq` recipes.

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

Any change that can alter v1/v2/v3 wire output MUST be validated against a baseline before it's declared complete. **Entry point:** `scripts/local-stands/verify.sh`.

**When touching the read-path / schema / v1-v2-v3 compat surface, read [`docs/registry/compat-gate.md`](docs/registry/compat-gate.md)** — it covers the `verify.sh` flags (`--restart` / `--reset-db` / `--allow-partial-migration`), exit codes, the polluted-run diff signature, the confidential `COMPAT_*` env contract, git-mode (no-db) stands, and how to read `build/reports/compat/summary.md`. For everyday work you don't need it.

## Design Documentation

**Start with [`DOCS.md`](DOCS.md)** — the wayfinding map showing what lives in this repo vs the Portal repo, with the "owns vs delegates" rules.

Architecture Decision Records and design docs in this repo live in `docs/registry/`:
- `technical-design.md` — primary working document
- `prd.md` — product requirements
- `functional-spec.md` — functional specification
- `non-functional-spec.md` — performance, availability, async-job SLAs
- `requirements-{common,migration,resolver}.md` — numbered requirements (`SYS-NNN`, `MIG-NNN`, `RES-NNN` for resolver parity)
- `adr/` — 19 ADRs (000–018), including ADR-012 (Portal architecture, canonical), ADR-013 (Cutover strategy, Proposed), ADR-014 (Schema v2), ADR-015 (Employee-service runtime validation), ADR-016 (Admin config-as-code), ADR-017 (Artifact-ownership modes), ADR-018 (Decoupled version model)
- `tech-debt/` — numbered tech-debt entries (`TD-NNN`)

## Tech Debt Tracking

Tech debt is tracked as numbered entries in `docs/registry/tech-debt/`; the directory listing is the index. Format: `TD-NNN`.

Rules:
- No `TODO` comments in code. Use `// see TD-NNN` to reference the backlog entry.
- Every workaround or deferred fix must have a corresponding `TD-NNN` file before the code is
  committed.
- The `TD-NNN` file must describe: context, workaround applied, and what to do when removing it.

## Technical Requirements Tracking

Два файла с нумерованными требованиями:
- `docs/registry/requirements-migration.md` — требования к миграции (MIG-xxx)
- `docs/registry/requirements-common.md` — общие требования (SYS-xxx)

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
