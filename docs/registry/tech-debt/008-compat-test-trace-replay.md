# TD-008: Compat-test — production trace replay

## Status

Open · P2 · framework completeness · belongs to the addendum of
`~/.claude/plans/async-stirring-koala.md` (TASK-E / Test 2 — trace replay).

## Context

The existing `*CompatTest` suites enumerate endpoints by hand: each suite
picks a smoke / full set of components and a curated set of versions. This
gives high coverage on the contract surface but does NOT reproduce the
**actual production traffic distribution** — i.e. which combinations of
component / version / endpoint are hit hottest in real life.

Concretely:

- A regression that affects the top-100 most-hit components is operationally
  far more painful than a regression on a single rarely-touched component,
  even though they look identical in the current `summary.md`.
- A combination of (path-param, query-param) that production hits 10⁴×/day
  but our smoke set never exercises is a silent gap.

Trace replay closes that gap by reading a dump of the **actual** production
HTTP request log, deduping to unique (method, path) tuples with frequency
counts, and replaying each against both baseline and candidate.

## Input

Trace file format (one entry per line, deduped):

```
<count> <METHOD> <path>
```

Example:

```
8421 GET /rest/api/2/components/payment-gateway
3217 GET /rest/api/3/components
2104 GET /rest/api/2/components/payment-gateway/versions/1.2.3
…
```

- `count` — number of times this exact tuple appeared in the source window.
- `METHOD` — uppercase HTTP verb.
- `path` — the URL path including any expanded path params (the trace is
  already concrete, not templated).

Source: `COMPAT_TRACE_FILE` env var. Default path documented in `README.md`
of `components-registry-compat-test/`; no default in code. When the var is
unset, `TraceReplayCompatTest` calls `Assumptions.assumeTrue(...)` at
`@BeforeAll` with a clear message — so an unconfigured CI lane is reported
as a skip (visible in the test log, not a silent empty replay) and the rest
of the compat run still passes (not a hard abort that would mask unrelated
results). See the Acceptance section below for the symmetric statement.

The trace file itself is **not** committed to the repo — it contains
production component identifiers and request bodies. Operators supply it via
local mount or TC artifact.

## Test class

`TraceReplayCompatTest`:

- Parameterised over trace lines (`@ParameterizedTest @MethodSource("traceLines")`).
- Each parameter = one (count, method, path) tuple.
- For each line:
  1. Make the request against baseline and candidate via `RawHttpClient`.
  2. Run `Comparators.compareRaw(...)` — the existing raw-layer compare
     pipeline (extracted in TD-007 L1) so the same diff-classification
     applies.
  3. Tag the resulting `DiffRecord` with `weight = count` for downstream
     frequency-weighted aggregation.

## Reporting

Two new sections in `summary.md`:

### Top-N frequency-weighted diffs

For each unique (endpoint, category) bucket, sum the `weight` of every
matching record. Sort descending. Show the top 20:

```
| Endpoint                                                    | Category         | Weight   | Distinct  |
|-------------------------------------------------------------|------------------|----------|-----------|
| GET /rest/api/2/components/{c}/maven-artifacts              | STRUCTURAL_DIFF  | 14,302   | 47        |
| GET /rest/api/2/components/{c}/versions/{v}                 | VALUE_DIFF       |  9,127   | 19        |
| …                                                           | …                | …        | …         |
```

- `Weight` = sum of `count` across all matching trace lines.
- `Distinct` = number of unique (component, version, queryParams) that
  produced this (endpoint, category) bucket.

This is the operational triage view: "which divergences hurt the most users".

### Long-tail summary

Aggregate stats for everything below the top-N:

```
- Long-tail: 314 (endpoint, category) buckets totalling 1,829 weight.
```

### Integration with 1a/1b classification (TD-006)

The trace-replay pass produces the canonical 1a set: any endpoint that
appears in trace_replay_test gets the `1a` label in `summary.md`. Endpoints
in `endpoints-baseline.json` that do NOT appear in any trace line get the
`1b` label (zero-traffic-in-sample — operator must explicitly decide whether
to fix or waiver via `known-deltas.json`).

## Configuration

| Env / -P | Default | Purpose |
|---|---|---|
| `COMPAT_TRACE_FILE` / `compat.trace.file` | _(none — must set)_ | Trace dump path |
| `COMPAT_TRACE_LIMIT` / `compat.trace.limit` | _(none — full file)_ | Replay only the top-N tuples of the (rank-ordered) file. The [1.7]/[1.8] auto gate sets `30000`; id16 leaves it unset (full ~75 k). Non-positive / unparseable ⇒ no cap (fail-open to full). |
| `COMPAT_TRACE_TOP_N` / `compat.trace.top-n` | `20` | Top-N in the weighted table |
| `COMPAT_TRACE_PARALLELISM` / `compat.trace.parallelism` | `8` | Concurrent in-flight per stand |

## Replay tiers & endpoint coverage

- **[1.7]/[1.8] auto gate (id17/id18):** `COMPAT_TRACE_LIMIT=30000` — replays the
  30 000 highest-traffic business tuples on every build, alongside the real-body
  replay (`RealBodyReplayCompatTest`, ~1.7 k real POST/PUT bodies). Sized so the
  auto gate reflects realistic prod traffic without an unbounded run.
- **id16 manual:** no limit — replays the full `latest-top.txt` (~75 k unique now,
  growing toward 100 k as more capture windows accumulate).
- **Endpoint-coverage guarantee (trace-present endpoints):** the cap is ranked by
  hit-count, so a rarely-called business endpoint could fall below it. After capping,
  `ensureEndpointCoverage` re-adds the busiest representative of every business-endpoint
  TEMPLATE present in the trace that the cap omitted (`endpointTemplate` collapses
  `{component}`/`{version}`/`{project}` but preserves `find*` actions —
  `find-by-artifact[s]`, `find-by-docker-images`, the legacy camelCase `findByArtifacts`).
  So the 30 k slice always exercises **every business endpoint seen in the capture
  window**. Unit-tested in `TraceReplayParsingTest` (`parseTraceLimit` /
  `applyTraceLimit` / `endpointTemplate` / `ensureEndpointCoverage`).
- **Zero-hit endpoints are NOT in scope of trace replay** (they are absent from the
  trace, so nothing can synthesise them here) — they are covered by dedicated
  `*CompatTest` classes that run in the same [1.7]/[1.8] `test` task. The find-by-* POST
  endpoints are covered by `RealBodyReplayCompatTest`; the formerly-uncovered zero-hit
  endpoints (#324) now have dedicated suites:
  - `CopyrightCompatTest` — `GET /rest/api/3/components/{c}/copyright` (binary octet-stream;
    byte-compare with **content redaction** — only byte lengths are recorded on a mismatch,
    never the licensee-identifying bytes).
  - `DistributionCompatTest` — component-level `GET /rest/api/{1,2}/components/{c}/distribution`
    plus the v2 per-version `.../versions/{v}/distribution`.
  - `ComponentsListV1CompatTest` — `GET /rest/api/1/components` (+ v1 detail).

  Net: every business endpoint is now exercised by the trace slice, the real-body replay,
  or a dedicated suite.

## Implementation

| PR | Scope |
|---|---|
| TD-008-A | This doc + a `@CompatEndpoint` annotation type so the existing suites can tag themselves for traffic classification (without it, the 1a/1b distinction stays advisory). |
| TD-008-B | `TraceReplayCompatTest.kt` (NEW), parameterised over `COMPAT_TRACE_FILE`. |
| TD-008-C | Extend `CompatibilityReporter` (Gradle task) with the top-N + long-tail sections + 1a/1b tagging in summary.md. |

## Acceptance

- A TeamCity manual run with a real trace file populates the top-N table.
- Smoke run with `COMPAT_TRACE_FILE=` unset → `TraceReplayCompatTest` is
  skipped via `Assumptions.assumeTrue(...)` with a clear message; the rest
  of the compat run still passes.
- The frequency-weighted ordering of diffs matches an expected hand-computed
  ordering on a synthetic 3-line fixture trace (unit-level sanity).
- A baseline-contract endpoint that never appears in the trace is labelled
  `1b` in `summary.md`, not silently elided.

## Related

- `~/.claude/plans/async-stirring-koala.md` — TASK-E details.
- `docs/registry/tech-debt/006-compat-test-coverage.md` — TD-006, the
  endpoint-coverage gate that this builds on.
- `docs/registry/tech-debt/009-compat-test-load.md` — TD-009, the k6
  load test that consumes the same trace but in a different shape.
