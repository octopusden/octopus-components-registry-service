# ADR-018: Decoupled version model — supported versions vs per-attribute overrides

## Status

**Accepted** (PR-2 of the version-model plan — migration to the decoupled model). Refines and
promotes the frozen design spec `docs/registry/version-model-spec-DRAFT.md` (which it supersedes as
the authoritative record), incorporating the three plan-review-3 corrections and the results of
pre-migration audits A1/A2.

Implementation: layer 1 (supported coverage) + layer 2 (ALL_VERSIONS base default + containment
overrides) at migration time, plus the coverage-gate rewire on both read paths
(`EntityMappers.toResolvedEscrowModuleConfig` and `ComponentCodeRenderer.renderResolved`) and the
write-side invariants (validations V1–V6).

> **Redesign refinement (authoritative; supersedes the original §1/§Algorithms/refinement-(b)
> below).** The first cut coupled enumeration to coverage: it stored one `RANGE_PRESENCE` row per
> legacy block (verbatim) and auto-split coverage on every override write. That was wrong — it leaked
> override boundaries into the *coverage* concept the two layers were meant to separate, and forced
> the user to re-merge ranges by hand. The corrected model:
>
> - **Coverage is stored MERGED and is override-independent.** At migration, `supported = mergeUnion`
>   of the legacy declared ranges (maximal contiguous segments); blocks that tile all-versions
>   collapse to `supported = ALL` (no `RANGE_PRESENCE` rows at all). A v4 PUT stores the requested
>   ranges merged the same way — overlapping/contiguous inputs merge, no disjoint requirement, no
>   auto-split. Coverage never reshapes itself around overrides.
> - **Enumeration is computed at READ time** as the *partition of `supported` by value-change edges*
>   (scalar/marker override range endpoints ∪ artifact-ownership range endpoints), each sub-range
>   resolved by containment (TD-010). No edge between two adjacent sub-ranges ⇒ they stay one view, so
>   redundant-identical legacy blocks collapse automatically; any real attribute change keeps its
>   edge. Doc links are NOT edges (legacy never enumerated a boundary for a doc major).
> - Net effect for the user: editing supported versions is purely about coverage; the v2/v3 range
>   lists are derived and always minimal. See `VersionRangePartition` (`mergeUnion` + `partition`).

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

- Stored as `RANGE_PRESENCE` rows, **merged** (`supported = mergeUnion` of the declared/requested
  ranges → maximal contiguous segments). The rows are override-independent.
- `resolve(v)` and enumeration **404 outside** `supported`.
- At **migration**, supported = `mergeUnion` of the legacy declared ranges. When the union is
  all-versions (blocks tile everything), there are no `RANGE_PRESENCE` rows — a single `ALL_VERSIONS`
  base stands for everything.
- A **v4 PUT** stores the requested ranges merged the same way (overlapping/contiguous merge; no
  disjoint requirement; no auto-split). `{all:true}` clears coverage back to ALL.

### 2. Per-attribute values

> **AMENDMENT (2026-07, base-row = newest): the base-row VALUE semantics below are SUPERSEDED.**
> The rules in this section originally set `base = (top-level block ⊕ Defaults.groovy)` and treated
> the open-upper block as an override. We intentionally supersede that: the **base row now carries the
> effective value of the OPEN-UPPER (newest) range** — i.e. what current versions resolve to — and the
> OLDER/other declared blocks (including the historical-left `(,Y)` and any bounded blocks) become the
> overrides. Rationale: the base row is the component's editable default in the portal and the fallback
> for the newest band, so it must show the value current versions actually use — not a `Defaults.groovy`
> fallback that no declared range uses (observed live as a stale `releaseVersionFormat` on one
> component and a stale `versionPrefix` on another). Everything else in this ADR (coverage layer,
> containment, enumeration, migration
> compatibility) is UNCHANGED — only which config seeds the base row changes. See
> `ImportServiceImpl.selectBaseConfig` and `VersionRangePartition.baseCandidateComparator`.
>
> **Confirmed legacy-surface change (2026-07-01).** Per-VERSION resolution is byte-identical, but the
> COMPONENT-LEVEL representative — `Component.distribution` (and `escrow`) in the legacy v1/v2/v3
> list (`GET /components`), detail (`GET /components/{c}`) and standalone
> (`GET /components/{c}/distribution`, now deprecated) responses — is rendered from the BASE row and
> therefore switches from the top-level/oldest block's values to the NEWEST block's. The full-stand
> compat run (build 4099: ~130k replayed prod-trace tuples + the exhaustive sweep) surfaced exactly
> 11 affected components — ten on `distribution.gav`/`docker` and one on `escrow.generation`
> (UNSUPPORTED→AUTO). Component keys are withheld from this public repo (CI Content Validation);
> the authoritative list is in the internal compat report for build 4099. The domain owner reviewed
> every live baseline↔candidate pair and confirmed the NEW (newest-block) values are the correct
> component-level representation (the old output showed retired platforms/artifacts: aix/rhel6
> zips, `file://` installers, dropped docker images, stale artifact names). These diffs are
> suppressed as intentional known-deltas (`known-deltas-db.json`, "ADR-018 base-row amendment"
> entries) until this release becomes the compat baseline, after which the entries match nothing.

- `base = effective value at the OPEN-UPPER (newest) range` at `ALL_VERSIONS` — selected by
  `selectBaseConfig`: the all-versions block if declared, else the open-upper `[X,)` block, else
  (no open-upper block) the highest declared range. **Required-field edge:** when the newest block
  leaves a required field unset the base column may be NULL (the `build_system` BASE check is relaxed);
  the value then lives purely in an older override.
- **Overrides** apply on ranges **including open-upper** (`[X,)`), selected by **containment**
  (`override.range ⊇ R`), not exact string match. After the amendment the OLDER blocks are the ones
  that survive as overrides (they differ from the newest base).
- **No synthetic bounded base.** The migrated base is always `ALL_VERSIONS`.

### Algorithms

- `resolve(v)`: `v ∉ supported → 404`; else for each attribute, the narrowest override whose range
  contains `v`, else the base value. (Point containment, hot path — unchanged.)
- `enumerate()`: `partition(supported, valueChangeEdges)` — split each supported segment at the
  override/ownership range endpoints that fall strictly inside it, resolve each sub-range by
  containment. Adjacent sub-ranges with no edge between them stay one view (redundant-collapse).

### Three refinements (plan-review-3) — authoritative here

- **(a) Containment scope (shipped, TD-010 / PR #377).** The containment predicate
  (`EntityMappers.rangeApplies`) is used **only on the range-VIEW enumeration path**. The
  single-version `resolve` hot path (`toResolvedEscrowModuleConfig` / `ComponentCodeRenderer
  .renderResolved`) continues to use **point** containment (`containsVersion`) and is untouched. The
  version-range library exposes no `containsRange`, so enumeration containment is a sample-points
  heuristic; one-sided-unbounded children are supported, with an **open-upper child requiring an
  open-upper parent** (decided structurally, not by probing a finite +∞ sentinel).
- **(b) ~~Enumeration breakpoint-alignment + write-time auto-split~~ → enumerate-time partition
  (REVISED by the redesign refinement above).** The original plan kept reads trivial by storing
  breakpoint-aligned coverage and auto-splitting on write. The redesign **reverses this choice**:
  coverage is stored merged and enumeration partitions by value-change edges at READ time. This is
  what keeps the two layers genuinely decoupled (coverage carries no override boundaries) and makes
  the derived v2/v3 range lists always minimal without any write-time coverage mutation. The
  "read-path complexity" is isolated in the pure, unit-tested `VersionRangePartition`.
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
  globally, and the base row is the newest block's MERGED config (2026-07 amendment) which still
  inherits that default, so every migrated base is non-null: components either set `buildSystem`
  top-level/in the newest block or inherit `MAVEN`. The components that set `buildSystem` only inside a
  range block all use `MAVEN` (equal to the Defaults value), so it stays non-null on the base.
  **No path to a null base `build_system` → the DB CHECK is kept unchanged (no relaxation).**

### `distribution.*` — per-range vs per-component (CRS #387)

The `distribution` block mixes two storage classes. The marker child-collections vary per range
(stored as `MARKER` override rows and resolved via `pickMarkerChildren`); three scalar-ish fields are
per-component only, stored on the `components` row and **never** overridable per range:

| Field | Per-range | Storage |
|---|---|---|
| `distribution.gav` (mavenArtifacts) | yes | `DISTRIBUTION_MAVEN` marker, `distribution_maven_artifacts` |
| `distribution.docker` | yes | `DISTRIBUTION_DOCKER` marker, `distribution_docker_images` |
| `distribution.deb` / `rpm` (packages) | yes | `DISTRIBUTION_PACKAGES` marker, `distribution_packages` |
| `distribution.fileUrl` | yes | `DISTRIBUTION_FILE_URL` marker, `distribution_file_url_artifacts` |
| `distribution.explicit` | **no** | `components.distribution_explicit` |
| `distribution.external` | **no** | `components.distribution_external` |
| `distribution.securityGroups.read` | **no** | `distribution_security_groups` (`group_type='read'`, group id in `group_name`) |

A bounded-range block that declares one of the three per-component fields with a value differing from
the base was **silently dropped** before (`buildEscrowModuleConfig` reads only the base scalar), so
`resolve` returned the base at every version — a data-loss trap surfaced by the DMS
`ee-component-with-version-ranges` FT fixture. The import now **fails loud**
(`validatePerComponentDistributionInvariants`, `ImportServiceImpl`): for every non-base config it
compares the resolved `explicit` / `external` / `securityGroups.read` against the base and throws
naming the component, range, and attribute(s). Because the DSL loader resolves an omitted per-range
field to the value it inherits from the component default, a range that merely omits these fields
resolves equal to the base and does not trip the guard. As-code rendering
(`ComponentCodeRenderer.writeMarkerDistribution`) emits only the four marker attributes per range and
never renders `explicit` / `external` / `securityGroups.read` inside a per-range block.

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
  `ALL_VERSIONS` base + **merged** `RANGE_PRESENCE` coverage + open-upper overrides) → the entire
  compat suite must be re-run, not just unit tests. Enumeration now diverges from the legacy
  DSL-loader for **redundant-collapse** cases (adjacent legacy blocks that resolve identically merge
  into one v2/v3 range — e.g. two identical `branch=v2` blocks; or two blocks differing only by a
  dropped/constant attribute). These are **intended** diffs; the DB-backed RES-001 test uses a
  separate `-decoupled` expectation fixture, and the ~130k stand baseline must be audited to confirm
  every enumerate diff is a redundant-collapse (plus benign range-whitespace canonicalization), not a
  resolve regression.
- The resolve coverage gate must be **explicitly rewired**: today both `toResolvedEscrowModuleConfig`
  and `ComponentCodeRenderer.renderResolved` *skip* the coverage check when `base.versionRange ==
  ALL_VERSIONS`. Once the base becomes `ALL_VERSIONS`, that gate must instead test membership in
  `supported = ∪ RANGE_PRESENCE`, or tails/gaps would start resolving to base instead of 404 —
  breaking the byte-identical goal.
- Write-side invariants: at most one open-upper override per attribute; V6 required-field write
  coverage (c); allow open-upper overrides server-side. **Coverage no longer requires disjoint input
  (overlaps merge) and there is no write-time auto-split** — both dropped by the redesign.

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
