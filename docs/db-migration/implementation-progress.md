# Implementation Progress

## Phase 1: Database Foundation ✅

| Task | Status | Notes |
|------|--------|-------|
| 1A | ✅ Done | Gradle deps, Flyway (V1-V3), docker-compose, application-dev-db.yml |
| 1B | ✅ Done | All JPA entities + Spring Data repositories |
| 1C | ✅ Done | EntityMappers.kt + V4Mappers.kt |

## Phase 2: CRUD API v4 + Audit ✅

| Task | Status | Notes |
|------|--------|-------|
| 2A | ✅ Done | ComponentManagementService + AuditService + domain events |
| 2B | ✅ Done | ComponentControllerV4Ye, AuditControllerV4, AdminControllerV4, ConfigControllerV4 |

## Phase 3: Component-Source Routing ✅

| Task | Status | Notes |
|------|--------|-------|
| 3A | ✅ Done | DatabaseComponentRegistryResolver (@Transactional readOnly) |
| 3B | ✅ Done | ComponentRoutingResolver (@Primary) + ComponentSourceRegistry (Caffeine cache) |

## Phase 4: React UI ✅ (extracted to `octopus-components-management-portal`)

This phase was originally implemented in-tree as a `components-registry-ui/` Gradle module. In April 2026 the entire module was deleted from this repo and the UI moved to `octopus-components-management-portal` as a Spring Cloud Gateway BFF + standalone Vite SPA — see [ADR-012](adr/012-portal-architecture.md) and PR #147 (commit `26278f2`). The original task list is preserved below for traceability; the live code now lives in the Portal repo. Per-feature status (component list / editor / audit / admin) is tracked in the Portal's own `docs/features/` going forward.

| Task | Status | Notes (historical, before extraction) |
|------|--------|-------|
| 4A | ✅ Done → moved to Portal | Vite + React 19 + TailwindCSS + shadcn/ui (Gradle integration removed in PR #147; Portal builds via npm + a single Gradle wrapper task) |
| 4B | ✅ Done → moved to Portal | Component list with filters, pagination, TanStack Table |
| 4C | ✅ Done → moved to Portal | Component editor (tabbed: General/Build/VCS/Distribution/Jira/Escrow), field overrides |
| 4D | ✅ Done → moved to Portal | Audit log viewer + Admin settings (field config + component defaults editors) |

## Phase 5: Import Service ✅

| Task | Status | Notes |
|------|--------|-------|
| 5A | ✅ Done | ImportServiceImpl: migrateComponent, migrateAllComponents, migrateDefaults, validateMigration |

## Phase 6: Full Migration ✅

| Task | Status | Notes |
|------|--------|-------|
| 6A | ✅ Done | 933/933 components migrated. Defaults.groovy migrated. |

## Phase 7: Editable Everything (Iteration 1) ✅

Backend tasks (M1, B1) live in this repo. Frontend tasks (U1-U3) were implemented here originally and **moved to `octopus-components-management-portal` in PR #147** along with the rest of the UI; the file paths below for U1/U2/U3 reflect Portal locations as of `develop`.

| Task | Status | Notes |
|------|--------|-------|
| M1 — Expand migrateDefaults() | ✅ Done | Backend. Nested sub-objects: build, jira, distribution, vcs, escrow, doc + deprecated, octopusVersion. File: `ImportServiceImpl.kt` |
| U3 — EnumSelect + useFieldConfig | ✅ Done → Portal | Portal: `frontend/src/components/ui/EnumSelect.tsx`, `frontend/src/hooks/useFieldConfig.ts`. `GeneralTab` uses EnumSelect for productType |
| B1 — Extend ComponentUpdateRequest | ✅ Done | Backend: 6 nested DTOs (BuildConfiguration, VcsSettings, Distribution, JiraComponentConfig, EscrowConfiguration). Files: `ComponentUpdateRequest.kt`, `ComponentManagementServiceImpl.kt`. Portal frontend: `frontend/src/hooks/useComponent.ts` |
| U2 — Editable sub-entity tabs | ✅ Done → Portal | Portal: all 5 tabs (`BuildTab.tsx`, `VcsTab.tsx`, `DistributionTab.tsx`, `JiraTab.tsx`, `EscrowTab.tsx`) under `frontend/src/components/editor/`, plus `frontend/src/pages/ComponentDetailPage.tsx`. Per-tab Save buttons; optimistic-locking on `@Version` |
| U1 — Structured Defaults form | ✅ Done → Portal | Portal: `frontend/src/components/admin/ComponentDefaultsForm.tsx` with tabbed sections (General, Build, Jira, Distribution, VCS, Escrow) + raw JSON toggle. `frontend/src/pages/AdminSettingsPage.tsx` updated |

## Phase 8: Smart Fields + Overrides (Iteration 2) ✅

Backend tasks (B2, B3) live in this repo. Frontend tasks (U4, U5) moved to Portal in PR #147; paths below reflect Portal locations.

| Task | Status | Notes |
|------|--------|-------|
| B2 — /meta/owners endpoint | ✅ Done | Backend. `GET /rest/api/4/components/meta/owners` returns distinct component owners. Files: `ComponentRepository.kt`, `ComponentControllerV4.kt` |
| U4 — PeopleInput autocomplete | ✅ Done → Portal | Portal: `frontend/src/components/ui/PeopleInput.tsx`, `frontend/src/hooks/useOwners.ts`. `GeneralTab` uses PeopleInput for componentOwner |
| B3 — Wire field_overrides into resolver | ✅ Done | Backend. `OverrideApplicator.kt`; `FieldOverrideRepository.findByComponentName()` wired into `DatabaseComponentRegistryResolver.getResolvedComponentDefinition()` and `findConfigurationByDockerImage()` |
| U5 — Inline field override UI | ✅ Done → Portal | Portal: `frontend/src/components/editor/FieldOverrideInline.tsx`, `frontend/src/lib/versionRange.ts`. Inline overrides in GeneralTab, BuildTab, JiraTab, EscrowTab |

## Phase 9: Operational Hardening (Auth, Info, History, Async, Schema)

| Task | Status | Notes |
|------|--------|-------|
| Keycloak auth | ✅ Done | `WebSecurityConfig` + `PermissionEvaluator` extending cloud-commons; v1/v2/v3 + v4 reads + `/info` permitAll, v4 writes/admin/audit `@PreAuthorize`. PR #150 (commit `b97fad2`). Contract: ADR-004. |
| Audit `changedBy` wiring | ✅ Done | `AuditServiceImpl` reads `SecurityService.getCurrentUser().username`; falls back to `"system"` for background jobs. Closed PR #148 review finding #5. |
| Anonymous `/info` endpoint | ✅ Done | `InfoControllerV4` returns `{name, version}` from `BuildProperties`. PR #154. Contract: SYS-033. |
| `/auth/me` endpoint | ✅ Done | `AuthController` delegates to `SecurityService`. Contract: SYS-034. |
| `/admin/migrate-history` | ✅ Done | `GitHistoryImportService` + `GitHistoryImportStateEntity` (V5 schema); idempotency via `INSERT … ON CONFLICT DO NOTHING`. PR #151 + auth gate fix #155. Contract: MIG-026. |
| Async `/admin/migrate` | ✅ Done | `MigrationJobService` + `MigrationJobResponse` + `MigrationExecutorConfig`; 202/409 re-run guard, `GET /admin/migrate/job` polling, in-memory state. PR #156 (commit `c81026b` / merged as `4d4abcb`). Contract: MIG-027. Open follow-up: persisted state — MIG-028. |
| V4 schema | ✅ Done | `V4__artifact_ids_version_level.sql` — polymorphic owner XOR for `component_artifact_ids` (component vs component_version). |
| V5 schema | ✅ Done | `V5__audit_source_and_history_state.sql` — `audit_log.source` (api / git-history) + `git_history_import_state` table. |
| `ft-db` profile | ✅ Done | H2 + auto-migrate for downstream FT testing. PR #148 (commit `7733f83`). Contracts: SYS-026, SYS-027. |
| UI extracted to Portal | ✅ Done | `components-registry-ui/` module + `SpaWebConfig.kt` removed; UI now lives in `octopus-components-management-portal`. PR #147 (commit `26278f2`). Decision recorded in [ADR-012](adr/012-portal-architecture.md), supersedes ADR-009. |

## Known Bugs Fixed During Development

- `MultipleBagFetchException` — use `findByName()` (lazy), not `findByNameWithAllRelations()`
- `LazyInitializationException` — `@Transactional(readOnly=true)` on `DatabaseComponentRegistryResolver`
- `VARCHAR(500)` overflow — V3 migration widens columns to TEXT
- React white page — `basename="/ui"` in BrowserRouter; Vite base stays `/`
- Radix `<SelectItem value="">` — use `"__none__"` sentinel
- Old JS served — Gradle `copyUiDist` changed to `Sync`
- `@Version` not incrementing on child-only PATCH — fixed with `entity.updatedAt = Instant.now()` before save (Phase 7)
- PATCH response returning stale version — changed `save()` to `saveAndFlush()` (Phase 7)
- Field override PATCH nulling value — changed to `request.value?.let { entity.value = it }` (Phase 8)
- Migrated `build-tools` endpoint returned `500`/`[]` for DB components — implemented `DatabaseComponentRegistryResolver.getBuildTools()` and preserved polymorphic `buildTools` metadata during migration (Migration regression MIG-022)
- DB `find-by-artifact` ignored version-specific artifact IDs — `DatabaseComponentRegistryResolver` now matches `componentVersion.artifactIds`, respects version ranges, and prefers version-specific matches over component-level ones (migration regression MIG-023)
- DB migration lost `parentComponent` and `escrow.additionalSources` on resolver path — import/entity mappers now preserve both values for DB-backed V1/V2 responses, covered by DB-backed resolver regression suite (`RES-006`, `RES-007`, `RES-008`)
- DB `jira-component-version-ranges` emitted entries with blank Jira project key — `DatabaseComponentRegistryResolver` now skips incomplete Jira configs so aggregate ranges match Git behavior (`RES-001`)

## Runtime Verification (Phase 7+8) — historical, pre-extraction

The table below was the smoke-check pass on the embedded UI build at the end of Phase 8 (commits between 2026-03 and 2026-04, before PR #147). It is preserved as a record of what was passing at the cutover, not as a current verification — the embedded UI no longer exists in this repo. The Portal repository owns the runtime verification of the corresponding UI flows from PR #147 onward; Portal-side tests live under `frontend/src/**/*.test.tsx` and `frontend/e2e/`.

Backend-only checks from the table (server health, GET endpoints, PATCH with nested DTOs, optimistic locking, field overrides CRUD) remain valid against the current codebase; they're now exercised by the regular integration test suite rather than ad-hoc curls against `localhost:4567`.

| Check | Result | Still valid? |
|-------|--------|---|
| Server health | UP | ✅ Yes — actuator |
| GET /components (paginated) | 933 total | ✅ Yes — covered by `ComponentControllerV4Test` and prod migration-status |
| GET /components/meta/owners | 97 owners | ✅ Yes — `SYS-017` |
| POST /admin/migrate-defaults | Nested build/jira/distribution/vcs/escrow keys | ✅ Yes — `SYS-018` |
| GET /admin/migration-status | db=933, git=0 | ✅ Yes |
| PATCH with nested buildConfiguration | javaVersion changed, version incremented | ✅ Yes — `SYS-004` |
| PATCH with nested jiraComponentConfig | projectKey + displayName updated | ✅ Yes — `SYS-007` |
| PATCH with nested escrowConfiguration | generation + diskSpace updated | ✅ Yes — `SYS-008` |
| PATCH with nested distribution | explicit/external flags updated | ✅ Yes — `SYS-006` |
| PATCH with nested vcsSettings (entries replace-all) | vcsType + entries replaced | ✅ Yes — `SYS-005` |
| Optimistic locking (stale version) | Proper error returned | ✅ Yes — `SYS-009` |
| Field overrides CRUD | POST 201, GET 200, PATCH 200, DELETE 204 | ✅ Yes — `SYS-012`–`SYS-016` |
| TypeScript / Kotlin / Vite build | 0 errors | Now Portal-side concern (`./gradlew build` over there) |
| E2E smoke tests | 4/4 pass | Historical — Portal `frontend/e2e/smoke.spec.ts` is the current equivalent |

## Playwright E2E Smoke Tests — moved to Portal

The original 4-test smoke pack at `components-registry-ui/e2e/smoke.spec.ts` was deleted with the rest of the UI module in PR #147. The current equivalent lives at `frontend/e2e/smoke.spec.ts` in the Portal repo. Authenticated e2e (Migration tab + Admin-mode + role gating) is still pending — see Portal `TD-001`.

## What's Left (see todo.md)

- Persisted async migration job state across pod restarts — MIG-028.
- Expand migration regression suite from local DB-backed resolver coverage to replayed prod traffic diff checks.
- Migration defaults application — some components (e.g. TEST_COMPONENT) may have empty fields that should inherit from Defaults.groovy.
- OverrideApplicator live version-range matching (Phase 1: scalar fields only, tested CRUD but not runtime application with version).
- TLS migration to Ingress + shared wildcard Secret on Portal side — Portal `TD-004`.
- OpenAPI v4 spec generation + sharing with Portal — TD-003 (CRS) / Portal TD-002.
- Cutover Phase 5: drop `component_source` table and remove Git resolver / JGit dependency once stability is confirmed — see [ADR-013](adr/013-cutover-strategy.md) (Proposed).
