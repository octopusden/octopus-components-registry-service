## Context

CRS's `ComponentConfigurationEntity` already models a per-version-range `javaVersion`/`mavenVersion` as CRS's own editable configuration (one of four DB-CHECK-enforced row shapes: BASE / SCALAR_OVERRIDE / MARKER / RANGE_PRESENCE — see `ComponentConfigurationEntity.kt`). 

RMS separately records the *actual* Java/Maven version used by each build (`BuildParameters.javaVersion`/`mavenVersion` on `ShortBuildDTO`/`BuildDTO`). This design covers how CRS reads that RMS data (Part A) and how CRS gates edits against it (Part B), without creating a build-time coupling between the two services.

## The three-tier model

- **DEFAULT** — the base (`ALL_VERSIONS`) configured value. Manual, editable.
- **OVERRIDDEN** — a per-range configured value. Manual, editable.
- **ACTUAL** — RMS's registered value per range. Read-only, CRS never writes it.

## The write rule

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

If a range overlaps **more than one** RMS-recorded range with different values (this only happens for DEFAULT, since it spans every version at once), the same rule applies against **each** of them: the save is blocked if it disagrees with **any** one of them, not just the first.

## Block vs. Warning — two different things

- **Block** = stops a save from happening, at write time.
- **Warning** = a label shown on an already-saved value that disagrees with RMS, at read time. Informational only.

If a mismatch already exists in stored data — for example, deleting an override reveals a fallback value that happens to disagree with RMS — CRS does **not** go back and fix or reject it. It shows a warning next to that value, naming which range and value RMS disagrees with, and leaves it alone. The person can then choose to save the RMS-matching value, which the rule above always allows.

### Worked example

Component has configured OVERRIDDEN ranges:
- `[2.0,3.0)` → Java 1.8
- `[3.0,4.0)` → Java 11

RMS's RC/RELEASE build history, in real version order, Java version recorded per build:

| Version | Java recorded |
|---|---|
| 2.2.1 | — (null) |
| 2.2.3 | — (null) |
| 2.2.5 | 17 |
| 2.2.7 | 17 |
| 2.2.10 | 17 |
| 2.3.1 | — (null, a different version line, not yet recording) |
| 2.3.2 | — (null) |
| 2.3.4 | 21 |
| 2.3.5 | 21 |
| 2.3.6 | 21 |

ACTUAL is built by walking this list in order (see Decision 2) — no "minor version" bucketing involved, just this sequence:
- `2.2.1` and `2.2.3` are null — nothing to start a run yet.
- `2.2.5` starts a run at Java 17. `2.2.7` and `2.2.10` continue it (same value) — the run keeps extending even though there's a gap in the raw version numbers between `2.2.7` and `2.2.10` (nothing was built at `2.2.8`/`2.2.9` at all — that's fine, the run is defined by consecutive *observed builds*, not by unbroken version numbers).
- `2.3.1` is null — this **closes** the 17-run. Its range is `[2.2.5,2.3.1)`.
- `2.3.1` and `2.3.2` are null — no run active.
- `2.3.4` starts a new run at Java 21. `2.3.5` and `2.3.6` continue it.
- `2.3.6` is the **last build in the whole fetched history** for this component, and it's non-null — so this run stays **open-ended**: `[2.3.4,)`.

Resulting ACTUAL ranges: `[2.2.5,2.3.1)` → 17, and `[2.3.4,)` → 21.

Consequences:
- Anything before `2.2.5`, and the stretch `[2.3.1,2.3.4)` — no ACTUAL data — still overridable.
- `[2.2.5,2.3.1)` — ACTUAL = 17, matches the existing override `[2.0,3.0)`'s own value — no warning, and **rewriting 17 there is permitted** (agrees with ACTUAL).
- `[2.3.4,)`, including `4.0` and beyond (versions that don't exist yet) — ACTUAL = 21, open-ended. Writing anything **other than** 21 there is blocked; writing 21 itself is permitted.
- The existing OVERRIDDEN row `[3.0,4.0)` → Java 11 disagrees with ACTUAL `[2.3.4,)` → 21 for their overlap, `[2.3.4,4.0)`. This is **not** retroactively blocked or changed — it's shown as a **warning** naming that sub-range and ACTUAL's value (21). The one write that resolves it — setting that override to 21 — is permitted, not blocked.

**Now suppose one more build exists, `2.3.7`, recorded *after* `2.3.6`, with Java null again** (the component's build stopped recording Java for some reason). This changes the last run: `2.3.6` is **no longer** the last build in the history — `2.3.7` is, and it's null. So the 21-run does **not** stay open-ended anymore; it closes at `2.3.7`: `[2.3.4,2.3.7)`, not `[2.3.4,)`. A later observation — even a null one — always overrides the "still true" assumption for whatever came before it.

## Goals / Non-Goals

**Goals:**
- Surface ACTUAL per range on CRS's v4 API, for human display in the Portal editor, plus a summary rollup for the components list.
- Reject a DEFAULT/OVERRIDDEN write that would introduce a new disagreement with ACTUAL for an intersecting range.
- Avoid any new cross-repo build dependency between CRS and RMS.
- Apply all of the above only to components whose build system is Maven or Gradle (Decision 13) — a Java/Maven version is meaningless for anything else.

**Non-Goals:**
- CRS becoming the source of truth for ACTUAL (RMS remains that source; any other consumer should query RMS directly, not depend on CRS to relay it).
- Persisting ACTUAL into CRS's own database.
- Enforcing that the *resulting state* always agrees with RMS — only a write that would *introduce* a disagreement is gated. Pre-existing or deletion-revealed mismatches are display warnings, correctable by a write that agrees with ACTUAL, never enforced retroactively.
- Gating `deleteFieldOverride` or the Git/DSL import path (`ImportServiceImpl`) — see Out of Scope below.

## Decisions

### 1. RMS client — thin, hand-rolled, no Gradle dependency

CRS calls RMS's build-listing endpoint directly with its own minimal DTO:

```
GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE&descending=false
```

**One call, unfiltered by attribute presence**. The collapsing algorithm (Decision 2) needs to see builds where the attribute is **null**, because a later null build is what closes off an earlier run rather than leaving it open-ended. Filtering server-side to "only builds where Java is present" would silently remove exactly the null observations the algorithm depends on. Both the **display sweep** (Decision 3) and the **write-time gate** (Decision 5) use this same single, full fetch per component — one call serves both Java and Maven collapsing, and both read paths.

**`descending: false` is relied on directly — CRS does not re-derive or re-verify build order.** RMS's build-listing endpoint guarantees the returned builds are ordered by real version (ascending, oldest first), so the collapsing algorithm (Decision 2) walks the response as-is; it does not parse each build's version itself to establish order, and there is no "unparseable version" case to fail closed on for ordering purposes.

This does **not** depend on RMS's published `client` Gradle artifact (`org.octopusden.octopus.release-management-service:client`). That dependency was confirmed technically non-circular — RMS's `client` module depends only on RMS's own `:common` module, not on CRS — but it was deliberately skipped anyway, to avoid introducing a new cross-repo `gradle.properties` version pin between CRS and RMS.

**Inspiration:** `octopus-components-management-portal`'s `ReleaseManagementClient.kt` calls the same endpoint family for the same reason. Portal is reactive (WebFlux) and uses `WebClient`/`Mono`; CRS is a blocking Spring MVC app (Boot `3.2.2`), so the equivalent CRS client uses a blocking `RestClient` call instead.

### 2. How ACTUAL ranges are built from RMS's builds

Run separately for Java and for Maven — each pass over the same fetched build list (Decision 1), taken in RMS's guaranteed ascending real-version order (`descending: false`) with no independent re-sorting.

**Step 1 — Walk the sorted builds, tracking one "current run" (a value + where it started).**
For each build in order, for the attribute being processed (Java or Maven):
- If the build's value is **null**, or **differs** from the current run's value: close the current run (if any) at the previous build's version, and — if this build's own value is non-null — start a new run here.
- If the build's value is the **same** as the current run's value (after normalization, see below): extend the current run; it doesn't matter how large the version gap since the run's last member is — a run bridges *unbuilt* stretches freely, it's only ever broken by an *observed, differing-or-null* build.

**Step 2 — The very last run is open-ended only if it also contains the single highest-version build in the whole fetched list.**
- If the highest-version build overall has a non-null value, its run (which may include several builds before it) has no upper bound — it extends into versions that don't exist yet, on the reasoning that RMS's most recent observation is the best information available until something newer contradicts it.
- If the highest-version build overall is **null**, no run is open-ended at all — the last non-null run (if any) closes at its own last member, because we now know something came *after* it, and it wasn't part of that run. A later observation, even a null one, always overrides the "still true" assumption (see the worked example's follow-up).
- Every other run — one that isn't the very last one — is simply closed at the point where it was broken (Step 1).

**A consequence worth being explicit about:** a run can bridge a stretch where nothing was built at all (e.g. `2.2.7` to `2.2.10` in the worked example, skipping `2.2.8`/`2.2.9` entirely) — the range is defined by the sequence of *observed* builds, not by asserting that every intermediate version number was itself tested. This is a deliberate simplification, not an oversight: distinguishing "genuinely untested" from "not built at all, but presumably fine" was considered and rejected as unnecessary complexity for this feature.

**Comparing values.** Before two builds' values can be compared, each one is normalized first:

- **Java:** `"1.8"` and `"8"` count as the same version (same for `"1.7"`/`"7"`, and so on) — this is just the existing `JavaVersion.isEight` check (`ToolVersion.kt`), generalized into a full rule instead of a single special case. RMS's real recorded values are confirmed to be short forms like `"17"` or `"1.8"` — but since this data passes through from an external legacy system CRS doesn't control, that's not assumed to hold forever: as a safety net, a longer form like `"17.0.9"` is also read as major version 17.
- **Maven:** real values look like `"3.3.6"`, `"3.3.9"`, `"4.0"` — plus one special case, the literal word `"LATEST"`. That's not a version number; it means "whatever's newest at build time," so it's never treated as equal to a numbered version. But since "latest" is by definition at least as new as anything else, it always counts as the biggest value when computing the maximum (Decision 4). Everything else compares using CRS's existing Maven version comparator, not plain string equality.

**Hotfix builds** (`ShortBuildDTO.hotfix`) are included in ACTUAL with no special handling — a hotfix is expected to always carry its parent version line's Java/Maven value, so there's no expected case where it would introduce a spurious value change. Considered and deliberately not filtered, not overlooked.

**Implementation note:** this is a small, new function, not a reuse of `VersionRangePartition.partition` (that one solves a different problem — splitting already-known ranges around other ranges' edges, not grouping raw points into runs). It does reuse a couple of `VersionRangePartition`'s existing helper functions for consistent range formatting.

### 3. Display caching — scheduled sweep + in-memory report

A scheduled background job sweeps every CRS component, calling the RMS client (the single unfiltered call from Decision 1) once per component, and stores the result in a `@Volatile` in-memory cache holding:
- `generatedAt` — timestamp of the last *successful* sweep.
- `lastAttemptAt` — timestamp of the last attempt, success or not.
- `refreshError` — set when the most recent attempt failed; the previous good data is retained (stale-but-honest), not cleared.
- Per-component `unavailable` flag for components whose individual RMS lookup failed even in an otherwise-successful sweep.

Single-flight guarded (an `AtomicBoolean`).

**Timing, concretely:**
- **On startup**, the scheduler runs an immediate first sweep rather than waiting for the first interval to elapse — otherwise every component would show "unavailable" for hours after every deploy, not just briefly.
- **Normal cadence** (the last sweep succeeded): every **4 hours**. RMS build data changes at release cadence, not continuously, so there's no benefit to checking more often, and it keeps steady-state RMS load low.
- **Retry cadence** (the last sweep failed): starts at **5 minutes**, then doubles on each consecutive failure — 5, 10, 20, 40 minutes, and so on — capped at the normal 4-hour interval so retries never take longer than the normal cadence itself. This lets a brief outage self-heal within minutes, while a prolonged one backs off instead of hammering RMS at a fixed short interval forever.
- **On the next success**, the cadence resets straight back to the normal 4-hour interval, and the backoff resets too — the next failure (if any) starts again at 5 minutes, not wherever the previous backoff left off.

**Inspiration:** `octopus-components-management-portal`'s `ValidationService.kt` — same shape (immediate first sweep, adaptive re-arming based on outcome), scoped here to just java/maven ACTUAL data, with an added exponential backoff Portal's own retry cadence doesn't need at this call volume.

**Cost (see Risks):** this is 1 RMS call per component per sweep cycle, on top of Portal's own independent RMS sweep for its own, unrelated purpose. Needs a concurrency bound and a per-call timeout budget at implementation time — this is not free to leave unbounded.

### 4. Summary vs. detail response shape

- `ComponentSummaryResponse` (list view, existing DTO) gets one rollup number per attribute: the maximum version seen across all of that attribute's ACTUAL ranges, comparing values using the same normalization named in Decision 2 (Java: legacy-spelling-aware major-version extraction; Maven: the existing Maven version comparator) — not raw string comparison.
- `ComponentDetailResponse` (detail view, existing DTO) gets the full per-attribute ACTUAL range list, plus warning entries on any DEFAULT/OVERRIDDEN row that disagrees with an intersecting ACTUAL range — each entry names the disagreeing sub-range and ACTUAL's value there; a row intersecting several differently-valued ACTUAL ranges gets one entry per disagreement.

**Known limitation of the max rollup.** "Maximum" means the highest version number ever seen, across every range — not what the component builds on today. Example: an old, still-maintained line once used Java 21, but the current line has since moved down to Java 8. The list view shows "21," even though the component actually builds on 8 right now. So this rollup can answer "what's the highest version this component has ever recorded" — it cannot answer "which components are still on an old Java version." That's an accepted trade-off (see Part A requirements), not something we missed.

The same rule applies to Maven's `"LATEST"`: if it shows up anywhere in a component's history, it always wins the rollup, regardless of what numbered versions also exist — by definition, "latest" outranks any fixed number.

**How warnings are computed.** Warnings aren't stored — they're worked out fresh each time a component is read, by comparing its configured rows against its cached ACTUAL ranges. This is cheap (a handful of rows against a handful of ranges), so there's no need to cache the result separately.

If RMS is down at that moment, warnings just use whatever ACTUAL data was last successfully fetched (same stale-but-honest rule as Decision 3). They're only hidden entirely for a component that has never had a single successful sweep.

### 5. Write-time check — live, strict on ambiguity

The block-override check (Part B) does not read the display cache (Decision 3). It makes its own synchronous RMS call at write time — the same single, unfiltered fetch shape as Decision 1 (there's no attribute-scoped variant to call instead, since the full build list, including null-value builds, is what the collapsing algorithm needs regardless of which attribute is being written). The disagreement check itself still only evaluates the one attribute actually being written.

**Only a confirmed 200-response with no matching builds counts as "ACTUAL is null → write permitted."** 

Any other outcome — 404, timeout, 5xx, connection failure — is treated as fail-closed (write rejected). This is a deliberate departure from Decision 1's read-side precedent (Portal's `ReleaseManagementClient` treats 404 as "no data"): that mapping is correct for a read-only sweep ("unknown to RMS ⇒ nothing to report") but wrong for a gate, where the same mapping would mean "unknown/misconfigured ⇒ allow the write." The client's return contract must let the two call sites — sweep and write gate — distinguish "confirmed empty" from "ambiguous/failed," since they treat the same underlying HTTP outcomes differently.

### 6. Disabled means the feature is off. Unreachable means RMS is down. These are different, and are handled differently.

- **Disabled** (`release-management-service.enabled=false`): this whole feature — both Part A's display and Part B's write check — is simply **turned off**. Nothing is shown, nothing is blocked. Writing `javaVersion`/`mavenVersion` behaves exactly as it did before this feature existed. This is a deliberate, intentional configuration state (an environment that hasn't set up RMS integration, or doesn't want this feature), not a failure — so it must never be treated as an error condition. A blank `url` in this state is irrelevant and never checked.
- **Misconfigured** (`release-management-service.enabled=true` with a blank `url`): this is **not** a silent "off" state — it fails application startup outright (Decision 11), the same fail-fast contract `TeamcityValidationProperties` already uses elsewhere in this codebase. Turning the feature on is an explicit action; doing so without also supplying a URL is a configuration mistake worth surfacing immediately, not a state the feature should quietly run through as if it were disabled.
- **Unreachable** (the feature is enabled, but the live call to RMS fails, times out, or is ambiguous): this is a genuine failure of a dependency the feature is actively relying on. See Decision 7 for exactly how this is handled — the answer differs for display vs. edits, and is *not* simply "block everything."

### 7. Fail-soft (display) vs. narrowly-scoped fail-closed (edits) — only while the feature is enabled and RMS is unreachable

- **Display:** if RMS is unreachable, the component response still returns normally. Each DEFAULT/OVERRIDDEN row is shown with an "ACTUAL data unavailable" indicator (distinct from a disagreement warning — this means "we couldn't check," not "we checked and it disagrees"), rather than silently showing nothing or omitting the row's status entirely.
- **Edit, when the write does not touch `javaVersion`/`mavenVersion` at all:** never blocked by RMS being unreachable. This already follows from the write rule's first question (Decision-adjacent, see "The write rule" above) — if `javaVersion`/`mavenVersion` isn't effectively changing, the RMS check is never even invoked, so an outage has no way to affect that write. Worth stating explicitly here: an RMS outage must never cause an unrelated field edit (or an unchanged resend of `javaVersion`/`mavenVersion` alongside other real changes) to fail.
- **Edit, when the write does change `javaVersion`/`mavenVersion`:** rejected if the live check is unreachable or ambiguous — the same outcome as ACTUAL being explicitly non-null and disagreeing. This is the one case where an RMS outage does block something, and only that something: the specific field actually being changed.

**Reason for the asymmetry:** the two paths have opposite risk profiles. A degraded display is read-only and self-heals on the next successful sweep. A wrongly-allowed write to `javaVersion`/`mavenVersion` is a data-integrity bug that can go unnoticed indefinitely. A blocked edit costs the user a retry on the one field that actually needed checking; a wrongly-allowed one does not undo itself. Scoping the block to only the field(s) actually changing keeps an outage from having any effect beyond that.

### 8. No new persisted table

ACTUAL is never written to CRS's database. It is kept structurally separate from `ComponentConfigurationEntity` — attached only at the DTO layer — so this read-only, externally-sourced data can never be confused with CRS's own editable configuration.

### 9. Gate placement and write-surface coverage

`ComponentManagementServiceImpl.enforceEditabilityOnUpdate` is a synchronous, in-memory, change-based check with no I/O — cheap, runs on every `PATCH`, including no-op echoes. The new RMS gate is a live network call with different failure semantics (fail-closed on any ambiguity, while the feature is enabled). It is its own function, called individually from:

- Base config `PATCH` (`ComponentControllerV4.updateComponent`, via `ComponentManagementServiceImpl.updateComponent`), for the `ALL_VERSIONS` range.
- Single field-override create/update (`createFieldOverride` / `updateFieldOverride`), evaluated against the *post-write* range and value together — covers a value change, a range change, or both.
- The bulk field-overrides apply-plan, per touched `build.javaVersion`/`build.mavenVersion` row.

**`deleteFieldOverride` is explicitly NOT gated.** Deletion never writes a new value — it reveals whatever value the range falls back to, which is exactly what the Part A warning (Decision 4) exists to surface. Gating deletion would conflate "the resulting state might disagree with RMS" (a display concern, deliberately not enforced) with "this write introduces a new disagreement" (the actual rule).

**Transaction hazard.** `ComponentManagementServiceImpl` is class-level `@Transactional`. The live RMS call in Decision 5 runs inside that transaction's write path — a slow or hanging RMS call holds a DB connection and row locks for its duration, which can exhaust the connection pool under concurrent writes. The gate needs an explicit, tight timeout budget (shorter than the write's overall request timeout), and implementation should evaluate it as early as possible in the write path rather than interleaved with other mutations, to minimize how long a lock is held waiting on a network call.

### 10. Module and package placement

Everything lives in `components-registry-service-server` — no new Gradle module. New code lives under:
- `service/rms/` — the RMS client, the sweep service, its scheduler, and the write-time override gate.
- `util/BuildRangeCollapser.kt` — the pure, sequential-run collapsing function, alongside `VersionRangePartition`.
- `dto/v4/RegisteredBuildParametersDtos.kt` — the new response DTOs (per-attribute range list + warning entries for detail; max-value rollup for summary).

`service/rms/` satisfies the existing ArchUnit rule (`ArchitectureFitnessTest.kt`: `@Service`-annotated beans must reside under `..service..` or `..teamcity..`) without a rule change. Decided: `service/rms/`, not a dedicated top-level `rms/` package — no ArchUnit rule change needed.

### 11. Configuration

A new `RMSProperties` (`@ConfigurationProperties(prefix = "release-management-service")`) follows the same "inert by default" pattern as `EmployeeServiceProperties`: an `enabled` flag, defaulting to `false`, so unconfigured environments boot cleanly. Also carries the sweep timing from Decision 3 as configurable, defaulted fields — normal interval (default 4 hours), initial retry interval (default 5 minutes), and the retry backoff cap (default equal to the normal interval) — rather than hardcoding them, so an environment can tune them without a code change. Registered in `ApplicationConfig.kt`'s `@EnableConfigurationProperties` list.

Per Decision 6, `enabled=false` means the same thing for both Part A and Part B: the feature is off. Neither the display sweep nor the write gate runs; nothing is shown, nothing is blocked. This is a single, consistent meaning, not two different interpretations per feature.

**Two independent gates on `url`, not one:**
- `@Validated` + a class-level `@AssertTrue` fails Bean Validation (and so application startup) if `enabled=true` and `url` is blank — `TeamcityValidationProperties`'s existing fail-fast pattern in this codebase, applied here because turning the feature on without a URL is a configuration mistake, not a state worth quietly tolerating.
- `RMSUrlConfiguredCondition`, at Spring `@Bean` registration time, is a second, independent gate on the same fact (`enabled=true` with a non-blank `url`) — kept as a defensive backstop in case `RMSClient`'s bean method is ever reached without the validated properties object (e.g. a future refactor that constructs `RMSProperties` directly), so the bean is never registered from a half-configured state even if the startup validation were bypassed.

No `gradle.properties` version pin is added — consistent with Decision 1, this is a runtime URL configuration, not a library dependency.

### 12. New exception types

- `RMSRegisteredValueConflictException` — ACTUAL disagrees with the value being written, for (part of) the range. Mapped to a 4xx.
- `RMSUnavailableException` — the feature is enabled, but the live check failed, timed out, or was ambiguous. Mapped to a distinguishable status (e.g. 503). Not thrown at all when the feature is disabled (Decision 6) — a disabled integration means the gate isn't invoked in the first place, not that it invokes and throws.

Both are mapped in `ControllerExceptionHandler.kt`.

### 13. Scoped to Maven and Gradle build systems only

This entire feature — ACTUAL display, the summary rollup, and the write gate — applies only to components whose (effective) build system is `MAVEN` or `GRADLE` (`BuildSystem` enum, `component-resolver-api`). For any other build system (`BS2_0`, `GOLANG`, `IN_CONTAINER`, `WHISKEY`, `PROVIDED`, `ESCROW_NOT_SUPPORTED`, `ESCROW_PROVIDED_MANUALLY`, ...), a Java/Maven version is a meaningless concept, so the feature does nothing: no RMS call for that component in the sweep, no ACTUAL field/rollup in its responses, no write gate on its `javaVersion`/`mavenVersion` fields (which, for a non-Maven/Gradle component, are unlikely to be meaningfully set in the first place).

`ECLIPSE_MAVEN` is a separate, distinct enum value from `MAVEN` in this codebase, and is **excluded** — treated the same as any other non-`MAVEN`/`GRADLE` build system (no ACTUAL data, no write gate). Only `MAVEN` and `GRADLE` are in scope.

**Implementation note:** a component's build system can, in principle, be set per version-range row, not only at the base/component level. This design checks the build system at the component/base level for simplicity; if per-range build system divergence turns out to matter in practice, that's a refinement to revisit, not something this design claims to handle.

**Known bypass, deliberately not fixed:** because a component's build system is itself an editable, ungated field, someone can change it away from `MAVEN`/`GRADLE`, write a disagreeing `javaVersion`/`mavenVersion` while the gate doesn't apply, then change the build system back — storing a value that was never actually checked. This was considered and left as-is: changing a component's build system is expected to be very rare, and closing it fully would mean adding a new gate on the build-system field itself, which is out of this feature's scope. See Risks.

### 14. A 404 from RMS during the display sweep is treated the same as unreachable

If RMS returns a 404 for a component during the scheduled sweep, that component's ACTUAL is marked unavailable (Decision 7's fail-soft path) — the same as a timeout or any other sweep failure. This mirrors Decision 5's write-gate rule (only a confirmed, unambiguous "no matching builds" response counts as null): a 404 is not treated as "clean, no data" for display either, since it's just as ambiguous a signal (could mean the component genuinely has no RMS history, or could mean a routing/configuration problem) and shouldn't be allowed to look identical to a healthy, checked, empty result.

### 15. A write rejected for an ACTUAL conflict triggers an immediate, targeted cache refresh for that one component

Because warnings and ACTUAL display are served from the sweep's cache (Decision 3) while the write gate checks live (Decision 5), it's possible for a user to see a clean, no-warning row, attempt a save, and have it rejected by ACTUAL data the cache hasn't caught up to yet (Decision 3 already accepts this as inherent to a scheduled-sweep design). To keep the display from staying visibly wrong afterward, a write rejected with `RMSRegisteredValueConflictException` triggers a refresh of *that one component's* cache entry immediately — not a full sweep, just the single component whose live check just ran and already has fresh data available from the same call. The next read for that component reflects the conflict that caused the rejection, without waiting for the next scheduled sweep interval.

## Out of Scope

- **Legacy v1–v3 API.** Confirmed read-only — no write endpoints exist outside `ComponentControllerV4` — so no gate is needed there, and they will not carry ACTUAL. v2's existing, unmodified response continues to serve the configured value straight from CRS's DB, unaffected by this change.
- **`deleteFieldOverride`.** See Decision 9 — deletion reveals state, it never writes a conflicting value.
- **The Git/DSL import path (`ImportServiceImpl`).** A one-time migration mechanism, not a standing, user-facing edit path. If the team later wants import-time reconciliation against RMS too, that is a separate change.

## Risks / Trade-offs

- **DEFAULT can become permanently unwritable — this includes the common case, not just a narrow one.** DEFAULT spans every version at once, so it's compared against *every* ACTUAL range that exists, not just one. The moment a component has recorded **two or more different** Java (or Maven) versions across its history — which happens to any component that's ever upgraded, not an edge case — no single value written to DEFAULT can agree with all of them, so every possible write is rejected. There is no way to fall back to deleting DEFAULT either (a BASE row cannot be deleted). This was raised in review and deliberately **left as-is**: DEFAULT edits are rare in practice, and the alternative (comparing DEFAULT only against the parts of the version range not already covered by an override) adds real complexity for a rarely-hit path. Documented here so it's a known, accepted limitation rather than a surprise.
- **Deleting a conflicting override is a one-way door.** A field override that's flagged with a warning can always be deleted. But recreating it afterward with that *same*, disagreeing value is a fresh write, and gets blocked exactly like any other disagreeing write would. Someone deleting a warned-about override expecting to "put it back" if needed will find they can't restore the old (disagreeing) value — only a value that agrees with ACTUAL. Worth a clear message in the UI when this happens, rather than a silent, confusing rejection.
- **Multiple CRS instances mean separate caches.** If CRS runs on more than one replica, each one refreshes its own copy of ACTUAL independently. For a short window, two replicas could show slightly different data. This is fine — the display already tolerates being briefly out of date (Decision 7).
- **Fewer calls per sweep, but likely more data per call — net RMS load may be higher, not lower.** Decision 1's single unfiltered call replaces an earlier draft's two filtered calls, which cuts the *request count* in half. But "unfiltered" means every RC/RELEASE build ever recorded, not just ones carrying a Java/Maven value — for most components, the bulk of that history predates OCTOPUS-2256 and is all null. The payload per call is plausibly far larger than the old filtered calls were, even though there are fewer of them. This is a real, uncapped cost, not a straightforward improvement — implementation needs a cap on concurrent sweeps and a per-call timeout, and `limit`/`maxAgeBuilds`-style filters should not be reached for casually: capping the fetched window can itself hide the exact null builds the open-ended/last-build determination depends on, silently changing correctness, not just cost.
- **First load after a restart/deploy is slow.** Right after a deploy, the cache is empty and has to refresh from scratch for every component before it's warm. A one-time cost per deploy — expected, not a concern.
- **Saving now waits on a network call, mid-transaction.** Every save of `javaVersion`/`mavenVersion` has to ask RMS first, while a database transaction is still open (see the transaction hazard in Decision 9). If that call is slow, it holds a DB connection and row locks the whole time. This needs a short, strictly-enforced timeout — otherwise it's a real availability risk under load, not just a minor delay.
- **A run can bridge a stretch that was never built at all**, as long as the builds on either side agree in value (Decision 2's "consequence worth being explicit about"). This means ACTUAL can claim coverage for a version that was never itself tested, if its neighbors on both sides happen to match. Accepted deliberately, in favor of a much simpler algorithm — distinguishing "confirmed identical" from "presumed identical because nothing said otherwise" was considered and rejected as unneeded complexity here.
- **A null build always breaks a run — considered treating it as invisible instead, deliberately kept as-is.** This can fragment an otherwise-unchanged Java/Maven version around any old, untracked build, and can cancel the open-ended forward-coverage guarantee if the single most recent build for a component happens to lack data (both are common, since most components have pre-OCTOPUS-2256 history). Kept because the alternative — quietly assuming a missing build agrees with its neighbors — means inferring a value CRS was never actually told, and there is no clean, non-arbitrary rule for how far such an inference should be allowed to reach. See Decision 2 for the full reasoning.
- **The build-system gate (Decision 13) can be bypassed by changing a component's build system.** A component's build system is itself an editable, ungated field. Changing it away from `MAVEN`/`GRADLE`, writing a disagreeing Java/Maven value while the gate doesn't apply, then changing it back, stores a value that was never actually checked against ACTUAL. Considered and deliberately **not fixed** — changing a component's build system is expected to be very rare, and closing this fully would mean gating the build-system field itself, which is new scope this feature doesn't otherwise need. Documented here as a known, accepted gap, not an oversight.
- **An environment with `enabled=false` gets neither the display nor the enforcement — silently, and by design.** Per Decision 6, this is intentional and safe to roll out incrementally (no writes are ever blocked in a disabled environment), but it does mean nobody is told "this feature exists but isn't active here" — an operator who never turns it on simply never sees ACTUAL data or any blocking, with no error to notice. Worth a startup log line (informational, not a warning) stating the feature is disabled, so it isn't mistaken for "not implemented." (This risk does *not* apply to the "turned on but forgot the URL" case — Decision 11's fail-fast validation surfaces that one loudly, at startup.)
- **`"LATEST"` creates the same kind of dead-end as DEFAULT, at override level.** If ACTUAL for a range is the literal Maven token `"LATEST"`, no numbered value can ever agree with it — that range's override can only ever be written as `"LATEST"` itself. Accepted the same way as DEFAULT's dead-end (see above): documented, not specially remediated.
