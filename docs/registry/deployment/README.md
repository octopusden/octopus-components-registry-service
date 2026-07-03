# Deployment Research Workspace

This directory collects the deployment-related inputs and working documents for onboarding
`components-registry-ui` to OKD and for defining a local developer setup with a working database.

## Files

- `keycloak-setup.md` — operator-facing setup steps for Keycloak realm-roles
  required by CRS authorization (manual Admin-Console actions not covered by
  source patches)
- `migration-runbook.md` — production migration ops playbook: git-mode-first →
  Flyway/validate → `POST /admin/migrate` → verify → flip `default-source` to `db`,
  with the partial-failure recovery and rollback paths (the reusable, generic
  form for QA/prod and another organization)
- `references/teamcity/` — raw TeamCity Kotlin DSL snippets relevant to the current OKD deployment flow
- `references/platform/okd-platform-patterns.md` — summarized conventions from `service-deployment`, `service-config`, `octopus-dms-ui`, and `octopus-api-gateway`

## Scope

The goal is not only to deploy a demo UI on a test OKD environment, but also to define:

- a local developer setup with a working database on a laptop
- a repeatable path for QA and production
- a reusable approach for a similar setup in another organization
