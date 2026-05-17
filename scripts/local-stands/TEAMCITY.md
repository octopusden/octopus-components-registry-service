# TeamCity local-stand compat job — operator spec

This document describes the manual TC build configuration that runs the local-
stand compat test (boot baseline + candidate side-by-side on the agent, then
run the compat-test module against both). The actual configuration is set up
by hand in the TC UI — there is no in-repo Kotlin DSL. The wrapper script
[`teamcity-run.sh`](teamcity-run.sh) does the orchestration so the TC build
has a single command-line step.

## Build identity

- **Build name**: `Components Registry — Local-Stand Compat`
- **Build type**: regular (not deployment).
- **Project**: place under the same TC project as the other CRS builds.

## Agent requirements

- Linux (preferred) or macOS — both supported by the helper scripts.
- **Java 21** on PATH.
- **Docker daemon** running (used by `postgres-up.sh` via `docker compose`).
- `lsof`, `curl`, `git`, `bash`.
- ≥ **8 GB RAM** free at job start — two CRS JVMs (~1.5 GB each) + postgres
  + gradle worker + headroom.
- ≥ 8 GB free disk under the agent's work-dir (gradle caches + DSL clone +
  service-config clone + postgres volume).

## VCS roots

Set up three TC VCS roots; the build attaches all three with checkout rules
that place each repo in a predictable sub-directory of the build's work-dir:

| VCS root | Repo | Checkout rule | Branch spec |
|---|---|---|---|
| `components-registry-service` | the CRS repo itself | `. => crs/` | `+:refs/heads/feat/schema-v2-sql` (override per run) |
| `registry-dsl` | the DSL git repo | `. => dsl/` | `+:refs/heads/master` (or the branch your QA stand consumes) |
| `service-config` | the service-config repo | `. => service-config/` | `+:refs/heads/master` (see drift note below) |

> **Service-config drift note.** Branch spec `master` means today's service-config
> overlays the baseline JAR (which may have been built against an older
> service-config snapshot). Spring tolerates unknown keys, so this is usually
> safe — but if a key's *value* changed (e.g. a deprecation flip), the baseline
> reports today's behaviour, not its-actual-release behaviour. For bit-exact
> baseline reproduction, override this branch spec to the service-config tag
> matching `%COMPONENTS_REGISTRY_SERVICE_VERSION%` (if your service-config repo
> tags releases). For ordinary "is the contract preserved" sweeps, master is
> fine.

After checkout the agent's work-dir looks like:

```
<work-dir>/
├── crs/                     ← compat-test source + scripts/local-stands/
├── dsl/                     ← Groovy/Kotlin DSL files (LOCAL_VCS_ROOT)
├── service-config/          ← application.yml + components-registry-service.yml
└── artifacts/               ← (populated by artifact deps below)
```

## Artifact dependencies

The build resolves two artifact dependencies — one per side. Both stage their
JAR into `<work-dir>/artifacts/` under predictable file names; the wrapper
script reads from those paths via `BASELINE_JAR` / `CANDIDATE_JAR` parameters.

### Baseline (released old version)

Wire an artifact dependency on whatever TC build produces the **released**
fat JAR of the CRS server.

- Build: `Components Registry — Build` (the same job that publishes to
  Artifactory).
- Build number selector: `%COMPONENTS_REGISTRY_SERVICE_VERSION%`
  (the parameter value at run time — see parameters section below).
- Artifact rule:
  `components-registry-service-server-%COMPONENTS_REGISTRY_SERVICE_VERSION%.jar => artifacts/baseline.jar`

### Candidate (current build chain output)

Wire an artifact dependency on the upstream `Build` step in the same chain.

- Build: the upstream `Build CRS` job (same chain).
- Build number selector: `lastFinished` (or chain-specific).
- Artifact rule:
  `components-registry-service-server-*.jar => artifacts/candidate.jar`

## Parameters

All set via TC's "Parameters" tab. **Marked `(secret)` are confidential and
must use TC's password / secure parameter type** — never inline into the
configuration XML or commit messages (`feedback_redacted_identifiers`).

### System parameters / configuration parameters

| Name | Value / Default | Notes |
|---|---|---|
| `COMPONENTS_REGISTRY_SERVICE_VERSION` | _required, set at run_ | Version of the released baseline (e.g. `2.0.86`). |
| `BUILD_VERSION` | from chain | Version of the candidate; usually `%dep.<upstream-build-id>.build.number%`. |
| `env.BASELINE_JAR` | `%teamcity.build.checkoutDir%/artifacts/baseline.jar` | Picked up by the wrapper. |
| `env.CANDIDATE_JAR` | `%teamcity.build.checkoutDir%/artifacts/candidate.jar` | Picked up by the wrapper. |
| `env.LOCAL_VCS_ROOT` | `%teamcity.build.checkoutDir%/dsl` | DSL clone path. |
| `env.SERVICE_CONFIG_DIR` | `%teamcity.build.checkoutDir%/service-config` | service-config clone path. |
| `env.COMPONENTS_REGISTRY_SERVICE_VERSION` | `%COMPONENTS_REGISTRY_SERVICE_VERSION%` | For the wrapper's log header. |
| `env.BUILD_VERSION` | `%BUILD_VERSION%` | Same. |
| `env.COMPAT_FULL` | `false` | `true` for full sweep (~10-30 min); default smoke (~1-3 min). |
| `env.COMPAT_PARALLELISM` | `8` | Concurrent in-flight requests per stand. |
| `env.COMPAT_RMS_URL` | (optional, secret) | RMS URL for real version sampling. |
| `env.COMPAT_SMOKE_COMPONENTS` | (secret) | CSV of real component names. |
| `env.RESET_DB` | `1` | `1` = `docker compose down -v` before postgres-up (wipes the postgres volume). Required on persistent agents so a previous run's stale rows don't leak. Set `0` only when intentionally iterating on the same DB locally. |
| `env.BASELINE_HEALTH_TIMEOUT_ITERS` | `75` (= 5 min) | Health-poll iterations × 4 s for the baseline. Bump on cold-cache agents. |
| `env.CANDIDATE_HEALTH_TIMEOUT_ITERS` | `120` (= 8 min) | Same for the candidate (includes auto-migrate). |

### Triggers

- **None by default**. Job is manual-run only — it's expensive and
  reproducibility hinges on which `COMPONENTS_REGISTRY_SERVICE_VERSION` the
  operator picks. Add a VCS trigger on `feat/schema-v2-sql` only after the
  job is proven stable.

## Build steps

Single step — invoke the wrapper:

| Step name | Runner type | Command |
|---|---|---|
| Run compat | Command Line | `bash crs/scripts/local-stands/teamcity-run.sh` |

The wrapper:

1. Validates all required env vars and resolved paths.
2. Brings up postgres via `docker compose`.
3. Boots the baseline JAR on port `4567` with V1 profile suite.
4. Waits for baseline `/actuator/health` (up to 5 min).
5. Boots the candidate JAR on port `4568` with schema-v2 DB-mode profile suite
   (`dev,dev-vcs-local,dev-db-automigrate,dev-db-only,local`).
6. Waits for candidate `/actuator/health` (up to 8 min — includes auto-migrate).
7. Parses the candidate log for the migration summary and exits with code `4`
   if any component failed to import (`POLLUTED RUN`).
8. Runs `compat.sh`, which invokes
   `:components-registry-compat-test:test` against both stands.
9. Tears down (kills both JVMs, `docker compose down`) on any exit, success
   or failure.

## Artifact rules (build artifacts)

Publish from the wrapper's gradle reports dir:

```
crs/components-registry-compat-test/build/reports/compat/summary.md          => compat/summary.md
crs/components-registry-compat-test/build/reports/compat/execution-log.md    => compat/execution-log.md
crs/components-registry-compat-test/build/reports/compat/execution-log.ndjson => compat/execution-log.ndjson
crs/components-registry-compat-test/build/reports/compat/diff-worker-*.ndjson => compat/
crs/components-registry-compat-test/build/test-results/test/**/*.xml         => junit-xml/
```

Optional (useful for postmortem when something goes wrong):

```
/tmp/crs-baseline-tc.log   => logs/baseline.log
/tmp/crs-candidate-tc.log  => logs/candidate.log
```

## Reports tab

Add a Markdown / HTML report tab:

- **Tab title**: `Compat summary`
- **Artifact path**: `compat/summary.md`

Operators get the diff classification on the build overview page without
unzipping the artifacts.

## JUnit report

Wire the standard JUnit XML report path: `junit-xml/**/*.xml`. The compat-test
methods are JUnit 5; per-endpoint failures appear as individual rows on the
"Tests" tab.

## Failure-mode mapping (wrapper exit code → TC status)

The wrapper preserves explicit exit codes; configure TC's failure conditions
to surface them clearly:

| Exit | Meaning | TC presentation |
|---|---|---|
| 0   | Compat clean (0 active diffs). | Green. |
| 2   | Env validation failed — missing parameter, file not found. | Red — fix configuration. |
| 3   | Baseline or candidate failed to come up within the health timeout. | Red — check the `logs/baseline.log` / `logs/candidate.log` artifacts. |
| 4   | Polluted run — auto-migrate reported failures. | Red — `compat/summary.md` is unreliable; resolve the import regression upstream. |
| 1   | Gradle exit (active diffs > 0). | Red — review `compat/summary.md`. |
| _other_ | Gradle hang / crash. | Red — check stdout. |

## Reproducibility checklist

Before treating a compat-run report as authoritative:

1. Confirm `COMPONENTS_REGISTRY_SERVICE_VERSION` matches the baseline you
   want to compare against (the build header in the wrapper stdout echoes
   the parameter).
2. Confirm the candidate `BUILD_VERSION` is the chain's expected upstream.
3. In `compat/summary.md`, check the "Environment / precondition" section:
   - No `CANDIDATE_NOT_DB_MODE` warning — candidate is in DB mode. (The wrapper
     pre-asserts `/service/status.defaultSource == "db"` and exits 2 if it
     isn't, so this should never appear in TC runs.)
   - No `SNAPSHOT_MISMATCH` warning — both stands read the same DSL revision.
4. Active divergence count is finite (not "5000+"). If it explodes, suspect
   sort-fix regression and inspect for STRUCTURAL_DIFF patterns.
5. **`componentsTested > 0` in `execution-log.md` header.** A zero count means
   every name in `COMPAT_SMOKE_COMPONENTS` mismatched the stand and the
   per-component tests were skipped via `assumeTrue` — gradle exits 0 and the
   report looks clean for the wrong reason. Cross-check the smoke-list against
   the latest `GET /rest/api/3/components` listing if this happens.

## One-time TC setup tasks

1. Create the build config under the CRS project, name as above.
2. Add the three VCS roots with checkout rules.
3. Add both artifact dependencies (point at correct upstream builds).
4. Add the parameters table.
5. Add the single build step (`bash crs/scripts/local-stands/teamcity-run.sh`).
6. Add the artifact rules + report tab + JUnit wiring.
7. **First run** with the smoke set (`COMPAT_FULL=false`) — should complete
   in ~5-10 min. Verify report tab loads, JUnit XML is parsed.
8. Subsequent runs may flip `COMPAT_FULL=true` for the full sweep.

## Notes / gotchas

- The wrapper kills any JVM listening on the configured ports on teardown
  via `stop-all.sh`. If multiple compat builds run concurrently on the same
  agent, configure them with different `BASELINE_PORT` / `CANDIDATE_PORT`
  values (TC parameters), otherwise they will TERM each other.
- The agent's `/tmp` is used for work dirs (`/tmp/crs-baseline-tc-work`,
  `/tmp/crs-candidate-tc-work`) and logs. Subsequent runs reuse them —
  fresh runs require the wrapper to clean them, OR for the agent to be
  ephemeral. The wrapper relies on Spring's idempotent `work-dir` semantics
  + the postgres `docker compose down` for state cleanup.
- The `bootJar` for either side is NOT built by this job; both JARs come
  from upstream builds via artifact-dependency. This is intentional — keeps
  the compat job fast and decoupled from build-side breakage.
- Real component names live ONLY in the `COMPAT_SMOKE_COMPONENTS` TC
  parameter (secret). They never appear in the repo or in any commit
  message. The wrapper echoes only the count, not the values.
