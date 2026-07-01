# Version model — decoupled supported-versions + per-attribute overrides (DRAFT for review)

> **Status:** PROMOTED → **ADR-018** (Accepted, PR-2). Retained as the detailed design companion
> (case matrix M1–M8, validations V1–V6); the authoritative decision record is
> `docs/registry/adr/018-decoupled-version-model.md`, which supersedes this draft. schema-spec.md /
> requirements-migration.md / requirements-resolver.md / technical-design.md were updated in PR-2.

## 1. Problem

The legacy escrow (Git) model **conflated two concerns** in one mechanism — a version-range
*block* simultaneously (a) declared that the component is *defined* for that range and (b)
overrode attribute values for it. The top-level `build {}`/`jira {}` defaults were *merged
into each block*, not applied as an open-ended fallback: a version matching **no** block
resolved to `null` (404).

The current v4 migration (`ImportServiceImpl.importModule`) reproduces this with a
**synthetic, bounded base**: when a component has range blocks but no explicit
`(,0),[0,)` (ALL_VERSIONS) block, the base row takes the **first range** (`buildBaseConfigRow`:
`versionRange = cfg.versionRangeString`) and `is_synthetic_base = true`. Consequences:

- Versions **above all declared ranges** resolve to nothing (the `EntityMappers`/
  `ComponentCodeRenderer.renderResolved` gate returns null when `base.versionRange != ALL_VERSIONS`
  and no override matches). Faithful to legacy, but…
- The editor **cannot express "this value is the default for all / future versions"** — editing
  the base value only affects the synthetic bounded range. Real incident:
  `ts-visa-click-to-pay`, base synthetic `(,1.0.16)`, resolving `2.1` → "No configuration
  resolves" (and, separately, that 404 surfaced as a 500 — fixed independently in **CRS PR #376**,
  text/plain 404 instead of JSON content-negotiation failure).
- `EntityMappers` documents the convention "synthetic base range is `(,)`" while `ImportServiceImpl`
  sets it to the first range — an internal inconsistency.

**Multi-attribute wrinkle (the key correctness point).** Coverage is a *component-level* property,
not per-attribute. If `build.javaVersion` is overridden on `(1.0,)` and `jira.releaseVersionFormat`
on `(0,3)`, then version `0.5` **is** defined (it's in `(0,3)`) and must resolve
(`javaVersion = default`, `releaseVersionFormat = (0,3) value`) — **not** 404. So an attribute's
"from-X-onward" value cannot be modelled as that attribute's *bounded base*; it must be an
**override** while the base stays the real default.

## 2. Decision — two independent layers

**Layer 1 — Supported versions (coverage).** A component-level set of versions the component is
defined for. `resolve(v)`: if `v ∉ supported` → **404**. Independent of overrides — editing
overrides never changes coverage, and vice versa.
- Migrated = **union of all legacy range-block ranges**; if no blocks → **ALL versions**.
- *(Deferred / future)* split `supported` into lifecycle states **Archived / On maintenance /
  Active development**. First release ships a single flat `supported`; status data not populated.
  Status is metadata, does not change resolve.

**Layer 2 — Per-attribute values.** Each attribute has a **base value** at
`ALL_VERSIONS`, plus **overrides** on sub-ranges, **including open-upper** ranges. Resolve of an
attribute for a covered version = `the override whose range CONTAINS v ?? base`.
- **Base = the effective value of the OPEN-UPPER (newest) range** (base-VALUE semantics superseded
  2026-07; was `top-level block ⊕ Defaults.groovy` — see [ADR-018 §2 amendment](adr/018-decoupled-version-model.md)).
  Selected by `ImportServiceImpl.selectBaseConfig`: the all-versions block if declared, else the
  open-upper `[X,)` block, else the highest declared range. The newest block's merged config still
  inherits `Defaults.groovy`, so required fields (e.g. `build_system`) stay non-null on the base.
  **Required-field edge (P1):** if a required BASE field is unset on the newest block AND has no
  Defaults value, **relax the BASE `build_system IS NOT NULL` DB invariant** (`V1__schema.sql:243`)
  to allow NULL — the value then lives purely in an older override.
- **The OLDER/other declared blocks are the overrides** (including the historical-left `(,Y)` and
  bounded blocks). The portal's current "D5" rule (open-upper → edit BASE) is **relaxed**: the newest
  band IS the base, and older bands are first-class overrides.
- Disjointness is enforced **per attribute**, not per component (different attributes may have
  different, independently-overlapping range structures).
- Overrides apply by **containment** (`containsRange`), not exact range match — see §5 / §8 (TD-010).
- **No synthetic-bounded base** — the base is always the real default at `ALL_VERSIONS`.

## 3. Algorithms

```
resolve(component, v):
  if v ∉ supported:                        return 404                  // supported = ∪ declared ranges
  for each attribute A:
     value(A) = the override(A) whose range CONTAINS v   ??   base(A)   // containsRange, NOT exact-match (TD-010)
  return merged config                                                 // base(A) = effective default

migrate(legacy DSL):
  declaredRanges = the block range strings, VERBATIM (incl composites)  // persisted as RANGE_PRESENCE rows
  supported      = ∪ declaredRanges                       || ALL        (no blocks → all versions)
  base(A)        = effective value of the OPEN-UPPER (newest) range   // ALL_VERSIONS row; superseded 2026-07 (was top-level ⊕ Defaults)
  overrides      = per attribute, derived from the OLDER blocks (newest = base); adjacent same-value MAY be merged.
                   NOTE: enumeration currently DOES key off override edges, so two adjacent value-identical
                   OLDER blocks yield two identical views. Preferred fix = clean the redundant blocks in the
                   source DSL (old CR) / fixtures, NOT a resolver-side collapse. See §compat.
  // no synthetic-bounded base

enumerate(component):                                    // v2/v3 range-list endpoints
  for each declared range R (RANGE_PRESENCE, VERBATIM — a composite stays ONE entry):
     config(R) = { A:  the override(A) whose range CONTAINS R  ??  base(A) }   // containsRange (TD-010)
  // declared ranges are breakpoint-aligned ⇒ every attribute is constant across each R
```

## 4. Case matrix (migration)

Visual / interactive: **case matrix artifact** — https://claude.ai/code/artifact/b3f88f30-7382-47a7-8e60-d2145f14154d
(Companion mockups: decoupled model https://claude.ai/code/artifact/ac9ea31d-509d-4b7c-baeb-a33e8d13e4f6 ·
split/coverage https://claude.ai/code/artifact/e26b664e-9b56-454c-ab9d-b8dffd8af33a)

`jV` = build.javaVersion, `def` = top-level default. Every case must preserve legacy resolve for
**real** versions (compat baseline = 0 diffs).

| # | Legacy DSL (shape) | supported | base | overrides | resolve highlights |
|---|---|---|---|---|---|
| M1 | only `build{jV=17}` (no ranges) | ALL | jV=17 | — | every v → 17 |
| M2 | `jV=17` + `"[1.0,)" {}` (empty) | `[1.0,∞)` | jV=17 | — | `<1.0`→404; `≥1.0`→17 |
| M3 | `jV=17` + `"[1.0,)" {jV=11}` | `[1.0,∞)` | jV=17 | jV `[1.0,∞)`=11 (open) | `<1.0`→404; `≥1.0`→11 |
| M4 | `jV=17` + `"(,1.0.16]"=1.8` + `"(1.0.16,2)"=11` | `(,2)` | jV=17 | two closed | `<1.0.16`→1.8; `(1.0.16,2)`→11; `≥2`→**404** |
| M5 | `"[1.0,2)"=11` + `"[2,)"=21` | `[1.0,∞)` | jV=17 | `[1.0,2)`=11, `[2,∞)`=21 (open) | `<1.0`→404; `[1.0,2)`→11; `≥2`→21 |
| M6 | jV `(1.0,)`=11 + relFmt `(0,3)`=Y (multi-attr) | `(0,∞)` | jV=17, relFmt=std | jV `(1.0,∞)`=11, relFmt `(0,3)`=Y | `0.5`→ jV **17** + relFmt **Y** (not 404); `2.0`→11+Y; `5.0`→11+std; `≤0`→404 |
| M7 | `"(,2.0)" {jV=11}` | `(,2.0)` | jV=17 | jV `(,2.0)`=11 | `<2.0`→11; `≥2.0`→404 |
| M8 | `"[1,2),[5,)" {jV=11}` (composite, hole) | `[1,2)∪[5,∞)` | jV=17 | jV composite=11 | `[1,2)`→11; `[2,5)`→**404**; `[5,∞)`→11 |

Notes:
- **M4** is `ts-visa-click-to-pay`. To later make "21 for ≥2": *extend supported* to include `[2,∞)`
  **and** add jV override `[2,∞)`=21 — two independent edits (Layer 1 + Layer 2).
- **M6**: legacy expressed this as disjoint component blocks at the breakpoint union `{1.0, 3}`
  (RANGE_PRESENCE = those block ranges); migration splits per attribute and **merges** adjacent
  same-value ranges → `jV (1.0,∞)`, `relFmt (0,3)`. Enumeration still emits the original declared
  ranges, resolving each by containment. (Consistent with §5; the override column shows the
  merged form.)
- **M8** composite is **one** RANGE_PRESENCE entry (verbatim `[1,2),[5,)`) and **one** override row
  (not promoted to base) → the hole `[2,5)` stays 404 and enumeration shows one config, not two.
  A composite with an open tail counts as **one** open-upper range for V2.

## 5. Enumeration is anchored to declared ranges (so per-attribute merge is safe)

The earlier "do not merge" rule is **withdrawn** — it conflated two things the model now keeps
separate. Enumeration does **not** recompute a partition from override edges; it iterates the
**preserved declared ranges** (RANGE_PRESENCE, stored verbatim — composites included). Therefore:

- Merging adjacent same-value **per-attribute overrides** is **safe** and allowed (cleaner data,
  e.g. `jV (1.0,∞)`), because override edges no longer drive enumeration.
- The redundant adjacent-identical-value case is safe too: legacy's two blocks are two
  RANGE_PRESENCE rows → enumeration still emits **two** entries (each resolved via containment),
  even though the override is merged. No structural diff.
- Composites (M8) stay **one** RANGE_PRESENCE entry → one enumerated config. No split.

**Required implementation item (P2 / TD-010):** overrides must apply by **containment**
(`override.range ⊇ R`), not exact equality. Current code applies an override only when
`parentRange == childRange` (`EntityMappers.kt:293`) — that must become a `containsRange` check
for both resolve and enumerate. Declared ranges are breakpoint-aligned, so each attribute is
constant across a declared range and containment is unambiguous.

## 6. Validation rules (migration + v4 write)

| Rule | Condition | Verdict | Behavior |
|---|---|---|---|
| V1 | Override range extends outside supported | warn | Allowed but flagged; never resolves there (coverage gate 404s first). Supported is **not** auto-extended. |
| V2 | Two open-upper ranges on the **same** attribute | block | Forbidden. Detect at migration (fail the component, clear error) + reject on write. Composite-with-open-tail = one open-upper range. |
| V3 | Overlapping override ranges on the **same** attribute | block | Forbidden — per-attribute ranges disjoint. |
| V4 | Open-upper overrides on **different** attributes | allow | Each attribute resolves independently. |
| V5 | Shrinking supported under an existing override | warn | Allowed; now-uncovered part of the override is unreachable → warn. Extending leaves overrides untouched. |

## 7. Compatibility plan / risks (v2/v3 REST)

- **Target = current v4 output, not "legacy in the abstract".** The current v4 code already
  reproduces legacy via the synthetic-base + RANGE_PRESENCE machinery (compat baseline = 0 diffs).
  This refactor must keep enumeration/resolve **identical to the current v4 responses** on real
  versions. So the win is *internal model cleanliness*, with **byte-identical external behavior**.
- **Synthetic-base suppression is replaced, prove equivalence.** Today enumeration **skips** the
  synthetic base when overrides exist (`EntityMappers.kt:143-150`, MIG-029). The new model has no
  synthetic base — enumeration iterates RANGE_PRESENCE verbatim instead. The suppression logic must
  be removed/reworked and the new enumeration **proven to emit the same range list** as the current
  code on the full baseline (the counterexample in §4-M5 / reviewer's `[1.0,2.0)`+`[2.0,∞)` case is
  the canonical check).
- **Full compat baseline** (real versions, ~130k): 0 diffs on resolve **and** enumeration.
- **Resolve**: identical for real versions (overrides cover them; base = default elsewhere).
- **Enumeration parity**: `enumerate()` iterates the **preserved declared ranges**
  (RANGE_PRESENCE, verbatim — composites stay single strings) and resolves each by containment →
  identical range list to legacy, independent of per-attribute merge. Verify: composites (one
  entry), redundant adjacent-identical-value blocks (two entries), and broad-override-over-narrow-
  declared-range (containment applies).
- **Tail / gaps**: versions outside `supported` stay 404 (M4 tail, M7 ≥2.0, M8 hole) — faithful.
- **Ordering**: the resolver currently sorts ranges **lexicographically** (`ARTIFACT_MAPPING_ORDER`
  in `V4Mappers.kt`; `.sorted()` in `EntityMappers.kt`) — `[1.10,)` before `[1.2,)`. This is an
  existing bug; ensure the new code does not *introduce* an ordering diff, and decide whether to fix
  to numeric ordering here (separate concern, flag).
- **Supported = ALL representation:** a component with no version blocks **or** an explicit
  `(,)`/`(,0),[0,)` block both → a single `ALL_VERSIONS` base and **no** bounded RANGE_PRESENCE
  rows; enumeration emits the one all-versions view. The two legacy shapes must **converge** to the
  same stored representation so they enumerate identically.
- **`ImportServiceImpl` change is structural** (drop synthetic-bounded base; add explicit supported;
  open-upper overrides) → re-run the entire compat suite, not just unit tests.

### Pre-migration audits (run before/while implementing)
- **A1 — overlapping legacy blocks:** scan prod DSL for components with overlapping component-level
  range blocks (legacy resolution was order-dependent). Per-attribute resolution must not change
  real-version output; document the chosen behavior for any genuine overlap.
- **A2 — per-range-only required fields:** does any real component set a required BASE field
  (esp. `build_system`) **only** inside version blocks (no top-level, no Defaults.groovy)? If none,
  the BASE `build_system IS NOT NULL` CHECK needs no relaxation (§8.2). If some, relax it.

## 8. Open decisions (for the reviewer)

1. **Supported storage** *(leaning decided)*: store declared ranges **verbatim** as `RANGE_PRESENCE`
   rows (composites kept as single strings); `supported = ∪` of them; resolve gate + enumerate both
   read them. Confirm this is the representation (vs a separate coverage table).
2. **Base required-field invariant (P1)**: gated on **audit A2**. If a required BASE field is ever
   per-range-only, **relax the DB CHECK to allow NULL** (preferred, no synthetic base) vs. neutral
   default vs. representative. If A2 finds none → no change needed.
3. **`containsRange` / TD-010 (P2)** — REQUIRED impl item, not optional: `rangeApplies()` currently
   returns `parentRange == childRange` (exact text equality, `EntityMappers.kt:315-319`); replace
   with a containment check (`override.range ⊇ R`) for resolve **and** enumerate. Counterexample
   today: override on `[1.0,3.0)` over declared ranges `[1.0,2.0)`+`[2.0,3.0)` is silently dropped
   (text mismatch) → resolves to base instead of the override.
4. **V1**: warn-and-allow vs hard-constrain override input to within supported.
5. **Resolver ordering**: fix lexicographic → numeric now (risk) or as a separate change?
6. **Lifecycle layer**: confirm deferred; default status at migration when introduced.
7. **Portal D5 relaxation**: confirm open-upper overrides become first-class in the editor
   (per-attribute disjointness), plus a `supported` editor (extend/limit/split) and base-as-default.

## 9. Out of scope / deferred

- Version lifecycle states (Archived / Maintenance / Active development) — future release.
- ADR-018 + schema-spec.md / requirements-migration.md / requirements-resolver.md updates — during
  implementation.
- Portal UX build (Supported block, per-attribute overrides, Configuration admin view).

## 10. Related work (already shipped/in-flight)

- **Portal PR #138** — version-range UX: sticky conflict toasts, persistent value-409 banner,
  readable ownership diff, As-Code default version + friendly out-of-range hint, "All versions"
  base label.
- **CRS PR #376** — `/as-code` returns text/plain **404** instead of 500 for an unresolvable
  version/component (content-negotiation fix). Independent of this model change.

## 11. Code touch-points (CRS) — for implementation

| Area | File:line | Change |
|---|---|---|
| Override application (resolve + enumerate) | `EntityMappers.kt:315-319` `rangeApplies()` | exact-match → **containsRange** (TD-010) |
| Enumeration synthetic-base skip | `EntityMappers.kt:143-150` | replace with verbatim RANGE_PRESENCE enumeration; prove equivalence |
| Resolve coverage gate | `EntityMappers.kt:~209` (`base.versionRange != ALL_VERSIONS` union check) + `ComponentCodeRenderer.renderResolved` ~190-204 | gate on `supported` (∪ RANGE_PRESENCE) |
| Base row build | `ImportServiceImpl.buildBaseConfigRow` + `selectBaseConfig` | base.versionRange = ALL_VERSIONS; value = effective config of the OPEN-UPPER (newest) block (2026-07 amendment, was top-level ⊕ Defaults); drop synthetic-bounded range |
| Synthetic base decision | `ImportServiceImpl.importModule:~895-904` | remove `isSyntheticBase` bounded-base path; supported = ∪ declared ranges |
| RANGE_PRESENCE emission | `ImportServiceImpl.emitRangePresenceRow:~970-993` | emit for every declared bounded block (verbatim, incl composites); none when supported = ALL |
| Enumeration ordering | `V4Mappers.kt` `ARTIFACT_MAPPING_ORDER` | lexicographic today (existing bug) — don't introduce a new diff; numeric fix is a separate decision (§8.5) |
| BASE field invariant | `V1__schema.sql:243` `chk_..._base_build_system` | relax to allow NULL only if audit A2 finds per-range-only required fields |
| Docs | `schema-spec.md`, `requirements-migration.md`, `requirements-resolver.md` + new **ADR-018** | update during implementation |
