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

## Phase 4: React UI ✅

| Task | Status | Notes |
|------|--------|-------|
| 4A | ✅ Done | Vite + React 19 + TailwindCSS + shadcn/ui + Gradle integration |
| 4B | ✅ Done | Component list with filters, pagination, TanStack Table |
| 4C | ✅ Done | Component editor (tabbed: General/Build/VCS/Distribution/Jira/Escrow), field overrides |
| 4D | ✅ Done | Audit log viewer + Admin settings (field config + component defaults editors) |

## Phase 5: Import Service ✅

| Task | Status | Notes |
|------|--------|-------|
| 5A | ✅ Done | ImportServiceImpl: migrateComponent, migrateAllComponents, migrateDefaults, validateMigration |

## Phase 6: Full Migration ✅

| Task | Status | Notes |
|------|--------|-------|
| 6A | ✅ Done | 933/933 components migrated. Defaults.groovy migrated. |

## Phase 7: Editable Everything (Iteration 1) ✅

| Task | Status | Notes |
|------|--------|-------|
| M1 — Expand migrateDefaults() | ✅ Done | Nested sub-objects: build, jira, distribution, vcs, escrow, doc + deprecated, octopusVersion. File: `ImportServiceImpl.kt` |
| U3 — EnumSelect + useFieldConfig | ✅ Done | New: `EnumSelect.tsx`, `useFieldConfig.ts`. GeneralTab uses EnumSelect for productType |
| B1 — Extend ComponentUpdateRequest | ✅ Done | 6 nested DTOs added: BuildConfiguration, VcsSettings, Distribution, JiraComponentConfig, EscrowConfiguration. Backend + frontend. Files: `ComponentUpdateRequest.kt`, `ComponentManagementServiceImpl.kt`, `useComponent.ts` |
| U2 — Editable sub-entity tabs | ✅ Done | All 5 tabs (Build, VCS, Distribution, Jira, Escrow) converted from read-only to editable with per-tab Save buttons. Files: `BuildTab.tsx`, `VcsTab.tsx`, `DistributionTab.tsx`, `JiraTab.tsx`, `EscrowTab.tsx`, `ComponentDetailPage.tsx` |
| U1 — Structured Defaults form | ✅ Done | New: `ComponentDefaultsForm.tsx` with tabbed sections (General, Build, Jira, Distribution, VCS, Escrow) + raw JSON toggle. File: `AdminSettingsPage.tsx` updated |

## Phase 8: Smart Fields + Overrides (Iteration 2) ✅

| Task | Status | Notes |
|------|--------|-------|
| B2 — /meta/owners endpoint | ✅ Done | `GET /rest/api/4/components/meta/owners` returns 97 distinct owners. Files: `ComponentRepository.kt`, `ComponentControllerV4.kt` |
| U4 — PeopleInput autocomplete | ✅ Done | New: `PeopleInput.tsx`, `useOwners.ts`. GeneralTab uses PeopleInput for componentOwner |
| B3 — Wire field_overrides into resolver | ✅ Done | New: `OverrideApplicator.kt`. `FieldOverrideRepository.findByComponentName()` added. Wired into `DatabaseComponentRegistryResolver.getResolvedComponentDefinition()` and `findConfigurationByDockerImage()` |
| U5 — Inline field override UI | ✅ Done | New: `FieldOverrideInline.tsx`, `versionRange.ts`. Inline overrides in GeneralTab, BuildTab, JiraTab, EscrowTab |

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
| V5 schema | ✅ Done | `V5__audit_source_and_history_state.sql` — `audit_log.source` (api / git_history) + `git_history_import_state` table. |
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

## Runtime Verification (Phase 7+8)

All verified on running server (localhost:4567) with 933 migrated components:

| Check | Result |
|-------|--------|
| Server health | UP |
| GET /components (paginated) | 933 total |
| GET /components/meta/owners | 97 owners |
| POST /admin/migrate-defaults | Nested build/jira/distribution/vcs/escrow keys |
| GET /admin/migration-status | db=933, git=0 |
| PATCH with nested buildConfiguration | javaVersion changed, version incremented |
| PATCH with nested jiraComponentConfig | projectKey + displayName updated |
| PATCH with nested escrowConfiguration | generation + diskSpace updated |
| PATCH with nested distribution | explicit/external flags updated |
| PATCH with nested vcsSettings (entries replace-all) | vcsType + entries replaced |
| Optimistic locking (stale version) | Proper error returned |
| Field overrides CRUD | POST 201, GET 200, PATCH 200, DELETE 204 |
| TypeScript compilation | 0 errors |
| Kotlin compilation | 0 errors |
| Vite UI build | Success |
| E2E smoke tests | 4/4 pass |

## Playwright E2E Smoke Tests

4 tests in `components-registry-ui/e2e/smoke.spec.ts` — all pass (~5s):
1. Component list loads
2. Navigation links work
3. Component detail page loads
4. No JS errors on main pages

## What's Left (see todo.md)

- Persisted async migration job state across pod restarts — MIG-028.
- Expand migration regression suite from local DB-backed resolver coverage to replayed prod traffic diff checks.
- Migration defaults application — some components (e.g. TEST_COMPONENT) may have empty fields that should inherit from Defaults.groovy.
- OverrideApplicator live version-range matching (Phase 1: scalar fields only, tested CRUD but not runtime application with version).
- TLS migration to Ingress + shared wildcard Secret on Portal side — Portal `TD-004`.
- OpenAPI v4 spec generation + sharing with Portal — TD-004 (CRS) / Portal TD-002.
- Cutover Phase 5: drop `component_source` table and remove Git resolver / JGit dependency once stability is confirmed — see ADR-013 (to be created in Step A5).
