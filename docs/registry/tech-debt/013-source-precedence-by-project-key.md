# TD-013: Source-precedence-by-project-key for VCSSettings / Distribution project endpoints

## Status

Implemented (issue #256) · P1 cutover correctness · schema-v2-fixup (former PR #192 review
items 1.2/1.3, "Group 6-H")

## Context

`ComponentRoutingResolver` routes read requests between the legacy git resolver and the new
DB resolver during the v3 DB-migration cutover. Two project-keyed endpoints —

- `GET /rest/api/2/projects/{projectKey}/versions/{version}/vcs-settings`
- `GET /rest/api/2/projects/{projectKey}/versions/{version}/distribution`

(`ProjectControllerV2.getVCSSettingForProject` / `getDistributionForProject`) — resolved via
a blanket fallback:

```kotlin
try { dbResolver.getVCSSettingForProject(projectKey, version) }
catch (e: Exception) { gitResolver.getVCSSettingForProject(projectKey, version) }
```

The `catch (e: Exception)` swallows `NotFoundException` from the DB **and** falls through to
git. For a **DB-sourced** project whose requested version is absent from the DB, git can
return a **stale** `VCSSettings` / `Distribution` that bleeds through as `200` with legacy
data instead of the correct `404` ("stale-git bleed-through"). At cutover this is a
correctness hazard: wrong VCS roots or distribution coordinates would point builds/releases
at legacy locations.

PR #245 fixed the sibling methods (1.1, 1.4, 1.4b, 1.6) with a stale-guard that checks the
returned object's component id against `getDbComponentNames()`. That pattern does **not**
apply here: `VCSSettings` and `Distribution` carry **no `componentName` accessor**, so there
is nothing on the return value to guard against. These two items were deferred pending a
source-precedence design.

## Decision

**Don't guard on the return type — discover the authoritative component first, then route
the data fetch to that component's owning resolver.** Reuses two already-audited pieces:

1. `getJiraComponentByProjectAndVersion(projectKey, version)` — already db-first +
   stale-guarded + transient-tolerant; returns a `JiraComponentVersion` whose
   `componentVersion?.componentName` is the authoritative component, or throws
   `NotFoundException` (→ 404) when a DB-sourced project's version is absent.
2. `resolverFor(componentName)` — returns `dbResolver` if the component's source is `"db"`,
   else `gitResolver`.

```kotlin
private fun resolverForProject(projectKey: String, version: String): ComponentRegistryResolver {
    val componentName = getJiraComponentByProjectAndVersion(projectKey, version).componentVersion?.componentName
    return if (componentName != null) resolverFor(componentName) else gitResolver
}

override fun getVCSSettingForProject(projectKey, version) =
    resolverForProject(projectKey, version).getVCSSettingForProject(projectKey, version)
override fun getDistributionForProject(projectKey, version) =
    resolverForProject(projectKey, version).getDistributionForProject(projectKey, version)
```

### Decision 1 — routing granularity: version-specific (not project-level)

The issue's literal acceptance said "if **any** component of the project is DB-sourced, the
DB resolver is authoritative." We deliberately implement **version-specific** routing
instead. A mid-migration project can carry a git component on one version range and a db
component on another; project-level routing would wrongly `404` a legitimate git version.
Resolving the exact component for the requested `(projectKey, version)` is strictly more
correct and the only behavior that keeps mixed-migration projects working.

### Decision 2 — transient errors: correctness > availability during cutover

`getJiraComponentByProjectAndVersion` tolerates transient (non-`NotFound`) DB errors for
*name discovery* (falls back to git for the name, per PR #245). But the subsequent route
through `resolverFor(name)` calls `sourceRegistry.getSource(name)`, which **also hits the
DB**, and the fetch lands on the **owning** resolver. Consequences:

- A db-sourced component **never** serves stale git data — even on a transient DB error the
  fetch is routed to `dbResolver`, which fails closed; the error propagates (no `200` bleed).
- It is therefore **not** true that these endpoints "stay fully available via git" during a
  DB outage. The trade-off is **correctness over availability** for the migration window.

This is **not a new systemic dependency**: every single-component method already routes via
`resolverFor → getSource → DB`, so the service already requires the DB to be up to route.
The change is *local* to these two endpoints (was "works via git", now "errors") and brings
them in line with the rest of the resolver.

### Acknowledged edge — null-inner JCV (pre-existing, out of scope)

A git `JiraComponentVersion` with `componentVersion == null` yields a null name → falls back
to `gitResolver`. The guard is only as strong as `getJiraComponentByProjectAndVersion`'s
null-degradation (PR #245 P1-B). This edge is inherited, not introduced, and left as-is.

### Acknowledged cost — double resolution

Name discovery resolves `(projectKey, version)` and the owning resolver re-resolves it on
the fetch (≈2× work on these cold endpoints). Acceptable for `vcs-settings` /
`distribution`; a follow-up lever should they ever become hot (e.g. thread the
already-resolved component through the fetch), noted alongside the #249/#321 perf work.

## Acceptance criteria

- [x] DB-sourced project + missing version → `404` (never stale git) — both endpoints.
- [x] DB-sourced project + present version → routed to `dbResolver`; git never called.
- [x] Git-sourced project → routed to `gitResolver`.
- [x] Transient DB error on a DB-sourced project → error propagates; git never called
  (correctness > availability).
- [x] Pure in-memory regression tests (no global fixtures) in
  `ComponentRoutingResolverStaleAndNotFoundTest` (`issue256_*`, 8 cases; RED→GREEN).
- [x] Compat known-delta entries documenting the intended `200`-stale → `404` divergence.

## Risk classification

P1 correctness for the v3→main cutover. The change closes a silent data-corruption path
(stale VCS/distribution served as `200`). The only behavior regression is reduced
availability of these two endpoints during a full DB outage — a deliberate trade-off,
consistent with the rest of the routing resolver.

## See also

- Issue #256; former PR #192 review items 1.2/1.3.
- PR #245 — the sibling stale-guards (1.1/1.4/1.4b/1.6) this builds on.
- `ComponentRoutingResolver.kt` — `resolverForProject` + `resolverFor` +
  `getJiraComponentByProjectAndVersion`.
- `ComponentRoutingResolverStaleAndNotFoundTest.kt` — `issue256_*` tests.
- `components-registry-compat-test/src/test/resources/known-deltas-db.json`.
