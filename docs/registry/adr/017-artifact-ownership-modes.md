# ADR-017: Explicit artifact-ID ownership model (modes)

## Status
Accepted.

## Context

A component's base groupId/artifactId mapping declares **which Maven artifacts the
component owns** — it is the reverse-resolution key (`find-by-artifact` → component)
and the forward `groupIdPattern`/`artifactIdPattern` of the v1–v3 contract. The
legacy DSL expressed it as an `artifactId` string that the legacy
`MavenArtifactMatcher` treated as a **regex** (`Pattern.matches(pattern.replace(",",
"|"), id)`). v3 imported it verbatim into `component_artifact_ids.artifact_id_pattern`
— an opaque pattern string.

Three problems:

1. **Opaque DX.** A component with no explicit artifactId inherits the catch-all
   `[\w-\.]+`; the Portal showed that raw regex. Users could neither read nor safely
   edit ownership.
2. **Lost capability.** The legacy per-range override of the ownership pattern was
   carried by an import-only `GROUP_ARTIFACT_PATTERN` marker excluded from
   `MarkerAttributes.ALL` — so it was **not editable** through v4 / the Portal.
3. **Imprecise uniqueness.** The legacy `EscrowConfigValidator` enforced "at most one
   component matches any artifact" (rules **#24** exact token-pair sharing and **#25**
   pattern containment). v3 dropped those cross-component checks
   (`VALIDATION-PARITY-2026-06-03.md`) because they cannot be re-expressed precisely
   while ownership is an opaque regex — two catch-alls both "match everything" with no
   deterministic way to say whether they truly collide.

**Ground truth (two distinct resolvers — do not conflate):**
- The **`main` git branch = legacy production**: no `DatabaseComponentRegistryResolver`;
  the legacy `ModuleByArtifactResolver` returns a component only when **exactly one**
  config matches and **throws "Configuration File Conflict"** on >1. So a
  no-explicit-artifactId catch-all **owns ALL artifacts in its group**, and prod is
  **overlap-free by construction**. Migration must preserve this default = ALL.
- The **v3 DB resolver** ranks by `artifactSpecificity` — a catch-all *yields* to a
  more specific rival. v1–v3 REST endpoints in v3 are served by this resolver.
- **Real prod DSL** (622 groups / 708 artifactIds): ~91% plain literal enumerations
  (single token or `,`/`|` lists); exactly **one** true regex — a negative-lookahead
  `((?!X)[\w-\.]+)` = literally "all except sibling X". groupId multi-value = 29 comma
  cases, 0 pipe. ~211 per-range override blocks.

## Decision

Model artifact ownership **explicitly** as a per-component **list of mappings**, each
carrying a group list + an **ownership mode** + a version range (+ literal tokens for
EXPLICIT). Decide cross-component uniqueness **deterministically from the modes**.

### Three ownership modes

| Mode | Owns | Maps legacy |
|------|------|-------------|
| `EXPLICIT` | exactly the listed literal artifact tokens under its group(s) | literal / `,`/`\|` enumeration |
| `ALL_EXCEPT_CLAIMED` | any artifact under its group NOT explicitly claimed by another component in an intersecting range (yields) | the `(?!X)` negative-lookahead |
| `ALL` | every artifact under its group(s), unconditionally (sole owner) | inherited catch-all / no explicit artifactId — **the `main` default** |

A component may own **several groups, each with its own rule** (separate mappings) —
an additive v4 capability the single-pair legacy DSL could not express. Existing
imported data is always a single mapping per component.

**`ALL_EXCEPT_CLAIMED` is single-group.** Its legacy/DSL export is a per-group
negative-lookahead built from that group's explicit siblings; one shared `(?!…)`
across `A,B` would over/under-exclude when A and B have different sibling sets. A
comma-group catch-all-with-exclusion is split into one `ALL_EXCEPT_CLAIMED` mapping
per group on import, and the v4 API rejects a comma-group `ALL_EXCEPT_CLAIMED` (400).
`ALL` and `EXPLICIT` may keep comma groups.

### Storage (`V1__schema.sql`, pre-prod — edited in place)

`component_artifact_mappings (id, component_id FK, version_range, group_pattern,
artifact_id_mode, sort_order)` + `component_artifact_mapping_tokens (id, mapping_id
FK, artifact_pattern, sort_order)`. Replaces `component_artifact_ids` (dropped) and
the `GROUP_ARTIFACT_PATTERN` ownership marker. `ALL`/`ALL_EXCEPT_CLAIMED` mappings
have **zero token rows** — the catch-all behavior is derived from the mode, not a
stored pattern. `sort_order=0` is the **primary** mapping (deterministic legacy
output). No FK to a configuration row: ownership is keyed by `(component, range)`
directly.

### Effective-per-range = replacement (most-specific wins)

At version V, a component's in-force mappings are those of its **narrowest
`version_range` containing V**. A per-range override **REPLACES** the base for that
subrange (it does not co-claim). Uniqueness and resolution both operate on this
effective set — mirrors the existing per-range scalar-override layering.

Invariants (enforced on v4 write + import, 400): an ownership `version_range` must be
`ALL_VERSIONS` or equal an existing configuration range of the component; per-component
non-base ranges are pairwise non-overlapping; within one `(component, range)` a group
token belongs to ≤1 mapping; `EXPLICIT` requires ≥1 token, `ALL`/`ALL_EXCEPT_CLAIMED`
carry none; group/token allowlist `[A-Za-z0-9_.-]+` (no regex operators).

### Deterministic cross-component uniqueness (restores #24/#25)

For two in-force mappings of different components sharing ≥1 group token in
intersecting ranges:

| | EXPLICIT | ALL_EXCEPT_CLAIMED | ALL |
|---|---|---|---|
| **EXPLICIT** | conflict iff tokens intersect | no | conflict |
| **ALL_EXCEPT_CLAIMED** | no | conflict | conflict |
| **ALL** | conflict | conflict | conflict |

This is exactly legacy's "at most one config matches any artifact", now decided from
stored modes with no probe/regex heuristics. The model also forbids ≤1 non-EXPLICIT
(ALL/ALL_EXCEPT) mapping per (group token, intersecting range), keeping the resolver
unambiguous.

**Enforcement boundary (scope decision):** the cross-component matrix runs on the
**v4 write path only** — `create` (unconditional) and `update` when `artifactIds` is
present. It is NOT wired into the §6.0 migration uniqueness pre-pass: production is
overlap-free by construction under the legacy single-match resolver, the ft-db test
fixtures are intentionally dirty for ownership (multiple `ALL` under shared groups),
and the live API guards every new write. Migration enforces correctness through
**strict classification** instead (below).

### Migration classification (strict, ordered, no escape hatch)

1. pattern contains `(?!` → `ALL_EXCEPT_CLAIMED` (checked first).
2. else pattern **exactly equals** a known catch-all `{ANY_ARTIFACT, *, .*, [\w-\.]+,
   [\w-]+, \w+}` → `ALL`.
3. else literal token / `,`/`|` enumeration of allowlist tokens → `EXPLICIT`.
4. else (any other regex) → **hard-fail migration** — the schema has no raw-regex
   column, so an unclassifiable pattern fails loudly for a human to resolve.

The probe-based `isCatchAllArtifactPattern` stays **resolver-only** (runtime
specificity ranking); it is intentionally NOT the migration classifier. The
real-production-DSL gate (`RealDslUniquenessAcceptanceTest`, `REAL_CR_DSL_DIR`)
confirms 0 unclassifiable patterns.

### v1–v3 compatibility (behavioral, not byte-exact)

- `EXPLICIT` → tokens stored unescaped; **dot-escaped** when rendered to any
  regex-consumed string (legacy `artifactIdPattern`, DSL export) so `foo.bar` matches
  `foo.bar` not `fooXbar`; the v3 DB resolver matches EXPLICIT by **exact equality**.
- `ALL` → emit the catch-all pattern.
- `ALL_EXCEPT_CLAIMED` → v3 wire emits the catch-all (resolver specificity yields);
  DSL export renders a `(?!(?:sibling\.a|sibling-b)$)[\w-\.]+` lookahead from the
  in-force EXPLICIT siblings, anchored + escaped.
- **Multi-mapping vs the single-pair legacy contract:** legacy forward fields render
  the **primary** (`sort_order=0`) mapping per range; reverse `find-by-artifact`
  flattens **all** mappings. All imported data is single-mapping ⇒ behavior unchanged;
  new multi-mapping components are deterministically lossy forward, complete reverse.

### v4 DTO contract

`ArtifactIdRequest { versionRange?, groupPattern, mode?, artifactTokens }` (no
`legacyArtifactIdPattern` on input — server-computed). `ArtifactIdResponse` adds
read-only `legacyArtifactIdPattern` (the `ALL_EXCEPT_CLAIMED` preview needs
cross-component siblings the client lacks). PATCH `artifactIds` is a **full
replacement** of the component's entire ownership set; `sort_order` is server-derived
from list position. Unspecified mode defaults to `ALL` when tokenless, else `EXPLICIT`.

## Consequences

### Positive
- Ownership is human-readable and editable (modes + literal tokens), not a raw regex.
- Per-range ownership override is a first-class, editable part of the model — the
  `MarkerAttributes` allowlist hack for ownership is gone.
- Cross-component uniqueness #24/#25 are restored, decided deterministically.
- Multi-group-per-component ownership is newly expressible.

### Negative
- A schema reshape (new tables, drop `component_artifact_ids`) — cheap pre-prod where
  the DB is built from scratch and the Groovy import is the data source of truth.
- Forward v1–v3 output for a (new) multi-mapping component is lossy by design
  (primary only); reverse resolution stays complete.
- Migration hard-fails on any artifactId regex outside the classifiable set — by
  design (no opaque escape hatch); prod has exactly the one `(?!X)`.

### Interaction with other ADRs
- **ADR-008 (component-level routing)** — v1–v3 endpoints route per-component via
  `ComponentRoutingResolver`; the mode model lives on the DB-resolver side.
- **ADR-013 (cutover)** — the strict classifier + prod-DSL gate are part of the
  migration-correctness story; ownership uniqueness on the write path replaces the
  dropped legacy validator rules tracked in `tech-debt/012`.

## References
- `ComponentArtifactMappingEntity` / `ComponentArtifactMappingTokenEntity`,
  `ArtifactOwnershipModeClassifier`, `ArtifactOwnershipRendering`, `OwnershipUniqueness`
- `DatabaseComponentRegistryResolver` (forward primary / reverse flatten),
  `EntityMappers`, `V4Mappers`, `ComponentCodeRenderer`
- `ComponentManagementServiceImpl#validateArtifactOwnershipIfChanged`,
  `ImportServiceImpl` (classification + mapping persistence)
- SYS-058 (`requirements-common.md`); functional-spec §2.5; issue #357
