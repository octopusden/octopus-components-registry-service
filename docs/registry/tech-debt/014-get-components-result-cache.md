# TD-014: result cache for `getComponents()` (full 2.0.87 latency parity)

## Status

Open · P2 · performance follow-up to GH #365 · not a correctness gap.

## Context

`GET /rest/api/3/components` (and other full-list aggregate reads) routes in DB mode to
`DatabaseComponentRegistryResolver.getComponents()`, which rebuilds the entire merged domain model
for every component on every request. GH #365 made this **bounded and fast** — surgical fixes only:

- `BATCH_FETCH_SIZE` raised above the production component count, so the per-request statement count
  is small and independent of size (proven by `GetComponentsListQueryCountTest`).
- reflective `EscrowModuleConfig` field lookups memoized once (`escrowModuleConfigField`).

Measured (local Testcontainers, 1000 components / ~3k config rows): ~29 statements, ~166 ms median.
This is well under the downstream 30 s consumer timeout and removes the round-trip storm that
ballooned latency under concurrent migration/sync load.

It does **not** reach prod 2.0.87's ~15 ms: 2.0.87 is git-backed and serves this list from a
startup-built in-memory graph (O(1)); the DB resolver still rebuilds per request. Full parity needs
a result cache.

## Why a naive cache was explicitly rejected

A `count + max(updated_at)` validity probe over `components` / `component_configurations` is **not
safe**: `getComponents()` depends on many child/junction tables (labels, required tools, build-tool
beans, maven/file/docker/package artifacts, vcs entries, security groups, doc links, …). Import and
management write paths can mutate those children without reliably bumping either parent's
`updated_at`, so the cache could serve stale data indefinitely.

## What a correct cache requires

- A **transactionally updated registry revision** (e.g. a single monotonically-bumped counter/row)
  incremented by *every* mutation path — import (`ImportServiceImpl`), v4
  `ComponentManagementService` create/update/delete and field-override create/update/delete — inside
  the writing transaction, so the cache key flips iff data changed.
- **Single-flight rebuild** (one rebuild under contention, others wait/serve-stale).
- **Consistent-snapshot** semantics (rebuild reads one transactional snapshot).
- **Multi-pod invalidation** (the revision lives in the DB and is read cheaply, or a pub/sub signal).
- **Failure-during-rebuild** handling (serve last-good, don't cache a partial).
- **Mutation isolation** of the cached `MutableCollection<EscrowModule>` (callers must not be able to
  mutate the shared snapshot).

## Test matrix (required if/when implemented)

Child-only-mutation invalidation · concurrent rebuilds · writes-during-rebuild · multi-instance
invalidation · cached-result mutation isolation · cold vs warm latency.

## References

- `docs/registry/non-functional-spec.md` §1 (Read-path query efficiency)
- `DatabaseComponentRegistryResolver.getComponents()`; `EntityMappers.kt`
