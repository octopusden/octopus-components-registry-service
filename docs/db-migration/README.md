# Components Registry: Git → DB Migration — Architecture Documentation

## Glossary

| Abbreviation | Full name | Purpose |
|---|---|---|
| **PRD** | Product Requirements Document | Why we're doing this, goals, user stories, project phases |
| **FS** | Functional Specification | What the system does — CRUD operations, search, audit, authorization rules |
| **NFS** | Non-Functional Specification | How well — performance, availability, security, observability |
| **TDD** | Technical Design Document | How — architecture, DB schema, API contracts, code examples |
| **ADR** | Architecture Decision Record | Why this way — rationale for each technical decision |

## Document Hierarchy

```
PRD (why?) ──→ FS (what?) ──→ TDD (how?)
               NFS (how well?)─┘      ↑
                              ADRs (why this way?) ──┘
```

### Level 1 — Why & What (Stakeholder-facing)

| Document | Description | Audience |
|----------|-------------|----------|
| [prd.md](prd.md) | Product Requirements Document — business goals, user stories, **project phases**, milestones, risks | PO, managers, stakeholders |

### Level 2 — Specifications (What exactly)

| Document | Description | Audience |
|----------|-------------|----------|
| [functional-spec.md](functional-spec.md) | Functional Specification — CRUD operations, search, audit, import, authorization rules, error codes | Developers, QA, analysts |
| [non-functional-spec.md](non-functional-spec.md) | Non-Functional Specification — performance, availability, security, observability, scalability | DevOps, architects, QA |

### Level 3 — Technical Design (How)

| Document | Description | Audience |
|----------|-------------|----------|
| [technical-design.md](technical-design.md) | Technical Design Document — architecture, DB schema, JPA entities, API v4, security, migration, testing strategy | Developers (primary working document) |

### Level 4 — Decision Log (Why this way)

| ADR | Decision |
|-----|----------|
| [ADR-000](adr/000-migrate-git-to-db-ui.md) | Migrate from Git-based DSL to Database + Web UI (overarching decision) |
| [ADR-001](adr/001-storage-postgresql.md) | PostgreSQL as storage engine (vs MySQL, MongoDB) |
| [ADR-002](adr/002-backend-language.md) | Kotlin vs Java 21 for new code (Proposed) |
| [ADR-003](adr/003-ui-stack-react19.md) | React 19 + Vite + shadcn/ui for the web UI |
| [ADR-004](adr/004-auth-keycloak.md) | octopus-security-common + Keycloak integration (Implemented 2026‑04‑28, PR #150) |
| [ADR-005](adr/005-audit-log.md) | Custom audit_log + Domain Events (vs Hibernate Envers) |
| [ADR-006](adr/006-api-versioning-v4.md) | New API v4 for CRUD; v1/v2/v3 unchanged |
| [ADR-007](adr/007-dual-read-migration.md) | Component-Source Routing — per-component migration strategy |
| [ADR-008](adr/008-component-level-routing.md) | ~~Component-level routing~~ — Superseded by ADR-007 |
| [ADR-009](adr/009-ui-repository-strategy.md) | ~~UI repository strategy — monorepo vs separate repo~~ — Superseded by ADR-012 |
| [ADR-010](adr/010-schema-extensibility.md) | Hybrid schema extensibility — Columns + JSONB (Proposed) |
| [ADR-011](adr/011-field-configuration.md) | Configurable field visibility, defaults, multi-org support (Proposed) |
| [ADR-012](adr/012-portal-architecture.md) | UI extracted to `octopus-components-management-portal` as a Spring Cloud Gateway BFF (Accepted 2026‑04‑14, PR #147) |

### Action Items

| # | Item | Status | Notes |
|---|------|--------|-------|
| AI-1 | **API versioning strategy**: analyze current v2/v3 client base (access logs, Feign client consumers), decide whether to extend v3 or create v4 for read/write | Open | v2 is the primary API (7+ consumers), v3 is underused (3 consumers). Study actual endpoint usage from server logs before deciding. |
| AI-2 | **Simplify storage modes**: replace 4 modes (`git\|db\|routing\|dual`) with single `component_source`-based routing | **Done** | Implemented in ADR-007. No global mode flag, single `ComponentRoutingResolver` always active. |
| AI-3 | **Rollback semantics**: reframe rollback as one-way cutover after first DB write per component | **Done** | Implemented in ADR-007 §Rollback Semantics. Updated NFS §5.6, §5.9. |

### Planned (create as needed)

| Document | When to create |
|----------|----------------|
| `migration-runbook.md` | Before production migration — step-by-step ops playbook |
| `api-changelog.md` | On API v4 release — changelog for consumers |
| `diagrams/erd.md` | At implementation start — Mermaid ERD |
| `diagrams/architecture.md` | At implementation start — C4 / deployment diagram |
| `deployment/` | During UI onboarding — OKD deployment research inputs, platform references, and rollout briefs |

## How to Read

**New to the project?** Read in order:
```
PRD → FS → NFS → TDD → ADRs (by interest)
```

**Developer starting implementation?** Focus on:
```
TDD (primary) → FS (for behavior details) → ADRs (for context on decisions)
```

**Reviewer / Architect?** Start with:
```
PRD (scope) → ADRs (decisions) → NFS (quality gates)
```

## How to Update

| Trigger | Action |
|---------|--------|
| New business requirement | Update PRD → FS → TDD |
| New technical decision | Create `adr/NNN-*.md` → update TDD |
| Changed NFR (performance, security) | Update NFS → possibly TDD |
| Bug in specification | Fix FS or TDD directly |

## ADR Lifecycle

Each ADR has a status:
- **Proposed** — under discussion, decision not yet finalized
- **Accepted** — decision made, follow it
- **Deprecated** — no longer relevant, not replaced
- **Superseded by ADR-NNN** — replaced by a newer decision

When a decision changes, don't delete the old ADR — mark it as `Superseded` and create a new one. This preserves the decision history.

## Directory Structure

```
docs/db-migration/
├── README.md                ← this file
├── prd.md                   ← Product Requirements
├── functional-spec.md       ← Functional Specification
├── non-functional-spec.md   ← Non-Functional Specification
├── technical-design.md      ← Technical Design Document
├── deployment/              ← OKD deployment research workspace
├── adr/                     ← Architecture Decision Records
│   ├── 001-storage-postgresql.md
│   ├── 002-backend-language.md
│   ├── ...
│   └── 008-component-level-routing.md
└── diagrams/                ← Architecture diagrams (planned)
```
