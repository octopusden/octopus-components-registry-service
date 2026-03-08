# ADR-005: Audit Log — Replacing Git History

## Status
Accepted

## Context

Currently, the audit trail is Git commit history: who committed, when, and what changed (via `git diff`). Migrating to a database requires an equivalent or better audit mechanism.

Requirements:
- Track every CREATE, UPDATE, DELETE operation
- Record: who (Keycloak user), when, what entity, old value, new value
- Support JSON diff view in UI
- Correlate related changes (e.g., updating component + its versions in one transaction)
- Queryable: filter by user, entity, date range

## Considered Options

### Option A: Custom audit_log table + Domain Events (DMS pattern)
- Dedicated `audit_log` table with JSONB `old_value` / `new_value` columns
- `@TransactionalEventListener` publishes events after commit
- Correlation ID groups related changes
- Custom AOP or service-layer interceptors to capture before/after state

### Option B: Hibernate Envers
- Automatic versioning of @Audited entities
- Shadow tables (`_AUD`) with revision tracking
- Built-in query API for historical data
- Less flexible JSON diff, more rigid schema

### Option C: PostgreSQL triggers
- Database-level triggers on INSERT/UPDATE/DELETE
- Captures all changes regardless of application code path
- Harder to include "who changed" (need to pass user context to DB)
- Harder to maintain and test

## Decision

**Option A: Custom audit_log table + Domain Events.**

### Schema
```sql
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    entity_type     VARCHAR(100) NOT NULL,
    entity_id       UUID NOT NULL,
    action          VARCHAR(20) NOT NULL,    -- CREATE, UPDATE, DELETE
    changed_by      VARCHAR(255) NOT NULL,   -- Keycloak user
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    old_value       JSONB,
    new_value       JSONB,
    change_diff     JSONB,                   -- computed diff of changed fields
    ip_address      INET,
    user_agent      TEXT,
    correlation_id  VARCHAR(100)             -- groups related changes
);
```

### Mechanism
1. Service method captures entity state before modification
2. Performs the modification
3. Publishes domain event with old/new state
4. `@TransactionalEventListener` writes to `audit_log` within same transaction
5. Correlation ID assigned per API request (MDC / RequestContext)

### Comparison with Git history

| Capability | Git (was) | audit_log (will be) |
|-----------|-----------|-------------------|
| Who changed | Committer name/email | Keycloak user ID (verified) |
| When | Commit timestamp | `changed_at` with timezone |
| What changed | `git diff` (file-level) | Per-field JSON diff |
| Revert | `git revert` | Restore from `old_value` (future) |
| Query/filter | `git log --author` (limited) | SQL: by user, entity, date, action |
| Correlation | Single commit | `correlation_id` groups related changes |

## Consequences

### Positive
- More granular than Git (per-field vs per-file)
- Queryable via SQL, easy to expose in UI
- Follows DMS domain event pattern
- Supports future webhook/notification integration

### Negative
- Storage growth over time → need retention policy or archival
- Custom implementation effort (vs Envers auto-magic)
- Must ensure all write paths go through audited service methods

### Risks
- Missed audit entries if writes bypass service layer → enforce via architecture (no direct repository writes from controllers)

## References
- [DMS Event pattern](https://github.com/octopusden/octopus-dms-service) — `server/.../event/`
- [PostgreSQL JSONB operators](https://www.postgresql.org/docs/current/functions-json.html)
