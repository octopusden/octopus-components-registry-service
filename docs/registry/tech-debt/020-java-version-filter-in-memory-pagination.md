# TD-020: `?javaVersion=` filters and paginates in memory

## Status

Open · P2 · scalability limit introduced by the RMS-registered-build-parameters change · not a
correctness gap at current data volumes.

## Context

`GET /rest/api/4/components?javaVersion=…` filters on the component's **effective** Java version:
RMS's registered value when the sweep has one for that component, otherwise the BASE configuration
row's configured `javaVersion` (`RegisteredBuildParametersMapper.effectiveJavaVersion`).

RMS's registered value is not a database column. It lives in `RMSBuildParametersService`'s in-memory
sweep cache, keyed by component key, and is recomputed from RMS's build history rather than stored.
So the effective value cannot appear in a JPA `Specification`, cannot be used in a SQL `WHERE`, and
cannot be paginated by the database.

`ComponentManagementServiceImpl.listComponents` therefore takes a second code path whenever
`filter.javaVersion` is non-empty:

1. every component matching the *other* filters is loaded (`findAll(spec, sort)` — sort only, **no**
   `Pageable`),
2. each candidate's effective Java version is computed in memory and compared against the filter,
3. the surviving list is sliced with `subList` and wrapped in a `PageImpl`.

The unfiltered path is unaffected — it still pages in SQL.

## The limit

Cost is O(all components matching the other filters), per request, regardless of page size. Asking
for page 1 with 20 rows still materializes every candidate `ComponentEntity` **and touches its
`configurations` collection** (needed to read the BASE row's `javaVersion`).

At the current scale — low thousands of components — this is acceptable and measured as such. It
degrades linearly, and it degrades on a path any authenticated caller can reach with a single query
parameter, so it is worth removing before the component count grows by an order of magnitude, not
after.

Two secondary consequences of the same design:

- **Sort ties are not stable across pages.** The slice is taken from a list ordered only by the
  requested sort; components tying on that key have no defined relative order, so a row can appear
  on two consecutive pages or on neither. The SQL-paginated path has the same property, but the
  in-memory path re-derives the list on every request, making it easier to observe.
- **A filtered request never benefits from the DB's own pagination limits.** `subList` bounds are
  clamped in long arithmetic (see the comment at the slice), so a large `?page=` is a cheap empty
  page rather than an exception — but the full candidate load still happened first.

## Removal options

None is free; all trade the sweep's "never persisted" property (design.md Decision 8) for
query ability.

- **Persist the effective value as a derived, sweep-maintained column** on `components` (or a small
  side table keyed by component). The sweep already runs on a schedule and already knows every
  component's collapsed ranges, so it can write the rollup at the end of each sweep. Makes the
  filter a plain `WHERE … IN (…)` and restores SQL pagination. Cost: ACTUAL data now lives in the
  database, which Decision 8 deliberately avoided — revisit that decision explicitly rather than
  quietly reversing it.
- **Materialized filter index in memory** — keep the sweep cache authoritative but maintain a
  `javaVersion → Set<componentKey>` inverted index alongside it, intersect that set with the
  DB-side id set, and page over the intersection. Avoids persistence but keeps two sources of truth
  in sync and still needs the id set from SQL.
- **Cap the candidate set** and fail loudly (`400`/`413`) past a configured bound, as a stopgap that
  makes the limit explicit instead of latent. Does not fix anything; only stops it degrading
  silently.

## Test matrix (required if/when implemented)

Filter parity between the old in-memory path and the new one (same components, same order) ·
components whose RMS value and configured value disagree · components with no RMS entry ·
`1.8`/`8` spelling equivalence (see `matchesJavaVersionFilter`) · pagination across a filtered set
larger than one page · sort stability · filter combined with every other `ComponentFilter` field.

## References

- `ComponentManagementServiceImpl.listComponents` (the `filter.javaVersion` branch)
- `RegisteredBuildParametersMapper.effectiveJavaVersion`
- `openspec/changes/consume-rms-registered-build-version/design.md` Decisions 3 and 8
- `docs/registry/non-functional-spec.md` §1 (Read-path query efficiency)
- TD-014 (the same "rebuilt per request" shape on the v3 full-list read path)
