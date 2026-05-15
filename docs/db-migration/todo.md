# TODO / Ideas / Tech Debt

## Recently Shipped

- [x] **Async migration** — `POST /admin/migrate` runs on a background executor with 202/409 re-run guard; `GET /admin/migrate/job` polled by Portal MigrationPanel. PR #156 (commit `c81026b` / `4d4abcb`). Contract: `MIG-027`. Open follow-up: persisted job state — `MIG-028`.
- [x] **Anonymous /info endpoint** — `GET /rest/api/4/info` returns `{name, version}` for the Portal footer. PR #154. Contract: `SYS-033`.
- [x] **Current-user endpoint** — `GET /auth/me` returns `User { username, roles, groups }`. Contract: `SYS-034`.
- [x] **Git history backfill** — `POST /admin/migrate-history` populates `audit_log` with `source='git-history'` rows. PR #151 + #155. Contract: `MIG-026`.

## Deferred (out of MVP scope)
- [ ] Port migration 4567 → 8080 — при OKD
- [ ] Profile selection для новых компонентов (pre-fill templates)
- [ ] TeamCity integration (create projects from UI)
- [ ] Webhook/Kafka events on configuration changes
- [ ] Approval workflow / PR-like change requests
- [ ] Config rollback/revert (git revert equivalent)
- [ ] Read-only view of Git-sourced components in UI
- [ ] E2E tests (Playwright)
- [ ] Performance tests (k6/Gatling)
- [ ] ArchUnit architecture tests

## Skills / Commands
- [ ] `/migrate-component <name>` — skill для запуска миграции одного компонента через API
- [ ] `/migration-status` — skill для проверки статуса миграции
- [ ] `/validate-migration` — skill для запуска валидации всех компонентов
- [ ] `/quality` — skill для запуска полного набора quality gates (static + coverage)
- [ ] `/dev-start` — skill для запуска docker-compose + bootRun с dev-db профилем
- [ ] `/ui-dev` — skill для запуска npm run dev в components-registry-ui/

## Backlog

- [ ] **SYS-037** v4 CRUD API for dependency mappings (`GET/POST/PUT/DELETE /rest/api/4/dependency-mappings`). Removes the last Git-DSL data dependency that has no management endpoint. No UI planned — API only. See [requirements-common.md §SYS-037](requirements-common.md#sys-037-v4-crud-api-for-dependency-mappings).

## Tech Debt
_(будет пополняться по мере реализации)_

- [x] ~~Embedded UI V1 hardening~~ — **Superseded** by UI extraction to `octopus-components-management-portal` (commit `26278f2`, PR #147). `SpaWebConfig.kt` deleted. See [TD-001](tech-debt/001-embedded-ui-v1-hardening.md).
- [ ] Enable Flyway on all environments and remove `columnDefinition = "TEXT"` workarounds from entity classes. See [TD-002](tech-debt/002-enable-flyway-remove-columnDefinition-workarounds.md).
- [ ] **OpenAPI v4 spec generation + share with Portal SPA** — mirror of Portal `TD-002`; closes the API/UI drift gap that came with the separate-repo decision (ADR-012). See [TD-003](tech-debt/003-openapi-v4-spec-generation.md).
- [ ] **Persist async migration job state across pod restarts.** See `MIG-028` in [requirements-migration.md](requirements-migration.md). Currently `MigrationJobServiceImpl` keeps state in `AtomicReference` (single-pod, lost on restart).
- [ ] **Field-override partial-range-overlap rejection.** `ComponentManagementServiceImpl.validateRangeSyntax` (Phase 4) currently only parses the range and relies on the DB UNIQUE `(component_id, version_range, overridden_attribute)` to block equal ranges. Per `schema-spec.md §7` the service should additionally reject *partial* overlap on the same `(component_id, overriddenAttribute)` while still permitting strict containment and disjoint ranges. Deferred from Phase 4 because the version-range library lacks a public range-intersection API; needs a small ad-hoc partial-overlap check (parse both ranges, sample boundaries, reject if neither contains the other). Land in Phase 5 or 6.
- [ ] **`EntityMappers.rangeApplies` strict-containment heuristic.** Sister item to the partial-overlap rejection above — both blocked on the same missing `containsRange` API in the version-range library. `rangeApplies(parent, child)` is currently equality-only, so a broader override (e.g., `[1.0,3.0)`) is silently dropped when enumerating a narrower range (e.g., `[1.0,2.0)`). Sample-points heuristic over `containsVersion`: parse `child` endpoints + a few interior probes via `NumericVersionFactory`, return true iff every probe is `parent.containsVersion(...)`. Needs `NumericVersionFactory` threaded through `resolveForRange` → `toEscrowModule`. Land alongside the partial-overlap item; same code area.
- [ ] **MIG-039: Port `ImportServiceImpl` to schema v2 (Phase 5b).** `migrateComponent` / `migrateAllComponents` / `validateMigration` / `migrate` are currently stubbed with `UnsupportedOperationException` (Phase 5 commit). The legacy `EscrowModule.toComponentEntity()` shortcut was removed because the v1 entity shape no longer matches v2 (no `metadata: Map`, multi-row VCS, distribution split into 4 family tables, override rows on `component_configurations`, etc.). Full port requires the schema-spec §6 pipeline: pre-pass dictionary discovery (systems, tools, labels), aggregator detection (REAL vs FAKE classification, `component_groups` rows), two-pass `parentComponent` linking, per-attribute scalar override emission, marker rows for child-collection overrides, `is_synthetic_base` detection for version-range-only components, distribution family split (maven / fileUrl / docker / packages), required-tools junction. `migrateDefaults` and `getMigrationStatus` keep working (they don't touch `ComponentEntity`). QA / FT seeding currently goes through the v4 CRUD API directly until the pipeline lands.

## Schema v2 known limitations (Phase 5b/6 follow-ups)

- [ ] **RES-001 `testGetAllJiraComponentVersionRanges`.** DB resolver enumerates more `(componentName, range)` pairs than the v1 fixture asserts. The DB path lists every override-row range as a distinct entry (per schema-spec §3 — each override row carries its own `versionRange`), while the v1 baseline collapsed them into the base `componentVersion` only. Either expand the fixture to match the v2 enumeration semantics, or filter range duplicates that match the parent in the resolver before exposing them on v1.
- [ ] **RES-008 `testGetDetailedComponent` — `escrow.buildTask` divergence.** The KTS override semantic stores `escrow.buildTask` and `build.buildTasks` as two distinct fields (Phase 5b added column `component_configurations.escrow_build_task` via Flyway `V2`). The v1 fixture was authored when the two were a single scalar, so `expected: "clean build -x test"` vs `got: "clean build"` is a fixture-drift, not a code bug. Rewrite the fixture to populate both fields, or split the assertion into separate per-field expectations.
- [ ] **RES-014 `testGetBuildTools` — KTS-only build tools.** Complex Groovy build-tool beans (`OracleDatabaseToolBean`, `PTKProductToolBean`) embed arbitrary code and can only be expressed in the Groovy DSL; the v2 `tools` dictionary stores primitive scalar fields (`name`, `escrow_env_variable`, `target_location`, `source_location`, `install_script`) which cannot round-trip the bean. EntityMappers therefore returns `emptyList()` for `buildTools` on these components. Resolution requires either (a) a schema extension that stores the bean payload (e.g., a `tool_bean_payload TEXT` column with a `bean_type` discriminator) plus a deserialiser in the resolver, or (b) excluding affected components from `buildTools` parity assertions and treating it as an v3-only legacy. Track here until decided.

## Cutover (PRD Phase 5)

- [ ] Stage 5A → 5B → 5C as described in [ADR-013](adr/013-cutover-strategy.md). 933/933 components are routed `source=db`, but Git resolver, JGit dependency, and `component_source` table are still in code.

## Future Ideas
- [x] ~~**Git history → audit log backfill**~~ — **Done.** Implemented as `POST /rest/api/4/admin/migrate-history?toRef=&reset=` in PR #151, with idempotent state via `GitHistoryImportStateEntity` and auth-gate fix in PR #155. Audit entries are written with `source = "git-history"` to distinguish from API‑driven changes. See `MIG-026` in [requirements-migration.md](requirements-migration.md) for the contract.
- [ ] **Migration validator / regression suite** — записать историю реальных HTTP-вызовов на продакшн-системе (Git resolver) за сутки, затем воспроизвести те же запросы после миграции в DB и сравнить ответы. Позволяет убедиться в полной обратной совместимости на реальном трафике. Варианты реализации:
  - Capture production traffic (nginx/Envoy access log → curl replay)
  - k6 или Gatling сценарий — replay записанных URL-ов, assert ≡ ответы
  - Diff-инструмент для JSON-ответов с учётом порядка полей
