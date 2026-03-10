# ADR-000: Migrate from Git-based DSL to Database + Web UI

## Status
Accepted

## Context

The Components Registry Service stores all component configuration (build settings, VCS, distribution, escrow, Jira mappings, version ranges) as Groovy DSL (.groovy) and Kotlin DSL (.kts) files in a Git repository. The service clones the repository on startup, parses all DSL files into an in-memory model, and serves the data via REST API (v1/v2/v3).

This architecture was appropriate at the initial stage when the registry was small and managed by a few developers. Over time, the number of components has grown (250+), the number of consumers has increased (CI/CD pipelines, DMS, Jira integrations, build plugins), and the following problems have emerged:

1. **Not automation-friendly** — there is no API for programmatic changes. Automation scripts (mass renames, bulk property updates, computed field generation) must generate DSL code, commit to Git, and hope the syntax is correct. Simple operations like "rename groupId across 50 components" or "auto-compute Jira projectKey from component name" require custom tooling around Git and DSL parsing instead of a straightforward API call.
2. **Hard to extend** — adding a new component property requires changes to the Groovy DSL grammar, Kotlin DSL parser, in-memory model classes, and all serialization layers. There is no way to add a field without a code release. Organizations with different needs (different metadata, different required fields) cannot customize the schema without forking the codebase.
3. **Version range conflicts** — version ranges (e.g., `[1.0, 2.0)`, `[2.0, 3.0)`) are defined independently per component in DSL files with override inheritance. Overlapping ranges, gaps, and contradictory overrides are only detected at parse time. There is no tooling to visualize which version inherits which value, making it easy to introduce subtle misconfiguration. Resolving conflicting ranges in DSL syntax is error-prone.
4. **Read-only service** — configuration can only be changed by committing to the Git repository. There is no API or UI for CRUD operations. Every change requires knowledge of the DSL syntax and Git workflow.
5. **No audit trail** — the only change history is Git log, which shows file-level diffs, not field-level "who changed what". There is no way to track who changed a specific component field and when.
6. **No access control** — anyone with write access to the Git repository can modify any component. There is no role-based access (reader/editor/admin) or ownership model.
7. **Full reload on change** — any modification (even a single field) triggers a complete re-parse of all DSL files. This takes tens of seconds and blocks all reads during reload.
8. **Blast radius of a single error** — a broken DSL file in one component fails the entire registry parse. The service cannot start or refresh until the error is fixed. All 250+ components become unavailable because of one bad commit in one file. There is no isolation between components at the parsing level.
9. **No validation on write** — broken DSL syntax or invalid values are discovered only after cache reload fails (see above). The author of the broken commit may not know about the failure.
10. **Merge conflicts** — parallel edits by different teams result in Git merge conflicts that require manual resolution in DSL files.
11. **High onboarding barrier** — new users must learn Groovy DSL syntax, understand inheritance (Default.groovy, version range overrides), and follow Git commit conventions.

**Strength of the current approach:** every change goes through a Git Pull Request with mandatory code review, providing a built-in approval gate. However, this also slows down development — PRs can remain pending for days, blocking configuration changes that are often urgent (e.g., release preparation, hotfix VCS settings). The review process was designed for code, not for configuration metadata, and the overhead is disproportionate to the risk of most changes.

## Decision

Migrate the Components Registry Service from Git-based DSL storage to a **relational database (PostgreSQL) with a web UI (React)**, while preserving full backward compatibility of existing REST APIs (v1/v2/v3).

The migration is structured in phases:

| Phase | Scope |
|-------|-------|
| **Phase 1** | DB schema, JPA entities, Flyway migrations, data import from DSL |
| **Phase 2** | Dual-read mode — feature flag switches reads between Git and DB resolvers; validation of data parity |
| **Phase 3** | Component-level routing — gradual canary migration, per-component source (Git or DB) |
| **Phase 4** | CRUD API (v4), audit log, authorization (Keycloak), web UI |
| **Phase 5** | Full cutover to DB, decommission Git-based resolver, remove DSL dependency |

Key architectural principles:
- **Backward compatibility first** — all 34 existing REST endpoints (v1/v2/v3) must return identical responses from DB as they did from Git
- **Gradual migration** — dual-read and component-level routing allow controlled rollout with rollback capability
- **Zero downtime** — no flag day; Git and DB coexist during migration
- **Separation of concerns** — new CRUD API (v4) is independent from legacy read API (v1/v2/v3)

## Considered Alternatives

### Alternative A: Improve Git-based workflow (enhanced DSL + validation)
Add pre-commit hooks for DSL validation, a CLI tool for editing, and structured Git log parsing for audit.

- **Pros**: No infrastructure changes, no migration risk
- **Cons**: Does not solve concurrent editing, access control, CRUD API, or UI problems. Validation hooks are fragile. Audit remains file-level, not field-level. Fundamentally limited by Git as a data store.

### Alternative B: Configuration-as-Code platform (e.g., Backstage, internal portal)
Adopt an existing configuration management platform and model components as entities.

- **Pros**: Ready-made UI, plugin ecosystem
- **Cons**: Heavy dependency on external platform. Custom DSL model (version ranges, escrow, build tools) does not fit generic entity schemas. Migration effort comparable to building our own. Loss of control over data model evolution.

### Alternative C: CRUD API on top of Git (GitOps)
Keep Git as storage but add an API layer that reads/writes DSL files programmatically, plus a UI on top.

- **Pros**: No database infrastructure needed
- **Cons**: Git is not designed for concurrent transactional writes. File-level locking needed. Audit still based on Git commits. Performance degrades with repo growth. Complex DSL generation from API input. All parsing/validation complexity remains.

## Consequences

### Positive
- **Automation-friendly CRUD API** (v4) — scripts can create, update, rename components, bulk-modify properties, and compute derived fields via standard REST calls. No DSL generation or Git manipulation needed.
- **Extensible schema** — new properties can be added via JSONB metadata (Tier 3) without code changes or migrations. Promotion path to dedicated columns (Tier 1) when a field becomes stable. Per-deployment field configuration controls visibility without forking. See [ADR-010](010-schema-extensibility.md), [ADR-011](011-field-configuration.md).
- **Version range integrity** — DB constraints enforce non-overlapping ranges. UI visualizes inheritance (component default vs. version override per field). API validates ranges on write, preventing gaps and conflicts that were silently accepted by DSL parsing.
- Users can manage component configuration through a web UI without knowing DSL syntax or Git workflows
- Field-level audit trail with JSON diffs (who, when, old value, new value)
- Role-based access control (reader, editor, admin, component owner) via Keycloak
- Per-field updates instead of full file rewrites; no merge conflicts
- Instant validation on write (DB constraints + application-level validation)
- Query performance: indexed lookups instead of in-memory linear scan
- Foundation for future features: approval workflows, notifications, component profiles

### Negative
- **Loss of PR-based review process** — in the current Git workflow every change goes through a Pull Request with mandatory approval, providing a review gate before any configuration reaches the service. The DB+UI model allows direct edits without review. This is partially mitigated by audit log, role-based access control, and component ownership, but there is no built-in approval workflow. A future enhancement (approval workflow / change requests) is documented as a non-goal for initial implementation but may be added later.
- New infrastructure dependency: PostgreSQL instance requires provisioning, monitoring, backups
- Service transitions from stateless (Git clone + in-memory) to stateful (DB connection pool)
- Migration effort across multiple phases; parallel maintenance of Git and DB resolvers during transition
- New UI requires separate frontend development and deployment
- Team needs to maintain JPA entity model alongside existing in-memory DSL model until Git resolver is decommissioned

### Risks
- **Data parity** — DB must return identical results to Git resolver for all 34 endpoints. Mitigated by dual-read validation with deep comparison.
- **Migration data loss** — complex DSL constructs (escrow, nested inheritance, tools) may not map cleanly to relational schema. Mitigated by import validation and per-component dry-run.
- **Rollback complexity** — once components are edited via CRUD API (new data that never existed in Git), rollback to Git-only mode loses those changes. Mitigated by component-level routing (Phase 3) allowing selective rollback.
- **DB as single point of failure** — mitigated by PostgreSQL replication, automated backups, and connection pool resilience.

## References
- [PRD: Components Registry Service — Migration to Database + Web UI](../prd.md)
- [ADR-001: PostgreSQL as Primary Storage](001-storage-postgresql.md)
- [ADR-007: Dual-read migration with feature flag](007-dual-read-migration.md)
- [ADR-008: Component-level routing — canary migration](008-component-level-routing.md)
