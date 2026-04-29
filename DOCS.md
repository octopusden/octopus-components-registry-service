# Documentation Map

The Components Registry domain is split across two repositories. This file is the wayfinding index — it tells you which doc lives where and which repo is the source of truth for each concern. The principle is **content in one place + link, not duplication**: when you see something cross-referenced, follow the link rather than copying.

## Repos

| Repo | Role |
|---|---|
| **`octopus-components-registry-service`** (this repo, branch `v3`) | Backend service — REST API, data model, migrations, resolvers, audit. Owns the data and business logic. |
| **[`octopus-components-management-portal`](https://github.com/octopusden/octopus-components-management-portal)** (branch `develop`) | Browser-facing BFF + React SPA. Spring Cloud Gateway + OAuth2 Login. Owns the browser experience. |

## What lives where

### This repo (CRS) owns

| Concern | Doc | What it covers |
|---|---|---|
| **Product requirements** | [`docs/db-migration/prd.md`](docs/db-migration/prd.md) | Goals, user stories, phases, milestones. |
| **Functional spec** | [`docs/db-migration/functional-spec.md`](docs/db-migration/functional-spec.md) | What the API does — CRUD, search, audit, import, info, auth. |
| **Non-functional spec** | [`docs/db-migration/non-functional-spec.md`](docs/db-migration/non-functional-spec.md) | Performance budgets, availability, observability, async-job SLAs. |
| **Technical design** | [`docs/db-migration/technical-design.md`](docs/db-migration/technical-design.md) | Architecture, DB schema, JPA entities, API contracts, security. |
| **Architecture decisions** | [`docs/db-migration/adr/`](docs/db-migration/adr/) | All ADRs — backend, data, security, including [ADR-012 Portal architecture](docs/db-migration/adr/012-portal-architecture.md) (canonical for the boundary). |
| **Numbered requirements** | [`docs/db-migration/requirements-common.md`](docs/db-migration/requirements-common.md) (`SYS-NNN`) and [`docs/db-migration/requirements-migration.md`](docs/db-migration/requirements-migration.md) (`MIG-NNN`) | Acceptance criteria + test pointers. |
| **Implementation status** | [`docs/db-migration/implementation-progress.md`](docs/db-migration/implementation-progress.md) | What's shipped, by phase. |
| **Backlog index** | [`docs/db-migration/todo.md`](docs/db-migration/todo.md) | Recently shipped, deferred items, tech-debt index, future ideas. |
| **Tech-debt entries** | [`docs/db-migration/tech-debt/`](docs/db-migration/tech-debt/) (`TD-NNN`) | Backend tech-debt: Flyway rollout, OpenAPI spec generation, etc. |
| **Local dev / deployment** | [`docs/db-migration/deployment/`](docs/db-migration/deployment/) | Dev runbooks, OKD deploy patterns, local Postgres. |
| **Agent / build commands** | [`AGENTS.md`](AGENTS.md) | Build, test, quality gates. Read before touching code. |

### Portal repo owns

Read these in [`octopus-components-management-portal`](https://github.com/octopusden/octopus-components-management-portal):

| Concern | Doc (in Portal) | What it covers |
|---|---|---|
| **Repo overview + local dev loop** | `README.md` | Vite proxy, npm/Gradle build commands. |
| **Portal architecture (BFF, CSRF, SPA fallback)** | `docs/architecture.md` | Implementation details with file pointers. Canonical decision is in CRS [ADR-012](docs/db-migration/adr/012-portal-architecture.md); the Portal doc is the implementation guide. |
| **Portal-side ADR summary** | `docs/adr/001-spring-cloud-gateway-bff.md` | Short Portal-side summary that links back to canonical CRS ADR-012. Not a copy. |
| **Frontend feature docs** | `docs/features/admin-migration.md`, `admin-mode.md`, `app-footer.md` | UX flows, hooks, state machines, role gating layers (UX vs server). |
| **OKD onboarding checklist** | `docs/onboarding/components-management-portal.md` | Vault, Spring Cloud Config, OKD secrets, TeamCity wiring for Portal pod. |
| **Frontend tech-debt** | `docs/tech-debt/TD-001-004` | Playwright Keycloak fixture, OpenAPI types, persisted session store, TLS Ingress migration. |

## Cross-repo concerns (read both)

These are concerns whose state is split deliberately — the link goes both ways and you may need both pages.

| Concern | CRS side | Portal side |
|---|---|---|
| **Architecture / boundary contract** | [ADR-012 (canonical)](docs/db-migration/adr/012-portal-architecture.md) | `docs/architecture.md` (impl guide) + `docs/adr/001-...` (summary+link) |
| **OpenAPI spec generation** | [TD-003](docs/db-migration/tech-debt/003-openapi-v4-spec-generation.md) — backend-side wiring | `docs/tech-debt/TD-002-openapi-types.md` — frontend-side consumption |
| **Async migration UX** | [MIG-027 (contract)](docs/db-migration/requirements-migration.md), [MIG-028 (persisted state, open)](docs/db-migration/requirements-migration.md) | `docs/features/admin-migration.md` — SPA hooks, polling, fallback |
| **Auth model** | [ADR-004](docs/db-migration/adr/004-auth-keycloak.md) — role/permission matrix on resource server | `docs/architecture.md` §"BFF pattern" + `SecurityConfig.kt` |
| **Build info / footer** | [SYS-033](docs/db-migration/requirements-common.md) (`/rest/api/4/info`) | `docs/features/app-footer.md` — `/portal/info` + `/rest/api/4/info` consumed by `useInfo.ts` |
| **Identity** | [SYS-034](docs/db-migration/requirements-common.md) (`/auth/me`) | `frontend/src/hooks/useCurrentUser.ts` |
| **Cutover** | [ADR-013 — Cutover strategy](docs/db-migration/adr/013-cutover-strategy.md) (Proposed) | Affects Portal indirectly when Git resolver / `migrate-history` retire — see ADR-013 §Stage 5C |

## Authoring rules

When you write a new doc, pick **one** repo as the owner using these rules:

1. **Backend behavior, data, contracts** → CRS.
2. **Browser experience, UI feature, BFF wiring** → Portal.
3. **Cross-cutting concern** → write in the repo that has more of the implementation; link from the other repo.
4. **Never duplicate content.** If you find yourself copy-pasting between repos, replace one side with a link.
5. **Cross-repo links should target a stable ref** (a release tag or a merge commit SHA), not `blob/<branch>/...`. Branches move; permalinks don't rot.

## How to update this map

Add a row when you create a new top-level doc. Move/reword a row when ownership shifts (e.g. a section moves from CRS to Portal). Don't list every individual ADR or requirement — point at the index.

Mirror file: [`octopus-components-management-portal/DOCS.md`](https://github.com/octopusden/octopus-components-management-portal/blob/develop/DOCS.md). Both files describe the same map from their own perspective; either can be the entry point.
