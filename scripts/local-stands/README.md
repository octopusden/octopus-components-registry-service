# Local compat stands

Spin up two Components-Registry services locally (one per branch) and run the compat
test against them — bypassing the TeamCity build + OKD deploy cycle.

| | Baseline | Candidate |
|---|---|---|
| Branch | `origin/main` (= prod) | `origin/feat/schema-v2-sql` (or its head) |
| Worktree | `_wt/local-baseline` | `_wt/schema-v2-sql` |
| Port | `4567` | `4568` |
| Runtime | fat JAR (`java -jar`, built once via `bootJar`) | Gradle `bootRun` (incremental iteration) |
| Mode | VCS (Git-DSL) | DB (Postgres + auto-migrate from VCS at startup) |
| Profiles | `dev,dev-vcs-local,local` | `dev,dev-vcs-local,dev-db-automigrate,local` |
| Cloud Config | disabled (yamls mounted from local `service-config` clone) | not used (`dev` profile) |

Both stands point at the same local Git-DSL clone (no remote git fetch) so the
**only** difference is the codepath, eliminating data-drift confounds.

## Prerequisites

- Local clone of the Components-Registry DSL repo, somewhere on disk. Tell the scripts
  where via `LOCAL_VCS_ROOT`:
  ```bash
  export LOCAL_VCS_ROOT="$HOME/path/to/your/registry-dsl-clone"
  ```
- Local clone of the `service-config` repo (the one Spring Cloud Config Server in
  prod publishes from). Both stands overlay `components-registry-service.yml`
  from this clone via `--spring.config.additional-location=` — it carries
  production-grade keys (`components-registry.product-type.*`, `supportedGroupIds`,
  …) that the candidate's bundled `dev/` overlays leave unset. The baseline JAR
  additionally pulls `application.yml` because Spring Cloud Config is disabled.
  ```bash
  export SERVICE_CONFIG_DIR="$HOME/path/to/your/service-config-clone"
  ```
  Both env vars have no default — confidential paths must not be hard-coded.
- Docker (for Postgres on candidate side).
- `gradlew` works in both worktrees.
- `AUTH_SERVER_URL` / `AUTH_SERVER_REALM` are **not** required — the scripts set
  `auth-server.disabled=true`.

## Worktrees

Once-per-machine setup:

```bash
# from the repo root or any existing worktree
git worktree add _wt/local-baseline origin/main
# _wt/schema-v2-sql is where these scripts live — you're already there
```

Refresh later:

```bash
git -C _wt/local-baseline fetch && git -C _wt/local-baseline reset --hard origin/main
git -C _wt/schema-v2-sql  fetch && git -C _wt/schema-v2-sql  reset --hard origin/feat/schema-v2-sql
```

## Typical loop

In three terminals (or use `tmux`):

```bash
# Terminal 1 — Postgres for candidate
./scripts/local-stands/postgres-up.sh

# Terminal 2 — baseline (main on 4567, VCS-mode)
./scripts/local-stands/baseline.sh

# Terminal 3 — candidate (schema-v2-sql on 4568, DB-mode with auto-migrate)
./scripts/local-stands/candidate.sh

# Terminal 4 — when both are healthy, run compat-test
# Real component names are confidential — pass via env, not via committed file.
# Provide either a local file outside the repo, or set the env directly:
export COMPAT_RMS_URL="https://your-rms-host/release-management-service/"
export COMPAT_SMOKE_COMPONENTS="comp-1,comp-2,comp-3"   # real names from your prod listing
./scripts/local-stands/compat.sh
```

### Inner-loop verify (after a fix)

`verify.sh` is the gate plan agents call after applying a code/schema fix:

```bash
# Code-only change (import/mapper/resolver) — rebuild candidate JVM, DSL→DB
# re-imports through the new code via dev-db-automigrate profile.
./scripts/local-stands/verify.sh --restart --tests "*VcsSettings*"

# Schema change (V1__schema.sql edit) — additionally wipe the postgres volume
# so Flyway re-applies the schema from scratch before automigrate.
./scripts/local-stands/verify.sh --reset-db --tests "*BuildTools*"

# No candidate change since last run — just re-read state.
./scripts/local-stands/verify.sh --tests "*MavenArtifacts*"
```

Subagent workflow: a PR agent in a sibling worktree exports
`CANDIDATE_WORKTREE="$(pwd)"` before invoking — `candidate.sh` then rebuilds
from that worktree's code on restart. Port-scoped kill leaves parallel
worktrees on other ports untouched.

## Tear-down

```bash
./scripts/local-stands/stop-all.sh
```

Stops both CRS JVMs (baseline `java -jar` on `:4567` and candidate `bootRun` on `:4568`)
by port — only the processes listening on those ports are killed, so parallel agent
worktrees on different ports survive. Then tears down the Postgres container.
Postgres data lives in a docker volume (project-prefixed `<project>_crs_postgres_data`) —
remove it with `docker volume rm <name>` to fully reset.

## Overrides

| Env var | Default |
|---|---|
| `BASELINE_PORT` | `4567` |
| `CANDIDATE_PORT` | `4568` |
| `LOCAL_VCS_ROOT` | _required, no default_ |
| `SERVICE_CONFIG_DIR` | _required for both stands, no default_ — local clone of the service-config repo |
| `BASELINE_WORKTREE` | absolute path of `_wt/local-baseline` |
| `CANDIDATE_WORKTREE` | absolute path of `_wt/schema-v2-sql` |
| `CRS_DB_PORT` | `5432` (Postgres) |
| `CRS_DB_USER` / `CRS_DB_PASSWORD` / `CRS_DB_NAME` | `crs` / `crs` / `components_registry` |
| `COMPAT_SMOKE_COMPONENTS` | _required for component-bearing tests_ — comma-separated real names from prod listing. **Never commit.** |
| `COMPAT_RMS_URL` | _required for version-bearing tests_ — URL of Release Management Service used for sampling real release versions |

Pass any of these to override the defaults before invoking the script:

```bash
export LOCAL_VCS_ROOT="$HOME/your-registry-dsl-clone"
./scripts/local-stands/baseline.sh
```

## Caveats

- `baseline.sh` runs from a fat JAR. On first invocation it executes
  `./gradlew :components-registry-service-server:bootJar -x test` in the
  baseline worktree (~2-3 min); subsequent starts are instant. To force a rebuild
  after pulling new main: delete `_wt/local-baseline/components-registry-service-server/build/libs/`.
- Candidate's `dev-db-automigrate` profile triggers a full import from VCS at
  startup. First start is slow (~30-60 sec for ~1000 components on a clean DB).
- Both Spring Boot apps log to their respective terminal. Use `--quiet` on bootRun
  for less noise once it works.
- The compat-test module is part of the candidate worktree only. `compat.sh` calls
  `gradlew :components-registry-compat-test:test` from there.

## Confidentiality of test inputs

Real production component names, hostnames, and similar customer-identifying data
must **not** be committed to this repo (per the open-source rule). The compat-test
module respects this and reads its smoke list from runtime sources only:

1. `-Pcompat.smoke-components=...` gradle property, or
2. `COMPAT_SMOKE_COMPONENTS` env var, or
3. `/smoke-components.txt` test resource — kept empty in the repo by design.

Same for `COMPAT_BASELINE_URL` / `COMPAT_CANDIDATE_URL` / `COMPAT_RMS_URL`: provide
at runtime, never hard-coded. The scripts in this directory follow the same rule —
`LOCAL_VCS_ROOT` is a mandatory env var with no default, and prod URLs come from a
local-only env file (recommended pattern: keep it under `/tmp/` or `$HOME/.config/`,
outside the repo tree).
