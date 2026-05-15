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
| 3B | ✅ Done | ComponentRoutingResolver (@Primary) + ComponentSourceRegistry (in-process cache removed by SYS-032) |

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

## Phase 10: Auth Role Provisioning ✅

| Task | Status | Notes |
|------|--------|-------|
| 10A | ✅ Done | 2026-05-08 — `COMPONENTS_REGISTRY_EDITOR` + `COMPONENTS_REGISTRY_VIEWER` materialised in QA and prod realms; `COMPONENTS_REGISTRY_EDITOR` added to `default-roles-{realm}`; phantom role keys renamed `ROLE_REGISTRY_*` → `ROLE_COMPONENTS_REGISTRY_*` (CRS PR #178, Portal PR #34). Operator-facing setup steps live in [`deployment/keycloak-setup.md`](deployment/keycloak-setup.md). |

## What's Left (see todo.md)

- Persisted async migration job state across pod restarts — MIG-028.
- Expand migration regression suite from local DB-backed resolver coverage to replayed prod traffic diff checks.
- Migration defaults application — some components (e.g. TEST_COMPONENT) may have empty fields that should inherit from Defaults.groovy.
- OverrideApplicator live version-range matching (Phase 1: scalar fields only, tested CRUD but not runtime application with version).
- TLS migration to Ingress + shared wildcard Secret on Portal side — Portal `TD-004`.
- OpenAPI v4 spec generation + sharing with Portal — TD-003 (CRS) / Portal TD-002.
- Cutover Phase 5: drop `component_source` table and remove Git resolver / JGit dependency once stability is confirmed — see [ADR-013](adr/013-cutover-strategy.md) (Proposed).

---

## Schema v2 Refactor

Driven by MIG-029 investigation + cross-installation DSL audit. See [ADR-014](adr/014-schema-v2.md) and [`schema-spec.md`](schema-spec.md). Project not yet in production; QA/dev databases recreate from baseline.

| Phase | Task | Status | Notes |
|---|---|---|---|
| 1a | Design artifacts: ADR-014, `schema-spec.md`, MIG-030..MIG-038, supersede ADR-010 | ✅ Done | PR #191 (docs-only). Canonical reference for v2 schema. |
| 1b | Land `V1__schema.sql` baseline (replaces V1..V6) in Flyway path | ✅ Done | Commit `94b52d4` (held back from docs-only PR). 23 tables; Model A'. |
| 2 | Refactor entities & repositories (delete `ComponentVersionEntity` + polymorphic FK pairs; introduce `ComponentGroupEntity`, distribution-split entities, etc.) | ✅ Done | Commits `f552a39` → `47e4849` (Phase 2.0–2.5). 23 entity classes + 22 repos. |
| 3 | Rewrite `EntityMappers` + `DatabaseComponentRegistryResolver` (base + override merge; marker rows; synthetic-base handling; doc-links resolution; unified VCS mapping) | ✅ Done | Commits `1934dab` (bidi collections), `2b13521` (EntityMappers), `7196099` (resolver). Phase 3b.1 (`c31869a`) adapted `AuditEventListener`, `ConfigControllerV4`, `ComponentSourceRegistryImpl`, `GitHistoryCommitWriter`, `AuditEvent` to v2 entities. |
| 4 | Update v4 DTOs + `ComponentControllerV4` (replace `metadata: Map` with explicit fields; surface group membership; doc links as `docs[]`) | ✅ Done | Commits `72d0c76` (rename `.v2` package suffix → drop) + `89e8792` (Phase 4: rewrite v4 layer). New DTOs (`ComponentConfigurationDtos`), V4Mappers, `ComponentManagementServiceImpl`, field-override CRUD against `component_configurations`. `OverrideApplicator` retired. |
| 5a | Adapt `TeamcitySyncService` to `component_teamcity_projects`; restore `GitHistoryImportServiceImpl` / `HistoryMigrationJobServiceImpl`; stub `ImportServiceImpl` so the app boots; @Disabled the auto-migrate-dependent tests | ✅ Done | Commits `1b8aea8` (TC sync rewrite), `849a7a9` (history services + ImportServiceImpl stub), `f47eaf1` (12 schema-v2 broken test stubs), `f226aa5` (triage fixup: empty migrate result, @Disabled 7 ft-db tests, Bucket b/f fixes). `AUTH_SERVER_URL=… AUTH_SERVER_REALM=octopus ./gradlew build -x dockerBuildImage -x dockerPushImage -x ocCreate -x ocProcess -x :components-registry-automation:test` exits 0. |
| 5b (MIG-039) | Port `ImportServiceImpl` to schema v2 per §6 (pre-pass dictionary discovery; aggregator detection; two-pass `parentComponent`; per-attribute override emission; distribution family split; synthetic-base flag) | 🚧 Pending | The §6 pipeline replaces the deleted `EscrowModule.toComponentEntity()` shortcut. Until it lands, `migrate{Component,AllComponents}` throw and operator-driven `POST /admin/migrate` is unavailable; the startup auto-migrate path (`migrate()`) is a no-op. See `todo.md` MIG-039. |
| 6 | Test suite (Layer 1 synthetic fixtures; Layer 2 env-gated integration; Layer 3 internal-CI baselines) | 🚧 Pending | MIG-029..MIG-038 coverage. **Includes the schema-v2 @Disabled test-suite cleanup** — see the disabled list below; Phase 6 is INCOMPLETE until every entry is either removed, rewritten, or re-enabled. |
| 7 | QA DB recreate + full `gradlew build` | 🚧 Pending | Requires `AUTH_SERVER` env + standard excludes |
| 8 | Supersede ADR-010 | ✅ Done | ADR-010 marked superseded; ADR-014 active |
| 9 | Final docs cleanup (mandatory before merging the refactor into `main`) | 🚧 Pending | See "Final docs cleanup scope" below. |

Requirements traceability: MIG-029..MIG-038 in [`requirements-migration.md`](requirements-migration.md). Each phase ends with an independent subagent review (Sonnet default).

### Phase 6 — schema-v2 disabled-test inventory

Once Phase 5 lands the rewritten import / TC services and the `compileKotlin` step is green again, every entry below must be either **removed**, **rewritten**, or **re-enabled** before Phase 6 can be marked done. Each file carries class-level `@Disabled("schema-v2: temporarily disabled until Phase 6 test-suite rewrite")`. **Assertion bodies are preserved** (do not delete) — see [user guidance recorded with Phase 4].

| Test class | Why disabled | Phase 6 action |
|---|---|---|
| `mapper/ComponentDetailMapperTest` | Constructs legacy `ComponentEntity` via `name` / `metadata` fields; asserts on dropped DTO fields. | Rewrite against v2 entity graph + new `ComponentDetailResponse`. |
| `mapper/ComponentSummaryMapperTest` | Same legacy field set + `BuildConfigurationEntity` references. | Rewrite against v2 + new `ComponentSummaryResponse`. |
| `mapper/DistributionEntityMapperTest` | Asserts on retired `DistributionEntity` / `DistributionArtifactEntity` shapes. | Rewrite against `distribution_*` family entities (maven / fileUrl / docker / packages). |
| `service/impl/DatabaseComponentRegistryResolverTest` | Constructs legacy entities with `.name` + `.metadata`; covers resolve algorithm. | Rewrite against v2 entities (base + override rows). MIG-029 coverage. |
| `teamcity/TeamcitySyncServiceTest` | Depends on broken `TeamcitySyncService` + uses `component.name` / `teamcityProjectId` columns. | Rewrite alongside the service in Phase 5 (carries over to Phase 6 for assertions). |
| `migration/TeamcityProjectIdPersistenceRoundtripTest` | Asserts on the deleted `teamcityProjectId` column; replaced by `component_teamcity_projects` rows. | Rewrite to assert on the child table. |
| `migration/FtDbProfileWriteTest` | Uses legacy `entity.name` + `.metadata`. | Update to `componentKey` and the new flat scalar fields. |
| `migration/MigrationIntegrationTest` | Heavy DSL→DB cross-cut; uses legacy entity field set. | Rewrite alongside `ImportServiceImpl` in Phase 5. |
| `migration/MigrateHistoryAsyncEndpointTest` | Depends on broken `GitHistoryImportServiceImpl` / `HistoryMigrationJobService`. | Re-enable after Phase 5. |
| `service/impl/MigrateHistoryIntegrationTest` | Same. | Re-enable after Phase 5. |
| `service/impl/HistoryMigrationJobServiceImplTest` | Same. | Re-enable after Phase 5. |

If any other test class fails to compile once Phase 5 lands, add it here with the same `@Disabled` reason string and a Phase 6 action.

**Phase 5 also disabled 7 more classes** that depend on auto-migrate actually seeding the DB. Listed for Phase 6:

| Test class | Why disabled | Phase 6 action |
|---|---|---|
| `DbBackedComponentsRegistryServiceControllerTest` | `@BeforeAll` calls `POST /admin/migrate-components`; `ImportServiceImpl.migrateAllComponents` throws. | Re-enable once MIG-039 lands the §6 import pipeline. |
| `migration/GitVsDbValidationTest` | Same — `@BeforeAll` calls the migrate endpoint. | Re-enable with MIG-039. |
| `migration/AutoMigrateOnStartupTest` | Asserts components are in DB after startup auto-migrate. | Re-enable with MIG-039, or rewrite to seed via v4 CRUD API. |
| `migration/FtDbProfileTest` | Same auto-migrate assumption. | Same. |
| `migration/GhostComponentAfterRenameTest` | Uses `ft-db` profile + `firstComponent()` helper that expects a seeded DB. | Rewrite to seed the renamed component via v4 CRUD API in `@BeforeAll`. |
| `migration/Sys039PersistenceRoundtripTest` | Uses `ft-db` profile + `firstComponent()`. | Rewrite to seed via v4 CRUD API. |
| `service/impl/FieldConfigEnforcementIntegrationTest` | Uses `ft-db` profile + `firstComponent()`. | Rewrite to seed via v4 CRUD API. |

**Plus one test class explicitly disabled because `POST /admin/migrate` cannot succeed until MIG-039 lands:**

| Test class | Why disabled | Phase 6 action |
|---|---|---|
| `migration/MigrateEndpointTest` | `POST /admin/migrate` async job calls `migrateAllComponents`, still throws `UnsupportedOperationException`. | Re-enable with MIG-039. |

Compile-only checklist (Phase 6 cannot ship until all are ✅):
- [ ] No source file carries `@Disabled("schema-v2: …")`.
- [ ] No source file imports a deleted v1/v2-transition entity (e.g., `FieldOverrideEntity`).
- [ ] `./gradlew :components-registry-service-server:compileTestKotlin` is green.

### Final docs cleanup scope (Phase 9)

Once Phases 1b–7 land, the refactor narrative ("V1..V6 → V2", MIG-029 investigation, phase tracking) stops being useful. The permanent reference docs are rewritten to describe the new state from the perspective of a reader who never knew V1..V6 existed. The intermediate Flyway evolution was never released; it should not appear in the final documentation.

**Rewrite (kept as canonical reference):**
- `adr/014-schema-v2.md` — reframe as **"Storage redesign: Groovy DSL → PostgreSQL"**. Context = portal needs DB-backed CRUD over the Component Registry. Remove all references to V1..V6, polymorphic FKs, JSONB extensibility, MIG-029 investigation. Keep the alternative-models section (A, B, C, D, A') — that is the value of the ADR.
- `schema-spec.md` — strip "current schema (V1..V6)" framing; drop the resolve-algorithm reference to "synthetic base for legacy variants-Map" once enumeration endpoints are formally retired. Becomes a pure column-by-column reference of the live schema.

**Delete (transient implementation aids, no permanent value):**
- `implementation-progress.md` — phase tracking is done.
- `requirements-migration.md` — MIG-029..MIG-038 acceptance criteria were implementation gates; their structural fixes live in the schema itself.
- `technical-design.md` — the "how to migrate from V1..V6" guide is no longer needed.
- `adr/010-schema-extensibility.md` — never implemented; superseded; remove rather than leave a tombstone.

Phase 9 ships as a single PR titled "docs: clean up DB migration artifacts" once Phases 1b–7 are merged and the refactor is observably working in QA.
