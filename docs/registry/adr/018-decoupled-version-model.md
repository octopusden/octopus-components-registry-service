# ADR-018: Decoupled version model — supported versions vs per-attribute overrides

## Status

**Accepted** (PR-2 of the version-model plan — migration to the decoupled model). Refines and
promotes the frozen design spec `docs/registry/version-model-spec-DRAFT.md` (which it supersedes as
the authoritative record), incorporating the three plan-review-3 corrections and the results of
pre-migration audits A1/A2.

PR-2 implements layer 1 (supported = ∪ RANGE_PRESENCE) + layer 2 (ALL_VERSIONS base default +
containment overrides) at migration time, plus the coverage-gate rewire on both read paths
(`EntityMappers.toResolvedEscrowModuleConfig` and `ComponentCodeRenderer.renderResolved`). The
write-side invariants (refinements (b) and (c) below, validations V1–V6) land in PR-3.

## Context

The legacy escrow model (and the v3/v4 migration that reproduced it) **conflated two distinct
concerns into a single construct** — the component-level version-range block:

1. **Coverage** — *which versions a component is defined for at all* (outside which a resolve must
   return "no configuration" → 404).
2. **Per-attribute overrides** — *what value an attribute takes over a sub-range* (e.g.
   `build.javaVersion = 17` from some version onward).

A DSL range block `"[1.6,)" { build { javaVersion = 17 } }` simultaneously declared "these versions
exist" and "javaVersion is 17 here". The v4 importer mirrored this by synthesising a **bounded BASE
row** from the first declared block for components that had no top-level (ALL_VERSIONS) config
(`isSyntheticBase`). Two consequences fell out of that:

- **Versions above all declared ranges resolve to nothing.** A component whose highest declared block
  is bounded (e.g. `[…,1.0.16)`) returns "no configuration resolves" for any later version — the
  live production blocker. The user's goal "this version onward → default value" was inexpressible.
- **The editor cannot express "default for all / future versions."** There is no first-class
  all-versions base to carry a default; open-upper intent had nowhere to live, and the portal
  rejected open-upper override input.

A separate but related correctness gap (TD-010) is that override application used **string equality**
on range text, so a broad override was silently dropped on a strictly contained enumeration view.

After a design iteration with the domain owner and two adversarial reviews, we settled on a
**decoupled model**. This ADR records that decision, the algorithms, the write-side invariants, and
the audit evidence that the migration is compatibility-safe.

## Decision

Model a component's configuration as **two independent layers**:

### 1. Supported versions (coverage)

- Stored as `RANGE_PRESENCE` rows. `supported = ∪` of those ranges.
- `resolve(v)` and enumeration **404 outside** `supported`.
- At **migration**, supported = the legacy declared ranges stored **verbatim** (composites kept as a
  single string). When `supported = ALL`, there are no bounded `RANGE_PRESENCE` rows — a single
  `ALL_VERSIONS` base stands for everything.
- After a **v4 write**, supported is stored as canonical **coverage segments** (see the write-time
  auto-split invariant below) — so the "verbatim" property holds only for freshly-migrated rows.

### 2. Per-attribute values

- `base = effective default = (top-level block ⊕ Defaults.groovy)` at `ALL_VERSIONS` — it always
  carries the required fields because the legacy loader resolved a value for every covered version
  via the Defaults fallback.
- **Overrides** apply on ranges **including open-upper** (`[X,)`), selected by **containment**
  (`override.range ⊇ R`), not exact string match.
- **No synthetic bounded base.** The migrated base is always `ALL_VERSIONS`.

### Algorithms

- `resolve(v)`: `v ∉ supported → 404`; else for each attribute, the narrowest override whose range
  contains `v`, else the base value.
- `enumerate()`: iterate `RANGE_PRESENCE` ranges (verbatim), resolving each by containment.

### Three refinements (plan-review-3) — authoritative here

- **(a) Containment scope (shipped, TD-010 / PR #377).** The containment predicate
  (`EntityMappers.rangeApplies`) is used **only on the range-VIEW enumeration path**. The
  single-version `resolve` hot path (`toResolvedEscrowModuleConfig` / `ComponentCodeRenderer
  .renderResolved`) continues to use **point** containment (`containsVersion`) and is untouched. The
  version-range library exposes no `containsRange`, so enumeration containment is a sample-points
  heuristic; one-sided-unbounded children are supported, with an **open-upper child requiring an
  open-upper parent** (decided structurally, not by probing a finite +∞ sentinel).
- **(b) Enumeration breakpoint-alignment + write-time auto-split (Phase 3).** Migrated declared
  ranges are breakpoint-aligned (every attribute is constant within each), so enumeration is one
  entry per `RANGE_PRESENCE`. A future override write that introduces a boundary **inside** a
  supported range must **auto-split** the covering `RANGE_PRESENCE` at that edge, so each enumerated
  range keeps constant resolved values. (Alternative — enumerate-time partition by override edges —
  rejected as read-path complexity; auto-split keeps reads trivial.)
- **(c) V6 — required-field write coverage (Phase 3).** Because the BASE `build_system NOT NULL`
  invariant is retained (see A2), no write may leave a covered version without a required value: if a
  required field has no base value, the union of that field's overrides must cover all of supported,
  else the write is rejected. (This guards the property the DB CHECK guarantees, at write time.)

### Decisions informed by the pre-migration audits

- **A1 — overlapping legacy blocks: none found.** Across the full legacy DSL (45 files), no component
  has two component-level range blocks whose numeric intervals overlap in more than a shared
  endpoint — including after splitting the ~26 compound-key components into their sub-ranges. Legacy
  blocks strictly partition their version space (adjacent / complementary). **Therefore
  containment-based per-attribute resolution produces byte-identical real-version output to legacy
  first-match-wins** — there is no resolution-order ambiguity to preserve. (The full ~130k
  component×version compat baseline remains the PR-2 merge gate; it is generated against the live
  stand, not stored locally.)
- **A2 — per-range-only required fields: none material.** `build_system` is the **only** required-on-
  BASE field in the schema (`chk_..._base_build_system`). `Defaults.groovy` sets `buildSystem = MAVEN`
  globally, so every migrated base (top-level ⊕ Defaults) is non-null: components either set it
  top-level or inherit `MAVEN`. The components that set `buildSystem` only inside a range block all
  use `MAVEN` (equal to the Defaults value), so their per-range value is a no-op against the base.
  **No path to a null base `build_system` → the DB CHECK is kept unchanged (no relaxation).**

## Consequences

**Positive**

- "Default for all / future versions" is expressible (open-upper override on a base = ALL_VERSIONS);
  the production blocker (versions above the highest declared range resolving to nothing) is fixed.
- Compatibility preserved: A1 proves identical real-version resolve/enumerate output; the full compat
  baseline (0-diff) gates PR-2.
- No schema migration for the BASE invariant (A2).
- The portal can edit supported versions and per-attribute overrides (incl. open-upper) as separate,
  legible concerns.

**Negative / cost**

- The `ImportServiceImpl` change is **structural** (drop the synthetic-bounded base; emit
  `ALL_VERSIONS` base + verbatim `RANGE_PRESENCE` + open-upper overrides) → the entire compat suite
  must be re-run, not just unit tests.
- The resolve coverage gate must be **explicitly rewired**: today both `toResolvedEscrowModuleConfig`
  and `ComponentCodeRenderer.renderResolved` *skip* the coverage check when `base.versionRange ==
  ALL_VERSIONS`. Once the base becomes `ALL_VERSIONS`, that gate must instead test membership in
  `supported = ∪ RANGE_PRESENCE`, or tails/gaps would start resolving to base instead of 404 —
  breaking the byte-identical goal.
- New write-side invariants are required (Phase 3): per-attribute disjoint overrides; at most one
  open-upper override per attribute; write-time auto-split (b); V6 required-field write coverage (c);
  allow open-upper overrides server-side.

**Deferred**

- Version lifecycle states (Archived / On maintenance / Active development) — future release; shown
  as a portal teaser only.
- Resolver numeric-vs-lexicographic ordering fix (`V4Mappers.ARTIFACT_MAPPING_ORDER`) — separate
  change; do not introduce a *new* ordering diff regardless.
- TD-010-b: fully-unbounded `(,)` parent/child (unparseable by the version-range factory; falls back
  to conservative string-equality) and negative version bounds.

## Related

- `docs/registry/version-model-spec-DRAFT.md` — detailed design + case matrix M1–M8 / validations
  V1–V6 (superseded by this ADR as the authoritative record).
- `docs/registry/tech-debt/010-range-applies-containment.md` — TD-010 containment predicate (shipped,
  CRS PR #377).
- CRS PR #376 — `/as-code` returns text/plain 404 (not 500) for an unresolvable version.
- Portal PR #138 — version-range editor UX (sticky conflict toasts, persistent value-409 banner,
  readable ownership diff, As-Code default version + friendly out-of-range hint, "All versions" base
  label).
- ADR-014 (schema-v2), ADR-012 (portal architecture / BFF boundary), ADR-017 (artifact-ownership
  modes).
