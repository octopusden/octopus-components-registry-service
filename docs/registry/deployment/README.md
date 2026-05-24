# Deployment Research Workspace

This directory collects the deployment-related inputs and working documents for onboarding
`components-registry-ui` to OKD and for defining a local developer setup with a working database.

## Files

- `keycloak-setup.md` — operator-facing setup steps for Keycloak realm-roles
  required by CRS authorization (manual Admin-Console actions not covered by
  source patches)
- `qa-post-deploy-migration.md` — what the `[3.1] Migrate components on QA DEV
  [AUTO]` TC build does, when it skips, and what TC parameters it expects
- `references/teamcity/` — raw TeamCity Kotlin DSL snippets relevant to the current OKD deployment flow
- `references/platform/okd-platform-patterns.md` — summarized conventions from `service-deployment`, `service-config`, `octopus-dms-ui`, and `octopus-api-gateway`

## Scope

The goal is not only to deploy a demo UI on a test OKD environment, but also to define:

- a local developer setup with a working database on a laptop
- a repeatable path for QA and production
- a reusable approach for a similar setup in another organization
