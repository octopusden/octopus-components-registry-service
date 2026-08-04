## Context

CRS's `ComponentConfigurationEntity` already models a per-version-range `javaVersion`/`mavenVersion` as CRS's own editable configuration (one of four DB-CHECK-enforced row shapes: BASE / SCALAR_OVERRIDE / MARKER / RANGE_PRESENCE — see `ComponentConfigurationEntity.kt`). RMS separately records the *actual* Java/Maven version used by each build (`BuildParameters.javaVersion`/`mavenVersion` on `ShortBuildDTO`/`BuildDTO`, OCTOPUS-2256). This design covers how CRS reads that RMS data (Part A) and how CRS gates edits against it (Part B), without creating a build-time coupling between the two services.

## Goals / Non-Goals

**Goals:**
- Surface RMS's registered value per range on CRS's v4 API, for human display in the Portal editor.
- Reject a configured-value edit for a range where RMS has already registered a non-null value for that range.
- Avoid any new cross-repo build dependency between CRS and RMS.

**Non-Goals:**
- CRS becoming the source of truth for RMS's registered value (RMS remains that source; any other consumer should query RMS directly, not depend on CRS to relay it).
- Persisting RMS's registered value into CRS's own database.
- Gating the Git/DSL import path (`ImportServiceImpl`) — see Out of Scope below.

## Decisions

### 1. RMS client — thin, hand-rolled, no Gradle dependency

CRS calls `GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE` directly, parsing only the fields it needs (`component`, `version`, `status`, `buildParameters.javaVersion`, `buildParameters.mavenVersion`) into a locally-owned DTO.

This does **not** depend on RMS's published `client` Gradle artifact (`org.octopusden.octopus.release-management-service:client`). That dependency was confirmed technically non-circular — RMS's `client` module depends only on RMS's own `:common` module, not on CRS — but it was deliberately skipped anyway, to avoid introducing a new cross-repo `gradle.properties` version pin between CRS and RMS.

**Inspiration:** `octopus-components-management-portal`'s `ReleaseManagementClient.kt` does exactly this, against the same endpoint, for the same reason. Portal is a reactive (WebFlux) app and uses `WebClient`/`Mono`; CRS is a blocking Spring MVC app (Boot `3.2.2`), so the equivalent CRS client uses a blocking `RestClient` call instead — same endpoint, same "404 → empty, anything else → propagate" failure split, different HTTP stack.

### 2. Display caching — scheduled sweep + in-memory report

A scheduled background job sweeps every CRS component, calling the RMS client once per component, and stores the result in a `@Volatile` in-memory cache holding:
- `generatedAt` — timestamp of the last *successful* sweep.
- `lastAttemptAt` — timestamp of the last attempt, success or not.
- `refreshError` — set when the most recent attempt failed; the previous good data is retained (stale-but-honest), not cleared.
- Per-component `unavailable` flag for components whose individual RMS lookup failed even in an otherwise-successful sweep.

The sweep is single-flight guarded (an `AtomicBoolean`), so an overlapping trigger is a no-op rather than a concurrent second sweep. Cadence is adaptive: a short retry interval while the last sweep failed, the normal interval otherwise.

**Inspiration:** `octopus-components-management-portal`'s `ValidationService.kt` — same shape (scheduled sweep, single-flight guard, stale-but-honest cache, adaptive retry cadence), scoped down here to just java/maven registered-value data instead of a whole validation-problems report.

### 3. Write-time check — always live, never cached

The block-override check (Part B) does not read the display cache. It makes its own synchronous RMS call at write time, independent of and unaffected by the sweep's cache state.

**Reason:** the check must answer "does RMS have a registered value for this range as of right now," not "as of the last sweep." Edits are infrequent relative to reads, so the added per-write latency is an acceptable cost for that correctness guarantee.

### 4. Fail-soft (display) vs. fail-closed (edits)

- **Display:** if RMS is unreachable, the component response still returns; the registered-value field reports itself unavailable rather than failing the whole request.
- **Edit:** if the live RMS call fails during a write attempt, the edit is rejected — the same outcome as if RMS had explicitly returned a non-null registered value for that range.

**Reason for the asymmetry:** the two paths have opposite risk profiles. A degraded display is read-only and self-heals on the next successful sweep. A wrongly-allowed write is a data-integrity bug that can go unnoticed indefinitely — exactly the outcome this feature exists to prevent. A blocked edit costs the user a retry; a wrongly-allowed one does not undo itself.

### 5. No new persisted table

The registered-value view is never written to CRS's database. It is kept structurally separate from `ComponentConfigurationEntity` — attached only at the DTO layer, on the v4 response — so this read-only, externally-sourced data can never be confused with CRS's own editable configuration, and so it never has to satisfy `ComponentConfigurationEntity`'s row-shape invariants (which don't apply to it).

### 6. Gate placement — separate function, not folded into `enforceEditabilityOnUpdate`

`ComponentManagementServiceImpl.enforceEditabilityOnUpdate` is a synchronous, in-memory, change-based check with no I/O — it runs on every `PATCH`, including no-op echoes, so it's deliberately cheap. The new RMS gate is a live network call with different failure semantics (fail-closed on error). Folding it into `enforceEditabilityOnUpdate` would make every PATCH — including edits to unrelated fields — pay for a network call and inherit fail-closed behavior it shouldn't have.

Instead, the RMS gate is its own function, called individually from each of the three write surfaces that can touch `javaVersion`/`mavenVersion`:
- Base config `PATCH` (`ComponentControllerV4.updateComponent`, via `ComponentManagementServiceImpl.updateComponent`), for the ALL_VERSIONS range.
- Single field-override create/update (`createFieldOverride` / `updateFieldOverride`), for that override's specific range.
- The bulk field-overrides apply-plan (the desired-full-set upsert inside `updateComponent`), per touched `build.javaVersion`/`build.mavenVersion` row.

Each call site should only invoke the gate when the write actually changes the value (mirroring the "unchanged echo is tolerated" principle already used elsewhere in this file), not on every PATCH regardless of content.

### 7. Range collapsing — new utility, reusing existing internals

RMS's endpoint has no server-side range filter — it returns every RC/RELEASE build for a component, unfiltered by version. CRS must fetch that full set and collapse consecutive builds with the same `(javaVersion, mavenVersion)` pair into ranges itself.

This is a new, small utility rather than a forced reuse of `VersionRangePartition.partition` — that function solves a different problem (splitting *already-known* segments at the union of *other ranges'* edges), not "group raw sorted points by equal value into contiguous runs." The new utility does, however, reuse `VersionRangePartition`'s internal (module-visible) `Segment`/`render`/`parseSegment` helpers, so RMS-derived ranges render with the exact same bracket syntax as CRS's own configured ranges (including the single-version `[x]` special case). It also reuses the existing `numericVersionComparator` (`EntityMappers.kt`) for ordering, so RMS-derived ranges compare consistently with CRS's own ranges when Part B checks whether a registered range intersects the range being edited.

### 8. Module and package placement

Everything lives in `components-registry-service-server` — no new Gradle module. New code lives under:
- `service/rms/` — the RMS client, the sweep service, its scheduler, and the write-time override gate.
- `util/RmsBuildRangeCollapser.kt` — the pure collapsing function, alongside `VersionRangePartition`.
- `dto/v4/RegisteredBuildParametersDtos.kt` — the new response DTOs.

`service/rms/` satisfies the existing ArchUnit rule (`ArchitectureFitnessTest.kt`: `@Service`-annotated beans must reside under `..service..` or `..teamcity..`) without a rule change.

**Open question — needs a decision, not a default:** would the team prefer a dedicated top-level `rms/` package instead, mirroring the existing `teamcity/` integration subtree 1:1? That would require a one-line addition to the ArchUnit rule (`servicesResideInServiceOrTeamcityPackage`) to allow `..rms..` as well. This proposal defaults to `service/rms/` (no rule change needed) but flags the alternative explicitly so it isn't decided silently.

### 9. Configuration

A new `RmsProperties` (`@ConfigurationProperties(prefix = "release-management-service")`) follows the same "inert by default, two-gate" pattern as `EmployeeServiceProperties`: an `enabled` flag plus a blank-URL-means-unconfigured second gate, so environments that haven't configured RMS integration boot cleanly with the feature silently disabled. Registered in `ApplicationConfig.kt`'s `@EnableConfigurationProperties` list. No `gradle.properties` version pin is added — consistent with Decision 1, this is a runtime URL configuration, not a library dependency.

### 10. New exception types

- `RmsRegisteredValueConflictException` — RMS has a non-null registered value for (part of) the range being edited. Mapped to a 4xx.
- `RmsUnavailableException` — RMS was unreachable during the write-time check (the fail-closed case). Mapped to a distinguishable status (e.g. 503) so a client can tell "blocked because RMS said no" apart from "blocked because RMS is down."

Both are mapped in `ControllerExceptionHandler.kt`.

## Out of Scope

- **Legacy v1–v3 API.** Confirmed read-only — no write endpoints exist outside `ComponentControllerV4` — so no gate is needed there, and they will not carry the new registered-value field. v2's existing, unmodified response continues to serve the configured value straight from CRS's DB, unaffected by this change.
- **The Git/DSL import path (`ImportServiceImpl`).** It also writes `javaVersion`/`mavenVersion` to `ComponentConfigurationEntity`, but as a one-time migration mechanism used when a component is migrated into CRS — not a standing, user-facing edit path. The RMS gate does not apply there. This is a stated scope boundary, not an oversight: if the team later wants import-time reconciliation against RMS too, that is a separate change.

## Risks / Trade-offs

- **Per-instance cache duplication.** If CRS runs with more than one replica, each replica's sweep runs independently — RMS call volume scales with replica count, and two replicas can briefly disagree on the cached registered value within the sweep interval. Accepted: the display path already tolerates staleness by design (Decision 4).
- **Cold cache after restart/deploy.** The first sweep after a rollout is a full pass over every component before the cache is warm. Accepted as a one-time cost per deploy, consistent with how `ValidationService` already behaves in Portal.
- **Write-path latency.** Every edit to `javaVersion`/`mavenVersion` now costs one synchronous RMS round-trip. Accepted per Decision 3 — edits are infrequent enough that this is not expected to be user-visible in practice, but should be verified during implementation (see `RmsProperties.liveTimeoutSeconds`-style budget in tasks).
