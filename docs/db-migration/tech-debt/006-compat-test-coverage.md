# TD-006: Compat-test endpoint coverage + traffic classification

## Status

Open · P1 · framework completeness · belongs to the addendum of
`~/.claude/plans/async-stirring-koala.md` (TASK-C / Test 1 — full endpoint
coverage).

## Context

The compat-test module today exercises a curated, but not exhaustive, list of
v1 / v2 / v3 endpoints. The strict-compat contract (`AGENTS.md:85`) covers
**34 endpoints across `/rest/api/{1,2,3}`**. The current `*CompatTest` set
covers ~27 of those. The gap leaves seven endpoints (or endpoint families)
unguarded against silent backward-compat regressions:

| # | Endpoint | Why uncovered |
|---|---|---|
| 1 | `GET /rest/api/1/components` | v1 surface — never had a dedicated suite |
| 2 | `GET /rest/api/1/components/{c}` | v1 surface |
| 3 | `GET /rest/api/1/components/{c}/distribution` (and v2 variant) | inherited from `BaseComponentController`, easy to miss in a hand-written enumeration |
| 4 | `POST /rest/api/3/components/find-by-artifacts` | new in v3, no compat suite ever wired |
| 5 | `POST /rest/api/3/components/find-by-docker-images` | new in v3, no compat suite ever wired |
| 6 | `GET /rest/api/3/components/{c}/copyright` | binary octet-stream — needs raw-bytes comparison path, not JSON shape |
| 7 | `GET /rest/api/2/components/{c}/versions/{v}/distribution` | per-version distribution; v2 variant |

There is also no static gate that asserts "every strict-compat baseline
endpoint has at least one `*CompatTest` method": today a new agent can land an
endpoint regression as long as no human happens to spot it.

## Plan

### Single source of truth: `endpoints-baseline.json`

A checked-in JSON file at
`components-registry-compat-test/src/test/resources/endpoints-baseline.json`
encodes the strict-compat contract. Each entry contains:

- `method`, `path` — the canonical mapping (e.g. `GET`, `/rest/api/2/components/{component}/maven-artifacts`).
- `controller` — origin class for traceability.
- `probe` (optional) — preflight probe with `pathParams` to substitute and
  `expectedStatus` for a known nonexistent / known-OK probe target.
- `expectedBodyShape` (optional) — for 404 / 410 cases, the set of keys the
  response body must contain (distinguishes router-404 from business-404 per
  `ErrorResponse.kt:6`).

The file is generated **once** from the main-branch controller AST (see
"Generation" below) and committed. Future legacy-endpoint additions on main
require a manual PR that updates this file.

Diagnostic endpoints (`/service/status`, `/service/ping`, `/service/updateCache`,
`/actuator/**`) are **explicitly excluded** from this baseline. See the
"compat surface scope" section of the addendum plan — they live in a separate
`diagnostic-endpoints.json` (or are filtered by path-prefix in the coverage
test).

### Coverage gate — two test classes

Per the TD-007 L4 split (`:unitTest` is URL-config-free; `:test` is
HTTP-gated), the coverage gate is implemented as **two** classes — the
static-analysis assertions and the live-HTTP probe must not share a JUnit
tag, otherwise either PR CI starts requiring a deployed candidate stand or
the liveness gate gets silently skipped on URL-less runs.

**`EndpointCoverageTest` (`@Tag("unit")`, pure static — runs in `:unitTest`):**

1. **Baseline coverage** — for each `endpoints-baseline.json` entry, at
   least one method in any `*CompatTest` class is annotated
   `@CompatEndpoint("METHOD path")` matching that entry. Today the
   annotation is informational; this test elevates it to a contract.
2. **Candidate additions (warn-only)** — statically scan the v3-branch
   controller AST, diff against the baseline, surface any *new* endpoints
   in the summary as `candidate adds: METHOD path`. Not blocking — new
   endpoints are allowed; this just makes them visible for review.

**`EndpointLivenessProbeTest` (`@Tag("http")`, URL-gated — runs in `:test`):**

3. **Candidate liveness (preservation probe)** — at `@BeforeAll`, HTTP-probe
   each baseline entry against the candidate stand. Expected
   `actualStatus == probe.expectedStatus` and, if `expectedBodyShape` is
   declared, the actual body must contain those keys. Probe failure means
   the candidate has dropped an endpoint or changed its terminal-status
   behavior.

### Generation

The static scan that produces `endpoints-baseline.json` uses the Kotlin
compiler / PSI API to walk:

- `@RestController` / `@Controller` classes.
- The class-level `@RequestMapping` prefix.
- All superclasses (notably `BaseComponentController<T>`) for inherited
  `@*Mapping` annotations.
- Method-level `@GetMapping` / `@PostMapping` / etc.

If the PSI approach proves fragile, fall back to runtime introspection via
`RequestMappingHandlerMapping` started in a minimal Spring context
(`auth-server.disabled=true`, `spring.autoconfigure.exclude=*JpaAutoConfig*`,
`--web=none`).

### Traffic-classified reporting (1a / 1b)

Optional preflight reads an env-provided trace file (default
`COMPAT_TRAFFIC_TRACE=/tmp/crs-top1000-augmented.txt`). For each baseline
endpoint, classify:

- **1a** — appears in the trace window. High operational priority.
- **1b** — in the baseline contract but NOT seen in the trace.

Both 1a and 1b diffs are **gate-failing by default** (the strict-compat
contract from §Context of the addendum plan applies to all v1/v2/v3 endpoints
regardless of traffic). The only way to suppress a diff is the existing
`known-deltas.json` mechanism with an explicit reviewer waiver. Traffic data
is used for **report ordering / prioritization** only — 1a diffs render at the
top of `summary.md` with a "high-traffic" badge, 1b diffs render lower with a
"zero-traffic-in-sample" badge.

## Implementation

One follow-up PR per dimension; the doc PR (this file) lands first to anchor
the spec.

| PR | Scope | Files |
|---|---|---|
| TD-006-A | `endpoints-baseline.json` + AST scanner | `components-registry-compat-test/src/test/resources/endpoints-baseline.json` (NEW, populated), `EndpointContractScanner.kt` (NEW) |
| TD-006-B | `EndpointCoverageTest` | `EndpointCoverageTest.kt` (NEW) + `@CompatEndpoint` annotation (NEW) + thread through existing `*CompatTest` methods |
| TD-006-C | `DistributionCompatTest` (v1 + v2 + per-version) | `DistributionCompatTest.kt` |
| TD-006-D | `FindByArtifactsV3CompatTest` | `FindByArtifactsV3CompatTest.kt` |
| TD-006-E | `FindByDockerImagesCompatTest` | `FindByDockerImagesCompatTest.kt` |
| TD-006-F | `CopyrightCompatTest` (binary) | `CopyrightCompatTest.kt` |
| TD-006-G | V1-surface extension | `ComponentsListV1CompatTest.kt` or extend `ComponentsListCompatTest.kt` |
| TD-006-H | Traffic-classification report ordering | extend `CompatibilityReporter` Gradle task |

Each follow-up PR has its own `@CompatEndpoint` wiring and lands the
corresponding `endpoints-baseline.json` row (or removes the corresponding
warn entry). The full set closes the gap.

## Acceptance

- `gradlew :components-registry-compat-test:unitTest` reports `EndpointCoverageTest` green.
- `endpoints-baseline.json` has 34 entries (matches `AGENTS.md:85`).
- All seven listed endpoint gaps are covered by a `*CompatTest` method.
- A new endpoint added on main without a matching baseline entry is flagged
  (warn) by the candidate-additions check.
- A baseline entry without a `*CompatTest` method fails the coverage assertion.

## Related

- `~/.claude/plans/async-stirring-koala.md` — TASK-C, including the
  per-endpoint preservation probe schema.
- `docs/db-migration/tech-debt/007-compat-test-self-tests-and-review.md` — the
  framework self-tests (TD-007) that this depends on for the comparator
  unit-test substrate (`Comparators.compareRaw` is extracted).
- `docs/db-migration/tech-debt/008-compat-test-trace-replay.md` — TD-008, the
  frequency-weighted production-trace replay that consumes the same baseline
  list.
