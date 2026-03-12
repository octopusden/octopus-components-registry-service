# PRD: Components Registry Service — Migration to Database + Web UI

## Status
**Draft** | Author: Team | Date: 2026-03-08

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

### Phase 1: Foundation
- PostgreSQL schema + Flyway migrations
- JPA entities + Spring Data repositories
- Integration tests with Testcontainers

### Phase 2: Security
- Keycloak integration (octopus-security-common)
- Role-based access: READER / EDITOR / ADMIN
- @PreAuthorize on endpoints

### Phase 3: Component-Source Routing
- `DatabaseComponentRegistryResolver` implementation
- `ComponentRoutingResolver` — routes per component based on `component_source` table
- No global mode flag — routing is always active once deployed
- Validation: DB results match Git results (per-component import step)

### Phase 4: Data Import
- Admin endpoint: Groovy/Kotlin DSL → DB
- Validation against existing test suite (250+ test .groovy files)

### Phase 5: Write API + Audit
- REST API v4 (CRUD for components, versions)
- Audit log with JSONB diff
- Bean Validation (Jakarta)

### Phase 6: Web UI
- React 19 + Vite + shadcn/ui + TailwindCSS
- Components list, editor (multi-tab), audit log viewer
- Keycloak JS adapter for authentication

### Phase 7: Cutover
- All components migrated to `source=db` in `component_source` table
- Remove Git resolver code, drop `component_source` table
- Remove JGit dependency
- Decommission Git-based DSL repository

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
