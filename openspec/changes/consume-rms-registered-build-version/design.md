## Context

CRS's `ComponentConfigurationEntity` already models a per-version-range `javaVersion`/`mavenVersion` as CRS's own editable configuration (one of four DB-CHECK-enforced row shapes: BASE / SCALAR_OVERRIDE / MARKER / RANGE_PRESENCE — see `ComponentConfigurationEntity.kt`). 

RMS separately records the *actual* Java/Maven version used by each build (`BuildParameters.javaVersion`/`mavenVersion` on `ShortBuildDTO`/`BuildDTO`, OCTOPUS-2256). This design covers how CRS reads that RMS data (Part A) and how CRS gates edits against it (Part B), without creating a build-time coupling between the two services.

## The three-tier model

- **DEFAULT** — the base (`ALL_VERSIONS`) configured value. **Manual**, **editable**.
- **OVERRIDDEN** — a per-range configured value. **Manual**, **editable**.
- **ACTUAL** — RMS's registered value per range. **Read-only**, **CRS never writes it**.

**Block = rejecting a write; Warning = flagging an already-existing mismatch in the display.** These are different mechanisms for different moments — write time vs. read time — and the rest of this design follows from keeping them separate:

- Writing a new DEFAULT or OVERRIDDEN value is rejected if the range being written intersects a non-null ACTUAL value for that attribute.
- A mismatch that already exists in stored data — because it predates ACTUAL data, or because deleting an override exposed an inherited value that disagrees with ACTUAL — is never retroactively enforced against. It surfaces only as a warning on the relevant DEFAULT/OVERRIDDEN row.

### Worked example

Component has configured OVERRIDDEN ranges `[2.0,3.0)` → Java 1.8 and `[3.0,4.0)` → Java 11 (no DEFAULT override beyond the base).

RMS's actual data: within line 2, real builds start at `2.5` (nothing before that in line 2 was ever built); within line 3, real builds start at `3.4`.

Resulting ACTUAL ranges: `[2.5,3.0)` → registered Java, and `[3.4,)` → registered Java (open-ended — RMS hasn't recorded anything past the last build it has seen, and until it does, this is the best information CRS has).

Consequences:
- `[2.0,2.5)` and `[3.0,3.4)` — never built, no ACTUAL data — still overridable.
- `[2.5,3.0)` — ACTUAL exists — cannot be overridden for Java.
- `[3.4,)`, including anything at or beyond `4.0` (a version that doesn't exist yet) — cannot be overridden for Java, because the last observed run is open-ended by design (Decision 3) until RMS itself reports something different there.
- The existing OVERRIDDEN row `[3.0,4.0)` → Java 11 already disagrees with ACTUAL `[3.4,)` for the sub-range `[3.4,4.0)` — this is **not** retroactively blocked or changed; it is shown as a **warning** on that override row.

## Goals / Non-Goals

**Goals:**
- Surface ACTUAL per range on CRS's v4 API, for human display in the Portal editor, plus a summary rollup for the components list.
- Reject a DEFAULT/OVERRIDDEN write for a range where ACTUAL is already non-null for that range/attribute.
- Avoid any new cross-repo build dependency between CRS and RMS.

**Non-Goals:**
- CRS becoming the source of truth for ACTUAL (RMS remains that source; any other consumer should query RMS directly, not depend on CRS to relay it).
- Persisting ACTUAL into CRS's own database.
- Enforcing that the *resulting state* always agrees with RMS — only the *act of writing* is gated. Pre-existing or deletion-revealed mismatches are display warnings, not violations to prevent.
- Gating `deleteFieldOverride` or the Git/DSL import path (`ImportServiceImpl`) — see Out of Scope below.

## Decisions

### 1. RMS client — thin, hand-rolled, no Gradle dependency; two calls per component

CRS calls RMS's build-listing endpoint twice per component: once with `javaVersionPresent=true` to build the Java ACTUAL range list, once with `mavenVersionPresent=true` for Maven. Two calls, not one with both flags — `BuildFilterDTO`'s boolean filters combine as AND on a single request, so one call with both flags set would only return builds where *both* fields are present, which is not what independent per-attribute ranges need.

```
GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE&javaVersionPresent=true
GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE&mavenVersionPresent=true
```

This does **not** depend on RMS's published `client` Gradle artifact (`org.octopusden.octopus.release-management-service:client`). That dependency was confirmed technically non-circular — RMS's `client` module depends only on RMS's own `:common` module, not on CRS — but it was deliberately skipped anyway, to avoid introducing a new cross-repo `gradle.properties` version pin between CRS and RMS.

**Inspiration:** `octopus-components-management-portal`'s `ReleaseManagementClient.kt` calls the same endpoint family for the same reason (avoiding a client-library dependency). Portal is reactive (WebFlux) and uses `WebClient`/`Mono`; CRS is a blocking Spring MVC app (Boot `3.2.2`), so the equivalent CRS client uses a blocking `RestClient` call instead.

### 2. Range collapsing — new utility, per-attribute, leading gaps free, trailing run open-ended

Two independent collapsing passes, one for Java and one for Maven, each over its own filtered build list from Decision 1. Per attribute:

- A run starts at its first build. Nothing before that first build is covered by ACTUAL — a leading gap (a stretch that was simply never built) stays uncovered and therefore still overridable.
- A run ends where the next differently-valued build starts.
- The **last** run (the highest version RMS has seen) is **open-ended**. This is intentional (see the worked example): until RMS reports something different, the last known value is the best information CRS has, and that includes version lines that don't exist yet. A future, not-yet-built line is therefore not overridable either, until RMS's data changes.

This is a new, small pure function — not a forced reuse of `VersionRangePartition.partition`, which solves a different problem (splitting *already-known* segments at the union of *other ranges'* edges, not grouping raw sorted points into contiguous runs). It does, however, reuse `VersionRangePartition`'s internal (module-visible) `Segment`/`render`/`parseSegment` helpers, so ACTUAL ranges render with the same bracket syntax as CRS's own configured ranges (including the single-version `[x]` special case), and the existing `numericVersionComparator` (`EntityMappers.kt`) for ordering, so ACTUAL ranges compare consistently with CRS's own ranges when Part B checks intersection.

### 3. Display caching — scheduled sweep + in-memory report

A scheduled background job sweeps every CRS component, calling the RMS client (both attribute calls from Decision 1) once per component, and stores the result in a `@Volatile` in-memory cache holding:
- `generatedAt` — timestamp of the last *successful* sweep.
- `lastAttemptAt` — timestamp of the last attempt, success or not.
- `refreshError` — set when the most recent attempt failed; the previous good data is retained (stale-but-honest), not cleared.
- Per-component `unavailable` flag for components whose individual RMS lookup failed even in an otherwise-successful sweep.

Single-flight guarded (an `AtomicBoolean`), adaptive cadence (short retry interval while the last sweep failed, normal interval otherwise).

**Inspiration:** `octopus-components-management-portal`'s `ValidationService.kt` — same shape, scoped here to just java/maven ACTUAL data instead of a whole validation-problems report.

### 4. Summary vs. detail response shape

- `ComponentSummaryResponse` (list view, existing DTO) gets one rollup number per attribute: the **maximum version seen across all of that attribute's ACTUAL ranges** — not the value of the most recent range, literally the highest number, regardless of which range it came from.
- `ComponentDetailResponse` (detail view, existing DTO) gets the full per-attribute ACTUAL range list, plus a warning flag on any DEFAULT/OVERRIDDEN row whose value disagrees with an intersecting ACTUAL range.

### 5. Write-time check — always live, strict on ambiguity

The block-override check (Part B) does not read the display cache (Decision 3). It makes its own synchronous RMS call at write time.

**Only a confirmed 200-response with no matching builds counts as "ACTUAL is null → write permitted."** Any other outcome — 404, timeout, 5xx, connection failure — is treated as fail-closed (write rejected). This is a deliberate departure from Decision 1's read-side precedent (Portal's `ReleaseManagementClient` treats 404 as "no data"): that mapping is correct for a read-only sweep ("unknown to RMS ⇒ nothing to report") but wrong for a gate, where the same mapping would mean "unknown/misconfigured ⇒ allow the write" — silently defeating the fail-closed guarantee. The two call sites therefore handle the *same* HTTP response differently, and that difference must be explicit in the client's contract, not implicit.

### 6. RMS integration disabled/unconfigured must not silently disable enforcement

If `release-management-service.enabled=false` or the URL is blank, Part B's write gate treats this the same as "RMS unreachable" (fail-closed, per Decision 5) — it does not skip the check. This is called out explicitly because the properties pattern borrowed from `EmployeeServiceProperties` (Decision 9) is an "inert by default" pattern designed for an enrichment lookup, where silently doing nothing is safe. Applied uncritically to a correctness gate, the same default would mean a misconfigured deployment silently enforces nothing while reporting itself healthy — exactly the kind of silent non-enforcement this feature exists to prevent elsewhere.

### 7. Fail-soft (display) vs. fail-closed (edits)

- **Display:** if RMS is unreachable, the component response still returns; the ACTUAL field/rollup reports itself unavailable rather than failing the whole request.
- **Edit:** if the live RMS call fails, is ambiguous, or RMS integration is disabled/unconfigured, the write is rejected — the same outcome as ACTUAL being explicitly non-null.

**Reason for the asymmetry:** the two paths have opposite risk profiles. A degraded display is read-only and self-heals on the next successful sweep. A wrongly-allowed write is a data-integrity bug that can go unnoticed indefinitely — exactly the outcome this feature exists to prevent. A blocked edit costs the user a retry; a wrongly-allowed one does not undo itself.

### 8. No new persisted table

ACTUAL is never written to CRS's database. It is kept structurally separate from `ComponentConfigurationEntity` — attached only at the DTO layer — so this read-only, externally-sourced data can never be confused with CRS's own editable configuration, and never has to satisfy `ComponentConfigurationEntity`'s row-shape invariants (which don't apply to it).

### 9. Gate placement and write-surface coverage

`ComponentManagementServiceImpl.enforceEditabilityOnUpdate` is a synchronous, in-memory, change-based check with no I/O — cheap, runs on every `PATCH`, including no-op echoes. The new RMS gate is a live network call with different failure semantics (fail-closed on any ambiguity). Folding it into `enforceEditabilityOnUpdate` would make every PATCH — including edits to unrelated fields — pay for a network call and inherit fail-closed behavior it shouldn't have. It is instead its own function, called individually from:

- Base config `PATCH` (`ComponentControllerV4.updateComponent`, via `ComponentManagementServiceImpl.updateComponent`), for the `ALL_VERSIONS` range.
- Single field-override create/update (`createFieldOverride` / `updateFieldOverride`), for that override's specific range.
- The bulk field-overrides apply-plan (the desired-full-set upsert inside `updateComponent`), per touched `build.javaVersion`/`build.mavenVersion` row.

Each call site should only invoke the gate when the write actually changes the value (mirroring the "unchanged echo is tolerated" principle already used elsewhere in this file).

**`deleteFieldOverride` is explicitly NOT gated.** Deletion never writes a new value — it reveals whatever value the range falls back to (base, or a shadowing override), which may or may not already disagree with ACTUAL. That disagreement, if any, is exactly what the Part A warning (Decision 4) exists to surface. Gating deletion would conflate "the resulting state might disagree with RMS" (a display concern, deliberately not enforced — see Non-Goals) with "this specific write action introduces a new disagreement" (the actual rule).

### 10. DEFAULT uses the same raw-intersection rule as any OVERRIDDEN write — no effective-value awareness

DEFAULT is `ALL_VERSIONS`, so once *any* ACTUAL data exists anywhere for an attribute, writing DEFAULT for that attribute is blocked — even for a sub-range that's already fully shadowed by an existing override and therefore wouldn't actually change what's served for that sub-range. This is an accepted trade-off for a uniform, simple rule: computing the effective/resolved value per sub-range before deciding whether a DEFAULT write matters was considered and rejected as unnecessary complexity. A component with any RC/RELEASE build history will have its DEFAULT permanently frozen for the affected attribute — this is a known, deliberate consequence, not an oversight.

### 11. Module and package placement

Everything lives in `components-registry-service-server` — no new Gradle module. New code lives under:
- `service/rms/` — the RMS client, the sweep service, its scheduler, and the write-time override gate.
- `util/RmsBuildRangeCollapser.kt` — the pure per-attribute collapsing function, alongside `VersionRangePartition`.
- `dto/v4/RegisteredBuildParametersDtos.kt` — the new response DTOs (per-attribute range list + warning flag for detail; max-value rollup for summary).

`service/rms/` satisfies the existing ArchUnit rule (`ArchitectureFitnessTest.kt`: `@Service`-annotated beans must reside under `..service..` or `..teamcity..`) without a rule change.

**Open question — needs a decision, not a default:** would the team prefer a dedicated top-level `rms/` package instead, mirroring the existing `teamcity/` integration subtree 1:1? That would require a one-line addition to the ArchUnit rule (`servicesResideInServiceOrTeamcityPackage`) to allow `..rms..` as well. This proposal defaults to `service/rms/` (no rule change needed) but flags the alternative explicitly so it isn't decided silently.

### 12. Configuration

A new `RmsProperties` (`@ConfigurationProperties(prefix = "release-management-service")`) follows the same "inert by default, two-gate" pattern as `EmployeeServiceProperties` for Part A's display path: an `enabled` flag plus a blank-URL-means-unconfigured second gate, so environments that haven't configured RMS integration boot cleanly with the display feature silently disabled. Registered in `ApplicationConfig.kt`'s `@EnableConfigurationProperties` list.

For Part B, "not enabled/configured" must resolve through the fail-closed path (Decision 6), not skip the gate — the properties object is shared, but the two features consume its absence differently.

No `gradle.properties` version pin is added — consistent with Decision 1, this is a runtime URL configuration, not a library dependency.

### 13. New exception types

- `RmsRegisteredValueConflictException` — ACTUAL has a non-null value for (part of) the range being written. Mapped to a 4xx.
- `RmsUnavailableException` — the live check failed, timed out, or RMS integration is disabled/unconfigured (the fail-closed case). Mapped to a distinguishable status (e.g. 503) so a client can tell "blocked because ACTUAL said no" apart from "blocked because RMS couldn't be checked."

Both are mapped in `ControllerExceptionHandler.kt`.

## Out of Scope

- **Legacy v1–v3 API.** Confirmed read-only — no write endpoints exist outside `ComponentControllerV4` — so no gate is needed there, and they will not carry ACTUAL. v2's existing, unmodified response continues to serve the configured value straight from CRS's DB, unaffected by this change.
- **`deleteFieldOverride`.** See Decision 9 — deletion reveals state, it never writes a conflicting value, so the gate does not apply.
- **The Git/DSL import path (`ImportServiceImpl`).** It also writes `javaVersion`/`mavenVersion` to `ComponentConfigurationEntity`, but as a one-time migration mechanism used when a component is migrated into CRS — not a standing, user-facing edit path. The gate does not apply there. This is a stated scope boundary, not an oversight: if the team later wants import-time reconciliation against RMS too, that is a separate change.

## Risks / Trade-offs

- **DEFAULT can become permanently frozen** for a component with any RC/RELEASE history on an attribute (Decision 10). Accepted as the cost of a simple, uniform rule.
- **Per-instance cache duplication.** If CRS runs with more than one replica, each replica's sweep runs independently — RMS call volume scales with replica count, and two replicas can briefly disagree on the cached ACTUAL value within the sweep interval. Accepted: the display path already tolerates staleness by design (Decision 7).
- **Cold cache after restart/deploy.** The first sweep after a rollout is a full pass over every component before the cache is warm. Accepted as a one-time cost per deploy, consistent with how `ValidationService` already behaves in Portal.
- **Write-path latency.** Every edit to `javaVersion`/`mavenVersion` now costs one synchronous RMS round-trip. Accepted per Decision 5 — edits are infrequent enough that this is not expected to be user-visible in practice, but should be verified during implementation.
- **Version-scheme mismatch.** RMS build-version strings can include forms (e.g. dash-qualified release identifiers) that CRS's own `NumericVersionFactory` may not parse the same way `DefaultArtifactVersion`-style fallbacks do. `numericVersionComparator` (`EntityMappers.kt`) silently falls back to a weaker comparator on a parse failure — the collapsing utility (Decision 2) inherits this risk and needs an explicit test case for RMS versions CRS's own factory can't parse, rather than assuming RMS and CRS always agree on version syntax.
