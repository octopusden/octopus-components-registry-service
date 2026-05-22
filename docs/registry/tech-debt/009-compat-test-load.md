# TD-009: Compat-test — k6 load profile

## Status

Open · P3 · framework completeness · doc only in this scope; implementation
deferred to a follow-up session (different toolchain — k6 / Gatling, NOT
JUnit). Belongs to the addendum of `~/.claude/plans/async-stirring-koala.md`
(TASK-F / Test 3 — load test).

## Context

The compat-test suites (`*CompatTest`) and the trace-replay suite (TD-008)
both exercise correctness — does the candidate return the same shape /
values as the baseline. Neither exercises **performance under sustained
load**:

- An N+1 query regression in `DatabaseComponentRegistryResolver` looks fine
  on a one-shot test (response correct, latency ~150ms) but becomes a p99
  cliff at 50 RPS.
- A race condition between the `auto-migrate` background pass and the
  `/rest/api/*` read path manifests only when both fire concurrently.
- Cache invalidation regressions show up as latency tail growth, not as
  diff-classifier failures.

A focused load test fills that gap.

## Scenario

- **Mix:** weighted by the same trace file (`COMPAT_TRACE_FILE`) as TD-008.
  Hottest endpoint = highest RPS share.
- **Ramp:** 0 → 30 RPS over 60 s, hold 30 RPS for 5 min, ramp 30 → 100 RPS
  over 60 s, hold 100 RPS for 5 min, ramp down.
- **Duration:** ~12–15 min total per run.
- **Targets:** baseline and candidate, separate runs (one job per target).

## Metrics

For each endpoint × target, capture:

- p50 / p95 / p99 latency.
- Error rate (any non-2xx for endpoints expected 2xx; any non-4xx for
  endpoints expected 4xx).
- Throughput RPS sustained.

Output as a comparison table:

```
| Endpoint                                                    | p50 base  | p50 cand  | p95 base  | p95 cand  | err base  | err cand  |
|-------------------------------------------------------------|-----------|-----------|-----------|-----------|-----------|-----------|
| GET /rest/api/2/components/{c}                              | 47 ms     | 51 ms     | 142 ms    | 198 ms    | 0%        | 0%        |
| GET /rest/api/2/components/{c}/maven-artifacts              | 89 ms     | 91 ms     | 287 ms    | 432 ms ⚠  | 0%        | 0.03% ⚠   |
| …                                                           | …         | …         | …         | …         | …         | …         |
```

Threshold for ⚠ flagging (configurable per env):

- p95 candidate / p95 baseline > 1.5 → flag.
- err candidate > 0.5% → flag.

## Tool choice

**k6** (default):

- JavaScript scenarios are readable; thresholds are first-class.
- Native Prometheus exporter for run-over-run graphs.

Gatling is a viable alternative if the team standardizes on it elsewhere;
the scenario shape is identical, only the syntax differs.

## Out of scope of `gradlew build`

The load test runs in a **separate workflow** (TeamCity manual job;
optionally a GitHub Actions workflow_dispatch). It is NOT part of:

- `gradlew build` — local + PR CI; would slow PR feedback by 15+ min.
- `gradlew :components-registry-compat-test:test` — that task is for
  correctness, not throughput.

The load test job is opt-in, gated on the same manual-run TC button as
TASK-E's trace replay but in a different configuration.

## Layout

When implemented, layout is expected to be:

```
load-test/                   (NEW top-level dir, not part of any module)
├── README.md                — how to run, where to find results
├── scenarios/
│   ├── compat-mix.js        — k6 scenario reading $COMPAT_TRACE_FILE
│   └── thresholds.js        — shared threshold definitions
├── docker-compose.yml       — k6 + InfluxDB + Grafana for local runs
└── grafana-dashboard.json   — saved dashboard for visualizing results
```

This is **deferred** — the doc spec is the deliverable here, not the code.

## Implementation

| PR | Scope |
|---|---|
| TD-009-A | This doc (current PR). |
| TD-009-B | (Deferred) `load-test/` scaffolding + first scenario. Separate session. |
| TD-009-C | (Deferred) TeamCity manual-run job. Separate session. |

## Acceptance (when fully implemented)

- A manual TC run completes in ~15 min and publishes the latency table as a
  build artifact.
- A regression that increases p95 of any endpoint by >50% causes the job to
  fail with the offending endpoint surfaced in the summary.
- The job consumes the same `COMPAT_TRACE_FILE` as TD-008 — no separate
  mix-file maintenance.

## Related

- `~/.claude/plans/async-stirring-koala.md` — TASK-F details.
- `docs/registry/tech-debt/008-compat-test-trace-replay.md` — TD-008,
  shares the trace file input.
