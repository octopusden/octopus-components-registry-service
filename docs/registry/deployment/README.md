# Deployment

Deployment inputs and operator runbooks for the Components Registry Service backend on OKD, plus the
local developer setup with a working database. (The web UI lives in
`octopus-components-management-portal` — see [ADR-012](../adr/012-portal-architecture.md).)

## Files

- `keycloak-setup.md` — operator-facing setup steps for Keycloak realm-roles
  required by CRS authorization (manual Admin-Console actions not covered by
  source patches)
- `migration-runbook.md` — production migration ops playbook: git-mode-first →
  Flyway/validate → `POST /admin/migrate` → verify → flip `default-source` to `db`,
  with the partial-failure recovery and rollback paths (the reusable, generic
  form for QA/prod and another organization)
- `qa-db-refresh.md` — the `[8.0] Recreate QA DB from PROD` TeamCity button: what it copies, why
  production stays read-only, the one-time role/Vault setup, and how to undo a run
- `references/teamcity/` — raw TeamCity Kotlin DSL snippets relevant to the current OKD deployment flow
- `references/platform/okd-platform-patterns.md` — summarized conventions from `service-deployment`, `service-config`, `octopus-dms-ui`, and `octopus-api-gateway`

## Scope

- deploy the CRS backend to a test OKD environment
- a local developer setup with a working database on a laptop
- a repeatable path for QA and production
- a reusable approach for a similar setup in another organization
