# ADR-013: Cutover Strategy — Removing the Git Resolver

## Status
**Proposed.** Decision pending; PRD Phase 5 cannot close until this lands.

## Context

[PRD §6 Phase 5](../prd.md#phase-5-cutover) describes the end-state for the Git → DB migration:

> All components migrated to `source=db` in `component_source` table. Remove Git resolver code, drop `component_source` table. Remove JGit dependency. Decommission Git-based DSL repository.

As of 2026-04-29, only the first half of that sentence is true:

- **All 933 production components are routed `source=db`** (see `GET /admin/migration-status`).
- **None of the Git-side scaffolding has been removed.** Specifically still present:
  - `GitVcsServiceImpl`, `GitTagResolver`, `HistoryEscrowLoaderFactory` (under `service/impl/`).
  - `JGit` dependency in `components-registry-service-server/build.gradle`.
  - `component_source` table and `ComponentSourceEntity` / `ComponentSourceRepository` / `ComponentRoutingResolver` (still routes per component, with all components currently mapping to `db`).
  - Configuration knobs for the legacy DSL repo URL / credentials in `application.yml` and `service-config`.

The migration-history backfill endpoint (`/admin/migrate-history`, MIG-026) and the `git_history_import_state` table also depend on the legacy DSL repo being clone-able. They are independently useful even after the resolver is removed (history is read-once and persisted into `audit_log`), so they have their own cutover question.

This ADR exists because Phase 5 is not "flip a switch"; it's a sequence of removals that need an ordering and acceptance bar.

## Decision (proposed)

Phase 5 is split into three sub-stages, each independently revertible:

### Stage 5A — Stop reading from Git on the hot path
- Confirm `component_source` shows `db` for 100% of components for ≥ 14 days.
- Confirm no production traffic resolves a component via the Git path. Add a metric (or temporary log line) on `GitVcsService` that increments on every call; verify count stays at 0.
- After the bake-in window, delete the Git-side resolver implementations:
  - `GitVcsServiceImpl`, `GitTagResolver`, `HistoryEscrowLoaderFactory` and their wiring.
  - The `git` branch of `ComponentRoutingResolver`. The class itself can stay if it now resolves only `db` (it becomes a thin pass-through; can also be inlined).
- Keep `component_source` table and `ComponentSourceEntity` for one more release so a "wrong" source value is visibly broken rather than silently bypassed.

### Stage 5B — Remove the table and the routing layer
- After Stage 5A is in production for ≥ 1 release with no incidents:
  - Drop `component_source` table via Flyway migration.
  - Delete `ComponentSourceEntity`, `ComponentSourceRepository`, `ComponentSourceRegistry`, `ComponentRoutingResolver`.
  - Wire `DatabaseComponentRegistryResolver` directly as `@Primary` (or rename it to drop the `Database` prefix).

### Stage 5C — Remove JGit and the DSL repo wiring
- After Stage 5B and a final history backfill (`POST /admin/migrate-history?reset=true` against the final tag of the legacy repo) is recorded:
  - Remove `JGit` from `build.gradle`.
  - Remove DSL-repo configuration knobs from `application.yml`, `service-config`, and Vault.
  - Delete `GitHistoryImportService` + `GitHistoryImportServiceImpl` + `GitHistoryImportStateEntity` + `git_history_import_state` table.
  - The `/admin/migrate-history` endpoint is retired with these classes; document the deprecation in [MIG-026](../requirements-migration.md).
  - Decommission the legacy DSL git repository (read-only archive).

## Considered alternatives

### A. "Big bang" — remove everything in one PR

**Pros:** Single review, single rollback target, minimum total churn.

**Cons:** Reverting needs to restore JGit + the resolver code + the table simultaneously; on production incident the on-call engineer has to do all of that under stress. Also conflicts with our practice of small, reviewable PRs.

**Verdict:** Rejected.

### B. Keep the Git resolver as a permanent fallback

**Pros:** "Free" disaster recovery — if DB corruption ever happened, fall back to Git.

**Cons:** This is the position we have today. It costs (a) JGit dependency surface in every build, (b) two code paths to test for every resolver change, (c) configuration coupling to a repo we don't write to anymore. The "free" DR is also illusory because the legacy DSL repo no longer reflects edits made via the API since the cutover — falling back to it would silently lose all those edits.

**Verdict:** Rejected as the steady state. Acceptable for the bake-in window of Stage 5A only.

### C. Staged removal (Stage 5A → 5B → 5C above)

**Pros:** Each stage is independently revertible. Bake-in windows give us signal before the next removal. JGit is the last thing to go (smallest step in terms of API surface, but the largest dependency-graph cleanup).

**Cons:** Three PRs instead of one. Slower wall-clock to the end state.

**Verdict:** Recommended.

## Acceptance criteria for closing PRD Phase 5

1. All three stages above shipped and stable in production for ≥ 1 release each.
2. `component_source` table no longer exists in any environment.
3. `JGit` is not a dependency of `components-registry-service-server`.
4. The legacy DSL repository is read-only (archived). No CI / no deployment writes to it.
5. `GET /admin/migration-status` is either removed or repurposed (it has no `git`/`db` distinction to report after Stage 5B; consider removing or returning a flat `total`).

## Risks

- **Stage 5A bake-in skipped.** A latent caller of `GitVcsService` removed without warning would 500. The metric/log-line gate is the cheap insurance — do not skip it.
- **History backfill regression.** If `migrate-history` has not been re-run against the final state of the legacy repo before Stage 5C, any pre-cutover commits we never ingested are gone. Make a deliberate, recorded call before deleting `GitHistoryImportService`.
- **Recovering from DB corruption.** Once the Git resolver is gone, recovery requires PostgreSQL backups (NFS §5.8). Verify the backup/restore drill is rehearsed before Stage 5C.

## Out of scope

- Deciding whether `/admin/migrate-history` itself stays or goes. Today it is useful (audit-log backfill is a one-time operation per cluster) but its dependency on the legacy DSL repo coincides with Stage 5C. If we want history backfill to remain available after the legacy repo is decommissioned, we'd need to materialize the import as a static fixture and ship it with the JAR. Tracked separately if needed.
- Changing the v1/v2/v3 API contract. None of these stages touch it.

## References

- PRD [§6 Phase 5](../prd.md#phase-5-cutover).
- [ADR-007](007-dual-read-migration.md) — Component-Source Routing — current architecture being unwound.
- [MIG-026](../requirements-migration.md) — `/admin/migrate-history` contract.
- [Implementation progress](../implementation-progress.md) — Phase 9 covers the operational hardening that preceded this cutover.
