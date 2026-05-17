# TD-010: `EntityMappers.rangeApplies` strict-containment heuristic

## Status

Open · P2 · correctness gap, not contract drift · sister to MIG-049-bis follow-ups · referenced from `EntityMappers.kt:243` and `docs/db-migration/todo.md` Tech Debt list.

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

### 2. Implementation approach

Sample-points heuristic over `NumericVersionFactory.parse(...)`:

1. Parse `parent` and `child` ranges via `VersionRangeFactory`.
2. Sample the child endpoints (closed-bound: inclusive; open-bound: ε-shifted via `NumericVersion.bump()`).
3. Sample at least N interior probes between child endpoints.
4. Return `true` iff EVERY sample satisfies `parent.containsVersion(sample)`.

Thread `numericVersionFactory: NumericVersionFactory` through `resolveForRange` → `toEscrowModule` → `rangeApplies` (currently `factory: VersionRangeFactory` is unused; rename or replace).

### 3. Regression-guard tests

In addition to the matrix above, add at least one **integration-level** test using an actual schema-v2 DB-backed component with a broad scalar override that the current code silently drops; the test asserts the override is applied to the narrow enumeration range.

### 4. Migration sanity

Per `project_crs_schema_v2_migration_policy`: schema-v2 is not in prod yet. The QA stand pererecreates from `V1__schema.sql` and re-imports from Groovy DSL. The fix changes only **read-side** semantics — no backfill / migration script needed.

## Out of scope

- Partial-overlap rejection on the **write** side (validation in `ComponentManagementServiceImpl.validateRangeSyntax`). Tracked separately in `docs/db-migration/todo.md` Tech Debt section ("Field-override partial-range-overlap rejection") — same blocker (no `containsRange` API in the version-range library).

## Why this isn't a merge blocker for PR #192

The conservative-false behaviour means consumers see *fewer* overrides than the DSL intended, not *wrong* overrides. End-users get the unmodified base value where they expected an overridden value — visible only on the specific enumeration endpoints and only for components whose DSL has strict-containment override stacks. None of those components are in the smoke set today.

Trace-replay against the candidate stand surfaces this if it bites; if no diff appears in compat-test, no production caller is affected.

## Related

- `docs/db-migration/todo.md` Tech Debt: "EntityMappers.rangeApplies strict-containment heuristic" and "Field-override partial-range-overlap rejection" (the same blocker on the write side).
- `EntityMappers.kt:243` — the FIXME callsite, now replaced with `// see TD-010` in PR fix/pr-192-docs-test-strengthening.
