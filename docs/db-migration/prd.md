# PRD: Components Registry Service — Migration to Database + Web UI

## Status
**Living document** | Author: Team | Last updated: 2026-04-29 (was Draft 2026-03-08)

---

## 1. Problem Statement

The Components Registry Service stores all component configuration in a Git repository as Groovy DSL (.groovy) and Kotlin DSL (.kts) files. The service clones the Git repo on startup, parses DSL into in-memory structures, and serves data via REST API.

**Current limitations:**
- **No CRUD operations** — configuration is read-only from the service perspective
- **Editing via git commit/push only** — no UI, no API for modifications
- **Audit only via Git history** — no granular tracking of who changed what
- **No access control** — anyone with Git repo access can modify anything
- **Full cache reload** — any change requires complete re-parse of all DSL files
- **No concurrent editing** — Git merge conflicts for parallel changes
- **No validation on write** — broken DSL discovered only after cache reload failure

## 2. Goals & Success Criteria

| # | Goal | Success Criteria |
|---|------|-----------------|
| G1 | **Preserve existing API** | All 34 REST endpoints (v1/v2/v3) return identical responses after migration |
| G2 | **Web UI for configuration management** | Users can create, view, edit, delete components and versions through browser |
| G3 | **Audit trail** | Every change is recorded: who, when, what changed (old→new values) |
| G4 | **Access control** | Role-based permissions via Keycloak: reader, editor, admin, component owner |
| G5 | **Data migration** | 100% of existing Groovy/Kotlin DSL data imported into database without loss |
| G6 | **Zero-downtime migration** | Per-component source routing allows gradual cutover; rollback safe for unedited components |

## 3. Non-Goals

- Rewriting existing Feign client contract (`ComponentsRegistryServiceClient`)
- Changing existing DTO classes (ComponentV1, V2, V3, etc.)
- Supporting real-time collaborative editing (Google Docs-style)
- Building a mobile UI
- Migrating other octopus services to new storage
- Approval workflow / PR-like process for configuration changes (may be added later)

## 4. User Stories

### Configuration Management (UI)
- **US-1**: As a **component owner**, I want to edit my component's configuration (build, escrow, VCS, distribution) through a web interface so I don't need to commit to Git.
- **US-2**: As a **team lead**, I want to see who changed what and when in any component's configuration, so I can track accountability.
- **US-3**: As a **release manager**, I want to add a new version range to a component with overridden build settings, so I can configure version-specific builds.
- **US-4**: As an **admin**, I want to import existing DSL configuration from Git into the database, so we can migrate without data loss.
- **US-5**: As a **developer**, I want to search and filter components by system, product type, owner, or archived status, so I can quickly find what I need.

### API Consumers (Backward Compatibility)
- **US-6**: As a **CI/CD system** (octopus-dms-service, octopus-jira-utils, etc.), I want the existing REST API to continue working without changes, so my integrations are not broken.
- **US-7**: As an **automation tool** (octopus-build-integration-gradle-plugin), I want `findByArtifacts` and `getSupportedGroupIds` to return the same results from the database as they did from Git.

### Security
- **US-8**: As a **security officer**, I want only authorized users with REGISTRY_EDITOR role to modify configurations, so unauthorized changes are prevented.
- **US-9**: As a **component owner**, I want only myself and admins to be able to edit my component, so changes require proper authorization.

### Audit
- **US-10**: As an **auditor**, I want to view the full change history of any component, with JSON diff of old/new values, so I can investigate incidents.
- **US-11**: As a **team lead**, I want to see a global feed of recent changes across all components, filtered by user or date.

## 5. Stakeholders

| Role | Responsibility |
|------|---------------|
| Product Owner | Prioritizes features, accepts deliverables |
| Tech Lead | Architecture decisions, code reviews |
| Backend Developers | Database, API v4, auth integration, data migration |
| Frontend Developers | Web UI (React) |
| DevOps / SRE | PostgreSQL provisioning, Keycloak configuration, deployment |
| QA | Testing API compatibility, UI, data migration validation |
| API Consumers (7+ octopus services) | Validate backward compatibility |

## 6. Scope & Milestones

### Phase 1: Foundation ✅ Done
- PostgreSQL schema + Flyway migrations (V1–V3 baseline; V4–V5 added in Phase 6)
- JPA entities + Spring Data repositories
- CRUD API (v4) for components and versions
- Audit log with JSONB diff
- Bean Validation (Jakarta)
- Integration tests with Testcontainers
- (Keycloak integration moved to Phase 6 — landed later than originally planned, see PR #150)

### Phase 2: Web UI ✅ Done — extracted to a separate repository

The original plan was a monorepo with embedded UI (per [ADR-009](adr/009-ui-repository-strategy.md)). That decision was reversed in April 2026: the UI now lives in `octopus-components-management-portal` as a Spring Cloud Gateway BFF + React 19 SPA. See [ADR-012](adr/012-portal-architecture.md) for the rationale and PR #147 for the extraction commit.

Functional scope of the UI itself remains the same:
- Components list (filter, paginate, search, owner autocomplete coming in Portal P1)
- Component editor — six tabs (General/Build/VCS/Distribution/Jira/Escrow), inline per-version field overrides
- Audit log viewer (per-entity history coming in Portal P1)
- Admin: field config + component defaults + Migration panel
- Keycloak login via OIDC authorization-code flow handled at the gateway (BFF), not in the SPA

### Phase 3: Component-Source Routing ✅ Done
- `DatabaseComponentRegistryResolver` implementation
- `ComponentRoutingResolver` — routes per component based on `component_source` table
- No global mode flag — routing is always active once deployed
- Existing v1/v2/v3 API serves both Git and DB sources transparently

### Phase 4: Data Import ✅ Done
- Per-component import from Groovy/Kotlin DSL → DB (`POST /admin/migrate-component/{name}`)
- Bulk synchronous import (`POST /admin/migrate-components`)
- Async bulk import (`POST /admin/migrate` → 202/409 + `MigrationJobResponse` + polling at `GET /admin/migrate/job`) — added in PR #156. Contract: [`MIG-027`](requirements-migration.md).
- Defaults import (`POST /admin/migrate-defaults`)
- Validation: deep-compare Git vs DB resolver output per component (`POST /admin/validate-migration/{name}`)
- All 933 production components migrated to `source=db`

### Phase 5: Cutover 🚧 In progress (proposed)

933/933 components are routed `source=db`. Cutover is **not yet complete** in the formal sense:

- Git resolver code, JGit dependency, and `component_source` table all still present.
- Strategy and trigger conditions for removal: see [ADR-013](adr/013-cutover-strategy.md) (Proposed, lands in Step A5 of `docs/v3-actualization`).

### Phase 6: Operational Hardening ✅ Done

Work that was originally rolled into Phase 1 but landed later, plus net-new features that became needed once the UI was a separate deploy unit:

- **Keycloak auth + `@PreAuthorize`** on v4 writes/admin/audit (PR #150, [ADR-004](adr/004-auth-keycloak.md) Implemented 2026-04-28).
- **Audit `changedBy` wiring** through `SecurityService` with a `"system"` fallback for background jobs.
- **`/auth/me`** endpoint for the SPA's identity display (`SYS-034`).
- **`/info`** endpoint, anonymous on both CRS and Portal sides, for the footer build label (`SYS-033`, PR #154).
- **`/admin/migrate-history`** to backfill git commit history into `audit_log` with `source='git_history'` (`MIG-026`, PR #151 + #155, V5 schema).
- **V4 schema** (`V4__artifact_ids_version_level.sql`) — polymorphic owner XOR for `component_artifact_ids`.
- **V5 schema** (`V5__audit_source_and_history_state.sql`) — `audit_log.source` column + `git_history_import_state` table.
- **`ft-db` profile** — H2 + auto-migrate for downstream FT testing (`SYS-026`, `SYS-027`, PR #148).
- **UI extraction to Portal** (PR #147, [ADR-012](adr/012-portal-architecture.md) Accepted).

Open follow-ups under this phase: persisted async migration job state (`MIG-028`), OpenAPI v4 spec generation (CRS `TD-004` + Portal `TD-002`), Portal TLS Ingress migration (Portal `TD-004`).

## 7. Risks

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Data loss during DSL→DB import | High | Per-component validation (deep-compare Git vs DB), 250+ test files, rollback for unedited components |
| Breaking existing API consumers | Critical | No changes to v1/v2/v3 endpoints or DTOs; integration tests |
| Groovy DSL edge cases | Medium | Comprehensive test coverage with production DSL files |
| Performance degradation | Medium | PostgreSQL indexes + Caffeine cache for hot paths |
| Downtime during cutover | Medium | Per-component source routing enables gradual rollout |

## 8. Open Questions

1. **Config versioning** — Do we need rollback/revert capability (like `git revert`)? If yes, add snapshot table.
2. **Multi-tenancy** — One DB instance per environment or shared?
3. **Bulk operations** — Import/export in JSON/YAML format beyond initial migration?
4. **Notifications** — Webhook/Kafka events on configuration changes?
5. **Approval workflow** — PR-like review process for changes? (future scope)
6. **Runtime access logs** — Need to verify which v1/v3 endpoints are actually called in production.
