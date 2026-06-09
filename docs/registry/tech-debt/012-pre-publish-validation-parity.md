# TD-012: Pre-publish CR validation parity (old `main` → v3/v4)

## Status

Complete. The v3/v4 write-path gaps have either been restored or recorded as
intentional decisions, and the companion Portal change surfaces field errors,
employee lookup results, and inactive-person badges.

Source of truth for the gap analysis: `.github/audit/VALIDATION-PARITY-2026-06-03.md`
(old = `main` @ `3aaeecf0`; new = `v3` @ `478dd287`). This ledger is owned by the
person-field PR line; the other stages append their status here as they land.

## Background

The old model was a **CI gate over a config repo** (`EscrowConfigValidator` +
the default-off `ComponentRegistryValidationTask` + `GroovySlurperConfigValidator`).
The new model is a **runtime API over a DB** — a v4 write *is* publication. Any
old rule not re-implemented in `ComponentManagementServiceImpl` is unenforced for
ongoing edits. The audit classified each rule as MIGRATED, INTENTIONAL, or GAP.

## Item ledger (audit row → status)

Legend: ✅ done (this/a merged PR) · ◻ open · ⊘ intentional (to be recorded as a decision).

### Person fields — Stage 1/2 (this PR line)

| Audit # | Rule | Status |
|---|---|---|
| #1 | `componentOwner` required (non-blank), all components | ✅ Stage 1 — `PersonFieldValidator` (unconditional) |
| #3 | `releaseManager` required + per-element `^\w+$` under `explicit && external` | ✅ Stage 1 — per-element (not CSV) |
| #4 | `securityChampion` required + per-element `^\w+$` under `explicit && external` | ✅ Stage 1 |
| #7 | active-employee check on owner/RM/SC | ✅ Stage 1 — runtime, flag-gated, **fail-open** (ADR-015) |
| — | employee lookup endpoints for the UI picker + badge | ✅ Stage 2 — `GET /meta/employees`, `POST /meta/employees/status` |
| #2 | `componentDisplayName` required under `explicit && external` | ✅ restored — `validateMalformedFieldRules` (gated on `explicit && external`); `displayName` stays nullable for all other components (preserving the legacy `$.name` wire) |
| #5 | `copyright` required if `copyrightPath` set, under `explicit && external` | ✅ final-state requiredness restored; hidden/unconfigured field is skipped |

### Cross-component integrity — Stage 4 (separate PR, independent)

| Audit # | Rule | Status |
|---|---|---|
| #24/#25 | duplicate / intersecting `groupId:artifactId` across components | ✅ 409 `CrossComponentConflictException` |
| #26 | (jira `projectKey`, `versionPrefix`) ≤1 non-archived component | ✅ 409 |
| #29 | docker image-name global uniqueness | ✅ 409 |
| #6 | explicit-external must define ≥1 distribution coordinate | ✅ 400 |
| #10 | `groupId` supported-prefix | ✅ 400 |
| #28 | archived component can't be `explicit && external` | ✅ 400 |
| #20 | doc-component existence (soft ref, no FK) | ✅ 400 soft lookup |

### Cheap field-format checks — Stage 5 (separate PR, independent)

| Audit # | Rule | Status |
|---|---|---|
| #16 | `clientCode` matches `[A-Z_0-9]+` | ✅ 400 |
| #21 | `copyright` ∈ supported list | ✅ 400 |
| #9 | `artifactId` non-empty + valid regex | ✅ 400 |
| #19 | build-tool per-field requireds (name/env/source/target) | ✅ v4 analogue: `beanType` required, 400 |

### Intentional / documented relaxations — Stage 6 (docs-only)

| Audit # | Rule | Disposition |
|---|---|---|
| #11 | per-build-system VCS structural rules | ⊘ INTENTIONAL — legacy build-system semantics; `repositoryType` enum MIGRATED |
| #14 | jira version-formats | ⊘ INTENTIONAL — inherited from Defaults, UI read-only |
| #15/#17/#18 | `system` / `releasesInDefaultBranch` / `solution` required→optional | ⊘ INTENTIONAL — recorded optional in functional-spec |
| #22 | `labels` ∈ closed `availableLabels` | ⊘ INTENTIONAL — open vocabulary + auto-create |
| #30/#31/#32 | double escrow.generation / DSL-range-missing / DSL structural | ⊘ N/A — DSL dual-source removed |
| #12 | hotfix version-format rules | ⊘ INTENTIONAL — inherited/read-only in Portal; permissive storage preserves imported configs and resolver compatibility |
| #33 | JIRA-delete guard (block deleting a component still in JIRA) | ⊘ INTENTIONAL — legacy config-snapshot CI guard; v4 delete archives in place and has no runtime JIRA dependency |

### Already MIGRATED (context — no action)

#8 buildSystem, #13 versionRange syntax, #23 within-component range intersection,
#27 parentComponent invariants. (Audit §3a/§3b.)

## Stage 1/2 implementation notes (this PR)

- New: `config/EmployeeServiceProperties.kt`, `config/EmployeeServiceConfig.kt`
  (two-gate optional bean), `service/impl/EmployeeDirectoryService.kt`
  (`ActiveStatus` + `ObjectProvider`-wrapped client), `service/impl/PersonFieldValidator.kt`
  (pure rules). Wired into `ComponentManagementServiceImpl.createComponent` /
  `updateComponent`. Two read endpoints on `ComponentControllerV4`.
- Gradle: explicit `org.octopusden.octopus.employee:client` `implementation` on
  the server module (was runtime-transitive only).
- Config: inert `employee-service:` block in `application.yml` (`enabled: false`);
  `employee-service.enabled: false` in test `application-common.yml`.
- Docs: ADR-015; functional-spec §1.3 person-field section.
- Fail-open is the locked decision (2026-06-03): UNAVAILABLE/DISABLED allow;
  INACTIVE/UNKNOWN reject. Get this distinction right — wrong way it is either
  useless or a hard outage dependency.

## Cross-repo follow-ups

- **Portal Stage 3** (after CRS Stage 1/2 merge + `crs.version` bump): inline
  errors via `parseServerFieldErrors`, the active-user picker, the inactive
  badge. Picker = suggest-from-owners + exact validate/annotate (no server-side
  directory search).
- A real-CRS e2e covering person-field editing is gated on the `crs.version`
  bump (see the existing real-CRS-edit-e2e backlog).

## Acceptance (whole TD, closed when all rows resolve)

- [x] Stage 1 person-field validation landed + green.
- [x] Stage 2 lookup endpoints landed + green.
- [x] Stages 4/5 cross-component + cheap-field checks landed.
- [x] Stage 6 intentional relaxations recorded in functional-spec; #12/#33 decided.
- [x] Portal Stage 3 surfaces the errors + picker + badge.
