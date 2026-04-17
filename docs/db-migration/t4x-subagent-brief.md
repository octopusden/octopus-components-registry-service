# T4x — Sub-agent brief for downstream FT migration to `ft-db` profile

This is the instruction template for each T4x task. One sub-agent per downstream.

## Context (common to all T4x)

We added a new Spring profile `ft-db` to components-registry-service on branch
`feature/ft-db-testing` (PR #148). It enables:

- H2 in-memory datasource (no external PostgreSQL)
- Flyway disabled (Hibernate `ddl-auto=create-drop`)
- `components-registry.auto-migrate=true` — on startup the server reads the Git DSL,
  migrates components into H2, then answers queries from the DB

Downstream FT suites currently run CRS as a container with `SPRING_PROFILES_ACTIVE=ft`
and a volume-mounted `application-ft.yaml` that disables VCS and points to a
`/components-registry` DSL tree. They use old CRS versions (e.g. 2.0.78 in releng).

Goal: switch each downstream to use CRS from this branch with profile
`SPRING_PROFILES_ACTIVE=common,ft-db`, while keeping the DSL mount so auto-migrate
has something to populate the DB from.

## Build inputs for the sub-agent

**CRS worktree:** `/Users/pgorbachev/projects/octopus/octopus-components-registry-service/_wt/ft-db-testing`

**Build image from that worktree (must be on branch `feature/ft-db-testing`):**
```
./gradlew :components-registry-service-server:dockerBuildImage
```
This tags locally as `ghcr.io/octopusden/components-registry-service:1.0-SNAPSHOT`.
Re-tag with a unique tag to avoid clashing with the default `1.0-SNAPSHOT` tag on other
branches:
```
docker tag ghcr.io/octopusden/components-registry-service:1.0-SNAPSHOT \
          ghcr.io/octopusden/components-registry-service:1.0-SNAPSHOT-ft-db
```

**Plan doc this task belongs to:** `docs/db-migration/ft-db-testing-plan.md`

## Guardrails

1. **Branch from fresh `origin/main`.** Before starting: `git fetch origin && git switch -c
   feature/crs-ft-db origin/main`. Do not branch off a stale local `main`, a feature
   branch, or a worktree that is behind upstream. If the downstream repo uses a different
   default branch (e.g. `master` or `develop`), branch from that — but always from the
   latest `origin/<default>` after `git fetch`.
2. **Do NOT merge** anything. End with an open PR in the downstream repo.
3. **Do NOT change application logic** in the downstream — the only scope is CRS wiring.
4. **Do NOT skip failing downstream FT** — if something breaks, diagnose. If the root
   cause is in CRS, stop and report; create a requirement in
   `octopus-components-registry-service/docs/db-migration/requirements-*.md` and a failing
   test in that repo (see "TDD rule on problems" below). Do not patch around it in the
   downstream.
5. **Do NOT delete** the existing `application-ft.yaml` / custom YAML overrides reflexively.
   Read them, decide which keys still matter, and either keep the mount or fold keys into
   environment variables on the CRS container. Justify the choice in the PR description.
6. **Commit messages:** `chore(ft): switch CRS to ft-db profile`. PR title mirrors that.

## TDD rule on problems

If CRS misbehaves under `ft-db` (missing endpoint behaviour, schema mismatch, migration
gap), the fix lives in CRS, not in the downstream:

1. Add a row to the matching `requirements-*.md` in CRS (new SYS-xxx / MIG-xxx / RES-xxx).
2. Write a failing test in CRS that names the requirement ID in `@DisplayName`.
3. Commit red → commit fix → push to `feature/ft-db-testing`.
4. Re-pull, re-build image, re-try the downstream switch.

Report back to the parent with the requirement ID and the CRS commit SHAs, so the parent
keeps the plan doc in sync.

## Per-task specifics

### T4a — DMS Service

- Repo: `/Users/pgorbachev/projects/octopus/octopus-dms-service`
- Files to touch:
  - `ft/src/ft/docker/docker-compose.yaml` (the `components-registry-service` block)
  - `ft/src/ft/docker/components-registry-service.yaml` (current override)
- FT runner: `./gradlew :ft:ft` (or equivalent — confirm from `ft/build.gradle.kts`)
- Old image env: `OCTOPUS_COMPONENTS_REGISTRY_SERVICE_VERSION` — override to the locally
  tagged `1.0-SNAPSHOT-ft-db` for the duration of this test; do not hardcode the override
  into repo files. The PR should show how to opt-in (env var or a new compose variant).

### T4b — Releng (jira-releng-plugin-ft)

- Repo: `/Users/pgorbachev/projects/ow/releng`
- File to touch: `ft/jira-releng-plugin-ft/pom.xml` — two `<image>` blocks for
  `components-registry-service` (lines ~140 and ~408).
- Runner: `mvn -f ft/jira-releng-plugin-ft/pom.xml verify`
- Version property: `<octopus-components-registry-service.version>` at top of pom.

### T4c — Releng (maven-crm-plugin-ft)

- Same repo as T4b. File: `ft/maven-crm-plugin-ft/pom.xml`.
- Runner: `mvn -f ft/maven-crm-plugin-ft/pom.xml verify`
- T4b and T4c should be ONE PR in `ow/releng` (same repo) — confirm with the parent before
  splitting into two PRs.

### T4d — ORMS (release-management-service)

- Repo: `/Users/pgorbachev/projects/octopus/octopus-release-management-service`
- Files to touch:
  - `ft/docker/docker-compose.yml` (the `components-registry-service` block ~line 30)
  - `ft/docker/components-registry-service.yaml` (current override)
- FT runner: confirm from `ft/build.gradle.kts` (likely `./gradlew :ft:ft`)
- Version property: `OCTOPUS_COMPONENTS_REGISTRY_SERVICE_VERSION` env var

## Expected report back to parent

Under 300 words, covering:

- What changed (file list + key diff summary)
- Downstream FT result (green / red; if red, why)
- If CRS was patched: requirement ID(s), CRS commit SHA(s), and that
  `ft-db-testing-plan.md` was NOT edited by the sub-agent (parent does that)
- Downstream PR URL

## Out of scope

- TeamCity / CI definitions (will happen after downstream PRs land)
- Moving CRS image to a registry (we consume locally tagged image)
- Cleaning up old `application-ft.yaml` files (do minimal change)
