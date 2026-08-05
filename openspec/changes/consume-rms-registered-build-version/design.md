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

RMS's build history, grouped by **minor version** (`major.minor` — e.g. `2.5` and `2.6` are different minors, both inside major line 2):
- Minor `2.5` has builds recording Java 1.8.
- Minor `2.6` has builds recording Java 1.8 (same value as `2.5`, and the very next minor — these two merge into one continuous range).
- Minor `2.7` has **no builds at all**.
- Minor `3.4` onward has builds recording Java 21 (the highest minor RMS has seen — see Decision 2 for why this one is open-ended).

Resulting ACTUAL ranges:
- `[2.5,2.7)` → Java 1.8 (minors `2.5` and `2.6` merged).
- `[3.4,)` → Java 21 (open-ended, highest known minor).

Consequences:
- `[2.0,2.5)` — never built — still free.
- `[2.5,2.7)` — matches the existing override's own value (1.8) — no warning, and **rewriting 1.8 there is permitted** (it agrees with ACTUAL, so the write rule's third question is "no").
- `[2.7,3.4)` — no builds at all anywhere in this stretch, including minor `2.7` itself — **entirely free**, even though it sits between two stretches that do have ACTUAL data. An unbuilt minor is always free, wherever it falls — that's the point of the uniform rule in Decision 2.
- `[3.4,)`, including anything at or beyond `4.0` (a version that doesn't exist yet) — ACTUAL = 21, open-ended. Writing anything **other than** 21 there is blocked; writing 21 itself is permitted.
- The existing OVERRIDDEN row `[3.0,4.0)` → Java 11 disagrees with ACTUAL `[3.4,)` → 21 for the overlapping part, `[3.4,4.0)`. This is **not** retroactively blocked or changed — it's shown as a **warning** naming that sub-range and ACTUAL's value (21). The one write that resolves it — setting that override to 21 — is permitted, not blocked.

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

### 2. How ACTUAL ranges are built from RMS's builds

Run separately for Java and for Maven. Three steps:

**Step 1 — Group builds by minor version.**
- A build's minor version is `major.minor` (e.g. `2.5`, `3.4`) — computed via CRS's existing `NumericVersionFactory`/`IVersionInfo` (the same tool behind `EscrowExpressionContext.getMajor()`/`getMinor()` elsewhere in CRS, not a new concept). 
- This is deliberately **minor**, not major: RMS's own build filter tracks `minors` and `lines` (major) as separate, distinct things, and CRS computes its own minor value locally rather than relying on RMS to supply one (RMS's build response doesn't include it). 
- Builds from different minors are **never** grouped together, even if they happen to record the same value.

**Step 2 — A minor with no builds at all is simply free — wherever it falls.**
- If a minor version has zero recorded builds, ACTUAL has no data for it, full stop. 
- It doesn't matter whether that minor comes before the first build, after the last one, or sits between two other minors that do have data — an unbuilt minor is never inferred or filled in from its neighbors. 
- Example: minor `2.7` has no builds, so it's free, even though `2.6` just before it and `3.4` after it both have data.

**Step 3 — Adjacent minors with the same value merge into one range; the highest one stays open-ended.**
- Two minors that are next to each other (e.g. `2.5` and `2.6`) and record the same value combine into a single range, purely so the display shows one clean range instead of many tiny ones — this doesn't change what's blocked or free, just how it's shown. 
- The **highest minor RMS has any data for** is the one exception to Step 2's boundedness: its range has no upper limit, and extends into versions that don't exist yet, until RMS reports something different there. 
- Reasoning: RMS's data is a timeline, and the newest known value is treated as still true until something newer replaces it — there's no equivalent basis for guessing what happens *before* the earliest data point, which is why only the trailing end gets this treatment.

**Comparing values.** Two builds' values only count as "the same" after normalizing:
- **Java:** the legacy `1.X` spelling and the short `X` spelling mean the same version (`1.8` = `8`, `1.7` = `7`, ...) — this generalizes the existing `JavaVersion.isEight` check (`ToolVersion.kt`) into a full rule rather than a single special case. Beyond that, RMS's actual recorded Java values are confirmed (via test fixtures) to be short forms like `"17"`/`"1.8"` — but since RMS's data is an unvalidated pass-through from an external legacy system CRS doesn't control, the normalizer also defensively takes the leading number of any longer form it might ever see (e.g. a hypothetical `"17.0.9"` would still read as major version 17), rather than assuming short forms are guaranteed forever.
- **Maven:** confirmed real values include ordinary `x.y`/`x.y.z` forms (`"3.3.6"`, `"3.3.9"`, `"4.0"`) **and the literal token `"LATEST"`**, which isn't a version number at all — it means "resolve to whatever is newest at build time" and can't be meaningfully compared against a fixed version like `"3.3.9"`. `"LATEST"` is treated as its own distinct value for equality/grouping (never silently equal to any numbered version), and — since it inherently represents "the newest possible" — it always wins the max-rollup comparison (Decision 4) against any numbered version. Numbered values otherwise compare via the same version-aware comparator CRS already uses elsewhere for Maven versions, not raw string equality.
- **Unparseable values:** if a build's version string can't be parsed by CRS's own version factory, it must **fail closed** — excluded from being silently mis-sorted by a weaker fallback comparator (the way `numericVersionComparator` in `EntityMappers.kt` degrades elsewhere in CRS today), which could otherwise place a build in the wrong minor and produce a wrong block or wrong allow. This applies to the write-time gate too (Decision 5) — the gate rebuilds the same ACTUAL ranges to check intersection, so a parsing failure there has the same correctness consequence as one in the display sweep, not just a cosmetic one.

**Hotfix builds** (`ShortBuildDTO.hotfix`) are included in ACTUAL with no special handling — a hotfix always follows its parent minor's Java/Maven version, so there's no expected case where it would introduce a spurious value change. Considered and deliberately not filtered, not overlooked.

**Implementation note:** this is a small, new function, not a reuse of `VersionRangePartition.partition` (that one solves a different problem — splitting already-known ranges around other ranges' edges, not grouping raw points into runs). It does reuse a couple of `VersionRangePartition`'s existing helper functions for consistent range formatting.

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

- `ComponentSummaryResponse` (list view, existing DTO) gets one rollup number per attribute: the maximum version seen across all of that attribute's ACTUAL ranges, comparing values using the same normalization named in Decision 2 (Java: legacy-spelling-aware major-version extraction; Maven: the existing Maven version comparator) — not raw string comparison.
- `ComponentDetailResponse` (detail view, existing DTO) gets the full per-attribute ACTUAL range list, plus warning entries on any DEFAULT/OVERRIDDEN row that disagrees with an intersecting ACTUAL range — each entry names the disagreeing sub-range and ACTUAL's value there; a row intersecting several differently-valued ACTUAL ranges gets one entry per disagreement.

**Known limitation of the max rollup, stated explicitly rather than left to be rediscovered:** "maximum" means the highest version number ever seen across *any* range, not the value of the *current* (highest-minor) range. A component whose oldest still-maintained line used Java 21 but whose current line has since moved to Java 8 will show "21" in the list view — the list view therefore cannot answer "which components are still on an old Java version," only "what's the highest version this component has ever recorded." This is an accepted trade-off (see Part A requirements), not an oversight. For Maven, `"LATEST"` (see Decision 2) always displays as the rollup value if it appears anywhere in a component's ACTUAL data, regardless of what numbered versions also exist — it's treated as "newer than any fixed number" by definition.

Warnings are computed at read time (bounded — a handful of configured rows against a handful of ACTUAL ranges per component) and are not cached separately from the ACTUAL data they're computed against. During an RMS outage, warnings are computed against the last known-good cached ACTUAL data (consistent with Decision 3's stale-but-honest retention), and suppressed only for a component that has never had a successful sweep at all.

### 5. Write-time check — live, single-attribute, strict on ambiguity

The block-override check (Part B) does not read the display cache (Decision 3). It makes its own synchronous RMS call at write time, for only the attribute being written (Decision 1).

**Only a confirmed 200-response with no matching builds counts as "ACTUAL is null → write permitted."** 

Any other outcome — 404, timeout, 5xx, connection failure — is treated as fail-closed (write rejected). This is a deliberate departure from Decision 1's read-side precedent (Portal's `ReleaseManagementClient` treats 404 as "no data"): that mapping is correct for a read-only sweep ("unknown to RMS ⇒ nothing to report") but wrong for a gate, where the same mapping would mean "unknown/misconfigured ⇒ allow the write." The client's return contract must let the two call sites — sweep and write gate — distinguish "confirmed empty" from "ambiguous/failed," since they treat the same underlying HTTP outcomes differently.

### 6. Disabled means the feature is off. Unreachable means RMS is down. These are different, and are handled differently.

- **Disabled** (`release-management-service.enabled=false`, or no URL configured): this whole feature — both Part A's display and Part B's write check — is simply **turned off**. Nothing is shown, nothing is blocked. Writing `javaVersion`/`mavenVersion` behaves exactly as it did before this feature existed. This is a deliberate, intentional configuration state (an environment that hasn't set up RMS integration, or doesn't want this feature), not a failure — so it must never be treated as an error condition.
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
- `util/RmsBuildRangeCollapser.kt` — the pure, minor-version-aware collapsing function, alongside `VersionRangePartition`.
- `dto/v4/RegisteredBuildParametersDtos.kt` — the new response DTOs (per-attribute range list + warning entries for detail; max-value rollup for summary).

`service/rms/` satisfies the existing ArchUnit rule (`ArchitectureFitnessTest.kt`: `@Service`-annotated beans must reside under `..service..` or `..teamcity..`) without a rule change. Decided: `service/rms/`, not a dedicated top-level `rms/` package — no ArchUnit rule change needed.

### 11. Configuration

A new `RmsProperties` (`@ConfigurationProperties(prefix = "release-management-service")`) follows the same "inert by default, two-gate" pattern as `EmployeeServiceProperties`: an `enabled` flag plus a blank-URL-means-unconfigured second gate. Registered in `ApplicationConfig.kt`'s `@EnableConfigurationProperties` list.

Per Decision 6, "not enabled/configured" now means the same thing for both Part A and Part B: the feature is off. Neither the display sweep nor the write gate runs; nothing is shown, nothing is blocked. This is a single, consistent meaning for the properties object's absence, not two different interpretations per feature.

No `gradle.properties` version pin is added — consistent with Decision 1, this is a runtime URL configuration, not a library dependency.

### 12. New exception types

- `RmsRegisteredValueConflictException` — ACTUAL disagrees with the value being written, for (part of) the range. Mapped to a 4xx.
- `RmsUnavailableException` — the feature is enabled, but the live check failed, timed out, or was ambiguous. Mapped to a distinguishable status (e.g. 503). Not thrown at all when the feature is disabled (Decision 6) — a disabled integration means the gate isn't invoked in the first place, not that it invokes and throws.

Both are mapped in `ControllerExceptionHandler.kt`.

## Out of Scope

- **Legacy v1–v3 API.** Confirmed read-only — no write endpoints exist outside `ComponentControllerV4` — so no gate is needed there, and they will not carry ACTUAL. v2's existing, unmodified response continues to serve the configured value straight from CRS's DB, unaffected by this change.
- **`deleteFieldOverride`.** See Decision 9 — deletion reveals state, it never writes a conflicting value.
- **The Git/DSL import path (`ImportServiceImpl`).** A one-time migration mechanism, not a standing, user-facing edit path. If the team later wants import-time reconciliation against RMS too, that is a separate change.

## Risks / Trade-offs

- **DEFAULT can become permanently unwritable — this includes the common case, not just a narrow one.** DEFAULT spans every version at once, so it's compared against *every* ACTUAL range that exists, not just one. The moment a component has recorded **two or more different** Java (or Maven) versions across its history — which happens to any component that's ever upgraded, not an edge case — no single value written to DEFAULT can agree with all of them, so every possible write is rejected. There is no way to fall back to deleting DEFAULT either (a BASE row cannot be deleted). This was raised in review and deliberately **left as-is**: DEFAULT edits are rare in practice, and the alternative (comparing DEFAULT only against the parts of the version range not already covered by an override) adds real complexity for a rarely-hit path. Documented here so it's a known, accepted limitation rather than a surprise.
- **Deleting a conflicting override is a one-way door.** A field override that's flagged with a warning can always be deleted. But recreating it afterward with that *same*, disagreeing value is a fresh write, and gets blocked exactly like any other disagreeing write would. Someone deleting a warned-about override expecting to "put it back" if needed will find they can't restore the old (disagreeing) value — only a value that agrees with ACTUAL. Worth a clear message in the UI when this happens, rather than a silent, confusing rejection.
- **Multiple CRS instances mean separate caches.** If CRS runs on more than one replica, each one refreshes its own copy of ACTUAL independently. For a short window, two replicas could show slightly different data. This is fine — the display already tolerates being briefly out of date (Decision 7).
- **This doubles how often RMS gets called, not just adds to it.** Every sweep cycle makes two calls per component (Java + Maven) — and Portal already runs its own separate, unrelated RMS sweep today. Implementation needs a cap on how many run at once and a timeout per call, not an unbounded fan-out across every component.
- **First load after a restart/deploy is slow.** Right after a deploy, the cache is empty and has to refresh from scratch for every component before it's warm. A one-time cost per deploy — expected, not a concern.
- **Saving now waits on a network call, mid-transaction.** Every save of `javaVersion`/`mavenVersion` has to ask RMS first, while a database transaction is still open (see the transaction hazard in Decision 9). If that call is slow, it holds a DB connection and row locks the whole time. This needs a short, strictly-enforced timeout — otherwise it's a real availability risk under load, not just a minor delay.
- **RMS and CRS might not agree on how to read a version string.** Some version strings RMS sends may not parse the way CRS's own `NumericVersionFactory` expects. When that happens elsewhere in CRS today, `numericVersionComparator` (`EntityMappers.kt`) quietly falls back to a weaker comparison — this feature's collapsing logic (Decision 2) inherits that same risk, and needs its own test for version strings CRS can't parse cleanly.
- **An environment without RMS configured gets neither the display nor the enforcement — silently, and by design.** Per Decision 6, this is intentional and safe to roll out incrementally (no writes are ever blocked in an unconfigured environment), but it does mean nobody is told "this feature exists but isn't active here" — an operator who forgets to configure it simply never sees ACTUAL data or any blocking, with no error to notice. Worth a startup log line (informational, not a warning) stating the feature is disabled, so it isn't mistaken for "not implemented."
