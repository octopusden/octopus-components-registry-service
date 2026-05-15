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

## Cutover (PRD Phase 5)

- [ ] Stage 5A → 5B → 5C as described in [ADR-013](adr/013-cutover-strategy.md). 933/933 components are routed `source=db`, but Git resolver, JGit dependency, and `component_source` table are still in code.

## Future Ideas
- [x] ~~**Git history → audit log backfill**~~ — **Done.** Implemented as `POST /rest/api/4/admin/migrate-history?toRef=&reset=` in PR #151, with idempotent state via `GitHistoryImportStateEntity` and auth-gate fix in PR #155. Audit entries are written with `source = "git-history"` to distinguish from API‑driven changes. See `MIG-026` in [requirements-migration.md](requirements-migration.md) for the contract.
- [ ] **Migration validator / regression suite** — записать историю реальных HTTP-вызовов на продакшн-системе (Git resolver) за сутки, затем воспроизвести те же запросы после миграции в DB и сравнить ответы. Позволяет убедиться в полной обратной совместимости на реальном трафике. Варианты реализации:
  - Capture production traffic (nginx/Envoy access log → curl replay)
  - k6 или Gatling сценарий — replay записанных URL-ов, assert ≡ ответы
  - Diff-инструмент для JSON-ответов с учётом порядка полей
