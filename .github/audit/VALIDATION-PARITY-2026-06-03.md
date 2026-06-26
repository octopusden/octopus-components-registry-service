# Pre-publish CR validation parity: old `main` → new `v3`

Audit date: 2026-06-03
Baselines: old = `main` @ `3aaeecf0`; new = `v3` @ `478dd287`
Scope: every validation the OLD format ran before a Component Release could be published, mapped to its status in the NEW v3 format. Question answered: did each one migrate, get intentionally changed/dropped, or is it an accidental gap?

> Note on the working tree: this report was produced against the `v3` branch tip, not the current worktree branch (`fix/as-code-synthetic-base-aspects`). All `v3` citations are read via `git show v3:<path>`. Re-confirm line numbers if `v3` advances.

## 1. Context & scope

OLD format (`main`): components are defined as Groovy/Kotlin DSL config files. Validation runs at **config-load / CI time**, not per request:
- `EscrowConfigValidator.validateEscrowConfiguration(...)` — the domain + cross-component rule set, invoked from the loader at `EscrowConfigurationLoader.groovy:227`.
- `EscrowConfigValidator.validateEscrow(...)` — a *separate* per-DSL-component pass for double-defined escrow generation, invoked at `EscrowConfigurationLoader.groovy:221`.
- `GroovySlurperConfigValidator` — DSL structural/syntax checks.
- `ComponentRegistryValidationTask` — a standalone **build/CI** task (`main()` + `System.exit`) that calls employee-service and JIRA. Gated by `cr.employeeServiceEnabled` (default `false`).

NEW format (`v3`): components live in PostgreSQL, created/edited at runtime via the `/rest/api/4` v4 REST API. Validation is **inline private methods in `ComponentManagementServiceImpl`** (no dedicated `*Validator` class) plus framework/`@PreAuthorize` checks. There is no separate "publish" step — a write to the DB *is* publication.

File legend (paths abbreviated in the matrix):
- `OLD-VALIDATOR` = `component-resolver-core/src/main/groovy/org.octopusden/octopus/escrow/configuration/validation/EscrowConfigValidator.groovy` (on `main`)
- `OLD-CITASK` = `component-resolver-core/src/main/groovy/org.octopusden/octopus/escrow/configuration/validation/ComponentRegistryValidationTask.groovy` (on `main`)
- `OLD-SLURPER` = `component-resolver-core/src/main/groovy/org.octopusden/octopus/escrow/configuration/validation/GroovySlurperConfigValidator.groovy` (on `main`)
- `OLD-LOADER` = `component-resolver-core/src/main/groovy/org.octopusden/octopus/escrow/configuration/loader/EscrowConfigurationLoader.groovy` (on `main`)
- `NEW-SVC` = `components-registry-service-server/src/main/kotlin/org/octopusden/octopus/components/registry/server/service/impl/ComponentManagementServiceImpl.kt` (on `v3`)
- `NEW-CTRL` = `components-registry-service-server/src/main/kotlin/org/octopusden/octopus/components/registry/server/controller/ComponentControllerV4.kt` (on `v3`)
- `NEW-ENTITY` = `components-registry-service-server/src/main/kotlin/org/octopusden/octopus/components/registry/server/entity/ComponentEntity.kt` (on `v3`)

Status key: ✅ migrated · ◐ partial / changed · ❌ absent · ⊘ N/A by design.
Classification: MIGRATED · INTENTIONAL (documented change/relaxation/N-A) · GAP (accidental — undocumented loss).

## 2. How validation worked then vs now

The old model was a **CI gate over a code repo**: a pull request to the config repo ran `validateEscrowConfiguration` + (optionally) the employee/JIRA `ComponentRegistryValidationTask` before the config could be merged/published. The new model is a **runtime API over a DB**: each v4 write validates itself synchronously.

Consequence that drives most of this audit: `ComponentRegistryValidationTask` and `EscrowConfigValidator` **still exist in `v3`** but run only against the **legacy Groovy config files** (employee-service still wired at `.teamcity/settings.kts:1087-1088`, client pinned at `gradle.properties:22`). They guard *import/migration* data; they do **not** guard components created or edited through the v4 API. So any rule that lived only in those classes and was not re-implemented in `NEW-SVC` is unenforced for ongoing edits.

`ADR-000` frames v3 as *adding* write-time validation that the DSL lacked (`docs/registry/adr/000-migrate-git-to-db-ui.md:65,70` — "API validates ranges on write, preventing gaps and conflicts that were silently accepted by DSL parsing"). That is true for several rules (enums, ranges, parent) but, as the matrix shows, a meaningful set was not carried over.

## 3. Parity matrix

### 3a. Single-field validations (old: invoked per config in `validateEscrowConfiguration`, `OLD-VALIDATOR:104-144`)

| # | Old rule | Old ref | v3 status | v3 ref | Class |
|---|----------|---------|-----------|--------|-------|
| 1 | `componentOwner` required (non-blank), **all** components | `OLD-VALIDATOR:146-150` | ❌ absent | raw assign `NEW-SVC:215` (create), FC-gated `NEW-SVC:410` (update); no required check | **GAP** (documented requirement — `functional-spec.md:45` lists `componentOwner` as required — not enforced at runtime) |
| 2 | `componentDisplayName` required — only if `explicit && external` | `OLD-VALIDATOR:184-185` | ❌ absent | raw assign `NEW-SVC:214`/`:409` | **GAP** (conditional; `functional-spec.md:46`) |
| 3 | `releaseManager` required **+** match `\w+(,\w+)*` — only if `explicit && external` | `OLD-VALIDATOR:187-195` | ❌ absent | `NEW-SVC:237` (create) / `:420-421` (update) → `NEW-ENTITY.replaceReleaseManagerUsernames` → `canonicalizeUsernames` (trim/dedupe/drop-blank only) | **GAP** (user-reported; `functional-spec.md:46`) |
| 4 | `securityChampion` required **+** pattern — only if `explicit && external` | `OLD-VALIDATOR:199-206` | ❌ absent | `NEW-SVC:238` / `:423-424` → same canonicalize-only path | **GAP** (user-reported; `functional-spec.md:46`) |
| 5 | `copyright` required if copyrightPath set — only if `explicit && external` | `OLD-VALIDATOR:196-198` | ❌ absent | raw assign `NEW-SVC:224`/`:426` | **GAP** (conditional; `functional-spec.md:46`) |
| 6 | explicit-external must define ≥1 distribution coordinate (GAV/DEB/RPM/Docker) | `OLD-VALIDATOR:167-180`, called `:207` | ❌ absent | none | **GAP** (medium) |
| 7 | active-employee check on owner/RM/SC (`getEmployee(...).active`) | `OLD-CITASK` `findErrors`, scope filter `OLD-CITASK:156` | ❌ absent at runtime; build/CI-only over legacy config | no employee-service usage anywhere in the server module (grep `v3` = 0 hits) | **GAP** at runtime (undocumented). See §5 caveat — was a default-off CI gate, never per-request |
| 8 | `buildSystem` specified | `OLD-VALIDATOR:211-215` | ✅ migrated (enum + required-on-create) | `validateBuildSystem` `NEW-SVC:1650`, called `:161,363`; build required on create per `NEW-SVC:150-152` | MIGRATED |
| 9 | `artifactId` non-empty + valid regex | `OLD-VALIDATOR:363-373` | ❌ absent | artifact patterns stored raw (no regex-validity check) | **GAP** (low) |
| 10 | `groupId` starts with a supported prefix | `OLD-VALIDATOR:376-390` | ❌ absent | none (GAV accepted as-is) | **GAP** (medium) |
| 11 | VCS roots required per build system; `vcsUrl` non-empty; BS2_0 single fake-url root | `OLD-VALIDATOR:392-415` | ◐ partial | `repositoryType` enum validated (`validateRepositoryType` `NEW-SVC:1664`); the per-build-system structural rules are gone (BS2_0/PROVIDED are legacy build-system concepts) | mostly INTENTIONAL (legacy build-system semantics), repositoryType enum MIGRATED |
| 12 | hotfix version-format rules (start-with / differ-from base, etc.) | `OLD-VALIDATOR:643-676` | ❌ absent | `jiraHotfixVersionFormat` stored raw `NEW-SVC:227`/`:431-432` | **GAP** (medium) — but see `schema-spec.md:138` (UI read-only, inherited from Defaults), so partly mitigated |
| 13 | `versionRange` syntax valid | `OLD-VALIDATOR:417-429` | ✅ migrated | `validateRangeSyntax` `NEW-SVC:1463`, called `:160,500` | MIGRATED |
| 14 | jira params: section present, `projectKey` non-blank, major/release formats set, release≠major, illegal-char check | `OLD-VALIDATOR:431-469` | ❌ absent | no jira-format validation in `NEW-SVC` | ◐ INTENTIONAL-ish — jira version formats are inherited from Defaults and UI read-only (`schema-spec.md:138`), so user can't set them; confirm before treating as a true gap |
| 15 | `system` required + pattern + supported values | `OLD-VALIDATOR:471-490` | ◐ partial | `validateAndCanonicalizeSystemCode` `NEW-SVC:986`, called `:188-189,386-387` (supported-value + canonicalize); **required** relaxed (system is optional in v4) | supported-value check MIGRATED; required-ness INTENTIONAL relaxation (`functional-spec.md:47` lists `system` optional) |
| 16 | `clientCode` matches `[A-Z_0-9]+` | `OLD-VALIDATOR:492-497` | ❌ absent | raw assign `NEW-SVC:217`/`:412` | **GAP** (low) — `functional-spec.md:47` lists it optional but does not state the pattern was dropped |
| 17 | `releasesInDefaultBranch` required | `OLD-VALIDATOR:499-504` | ◐ relaxed | optional Boolean `NEW-SVC:225`/`:427-428` | INTENTIONAL (`functional-spec.md:47` optional) |
| 18 | `solution` required | `OLD-VALIDATOR:506-511` | ◐ relaxed | optional Boolean `NEW-SVC:219`/`:413` | INTENTIONAL (`functional-spec.md:47`, `:14` optional) |
| 19 | build tools: name/escrowEnvironmentVariable/sourceLocation/targetLocation specified | `OLD-VALIDATOR:513-530` | ◐ partial | `validateBuildToolBeans` `NEW-SVC:1317` validates bean-type enum + edition; per-tool required fields not reproduced | ◐ partial — bean-type enum MIGRATED, per-field requireds GAP (low) |
| 20 | doc: referenced `doc.component` exists, has no own `doc`, has `distribution.GAV` | `OLD-VALIDATOR:532-552` | ❌ absent | doc link is a **soft string ref** with no FK (`NEW-ENTITY` "Soft references" doc-comment; `schema-spec.md:288`; `addDocLinks` `NEW-SVC:242`), resolved at read | **GAP** (medium) |
| 21 | `copyright` in supported list (if copyrightPath set) | `OLD-VALIDATOR:554-563` | ❌ absent | write side stores raw; `CopyrightServiceImpl` only validates the filename regex on **read** | **GAP** (low-medium) |
| 22 | `labels` ∈ predefined `availableLabels` | `OLD-VALIDATOR:565-576` | ◐ changed | `validateLabels` `NEW-SVC:886` requires ≥1 non-blank + canonicalizes + **auto-creates** label rows (`ensureLabelExists`) | INTENTIONAL — open vocabulary replaces closed list (confirm this was a deliberate model change) |

### 3b. Cross-component / composite validations (old: invoked `OLD-VALIDATOR:132-138` only when no single-field errors)

| # | Old rule | Old ref | v3 status | v3 ref | Class |
|---|----------|---------|-----------|--------|-------|
| 23 | version-range intersections **within** a component | `OLD-VALIDATOR:309-361` | ✅ migrated (per-field) | field-override disjointness `validateFieldOverrideRange` `NEW-SVC:1576`; documented `functional-spec.md:109-114`, `adr/000:65` | MIGRATED + enhanced |
| 24 | duplicate `groupId:artifactId` **across components** in overlapping ranges | `OLD-VALIDATOR:309-361` (map pass) | ❌ absent | no cross-component query | **GAP** (medium-high) |
| 25 | `groupId:artifactId` pattern intersection across different components | `OLD-VALIDATOR:217-242` | ❌ absent | none | **GAP** (medium-high) |
| 26 | each (jira `projectKey`, `versionPrefix`) maps to ≤1 non-archived component | `OLD-VALIDATOR:244-265` | ❌ absent | none | **GAP** (medium) |
| 27 | `parentComponent` exists + no parent-of-parent | `OLD-VALIDATOR:267-284` | ✅ migrated + enhanced | `validateParentInvariants` `NEW-SVC:1063` (exists, canBeParent, single-level, demotion guard); documented `schema-spec.md:132-133,450` | MIGRATED |
| 28 | archived component can't be `explicit && external` | `OLD-VALIDATOR:292-302` | ❌ absent | none | **GAP** (low-medium) |
| 29 | docker image-name globally unique | `OLD-VALIDATOR:605-628` | ❌ absent | none (`schema-spec.md:321` has no uniqueness constraint) | **GAP** (medium) |

### 3c. Separate / structural / build-time validations

| # | Old rule | Old ref | v3 status | v3 ref | Class |
|---|----------|---------|-----------|--------|-------|
| 30 | escrow.generation not double-defined (groovy + kotlin DSL), at component / subcomponent / version-range scope | `OLD-VALIDATOR:585-603`, invoked `OLD-LOADER:221` | ⊘ N/A | single DB source of truth; generation is one enum field validated by `validateEscrowGenerationMode` `NEW-SVC:1657` | INTENTIONAL (dual-source concept removed) |
| 31 | DSL subcomponent version range must have a matching groovy config | `OLD-LOADER:258` | ⊘ N/A | DSL/groovy dual-source removed | INTENTIONAL |
| 32 | DSL structural/syntax: unknown attributes, section shapes, GAV/DEB/RPM/Docker pattern match, no `$` in docker image name, security-groups pattern, etc. (~18 checks) | `OLD-SLURPER` (whole class) | ⊘ N/A by design | v3 validates v4 JSON payloads via framework binding + inline shape checks (e.g. marker-vs-scalar + `rejectExtraneousMarkerFields` `NEW-SVC:1394-1424`) | INTENTIONAL (format-specific; DSL gone) |
| 33 | JIRA guard: block deleting a component that still exists as a JIRA component | `OLD-CITASK` `runEscrow` JIRA block | ❌ absent at runtime; build/CI-only | none in `NEW-SVC` (delete is soft-archive `NEW-SVC:552-564`) | **GAP** (low-medium), build-time only originally |

### 3d. New v3 validations with no old single-field equivalent (added safety — context only)

Name uniqueness `NEW-SVC:128-129` / rename conflict `NEW-SVC:353-354`; optimistic locking `NEW-SVC:331-333`; `@PreAuthorize` on every write `NEW-CTRL:162-163,328-330,350-354`; product-type/repository-type/package-type/bean-type enums; audit log. These are improvements over the DSL model (which relied on git access control + CI), not regressions.

## 4. Confirmed accidental gaps, prioritized

Gaps = rules that existed in the old pre-publish path, are unenforced on v4 writes, and are **not** recorded anywhere in `docs/registry/` as a deliberate drop.

High (data integrity / the explicit ask):
- Person fields entirely unvalidated (#1–#5, #7) — any string is accepted for `componentOwner` / `releaseManager` / `securityChampion`; `functional-spec.md:45-46` says these are required/conditionally-required and "enforced by current `EscrowConfigValidator`", but that enforcement is import-time only.
- Cross-component artifact collisions (#24, #25) — two components can now claim the same `groupId:artifactId` in overlapping ranges; the old validator made that a hard error.

Medium:
- jira `projectKey`+`versionPrefix` uniqueness (#26); docker image-name uniqueness (#29); explicit-external ≥1 distribution coordinate (#6); `groupId` supported-prefix (#10); doc-component existence/shape (#20); hotfix-format rules (#12).

Low:
- `clientCode` pattern (#16); `copyright` supported-list (#21); build-tool per-field requireds (#19); archived≠explicit-external (#28); JIRA-delete guard (#33); `artifactId` regex-validity (#9).

Intentional / documented (verify, then record): #11 (legacy build-system VCS rules), #14 (jira formats — inherited Defaults, read-only), #15/#17/#18 (required→optional relaxations, `functional-spec.md:47`), #22 (open label vocabulary), #30/#31/#32 (DSL-only concepts).

## 5. Caveats / things the report should not overstate

1. The active-employee check (#7) was **never** a per-request runtime validation in the old format either — it was a default-off (`cr.employeeServiceEnabled=false`) CI gate over the config repo, scoped to components whose `componentDisplayName` does not end with `(archived)` (`OLD-CITASK:156`; `archived` itself is derived from explicit config or that suffix, `OLD-LOADER:789`). So the §6 recommendation is a **modernization** (stronger than the old behavior), not a like-for-like port.
2. Doc-reference (#20): both `NEW-ENTITY` (the "Soft references" doc-comment) and `schema-spec.md:288` agree that `component_doc_links.doc_component_key` is a soft string reference with **no** FK (the target may be archived or in a different installation, so it is resolved in the service layer). There is no FK fallback, so the old referential check (`OLD-VALIDATOR:532-552`) is an unambiguous accidental gap. Severity: medium.
3. Per-component **ownership** is documented as a *deferred* feature (`technical-design.md:327`, `adr/004-auth-keycloak.md:148,231`, `functional-spec.md:242`) — but that is about ownership-based **permissions** (who may edit/archive), which is a different thing from **validating that the owner field names a real, active employee**. Do not cite the ownership-deferral as cover for the missing active-employee validation.

## 6. Recommendations

Headline (person fields — chosen direction): restore an **active-employee check via employee-service at the v4 API**, on create (`POST /rest/api/4/components`) and update (`PATCH /rest/api/4/components/{id}`). Resolve each `componentOwner` / `releaseManager` / `securityChampion` through an employee-service client bean and reject unknown / inactive users.

- Pair it with the cheap required/pattern checks from the old validator, **preserving the old conditionality** rather than inventing a stricter contract by default: `componentOwner` required for all components; `releaseManager` / `securityChampion` required + `\w+(,\w+)*` only when `distributionExplicit && distributionExternal` (the v4 columns at `NEW-ENTITY` map directly to the old `explicit && external` gate). If the team instead wants these unconditionally required in v4, state that as a deliberate contract change in `functional-spec.md` first.
- Trade-offs to make explicit in the implementing PR: employee-service becomes a **runtime** dependency (today the client is build-time only); it should sit behind a feature flag mirroring `employeeServiceEnabled` plus URL/token config, and degrade safely (fail-open vs fail-closed is a product decision) when the flag is off or the service is unreachable.

Other gaps — proposed disposition:
- Re-introduce as runtime checks: #24/#25 (cross-component artifact collision), #26 (jira key+prefix uniqueness), #29 (docker name uniqueness), #6 (explicit-external ≥1 coordinate), #10 (groupId prefix), #28 (archived≠explicit-external), #20 (doc-component existence/shape — a soft-ref existence lookup). These need cross-component / cross-row queries the v4 service does not yet issue.
- Cheap field checks worth restoring: #16 (clientCode pattern), #21 (copyright list), #9 (artifactId regex), #19 (build-tool requireds).
- Confirm-then-document as intentional (no code): #11, #14, #15, #17, #18, #22, #30, #31, #32.
- Decide separately: #12 (hotfix format — mostly mitigated by read-only inherited Defaults), #33 (JIRA-delete guard — was build-time only).

Process note: this audit is verification only — no code changes. Each remediation above is a separate follow-up PR that must update `docs/registry/` and add a backlog entry `docs/registry/tech-debt/012-pre-publish-validation-parity.md` (next free id is 012), per the project rule that implementation plans update the arch/design docs. Rows classified INTENTIONAL should be recorded explicitly in `functional-spec.md` so a future reader sees them as decisions, not omissions.

## Appendix A — reproduction of the headline gap (optional, not yet run)

With the service running locally, `POST /rest/api/4/components` with an **otherwise-valid** payload (valid `name` + `baseConfiguration.build.buildSystem`, which the v4 contract requires at `NEW-SVC:127-129,150-152`) where only the person fields hold garbage, e.g. `"componentOwner": "!!!nonsense###"`, `"releaseManager": ["zzz not a user"]`, `"securityChampion": ["@@@"]`. Expected: 2xx — demonstrating no person-field validation. Building the payload this way ensures a 400 from a missing `name`/`buildSystem` is not misread as person-field validation.

## Appendix B — key references

- Old orchestrators: `OLD-LOADER:180` (`loadFullConfiguration`), `:211` (`new EscrowConfigValidator`), `:221` (`validateEscrow`), `:227` (`validateEscrowConfiguration`); `OLD-VALIDATOR:104-144` (per-config + composite call order).
- Old employee/JIRA CI task: `OLD-CITASK` (`runEscrow`, `findErrors`, `getJiRAComponents`); still wired at `.teamcity/settings.kts:1087-1088`, `gradle.properties:22`, `component-resolver-core/build.gradle:22`.
- New person-field handling: `NEW-SVC:215,237-238` (create), `NEW-SVC:410,420-424` (update); `NEW-ENTITY.replaceReleaseManagerUsernames` / `replaceSecurityChampionUsernames` / `canonicalizeUsernames` (trim/dedupe/drop-blank, no validation).
- Docs consulted: `docs/registry/functional-spec.md:45-46,47,109-114,242`; `docs/registry/technical-design.md:327`; `docs/registry/schema-spec.md:132-133,138,288,321,450`; `docs/registry/adr/000-migrate-git-to-db-ui.md:65,70`; `docs/registry/adr/004-auth-keycloak.md:148,231`.

## Addendum 2026-06-12 — #24/#25 scope correction + migration gate

The v3 runtime re-implementation of #24/#25 (`validateMavenArtifactCollisions`) keyed the collision
on `groupPattern:artifactPattern` only, while the persisted distribution rows carry `extension` and
`classifier`. That was STRICTER than legacy in the wrong dimension: the old validator's #24/#25 ran
over the component `groupId`/`artifactId` ownership mapping (OLD-VALIDATOR:217-242, 309-361) and
never cross-validated `distribution { GAV }` coordinates at all — so two components publishing the
same `g:a` with different packaging (e.g. `…:zip` vs `…:apk`) were legal data for years, and the v3
check false-positived on them, blocking ANY save of either component (observed on QA 2026-06-12 as a
misleading Portal "Save conflict / updated by another user" toast).

Resolution (per product decision):
- Collision identity is now the FULL coordinate `(group, artifact, extension, classifier)`
  (`MavenGavCollision`); null extension/classifier only matches null.
- All uniqueness 409s say `uniqueness violation: …` and carry `ErrorResponse.errorCode =
  UNIQUENESS_VIOLATION` (optimistic-lock 409s carry `OPTIMISTIC_LOCK`) so the Portal can stop
  conflating them.
- The migration pipeline now enforces the SAME invariants up front (§6.0 aggregated uniqueness
  pre-pass: distribution GAV, jira projectKey+versionPrefix, docker image, displayName) and fails
  before the first write with a full offender report; single-component migration checks before any
  write as well.
- Residual gap (tracked as a follow-up issue): the TRUE legacy #24/#25 subject — the
  `component_artifact_ids` ownership mapping — is still not validated on the v4 write path
  (base-row mapping changes; per-range override mapping IS covered via GROUP_ARTIFACT_PATTERN
  rows in the distribution table).

## Addendum 2026-06-26 — #24/#25 ownership mapping closed (SYS-058 / ADR-017)

The residual gap above is now closed. The `component_artifact_ids` opaque-regex ownership mapping
was replaced by an explicit mode model (`component_artifact_mappings` + `_tokens`;
EXPLICIT / ALL_EXCEPT_CLAIMED / ALL) — see ADR-017 and SYS-058. The TRUE legacy #24/#25 subject (the
component groupId/artifactId ownership mapping, OLD-VALIDATOR:217-242, 309-361) is now validated on
the v4 write path by a deterministic mode-aware matrix (`validateArtifactOwnershipCollisions` /
`OwnershipUniqueness.computeOwnershipCollisions`), keyed off the stored modes rather than regex
probing — `EXPLICIT×EXPLICIT` conflicts iff tokens intersect, `ALL×anything` and
`ALL_EXCEPT_CLAIMED×ALL_EXCEPT_CLAIMED` conflict, `EXPLICIT×ALL_EXCEPT_CLAIMED` yields. The per-range
override is now first-class mapping rows (the `GROUP_ARTIFACT_PATTERN` ownership marker is retired),
so base and override mappings are validated uniformly.

Enforcement boundary (decision): the ownership matrix runs on the v4 write path (create
unconditional; update when `artifactIds` is present — a field-override cannot change ownership and
must not re-trigger it), NOT in the §6.0 migration pre-pass. Production is overlap-free by
construction under the legacy single-match resolver, and migration enforces correctness via strict
artifactId→mode classification (no escape hatch), gated by `RealDslUniquenessAcceptanceTest` (0
unclassifiable patterns). This is distinct from the distribution-GAV / jira / docker invariants,
which DO run in the migration pre-pass.
