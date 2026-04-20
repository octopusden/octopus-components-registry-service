# Task: Research and Define the OKD Deployment Path for `components-registry-ui`

## Purpose

Define a repeatable deployment model for `components-registry-ui` that works in four stages:

1. local developer setup on a laptop with a working database
2. demo deployment on a test OKD environment
3. QA deployment
4. production deployment
5. reuse in another organization with a similar platform structure

The task is not limited to "make it run once". The output must be usable as the basis for:

- delivery planning
- deployment documentation
- future `AGENTS.md` / `CLAUDE.md`
- a reusable Codex/Claude skill for OKD onboarding

The local setup requirement is mandatory: the team wants a working end-to-end system on a developer
machine, not only an OKD deployment story.

## Mandatory Inputs

### Architecture and product docs in this repository

Read first:

- `docs/db-migration/prd.md`
- `docs/db-migration/non-functional-spec.md`
- `docs/db-migration/technical-design.md`
- `docs/db-migration/adr/003-ui-stack-react19.md`
- `docs/db-migration/adr/004-auth-keycloak.md`
- `docs/db-migration/adr/009-ui-repository-strategy.md`

### Deployment and platform references saved in this repository

- `docs/db-migration/deployment/references/teamcity/50-deploy-to-okd-qa-auto.kt`
- `docs/db-migration/deployment/references/teamcity/idp-component-okd-deploy-template.kt`
- `docs/db-migration/deployment/references/platform/okd-platform-patterns.md`

### External/local repositories that must be studied

- `service-deployment`
- `service-config`
- `octopus-dms-ui`
- `octopus-api-gateway`

Do not invent a new deployment model before checking whether the existing F1 conventions already
solve the problem.

## Context and Constraints

### Known organizational delivery model

- build/release orchestration is done in TeamCity
- GitHub is used for source and public releases
- OKD deployment is currently standardized around TeamCity + Helm + `service-deployment`
- runtime config is currently standardized around `service-config`

### Known target state

The final application will likely live behind `api-gateway` and fit the platform security model.
However, the first version may be deployed without `api-gateway` and without full security if that
significantly speeds up the demo.

There is also a mandatory developer experience requirement:

- backend + UI + database must be runnable locally on a laptop
- the local database path should be practical for day-to-day development
- the local path should not drift too far from the target deployment model

This creates a required two-phase design:

1. **Demo topology**
   - simplest acceptable deployment for a test stand
   - may skip gateway and full Keycloak integration
2. **Target topology**
   - compatible with `api-gateway`
   - compatible with security model and Keycloak
   - suitable for QA and production

The demo path must not paint the team into a corner.

In addition, there must be a **Local developer topology**:

- works on a laptop
- includes a local database
- gives a working system for implementation and debugging
- is documented clearly enough for repeatable onboarding

## Core Questions to Answer

### Deployment model

1. Should `components-registry-ui` be:
   - a Spring Boot BFF similar to `dms-ui`
   - a standalone UI service/container
   - embedded into `components-registry-service`
2. Which of these options best fits:
   - current TeamCity template
   - current Helm chart `spring-cloud`
   - current config-server model
   - future gateway/security model
3. If the preferred option does not fit the current platform well, what is the minimal platform
   extension required?

### OKD onboarding

4. What exact files must be created or changed in:
   - this repository
   - `service-deployment`
   - `service-config`
   - `octopus-api-gateway`
   - TeamCity config repo or TeamCity project settings
5. What should `COMPONENT_NAME`, image name, Helm release, route host, and config file names be?
6. Which secrets, config values, DNS/route names, service accounts, and registry settings are
   required?

### Local developer setup

7. What is the recommended local development topology for backend + UI + database?
8. Should the local DB run via Docker Compose, Testcontainers-assisted flow, native PostgreSQL, or
   another option?
9. Which local env vars, profiles, seed data, and startup commands are required to get a working
   system on a laptop?
10. How close should the local setup remain to the target OKD/runtime model?

### Security and gateway

11. What can be safely omitted for the demo?
12. What must already be designed to remain gateway-ready and security-ready later?
13. What would be the migration path from demo deployment to target deployment behind `api-gateway`?

### Operations

14. What is the manual deployment path for a test cluster?
15. What is the future automated deployment path in TeamCity?
16. What is the rollback and smoke-check strategy for local, test, QA, and production?

## Expected Deliverables

Produce a concrete recommendation, not a generic options list.

### Deliverable 1: Deployment Decision Memo

A short decision memo that states:

- recommended deployment model
- rejected alternatives
- reasons grounded in the current platform
- explicit trade-offs for demo vs target state

### Deliverable 2: Repository Change Map

A table with:

- repository
- file(s) to add or modify
- purpose
- whether it is required for demo, target, or both

The map must include at least:

- this repository
- `service-deployment`
- `service-config`
- `octopus-api-gateway`
- TeamCity config location

### Deliverable 3: Manual Test Deployment Checklist

A step-by-step checklist for first deployment to a test OKD environment.

It must include:

- prerequisites
- image build/publish assumptions
- config prerequisites
- secrets and tokens
- Helm/oc commands or TeamCity-equivalent actions
- rollout verification
- smoke checks
- rollback notes

### Deliverable 3a: Local Developer Setup Guide

A practical local runbook for a developer laptop.

It must include:

- recommended local database approach
- startup order for backend, UI, and DB
- profiles and environment variables
- local data/bootstrap assumptions
- which parts may be stubbed or simplified for local work
- a minimal verification checklist proving the system is actually usable

### Deliverable 4: Automation Plan

A concrete path to automated deployment in TeamCity:

- build stages
- artifact/image expectations
- TeamCity build configuration changes
- usage of existing `RnDProcessesAutomation_IdpComponentOkdDeploy` template, if applicable
- any required template extensions

### Deliverable 5: Documentation Artifacts To Create

Define the intended contents for these files:

- `docs/db-migration/deployment-ui-okd.md`
- `AGENTS.md` updates related to deployment/security work
- `CLAUDE.md` deployment memory
- optional reusable skill for onboarding a service to OKD

For each file, specify:

- purpose
- intended audience
- main sections

## Quality Bar

The result is acceptable only if it is:

- usable for a developer who wants the system running locally with a database
- grounded in the real platform conventions, not hypothetical best practices
- explicit about what is manual vs automated
- explicit about what is demo-only vs production-grade
- explicit about what still requires human/platform-team action
- reusable across test, QA, prod, and a similar organization

## Investigation Order

Use this order unless blocked:

1. architecture docs in `docs/db-migration`
2. local developer experience constraints for DB + UI + backend
3. TeamCity deploy template and its assumptions
4. `service-deployment` chart and environment values
5. `service-config` hierarchy and naming conventions
6. `octopus-dms-ui` as reference application
7. `octopus-api-gateway` and security docs
8. final recommendation and gap list

## Non-Goals

Do not:

- implement the deployment yet
- rewrite platform standards without evidence
- assume gateway/security are already configured for this service
- assume nginx/static hosting is acceptable without validating it against the current Helm/TeamCity
  model

## Human Inputs That May Be Needed

Call these out explicitly if they block the recommendation:

- test OKD cluster/project availability
- expected namespace/project naming
- desired `components-registry-ui` artifact/image naming
- whether UI will be a separate service or embedded/BFF
- preferred local DB runtime if the team already has one
- Keycloak client provisioning ownership
- TeamCity project/repo where the new build configuration should live
- route/DNS ownership

## Definition of Done

The task is done only when all of the following are true:

- a recommended deployment model is selected
- a recommended local developer setup with database is selected
- the demo and target topologies are both defined
- the required repository/config changes are enumerated
- the TeamCity automation path is described concretely
- manual actions are clearly separated from git-managed changes
- the outputs are good enough to serve as the basis for `deployment-ui-okd.md`, `AGENTS.md`,
  `CLAUDE.md`, and a reusable OKD onboarding skill
