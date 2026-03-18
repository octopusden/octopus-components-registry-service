# TODO / Ideas / Tech Debt

## Deferred (out of MVP scope)
- [ ] Keycloak integration (auth для v4, role-based access) — при деплое в OKD
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

## Tech Debt
_(будет пополняться по мере реализации)_

- [ ] Embedded UI V1 hardening — tighten SPA routing, remove duplicate packaging path, stabilize UI/API base path. See [TD-001](tech-debt/001-embedded-ui-v1-hardening.md).
- [ ] Enable Flyway on all environments and remove `columnDefinition = "TEXT"` workarounds from entity classes. See [TD-002](tech-debt/002-enable-flyway-remove-columnDefinition-workarounds.md).

## Future Ideas
- [ ] **Git history → audit log backfill** — насколько возможно, перенести историю изменений из Git в `audit_log` для уже существующих компонентов. Ограничения: старая DSL-структура и модель данных периодически менялись, поэтому полный и точный перенос может быть недостижим; нужен отдельный дизайн partial/backfill strategy с явной маркировкой импортированных исторических записей.
- [ ] **Migration validator / regression suite** — записать историю реальных HTTP-вызовов на продакшн-системе (Git resolver) за сутки, затем воспроизвести те же запросы после миграции в DB и сравнить ответы. Позволяет убедиться в полной обратной совместимости на реальном трафике. Варианты реализации:
  - Capture production traffic (nginx/Envoy access log → curl replay)
  - k6 или Gatling сценарий — replay записанных URL-ов, assert ≡ ответы
  - Diff-инструмент для JSON-ответов с учётом порядка полей
