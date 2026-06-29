# TD-010: `EntityMappers.rangeApplies` strict-containment heuristic

## Status

**Resolved** · P2 · correctness gap, not contract drift · sister to MIG-049-bis follow-ups.

Fixed in branch `fix/td-010-range-containment`: `EntityMappers.rangeApplies` now implements range
containment (child is a subset of parent) via the sample-points heuristic below, threading
`numericVersionFactory: NumericVersionFactory` through `toEscrowModule` → `resolveForRange` →
`rangeApplies`. The conservative `// see TD-010` KDoc at the callsite is replaced with the
containment contract. Scope is the **range-VIEW enumeration** path only; the single-version
`resolve` path (`toResolvedEscrowModuleConfig` / `ComponentCodeRenderer.renderResolved`) was already
correct (point `containsVersion`) and is untouched.

Bounded cases 1–9, union cases 10–11, and **open-ended cases OE-1..OE-10** (the everyday
"from version X onward" `[X,)` / "up to version Y" `(,Y)` shapes) ship as a parameterized matrix in
`RangeAppliesContainmentTest`; the DB-backed `@Tag("integration")`
`RangeViewBroadOverrideContainmentIntegrationTest` proves a broad `[1.0,3.0)` scalar override is
projected onto a contained narrow `[1.0,2.0)` enumeration view (RED-verified against the old
equality predicate). Only the fully-unbounded `(,)` parent/child and negative bounds are deferred to
**TD-010-b** (see Deferred below).

## Problem

`EntityMappers.rangeApplies(parent, child, factory)` (line 243) is currently a string-equality predicate:

```kotlin
private fun rangeApplies(
    parentRange: String,
    childRange: String,
    @Suppress("UNUSED_PARAMETER") factory: VersionRangeFactory,
): Boolean = parentRange == childRange
```

Consumers — `resolveForRange` and the per-range scalar/marker override resolution paths — therefore only match a parent override range to a child target range when the two strings are byte-identical. In particular:

- A broader override (e.g., `[1.0,3.0)`) is silently dropped when enumerating a strictly contained child range (e.g., `[1.0,2.0)`), because `"[1.0,3.0)" != "[1.0,2.0)"`.
- A multi-segment union override (e.g., `(,0),[0,)`) is dropped against any concrete child range.

Surfaces as missing scalar overrides on enumeration endpoints (`getAllJiraComponentVersionRanges`, `getMavenArtifactParameters`) for components whose DSL declares broad-then-narrow override stacks.

## Why this is deferred (not in PR #192 main branch)

Plan rev 3 — Sonnet plan-review pushback: a "sample-points heuristic" can yield false positives on open/closed bound mismatches, on union-vs-interval intersections, and on partial overlap (neither contains the other). Without an exhaustive matrix test it is unsafe to ship as a merge-blocker correctness fix.

## Acceptance

A follow-up PR must satisfy ALL of the following before the FIXME-equivalent comment in `EntityMappers.kt:243` can be removed:

### 1. Matrix test set — minimum 8 cases

For each case, the test asserts the expected `rangeApplies(parent, child)` outcome.

| # | parent | child | expected | rationale | category |
|---|--------|-------|----------|-----------|----------|
| 1 | `[1.0,2.0)` | `[1.0,2.0)` | true | exact equality (preserved) | bounded |
| 2 | `[1.0,3.0)` | `[1.0,2.0)` | true | strict left-aligned containment | bounded |
| 3 | `[1.0,3.0)` | `[2.0,3.0)` | true | strict right-aligned containment | bounded |
| 4 | `[1.0,3.0)` | `[1.5,2.5)` | true | strict interior containment | bounded |
| 5 | `[1.0,2.0)` | `[1.5,2.5)` | false | partial overlap, child extends past parent | bounded |
| 6 | `[1.0,2.0]` | `[2.0,3.0)` | false | single-point intersection at closed boundary — NOT containment | bounded |
| 7 | `[1.0,2.0)` | `[2.0,3.0)` | false | adjacent disjoint, no overlap | bounded |
| 8 | `(1.0,3.0)` | `[1.5,2.5]` | true | strict interior, mixed bound styles (parent open, child closed) | bounded |
| 9 | `[1.0,2.0)` | `[1.0,2.0]` | false | child's closed upper exceeds parent's open upper by ε | bounded |
| 10 | `(,0),[1.0,)` | `[2.0,3.0)` | true | union-parent contains right-segment-only child | union |
| 11 | `(,0),[1.0,)` | `[-1.0,0.5)` | false | child straddles a gap in the union-parent | union |
| 12 | `(,)` | `[1.0,2.0)` | true | unbounded parent contains any child | unbounded |

**Acceptance bar:** all 9 **bounded** cases (1–9) must ship in the TD-010 PR. The 3 **union/unbounded** cases (10–12) may be deferred to a `TD-010-b` follow-up if the underlying `VersionRangeFactory` API doesn't yet support union/unbounded ranges — track explicitly. The "minimum 8" headline above is satisfied by the bounded subset alone.

**As shipped:** cases 1–9, **10–11**, and the **open-ended OE-1..OE-10** cases are green in
`RangeAppliesContainmentTest`. The `VersionRangeFactory` (releng-lib 2.0.8) *does* parse the
open-left-unbounded union parent `(,0),[1.0,)`, so the heuristic evaluates 10/11 correctly.

**Open-ended child support (review follow-up).** One-sided-unbounded children — the everyday
`[X,)` "from version X onward" and `(,Y)` "up to version Y" shapes — are supported: `parseSingleInterval`
accepts one empty bound. The **open-upper** case is decided **structurally**, not by probing: a child
that runs to +inf can only be contained in a parent that ALSO runs to +inf, so `rangeApplies` first
checks `isOpenUpper(parentRange)` and returns `false` for an open-upper child against any
non-open-upper (i.e. bounded, including closed-or-finite-upper) parent — *regardless* of how high the
parent's finite upper bound is. Only once the parent is confirmed open-upper does sampling proceed,
where `childRangeSamples` probes the child's floor plus a high in-tail probe (`HUGE_TAIL_VERSION`)
against that confirmed-open-upper parent. The high probe is therefore a sample *within a confirmed
unbounded tail*, **not** a finite stand-in for +inf that a bounded parent could exceed — encoding
infinity as a finite number was the original flaw (a parent like `[1.0,10000000.0)` would have
"contained" `[2.0,)`, a false positive). The open-lower side uses `ZERO_VERSION` symmetrically.
Acceptance cases: `rangeApplies("[1.0,)","[2.0,)")==true` (OE-1),
`rangeApplies("[2.0,)","[1.0,)")==false` (OE-2), `rangeApplies("(,0),[1.0,)","[2.0,)")==true` (OE-3,
open-left-union parent), and the structural-guard guards
`rangeApplies("[1.0,10000000.0)","[2.0,)")==false` (OE-9, bounded parent above the sentinel) and
`rangeApplies("[5.0,)","[2.0,)")==false` (OE-10, open-upper parent, child floor below). Only the
fully-unbounded `(,)` (both sides empty) remains unparseable/deferred.

Two adjustments are recorded:

- **Case 11 child** is shipped as `[0.5,1.5)` rather than the literal `[-1.0,0.5)`. The factory treats
  `-` as a version separator (so `-1.0` parses to `1.0`), which makes `[-1.0,0.5)` fail the
  `min ≤ max` check at parse time — negative versions are unsupported. `[0.5,1.5)` preserves the
  case's intent: its lower endpoint `0.5` lies in the union-parent's gap `[0,1.0)`, so it is not
  contained → `false`.
- **Case 12** (`(,)`, both-sides-open unbounded parent) is **deferred to TD-010-b**: the factory
  rejects a restriction with neither bound at parse time ("Bad range: no minimum, maximum allowed
  versions are specified"), so the heuristic cannot probe against it. `rangeApplies` falls back to
  string equality when the parent fails to parse, which keeps `(,)` vs a concrete child conservatively
  `false` (a missed override, never a wrong one). The deferral is pinned by an explicit test asserting
  the current conservative `false`, so TD-010-b will flip a known assertion rather than discover the
  gap.

### 2. Implementation approach

Sample-points heuristic over `NumericVersionFactory.parse(...)`:

1. Parse `parent` via `VersionRangeFactory`; parse the `child` bound string into its endpoints.
2. Sample the child endpoints (closed-bound: inclusive; open-bound: ε-shifted just inside the interval).
3. Sample N interior probes between child endpoints.
4. Return `true` iff EVERY sample satisfies `parent.containsVersion(sample)`.

Thread `numericVersionFactory: NumericVersionFactory` through `toEscrowModule` → `resolveForRange` → `rangeApplies` (the old `factory: VersionRangeFactory` param was unused).

**As implemented (API note).** releng-lib 2.0.8 exposes `NumericVersion`/`IVersionInfo` with **no
`bump()`** method, so the ε-shift is done by constructing a successor/predecessor version from the
endpoint string rather than calling `bump()`:

- ε-above (open lower bound): append a low-order `.0.0.0.1` tail — trailing zeros compare equal under
  the factory's padding, so the value sorts immediately above the endpoint.
- near-upper probe (open upper bound): a value just below `upper` (drop one minor and append a large
  low-order tail), covering an overshoot above the coarse whole-version grid.
- interior probes: whole-version grid points plus sub-major minor steps across the span; versions in
  this registry are integer-component, so the grid + endpoints + near-upper probe fully cover any
  sub-interval that could escape the parent. A parent that fails to parse falls back to string
  equality (conservative).

### 3. Regression-guard tests

In addition to the matrix above, add at least one **integration-level** test using an actual schema-v2 DB-backed component with a broad scalar override that the current code silently drops; the test asserts the override is applied to the narrow enumeration range.

### 4. Migration sanity

Per `project_crs_schema_v2_migration_policy`: schema-v2 is not in prod yet. The QA stand pererecreates from `V1__schema.sql` and re-imports from Groovy DSL. The fix changes only **read-side** semantics — no backfill / migration script needed.

## Deferred — TD-010-b

- **Unbounded parent `(,)` (matrix case 12).** `VersionRangeFactory` rejects a both-sides-open
  restriction at parse time, so the sample-points heuristic cannot run against it. `rangeApplies`
  falls back to string equality (conservative `false`). A pinned test asserts the current `false` so
  TD-010-b is a known flip rather than a discovery. Options for TD-010-b: special-case `(,)` (and any
  `ALL_VERSIONS`-equivalent) as "contains everything" before the parse, or add a `containsRange` to
  the version-range library.
- **Negative version bounds.** The factory parses `-` as a separator, so negative endpoints (e.g.
  `[-1.0,…)`) are unrepresentable; not a real registry case, noted only because the original matrix
  case 11 used one.
- **Multi-segment parent with a far-out internal gap.** The sample-points heuristic can theoretically
  false-positive if a parent (an override row) has an internal gap narrower than the probe spacing and
  located beyond the sampled grid (e.g. a union override missing `[500,600)` while an open-ended child
  spans it). Override rows in this registry are single ranges of small integer-component versions, so
  this does not occur in practice; a real `containsRange` API would remove the approximation entirely.

## Out of scope

- Partial-overlap rejection on the **write** side (validation in `ComponentManagementServiceImpl.validateRangeSyntax`) — same blocker (no `containsRange` API in the version-range library); separate follow-up.

## Why this isn't a merge blocker for PR #192

The conservative-false behaviour means consumers see *fewer* overrides than the DSL intended, not *wrong* overrides. End-users get the unmodified base value where they expected an overridden value — visible only on the specific enumeration endpoints and only for components whose DSL has strict-containment override stacks. None of those components are in the smoke set today.

Trace-replay against the candidate stand surfaces this if it bites; if no diff appears in compat-test, no production caller is affected.

## Related

- `EntityMappers.kt` — `rangeApplies` (the former FIXME callsite); the `// see TD-010` KDoc is now the
  containment contract. The predicate is `internal` so `RangeAppliesContainmentTest` calls it directly.
- `RangeAppliesContainmentTest` — the cases 1–11 matrix + the case-12 deferral pin + near-upper
  robustness cases.
- `RangeViewBroadOverrideContainmentIntegrationTest` — DB-backed (`@Tag("integration")`,
  `:components-registry-service-server:dbTest`) end-to-end guard.
