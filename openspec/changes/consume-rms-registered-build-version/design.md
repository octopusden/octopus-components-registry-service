## Context

CRS's `ComponentConfigurationEntity` already models a per-version-range `javaVersion`/`mavenVersion` as CRS's own editable configuration (one of four DB-CHECK-enforced row shapes: BASE / SCALAR_OVERRIDE / MARKER / RANGE_PRESENCE — see `ComponentConfigurationEntity.kt`). 

RMS separately records the *actual* Java/Maven version used by each build (`BuildParameters.javaVersion`/`mavenVersion` on `ShortBuildDTO`/`BuildDTO`). This design covers how CRS reads that RMS data (Part A) and how CRS gates edits against it (Part B), without creating a build-time coupling between the two services.

## The three-tier model

- **DEFAULT** — the base (`ALL_VERSIONS`) configured value. Manual, editable.
- **OVERRIDDEN** — a per-range configured value. Manual, editable.
- **ACTUAL** — RMS's registered value per range. Read-only, CRS never writes it.

## The write rule, in plain terms

**You can always save a value that matches what RMS recorded. You're only blocked from saving something that contradicts it.**

Every save of a Java or Maven version — whether it's the component's default, an override for a specific range, or moving an override to a different range — goes through the same three-question check. A save is blocked only if the answer to **all three** is yes:

1. **Is anything actually changing?** If the value and range are exactly the same as before — including Portal's Save button resending fields nobody touched — nothing is checked, and the save just goes through.
2. **Does RMS have real data for that range?** If RMS has never seen a build there, there's nothing to compare against, so nothing is blocked.
3. **Does the new value contradict what RMS recorded?** If it matches, the save is allowed.

A quick reference:

| What you're trying to save | Blocked? | Why |
|---|---|---|
| The same value that's already saved | No | Nothing changed |
| A value that matches what RMS recorded | No | It agrees with RMS |
| A value RMS never saw for that range | No | No RMS data to conflict with |
| A value that contradicts what RMS recorded | **Yes** | This is the one real conflict |

## Block vs. Warning — two different things

- **Block** = stops a save from happening, at write time.
- **Warning** = a label shown on an already-saved value that disagrees with RMS, at read time. Informational only.

If a mismatch already exists in stored data — for example, deleting an override reveals a fallback value that happens to disagree with RMS — CRS does **not** go back and fix or reject it. It shows a warning next to that value, naming which range and value RMS disagrees with, and leaves it alone. The person can then choose to save the RMS-matching value, which the rule above always allows.

### Worked example

Component has configured OVERRIDDEN ranges:
- `[2.0,3.0)` → Java 1.8
- `[3.0,4.0)` → Java 11

RMS's actual data:
- Within major-line 2, real builds start at `2.5` recording Java 1.8;
- Within major-line 3, real builds start at `3.4` recording Java 21.

**Lines are computed from each build's version via CRS's existing `NumericVersionFactory`/`IVersionInfo`** (the same factory already used throughout CRS for version math — see `EscrowExpressionContext.getMajor()`/`getMinor()`). 

A run **never crosses a major-line** boundary, even where two lines happen to record the same value. This line-awareness is what makes the gap below hold regardless of whether line 2's and line 3's values agree or differ — a purely value-based collapsing rule would have silently merged the two lines into one run whenever their values happened to match, and `[3.0,3.4)` would stop being free.

Resulting ACTUAL ranges:
- `[2.5,3.0)` → Java 1.8 (line 2 — bounded by the next line's start, no value change is needed to end it there);
- `[3.4,)` → Java 21 (line 3, the highest known line — open-ended).

Consequences:
- `[2.0,2.5)` and `[3.0,3.4)` — never built, no ACTUAL data — still overridable.
- `[2.5,3.0)` — ACTUAL = 1.8, matching the existing override's own value — no warning, and **rewriting the same value there is permitted** (the unified rule's condition 3 fails: it's not a disagreement).
- `[3.4,)`, including anything at or beyond `4.0` (a version that doesn't exist yet) — ACTUAL = 21 (open-ended, highest known line). Writing anything **other than** 21 there is blocked; writing 21 itself is permitted.
- The existing OVERRIDDEN row `[3.0,4.0)` → Java 11 disagrees with ACTUAL `[3.4,)` → 21 for the sub-range `[3.4,4.0)`. This is **not** retroactively blocked or changed — it is shown as a **warning** naming the sub-range `[3.4,4.0)` and ACTUAL's value (21). The one write that resolves it — setting that override to 21 — is permitted, not blocked.

## Goals / Non-Goals

**Goals:**
- Surface ACTUAL per range on CRS's v4 API, for human display in the Portal editor, plus a summary rollup for the components list.
- Reject a DEFAULT/OVERRIDDEN write that would introduce a new disagreement with ACTUAL for an intersecting range.
- Avoid any new cross-repo build dependency between CRS and RMS.

**Non-Goals:**
- CRS becoming the source of truth for ACTUAL (RMS remains that source; any other consumer should query RMS directly, not depend on CRS to relay it).
- Persisting ACTUAL into CRS's own database.
- Enforcing that the *resulting state* always agrees with RMS — only a write that would *introduce* a disagreement is gated. Pre-existing or deletion-revealed mismatches are display warnings, correctable by a write that agrees with ACTUAL, never enforced retroactively.
- Gating `deleteFieldOverride` or the Git/DSL import path (`ImportServiceImpl`) — see Out of Scope below.

## Decisions

### 1. RMS client — thin, hand-rolled, no Gradle dependency

CRS calls RMS's build-listing endpoint directly with its own minimal DTO:

```
GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE&javaVersionPresent=true
GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE&mavenVersionPresent=true
```

The **display sweep** (Decision 4) makes both calls per component, one per attribute — not one call with both flags, since `BuildFilterDTO`'s boolean filters combine as AND on a single request, and one call with both flags set would only return builds where *both* fields are present. The **write-time gate** (Decision 6) makes only **one** call, for the specific attribute being written — there is no reason for a Java-only edit to also query Maven's build history.

This does **not** depend on RMS's published `client` Gradle artifact (`org.octopusden.octopus.release-management-service:client`). That dependency was confirmed technically non-circular — RMS's `client` module depends only on RMS's own `:common` module, not on CRS — but it was deliberately skipped anyway, to avoid introducing a new cross-repo `gradle.properties` version pin between CRS and RMS.

**Inspiration:** `octopus-components-management-portal`'s `ReleaseManagementClient.kt` calls the same endpoint family for the same reason. Portal is reactive (WebFlux) and uses `WebClient`/`Mono`; CRS is a blocking Spring MVC app (Boot `3.2.2`), so the equivalent CRS client uses a blocking `RestClient` call instead.

### 2. Range collapsing — line-aware, per-attribute, leading gaps free, only the highest line's trailing run open-ended

Two independent collapsing passes, one for Java and one for Maven, each over its own filtered build list from Decision 1. Per attribute:

- **Builds are first grouped by major-version line**, computed via the existing `NumericVersionFactory`/`IVersionInfo` (the same utility backing `EscrowExpressionContext.getMajor()`/`getMinor()` elsewhere in CRS — not a new "line" concept invented for this feature, and not tied to CRS's currently-configured range boundaries, which can change over time). A run never crosses a line boundary, even if the values on either side happen to be equal.
- Within a line, a run starts at the first build carrying a given value. **Java values are normalized before comparison** — `"1.8"` and `"8"` must compare equal, reusing the existing `JavaVersion.isEight` logic (`ToolVersion.kt`) rather than treating them as two different values that would otherwise fabricate a spurious run boundary. Maven has no equivalent dual-spelling and needs no normalization.
- A run ends where the next differently-valued build starts, **or where the line itself ends, whichever comes first.** A lower line's run does not need a value change to end — the line boundary itself ends it, which is what keeps a gap like `[3.0,3.4)` free regardless of whether line 2's and line 3's actual values happen to match.
- The highest-version run of the **highest known line only** is open-ended (no upper bound) — intentional, not a defect (see worked example). Every other line's last run is bounded by the next line's start.

This is a new, small pure function — not a forced reuse of `VersionRangePartition.partition`, which solves a different problem (splitting *already-known* segments at the union of *other ranges'* edges, not grouping raw sorted points into contiguous runs). It reuses `VersionRangePartition`'s internal (module-visible) `Segment`/`render`/`parseSegment` helpers for consistent bracket rendering, and the existing `numericVersionComparator` (`EntityMappers.kt`) for ordering.

**Why the leading/trailing asymmetry:** ACTUAL only ever projects *forward* from the most recent known state — the highest-version build is the most relevant data point absent newer information, so it's treated as still applying beyond itself until proven otherwise. A leading gap has no prior state to project *from*; there is nothing before the first build to anchor a projection on. The two ends are not symmetric because the reasoning is inherently directional (recency), not simply "RMS has no data here."

### 3. Display caching — scheduled sweep + in-memory report

A scheduled background job sweeps every CRS component, calling the RMS client (both attribute calls from Decision 1) once per component, and stores the result in a `@Volatile` in-memory cache holding:
- `generatedAt` — timestamp of the last *successful* sweep.
- `lastAttemptAt` — timestamp of the last attempt, success or not.
- `refreshError` — set when the most recent attempt failed; the previous good data is retained (stale-but-honest), not cleared.
- Per-component `unavailable` flag for components whose individual RMS lookup failed even in an otherwise-successful sweep.

Single-flight guarded (an `AtomicBoolean`), adaptive cadence (short retry interval while the last sweep failed, normal interval otherwise).

**Inspiration:** `octopus-components-management-portal`'s `ValidationService.kt` — same shape, scoped here to just java/maven ACTUAL data.

**Cost (see Risks):** this is 2 RMS calls per component per sweep cycle, on top of Portal's own independent RMS sweep for its own, unrelated purpose. Needs a concurrency bound and a per-call timeout budget at implementation time — this is not free to leave unbounded.

### 4. Summary vs. detail response shape

- `ComponentSummaryResponse` (list view, existing DTO) gets one rollup number per attribute: the maximum version seen across all of that attribute's ACTUAL ranges, using the same normalization as Decision 2 (`"1.8"` and `"8"` must not be treated as two different maxima).
- `ComponentDetailResponse` (detail view, existing DTO) gets the full per-attribute ACTUAL range list, plus warning entries on any DEFAULT/OVERRIDDEN row that disagrees with an intersecting ACTUAL range — each entry names the disagreeing sub-range and ACTUAL's value there; a row intersecting several differently-valued ACTUAL ranges gets one entry per disagreement.

Warnings are computed at read time (bounded — a handful of configured rows against a handful of ACTUAL ranges per component) and are not cached separately from the ACTUAL data they're computed against. During an RMS outage, warnings are computed against the last known-good cached ACTUAL data (consistent with Decision 3's stale-but-honest retention), and suppressed only for a component that has never had a successful sweep at all.

### 5. Write-time check — live, single-attribute, strict on ambiguity

The block-override check (Part B) does not read the display cache (Decision 3). It makes its own synchronous RMS call at write time, for only the attribute being written (Decision 1).

**Only a confirmed 200-response with no matching builds counts as "ACTUAL is null → write permitted."** Any other outcome — 404, timeout, 5xx, connection failure — is treated as fail-closed (write rejected). This is a deliberate departure from Decision 1's read-side precedent (Portal's `ReleaseManagementClient` treats 404 as "no data"): that mapping is correct for a read-only sweep ("unknown to RMS ⇒ nothing to report") but wrong for a gate, where the same mapping would mean "unknown/misconfigured ⇒ allow the write." The client's return contract must let the two call sites — sweep and write gate — distinguish "confirmed empty" from "ambiguous/failed," since they treat the same underlying HTTP outcomes differently.

### 6. RMS integration disabled/unconfigured must not silently disable enforcement

If `release-management-service.enabled=false` or the URL is blank, the write gate treats this the same as "RMS unreachable" (fail-closed, per Decision 5) — it does not skip the check. This is called out explicitly because the properties pattern borrowed from `EmployeeServiceProperties` (Decision 11) is an "inert by default" pattern designed for an enrichment lookup, where silently doing nothing is safe; applied uncritically to a correctness gate, the same default would mean a misconfigured deployment silently enforces nothing while reporting itself healthy.

The disabled/unconfigured case is additionally surfaced to operators as **distinct** from a genuine RMS reachability failure — same fail-closed HTTP outcome for the caller, different log/diagnostic signal — so an operator isn't left guessing whether CRS's dependency is down or simply never configured. See Risks for the rollout implication.

### 7. Fail-soft (display) vs. fail-closed (edits)

- **Display:** if RMS is unreachable, the component response still returns; the ACTUAL field/rollup reports itself unavailable rather than failing the whole request.
- **Edit:** if the live RMS call fails, is ambiguous, or RMS integration is disabled/unconfigured, the write is rejected — the same outcome as ACTUAL being explicitly non-null and disagreeing.

**Reason for the asymmetry:** the two paths have opposite risk profiles. A degraded display is read-only and self-heals on the next successful sweep. A wrongly-allowed write is a data-integrity bug that can go unnoticed indefinitely. A blocked edit costs the user a retry; a wrongly-allowed one does not undo itself.

### 8. No new persisted table

ACTUAL is never written to CRS's database. It is kept structurally separate from `ComponentConfigurationEntity` — attached only at the DTO layer — so this read-only, externally-sourced data can never be confused with CRS's own editable configuration.

### 9. Gate placement and write-surface coverage

`ComponentManagementServiceImpl.enforceEditabilityOnUpdate` is a synchronous, in-memory, change-based check with no I/O — cheap, runs on every `PATCH`, including no-op echoes. The new RMS gate is a live network call with different failure semantics (fail-closed on any ambiguity). It is its own function, called individually from:

- `createComponent` (`baseConfiguration.build.javaVersion`/`mavenVersion`) — closes the edge case of a component key being recreated after already having RMS history.
- Base config `PATCH` (`ComponentControllerV4.updateComponent`, via `ComponentManagementServiceImpl.updateComponent`), for the `ALL_VERSIONS` range.
- Single field-override create/update (`createFieldOverride` / `updateFieldOverride`), evaluated against the *post-write* range and value together — covers a value change, a range change, or both.
- The bulk field-overrides apply-plan, per touched `build.javaVersion`/`build.mavenVersion` row.

**`deleteFieldOverride` is explicitly NOT gated.** Deletion never writes a new value — it reveals whatever value the range falls back to, which is exactly what the Part A warning (Decision 4) exists to surface. Gating deletion would conflate "the resulting state might disagree with RMS" (a display concern, deliberately not enforced) with "this write introduces a new disagreement" (the actual rule).

**Transaction hazard.** `ComponentManagementServiceImpl` is class-level `@Transactional`. The live RMS call in Decision 5 runs inside that transaction's write path — a slow or hanging RMS call holds a DB connection and row locks for its duration, which can exhaust the connection pool under concurrent writes. The gate needs an explicit, tight timeout budget (shorter than the write's overall request timeout), and implementation should evaluate it as early as possible in the write path rather than interleaved with other mutations, to minimize how long a lock is held waiting on a network call.

### 10. Module and package placement

Everything lives in `components-registry-service-server` — no new Gradle module. New code lives under:
- `service/rms/` — the RMS client, the sweep service, its scheduler, and the write-time override gate.
- `util/RmsBuildRangeCollapser.kt` — the pure, line-aware collapsing function, alongside `VersionRangePartition`.
- `dto/v4/RegisteredBuildParametersDtos.kt` — the new response DTOs (per-attribute range list + warning entries for detail; max-value rollup for summary).

`service/rms/` satisfies the existing ArchUnit rule (`ArchitectureFitnessTest.kt`: `@Service`-annotated beans must reside under `..service..` or `..teamcity..`) without a rule change.

**Open question — needs a decision, not a default:** would the team prefer a dedicated top-level `rms/` package instead, mirroring the existing `teamcity/` integration subtree 1:1? That would require a one-line addition to the ArchUnit rule (`servicesResideInServiceOrTeamcityPackage`) to allow `..rms..` as well. This proposal defaults to `service/rms/` (no rule change needed) but flags the alternative explicitly so it isn't decided silently.

### 11. Configuration

A new `RmsProperties` (`@ConfigurationProperties(prefix = "release-management-service")`) follows the same "inert by default, two-gate" pattern as `EmployeeServiceProperties` for Part A's display path: an `enabled` flag plus a blank-URL-means-unconfigured second gate. Registered in `ApplicationConfig.kt`'s `@EnableConfigurationProperties` list.

For Part B, "not enabled/configured" resolves through the fail-closed path (Decision 6), not skip-the-gate — the properties object is shared between both features, but each consumes its absence differently.

No `gradle.properties` version pin is added — consistent with Decision 1, this is a runtime URL configuration, not a library dependency.

### 12. New exception types

- `RmsRegisteredValueConflictException` — ACTUAL disagrees with the value being written, for (part of) the range. Mapped to a 4xx.
- `RmsUnavailableException` — the live check failed, timed out, or RMS integration is disabled/unconfigured (the fail-closed case). Mapped to a distinguishable status (e.g. 503); the disabled/unconfigured case additionally logs distinctly from a genuine reachability failure (Decision 6).

Both are mapped in `ControllerExceptionHandler.kt`.

## Out of Scope

- **Legacy v1–v3 API.** Confirmed read-only — no write endpoints exist outside `ComponentControllerV4` — so no gate is needed there, and they will not carry ACTUAL. v2's existing, unmodified response continues to serve the configured value straight from CRS's DB, unaffected by this change.
- **`deleteFieldOverride`.** See Decision 9 — deletion reveals state, it never writes a conflicting value.
- **The Git/DSL import path (`ImportServiceImpl`).** A one-time migration mechanism, not a standing, user-facing edit path. If the team later wants import-time reconciliation against RMS too, that is a separate change.

## Risks / Trade-offs

- **DEFAULT can become effectively frozen at whatever value ACTUAL reports**, for a component with RC/RELEASE history covering the whole `ALL_VERSIONS` scope with a single consistent value — DEFAULT can still be rewritten to *match* ACTUAL (per the unified rule), but not diverge from it. Accepted as the intended enforcement, not a defect, per the goals of Part B.
- **Per-instance cache duplication.** With more than one CRS replica, each replica's sweep runs independently — RMS call volume scales with replica count, and two replicas can briefly disagree on cached ACTUAL within the sweep interval. Accepted: display already tolerates staleness by design (Decision 7).
- **Sweep cost is doubled, not single.** Decision 3's 2-calls-per-component sweep, run on a schedule across every component, is a second, independent RMS sweep alongside Portal's own existing one for an unrelated purpose. This needs a concurrency bound and timeout budget at implementation time, not an unbounded fan-out.
- **Cold cache after restart/deploy.** The first sweep after a rollout is a full pass over every component before the cache is warm. Accepted as a one-time cost per deploy.
- **Write-path latency and transaction exposure.** Every edit to `javaVersion`/`mavenVersion` now costs one synchronous RMS round-trip inside an open DB transaction (Decision 9's transaction hazard). Needs a tight, explicit timeout at implementation time — an unbounded or generously-timed call here is a real availability risk under load, not just a UX latency concern.
- **Version-scheme mismatch.** RMS build-version strings can include forms CRS's own `NumericVersionFactory` may not parse the same way a fallback comparator would. `numericVersionComparator` (`EntityMappers.kt`) silently falls back to a weaker comparator on a parse failure — the collapsing utility (Decision 2) inherits this risk and needs an explicit test case for RMS versions CRS's own factory can't parse.
- **Rollout dependency on configuration.** Per Decision 6/11, a deploy of this feature to an environment where RMS integration isn't yet configured rejects every `javaVersion`/`mavenVersion` write in that environment. Configuration must land in or before the same deploy — this is an operational sequencing risk, not just a code concern.
