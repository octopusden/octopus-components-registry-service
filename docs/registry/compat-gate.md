# Compatibility Verification Gate

> **Read this only when you are touching the read-path / schema / API compatibility surface** (import, mapper, resolver, `V1__schema.sql`, or any v1/v2/v3 endpoint behaviour). For everyday work, `AGENTS.md` links here — you don't need it otherwise.

Any change that can alter v1/v2/v3 wire output must be validated against a baseline before it's declared complete. Historically this gate was the mandatory check for the schema-v2 bug-cluster PRs (B / C / D+E / F+G); the same mechanics apply to any read-path/schema change.

**Entry point:** `scripts/local-stands/verify.sh`.

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

**Git-mode (no-db) stands need no Postgres** (issue #310 / SYS-047): `candidate.sh --mode=vcs` and the TeamCity git-mode stand (`id18`, `CANDIDATE_MODE=git`) boot the candidate with the `no-db` profile, which excludes the JDBC/JPA/Flyway auto-configs — the candidate runs with no database. Skip `postgres-up.sh` for these; only db-mode (`--mode=db` / `id17`) requires Postgres.

**Reading `build/reports/compat/summary.md`:**
- `Total recorded / Suppressed / Active` counts at the top — exit code is `0` iff active == 0.
- Diffs are grouped under `### STATUS_CODE_DIFF`, `### STRUCTURAL_DIFF`, `### VALUE_DIFF` headings (plus `### TIMESTAMP_DRIFT` etc. when present).
- For a typed-layer (`Feign` recursive comparison) diff, the assertion direction is `assertThat(candidate).isEqualTo(baseline)` — `actual` = candidate, `expected` = baseline.
- An agent owning one cluster (B / C / D+E / F+G) cares only that diffs scoped to their cluster's endpoint+field signature go to zero. Diffs from sibling PRs not yet landed are expected and tracked separately.

The skill `/crs-compat verify` (user-local, not in this repo) wraps `verify.sh` with the same flag semantics; if invoked from the user's session, prefer the skill — it also surfaces `summary.md` cluster digest. If invoked directly (subagent / CI), call `verify.sh`.
