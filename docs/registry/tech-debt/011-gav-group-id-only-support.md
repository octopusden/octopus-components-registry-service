# TD-011: groupId-only Maven GAV round-trip (SYS-030)

## Status

Open · P1 · backward-compat regression · sister to PR #192 review Group 3 (item 3.6).

## Problem

V1 Groovy DSL supports a `distribution.GAV` value that is a bare groupId (no `artifactId`), e.g. `"org.example.teamcity.ee"`. Schema-v2's import + resolver pipeline silently drops these entries, breaking SYS-030 round-trip.

Failure points (current state on `feat/schema-v2-sql`):

- **Parser:** `GavParsing.parseMavenGavEntry()` (line 35) returns `null` for any entry with fewer than two `:` segments, AND the `MavenCoords.artifactId: String` field is non-nullable.
- **Schema:** `V1__schema.sql:348` declares `artifact_pattern TEXT NOT NULL` — there is no representation for "no artifactId".
- **Entity:** `DistributionMavenArtifactEntity.artifactPattern: String = ""` mirrors the NOT NULL constraint.
- **Import path:** `ImportServiceImpl.attachMavenArtifacts` line 1572 reads `parseMavenGavEntry(entry) ?: continue` — the `?: continue` silently drops groupId-only entries.
- **Read path:** `DatabaseComponentRegistryResolver.getMavenArtifactParameters()` line 358 does `coords.joinToString(",") { it.artifactPattern }` — would produce `null` literal or compile-fail if `artifactPattern` becomes nullable.
- **Write path:** `ComponentManagementServiceImpl.replaceMavenArtifacts()` accepts only the current shape; no validation for groupId-only entries.
- **V4 DTO:** `MavenArtifactRequest` / `DistributionResponse` declare `artifactPattern: String` (non-nullable).

## Why deferred from PR #192 Group 3

Group 3 of the post-review plan budgeted minimal-scope changes. Item 3.6 expanded to a 8+ file feature-scope change spanning schema, entity, parser, composer, read mapper, write validation, OpenAPI/DTO, and round-trip tests. The work is genuinely SYS-030 feature support, not a small fixup.

Tracking here so the spec doesn't decay. Implementation lands as a separate PR after PR #192 merges.

## Implementation spec

### Path chosen: nullable `artifact_pattern` (mapper-side null)

Considered alternatives:
- **String sentinel** (e.g., `""` to mean "no artifactId") — fragile, easy to confuse with "valid empty string".
- **Synthetic placeholder** — cascading representation problem, breaks round-trip readability.
- **Nullable** — direct, type-system enforced. Chosen.

### Required changes

| # | File | Change |
|---|------|--------|
| 1 | `V1__schema.sql:348` | `artifact_pattern TEXT` (drop `NOT NULL`); keep TEXT type — column stores DSL patterns / regex, not short codes. Optionally add CHECK `(artifact_pattern IS NULL OR length(artifact_pattern) > 0)` so null and empty-string remain distinguishable. |
| 2 | `DistributionMavenArtifactEntity.kt:27-28` | `var artifactPattern: String? = null`. Keep `columnDefinition = "TEXT"`. |
| 3 | `util/GavParsing.kt` | (a) `data class MavenCoords(val groupId: String, val artifactId: String?, …)`. (b) `parseMavenGavEntry` for 1-segment input returns `MavenCoords(groupId, null, null, null)` (drop the `parts.size < 2` reject when groupId non-blank). (c) If the entry has 2+ segments, current logic stays — groupId AND artifactId both non-blank. |
| 4 | `GavParsing.composeGavCsv` (or call-site in `EntityMappers`) | `if (artifactId == null) groupId else "$groupId:$artifactId[:extension][:classifier]"`. |
| 5 | `ImportServiceImpl.attachMavenArtifacts` lines 1566-1580 | After `parseMavenGavEntry`, write `DistributionMavenArtifactEntity` with `artifactPattern = coords.artifactId` (was non-null; now String?). |
| 6 | `EntityMappers` read path (`buildDistribution` / equivalent) | Handle `artifactPattern == null` without NPE; emit pure `groupId` in DSL/DTO. |
| 7 | `DatabaseComponentRegistryResolver.getMavenArtifactParameters()` line 358 | **Confirm V1 behaviour first via compat/fixture test.** What does V1 put in `ComponentArtifactConfiguration.artifactPattern` for a groupId-only entry? Variants: `""`, no entry at all, or duplicate of `groupPattern`. Pin V1 ground truth via fixture-test, then mirror in V2. **Do NOT encode GAV-CSV into `artifactPattern`** — the field is historically a CSV of artifactId-pattern regexes, not GAV tokens. |
| 8 | `ComponentManagementServiceImpl.replaceMavenArtifacts()` | Validation: (a) `artifactPattern: String?` accepted as null. (b) Reject blank (`""` / whitespace-only) — distinguish from null. (c) Reject `artifactPattern=null + extension != null` or `+ classifier != null` (invalid coords: `group::ext:cls` cannot be serialised back to GAV). Preserve normal `group:artifact` behaviour for non-null values. |
| 9 | `V4Mappers` + `MavenArtifactRequest` / `DistributionResponse` DTOs | `artifactPattern: String?` in JSON schema. Update OpenAPI generated artifact. |
| 10 | Tests | Round-trip: `"org.example.teamcity.ee"` → import → DB row with `artifactPattern = NULL` → resolver → `"org.example.teamcity.ee"` byte-equal. Mixed CSV (one groupId-only + one `group:artifact`). V4-write: POST `artifactPattern=null` → 200; POST `artifactPattern=""` → 400; POST `artifactPattern=null + extension=jar` → 400. |

### Re-import sanity (per `project_crs_schema_v2_migration_policy`)

DB is recreated from scratch from `V1__schema.sql` and reseeded from the Groovy DSL — no backfill / migration script needed. The fix is read-side and import-side: a fresh import on a clean DB after this change puts groupId-only GAV entries as `artifact_pattern = NULL` rather than silently dropping them.

## Acceptance

- [ ] All 10 changes landed in a single PR titled `feat(schema-v2): SYS-030 — groupId-only Maven GAV round-trip`.
- [ ] Fixture/compat test pins V1 behaviour for a known groupId-only component (preferably an existing prod component with `distribution.GAV = "<group>"` — pick from `Components.groovy` once literals are scrubbed in Group 2).
- [ ] V2 resolver returns the same `ComponentArtifactConfiguration` shape as V1 byte-for-byte.
- [ ] V4-write validation tests pass (3 cases).
- [ ] Full `:components-registry-service-server:test` green.
- [ ] Sonnet correctness + Opus adversarial review per `feedback_pr_review_via_subagent`.

## Why this isn't a merge blocker for PR #192

V1 baseline returns `200` with the groupId-only entry. Schema-v2 currently returns `200` with the entry **silently absent** from the distribution list. This is a **value-level** divergence visible only on `distribution.gav`-using endpoints (`/components/{name}` v2, `/components/{name}/distribution`). Trace-replay will catch it as STRUCTURAL_DIFF or VALUE_DIFF on a small set of components (those whose DSL uses bare groupIds in `distribution.GAV`).

No data corruption beyond the missing entries. Operators get the `groupPattern` (which is enough to compute distribution URLs in many cases). Full SYS-030 round-trip is the right bar but doesn't block other compat fixes.

## Related

- `docs/registry/requirements-common.md` SYS-030 row.
- `util/GavParsing.kt` — current implementation that needs amending.
