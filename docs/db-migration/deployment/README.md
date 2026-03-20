# Deployment Research Workspace

This directory collects the deployment-related inputs and working documents for onboarding
`components-registry-ui` to OKD and for defining a local developer setup with a working database.

## Files

- `claude-subagent-brief.md` — detailed task for Claude or a subagent to research and design the deployment path
- `references/teamcity/` — raw TeamCity Kotlin DSL snippets relevant to the current F1 OKD deployment flow
- `references/platform/okd-platform-patterns.md` — summarized conventions from `service-deployment`, `service-config`, `octopus-dms-ui`, and `octopus-api-gateway`

## Scope

The goal is not only to deploy a demo UI on a test OKD environment, but also to define:

- a local developer setup with a working database on a laptop
- a repeatable path for QA and production
- a reusable approach for a similar setup in another organization
