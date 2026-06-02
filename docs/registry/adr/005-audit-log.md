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
3. Publishes domain event with old/new state via `ApplicationEventPublisher`
4. `@TransactionalEventListener(phase = BEFORE_COMMIT)` writes to `audit_log` **within the same transaction** — guaranteeing that audit entry and data change either both commit or both roll back. If audit write fails, the entire transaction (including the data change) is rolled back.
5. Correlation ID assigned per API request (MDC / RequestContext)

> **Note:** Using `BEFORE_COMMIT` phase (not the default `AFTER_COMMIT`) is a deliberate choice to ensure atomicity of data + audit writes. The trade-off is slightly longer transaction duration.

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

## Refinements (post-acceptance)

These tighten the original mechanism; the table + domain-event design is unchanged.

### Action vocabulary
`action` is `CREATE | UPDATE | DELETE | RENAME | MIGRATED`. `MIGRATED`
(`AuditLogEntity.ACTION_MIGRATED`) is written by the git-history backfill for a
component's **first appearance** in history, instead of `CREATE`. It marks
migration-origin baseline rows so the audit views can hide them by default — one
`MIGRATED` row per component is noise, not an operational event. Runtime API
creates stay `CREATE`. (SYS-049)

### Migration noise hidden by default
Both read endpoints (`GET /audit/recent`, `GET /audit/{entityType}/{entityId}`)
exclude `action = MIGRATED` rows unless the caller passes `includeMigrated=true`,
or pins them with an explicit `action=MIGRATED` filter. The Portal exposes this as
a "Show migration" toggle (default off). (SYS-049)

### No-op suppression
A write that changes nothing must not leave an audit row. `AuditEventListener`
drops an event whose `oldValue` and `newValue` are both present but whose computed
`change_diff` is empty — `AuditDiff.compute` returns `null` for an empty diff, and
the guard condition is `oldValue != null && newValue != null && changeDiff == null`.
This is centralised in the listener, so it covers every publisher. `CREATE` (null `oldValue`) and `DELETE` legitimately have a null diff
and are always kept. (SYS-048)

### Coverage: field overrides
Field-override (version-ranged attribute override) create/update/delete publish a
Component `UPDATE` event keyed by the overridden attribute, so version-range edits
appear in the component history alongside top-level attribute edits — closing the
"all write paths go through audited service methods" risk for these paths. (SYS-050)

## References
- [DMS Event pattern](https://github.com/octopusden/octopus-dms-service) — `server/.../event/`
- [PostgreSQL JSONB operators](https://www.postgresql.org/docs/current/functions-json.html)
