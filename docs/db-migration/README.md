# Components Registry: Git → DB Migration — Architecture Documentation

## Document Hierarchy

```
PRD (why?) ──→ FS (what?) ──→ TDD (how?)
               NFS (how well?)─┘      ↑
                              ADRs (why this way?) ──┘
```

### Level 1 — Why & What (Stakeholder-facing)

| Document | Description | Audience |
|----------|-------------|----------|
| [prd.md](prd.md) | Product Requirements Document — business goals, user stories, scope, milestones, risks | PO, managers, stakeholders |

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
| [ADR-001](adr/001-storage-postgresql.md) | PostgreSQL as storage engine (vs MySQL, MongoDB) |
| [ADR-002](adr/002-backend-language.md) | Kotlin vs Java 21 for new code (Proposed) |
| [ADR-003](adr/003-ui-stack-react19.md) | React 19 + Vite + shadcn/ui for the web UI |
| [ADR-004](adr/004-auth-keycloak.md) | octopus-security-common + Keycloak integration |
| [ADR-005](adr/005-audit-log.md) | Custom audit_log + Domain Events (vs Hibernate Envers) |
| [ADR-006](adr/006-api-versioning-v4.md) | New API v4 for CRUD; v1/v2/v3 unchanged |
| [ADR-007](adr/007-dual-read-migration.md) | Dual-read migration with feature flag |
| [ADR-008](adr/008-component-level-routing.md) | Component-level routing — canary migration |
| [ADR-009](adr/009-ui-repository-strategy.md) | UI repository strategy — monorepo vs separate repo (Proposed) |

### Planned (create as needed)

| Document | When to create |
|----------|----------------|
| `migration-runbook.md` | Before production migration — step-by-step ops playbook |
| `api-changelog.md` | On API v4 release — changelog for consumers |
| `diagrams/erd.md` | At implementation start — Mermaid ERD |
| `diagrams/architecture.md` | At implementation start — C4 / deployment diagram |

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
├── adr/                     ← Architecture Decision Records
│   ├── 001-storage-postgresql.md
│   ├── 002-backend-language.md
│   ├── ...
│   └── 008-component-level-routing.md
└── diagrams/                ← Architecture diagrams (planned)
```
