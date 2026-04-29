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

## Design Documentation

**Start with [`DOCS.md`](DOCS.md)** — the wayfinding map showing what lives in this repo vs the Portal repo, with the "owns vs delegates" rules.

Architecture Decision Records and design docs in this repo live in `docs/db-migration/`:
- `technical-design.md` — primary working document
- `prd.md` — product requirements
- `functional-spec.md` — functional specification
- `non-functional-spec.md` — performance, availability, async-job SLAs
- `implementation-progress.md` — **living document tracking all completed tasks, bugs fixed, and current status**
- `todo.md` — backlog index (recently shipped, deferred, tech-debt, future ideas)
- `requirements-{common,migration,resolver}.md` — numbered requirements (`SYS-NNN`, `MIG-NNN`, `RES-NNN` for resolver parity)
- `adr/` — 14 ADRs (000–013), including ADR-012 (Portal architecture, canonical) and ADR-013 (Cutover strategy, Proposed)
- `tech-debt/` — numbered tech-debt entries (`TD-NNN`)

## Implementation Progress Tracking

**Rule: `docs/db-migration/implementation-progress.md` MUST be updated after every completed task.**

This is a mandatory step in the workflow. After finishing any implementation task:

1. Add the task to the appropriate phase/iteration section in `implementation-progress.md`
2. Mark status (✅ Done, 🚧 In Progress, ❌ Blocked)
3. Include brief notes: what was done, key files changed, bugs found/fixed
4. If a new bug was discovered and fixed, add it to the "Known Bugs Fixed" section

### System Verifier Agent

When using the `system-verifier` agent after implementation work, its prompt **MUST** include:
- Instruction to read `docs/db-migration/implementation-progress.md`
- Instruction to verify that all claimed completed tasks actually work
- Instruction to update the document if verification reveals issues or if tasks are missing from the log

### Workflow

```
Implement task → Verify (compile/test/runtime) → Update implementation-progress.md → Next task
```

Do NOT batch progress updates. Update the document **per task**, not at the end of a session. This ensures the document always reflects the true state even if a session is interrupted.

## Tech Debt Tracking

Tech debt is tracked as numbered entries in `docs/db-migration/tech-debt/` with an index in
`docs/db-migration/todo.md`. Format: `TD-NNN`.

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
