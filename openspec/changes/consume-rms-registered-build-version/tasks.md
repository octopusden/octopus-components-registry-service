## 1. Range-collapsing utility (sequential-run, per attribute)

- [x] 1.1 Write failing unit tests for `BuildRangeCollapser`:
  - [x] 1.1.1 Builds are walked once per attribute in the order given (RMS's `descending=false` guarantees ascending real version order — no independent sort/parse) — no version-format-dependent bucketing of any kind.
  - [x] 1.1.2 A run starts at the first build carrying a (normalized) non-null value.
  - [x] 1.1.3 A run extends through consecutive same-valued builds, bridging any stretch where nothing was built at all in between.
  - [x] 1.1.4 A build with a **different** non-null value ends the current run and starts a new one.
  - [x] 1.1.5 A build with a **null** value ends the current run and starts no new one.
  - [x] 1.1.6 A null build between two runs of the *same* value still splits them into two separate ranges — values never bridge across an explicit null observation.
  - [x] 1.1.7 Only a run containing the single highest-version build in the whole fetched history is open-ended, and only if that build's value is non-null.
  - [x] 1.1.8 If the highest-version build in the history is null, no run is open-ended — the last non-null run closes at its own last member.
  - [x] 1.1.9 A single-build run renders open-ended, `[x,)` — build versions in a run are always distinct, so a run's upper bound is always the *next* build's version (or nothing), never equal to its own start.
  - [x] 1.1.10 Java `"1.8"`/`"8"` compare as equal (generalizing `JavaVersion.isEight` from `ToolVersion.kt` into a full major-version-extraction rule).
  - [x] 1.1.11 A longer Java form like `"17.0.9"` reads as major version 17 (defensive normalization, not a confirmed real case).
  - [x] 1.1.12 Maven values compare via the existing Maven version comparator, not raw string equality.
  - [x] 1.1.13 The Maven token `"LATEST"` is its own distinct value (never equal to a numbered version) and always wins a max comparison against any numbered version.
  - [x] 1.1.14 Builds are trusted to arrive in ascending real version order (RMS's `descending=false` guarantee) — `collapse()` does not parse or re-sort them, and has no "unparseable build version" case.
- [x] 1.2 Implement, as separate units:
  - [x] 1.2.1 `util/BuildRangeCollapser.kt` — attribute-agnostic collapsing over the (already RMS-ordered) build list; the caller runs it twice, once per attribute, passing in that attribute's `valuesEqual`.
  - [x] 1.2.2 `util/JavaVersionComparator.kt` — major-version extraction, `valuesEqual`, and `compare` (throws on an unparseable value).
  - [x] 1.2.3 `util/MavenVersionComparator.kt` — `valuesEqual`/`compare` (version-aware, `LATEST` handling), the same shape as `JavaVersionComparator`.
  - [x] 1.2.4 A single linear walk in `collapse()`: track one "current run" (value + start), close/open runs per the rules above.
  - [x] 1.2.5 Reuse `VersionRangePartition`'s internal `Segment`/`render` helpers for consistent range rendering; `defaultVersionCompare` reused by `MavenVersionComparator`.
- [x] 1.3 Confirm tests pass. No Spring context required — these are pure functions. (`BuildRangeCollapserTest`, `JavaVersionComparatorTest`, `MavenVersionComparatorTest` all green.)

## 2. RMS client

- [x] 2.1 Add `RMSProperties`:
  - [x] 2.1.1 `@ConfigurationProperties(prefix = "release-management-service")` with an `enabled` flag.
  - [x] 2.1.2 Blank-URL-means-unconfigured as a second gate at bean-registration time (`RMSUrlConfiguredCondition`); additionally, `@Validated` + `@AssertTrue` fails application startup outright if `enabled=true` with a blank `url` — a clear configuration error rather than a silent no-op (mirrors `TeamcityValidationProperties`'s fail-fast pattern). Covered by `RMSPropertiesTest`.
  - [x] 2.1.3 Configurable sweep-timing fields: normal interval (default 4h), initial retry interval (default 5min), retry backoff cap (default = normal interval).
  - [x] 2.1.4 Register in `ApplicationConfig.kt`'s `@EnableConfigurationProperties`.
- [x] 2.2 Write failing tests for `RMSClient` (WireMock, `DefaultRMSClientTest`):
  - [x] 2.2.1 One call, unfiltered, against `GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE&descending=false` — no `javaVersionPresent`/`mavenVersionPresent` filter, since null-value builds must be visible to the collapsing algorithm; `descending=false` is what lets `BuildRangeCollapser` trust the response's ascending order without re-sorting.
  - [x] 2.2.2 The response parses `version`/`buildParameters.javaVersion`/`buildParameters.mavenVersion` for every build, including ones where either field is null, and tolerates unknown fields (`component`, `status`, `hotfix`) present on the real wire response but unused here.
  - [x] 2.2.3 A confirmed empty-builds 200 response (`Available(emptyList())`) is distinguished, in the client's return type, from any error response (404, 5xx, connection failure → `Unavailable`) — the display sweep and the write gate must be able to treat these two outcomes differently.
- [x] 2.3 Implement `RMSClient`:
  - [x] 2.3.1 `service/rms/RMSClient.kt` (interface + `RMSBuild`/`RMSBuildsResult`) and `DefaultRMSClient.kt` — thin blocking `RestClient` wrapper with a locally-owned DTO, no dependency on RMS's published `client` artifact.
  - [x] 2.3.2 One method, used identically by both the sweep and the write gate — no attribute-scoped variant, since both need the same full, unfiltered fetch.
  - [x] 2.3.3 `RMSClientConfig` — two-gate optional bean registration (`enabled` property + non-blank `url`), mirroring `EmployeeServiceConfig`/`TeamcityClientConfig`.
- [x] 2.4 Confirm tests pass. (`DefaultRMSClientTest`, 5/5 green; `RMSPropertiesTest`, 4/4 green; `compileKotlin`/`compileTestKotlin` clean.)

## 3. Sweep + cache (display)

- [x] 3.1 Write failing tests for `RMSBuildParametersService` (hand-written fake `RMSClient`/`EligibleComponentsProvider`, no mocking framework):
  - [x] 3.1.1 A full sweep populates the cache with `generatedAt`/`lastAttemptAt` and both attributes' collapsed ranges, computed from the one fetched build list per component.
  - [x] 3.1.2 A failed sweep retains previous data and sets `refreshError`.
  - [x] 3.1.3 The single-flight guard rejects an overlapping trigger.
  - [x] 3.1.4 A per-component RMS failure marks only that component unavailable, without failing the whole sweep — and only if it has never had a successful lookup; a component with prior good data keeps that stale data instead of being marked unavailable (per spec.md: "shown as unavailable only for a component that has never had a successful sweep"). Found and fixed during review: the first pass rebuilt `components` from scratch every sweep, silently dropping a component's last known-good data the moment one sweep failed for it.
  - [x] 3.1.5 A concurrency bound and per-call timeout budget are respected — 1 call × N components does not run unbounded. (Bounded `ExecutorService` sized by `sweepConcurrency`; each `Future.get` waits only the time remaining in the shared `sweepTimeout` deadline — a slow component is marked unavailable and cancelled rather than left hanging. Directly verified: max simultaneous in-flight calls never exceeds `sweepConcurrency`; total wall-clock across several slow components stays bounded by the shared `sweepTimeout`, not N × per-call duration.)
  - [x] 3.1.5a (added on review) A per-component call that throws directly, not just one returning `Unavailable`, is isolated the same way — never fails the whole sweep.
  - [x] 3.1.5b (added on review) `enabled=true` with no `RMSClient` present (defensive: the `@AssertTrue`/condition gates should prevent this, but the service checks anyway) never sweeps.
  - [x] 3.1.5c (added on review) A component with no prior data that recovers on a later sweep moves into `components`; a component *with* prior good data that fails on a later sweep keeps that stale data in `components` and is never marked unavailable (3.1.4); a component that drops out of eligibility entirely (archived, or no longer Maven/Gradle) has no entry in either map.
  - [x] 3.1.5d (added on review) After the single-flight guard rejects an overlapping call, a later non-overlapping `refresh()` still runs — the guard releases on completion, not permanently.
  - [x] 3.1.5e (added on review) Before any sweep has ever run, `nextDelay()` returns the normal interval.
  - [x] 3.1.6 Components whose build system is not `MAVEN`/`GRADLE` are skipped entirely — no RMS call made for them. (`ComponentConfigurationRepository.findNonArchivedMavenOrGradleComponentKeys()`, verified by the Testcontainers-backed `ComponentConfigurationRepositoryEligibleComponentsTest`, `@Tag("integration")` — not run locally in this environment, no Docker available; compiles cleanly, runs on CI per this repo's convention for infra-dependent suites.)
  - [x] 3.1.7 A 404 from RMS for a component during the sweep marks that component unavailable, the same as any other failure — never treated as "confirmed, no data." (Falls out of 3.1.4: RMS's client already maps a 404 to `RMSBuildsResult.Unavailable`, covered by `DefaultRMSClientTest`; the sweep treats `Unavailable` uniformly regardless of the underlying HTTP outcome, so no separate 404-specific case is needed at this layer.)
  - [x] 3.1.8 On startup, the first sweep runs immediately — it does not wait for the first scheduled interval to elapse.
  - [x] 3.1.9 After a successful sweep, the next sweep is scheduled at the normal interval (4h).
  - [x] 3.1.10 After a failed sweep, the next sweep is scheduled at the retry interval, starting at 5 minutes.
  - [x] 3.1.11 Each consecutive failure doubles the retry interval (5, 10, 20, 40 min, ...), capped at the normal interval (4h) — it never waits longer than normal cadence to retry.
  - [x] 3.1.12 A success resets the cadence to the normal interval and resets the backoff, so the next failure (if any) starts again at 5 minutes, not from wherever the previous backoff left off.
- [x] 3.2 Implement:
  - [x] 3.2.1 `RMSBuildParametersService` — sweep orchestration + `@Volatile` cache, mirroring Portal's `ValidationService`. Always registered (not conditional on the `RMSClient` bean); every operation short-circuits on `RMSProperties.enabled` (and a null `RMSClient`) rather than relying on `@ConditionalOnBean` ordering across regular `@Configuration` classes, which is fragile outside Spring Boot auto-configuration.
  - [x] 3.2.2 `RMSRefreshScheduler` — immediate first sweep on startup, then re-arms itself per the normal/retry/backoff rules above (3.1.8–3.1.12), mirroring Portal's `ValidationRefreshScheduler`'s dynamic-`Trigger` pattern with an added exponential backoff. Covered by `RMSRefreshSchedulerTest` (drives the registered `Trigger` directly with a hand-written `TriggerContext`, no Spring context needed).
  - [x] 3.2.3 `RMSProperties` gained `connectTimeout`/`readTimeout` (wired into `RMSClientConfig`'s `RestClient` via `SimpleClientHttpRequestFactory`) and `sweepConcurrency`/`sweepTimeout` (used by the sweep's bounded executor) — not in the original 2.1.3 list, added here since 3.1.5 requires them and they have no other natural home.
  - [x] 3.2.4 `EligibleComponentsProvider` (interface) / `JpaEligibleComponentsProvider` (impl) — thin seam over the new repository projection, kept separate so `RMSBuildParametersServiceTest` never touches JPA/DB.
- [x] 3.3 Confirm tests pass. (`RMSBuildParametersServiceTest` — 23/23 green, no Spring context; `RMSRefreshSchedulerTest` — 2/2 green. `ComponentConfigurationRepositoryEligibleComponentsTest` compiles; not run locally, no Docker in this environment.)

## 4. Display wiring

- [ ] 4.1 Add `RegisteredBuildParametersDtos`:
  - [ ] 4.1.1 Per-attribute ACTUAL range list + named warning entries (sub-range + ACTUAL value) for detail view.
  - [ ] 4.1.2 Normalized max-value rollup shape for summary view.
- [ ] 4.2 Wire the DTOs in:
  - [ ] 4.2.1 Rollup fields on `ComponentSummaryResponse`.
  - [ ] 4.2.2 Full range-list + warning fields on `ComponentDetailResponse`.
- [ ] 4.3 Write failing tests:
  - [ ] 4.3.1 The summary rollup is the maximum, normalized version number across all ACTUAL ranges for that attribute (Java major-version-extraction, Maven comparator — not raw string comparison).
  - [ ] 4.3.2 The rollup can reflect a numerically-higher-but-superseded line — assert the value, not that it matches the "current" line (documented limitation, not a bug).
  - [ ] 4.3.3 Maven's `"LATEST"` wins the rollup over any numbered version.
  - [ ] 4.3.4 The detail response's warnings name the correct disagreeing sub-range(s) and ACTUAL value(s).
  - [ ] 4.3.5 A row intersecting multiple disagreeing ACTUAL ranges produces multiple warning entries.
  - [ ] 4.3.6 A matching (non-disagreeing) row shows no warning.
  - [ ] 4.3.7 Each DEFAULT/OVERRIDDEN row is marked "ACTUAL data unavailable" only for a component with no successful sweep ever.
  - [ ] 4.3.8 Rows are evaluated against stale-but-good cached data when a prior sweep succeeded, even if the latest one failed.
  - [ ] 4.3.9 A component whose build system is not `MAVEN`/`GRADLE` carries no ACTUAL data, rollup, or warnings at all in its response.
- [ ] 4.4 Implement:
  - [ ] 4.4.1 `attachRegisteredBuildParameters` (detail) and the summary rollup computation in `ComponentManagementServiceImpl`, mirroring the existing `attachTeamcityValidations` post-processing pattern.
  - [ ] 4.4.2 Warning computation: intersect each DEFAULT/OVERRIDDEN row against ACTUAL, per disagreeing sub-range.
- [ ] 4.5 Confirm tests pass.

## 5. Write-gate (unified rule)

- [ ] 5.1 Add exception types:
  - [ ] 5.1.1 `RMSRegisteredValueConflictException` and `RMSUnavailableException`.
  - [ ] 5.1.2 Map both in `ControllerExceptionHandler.kt` to distinguishable HTTP statuses.
  - [ ] 5.1.3 `RMSUnavailableException` is only ever thrown while the feature is enabled (a genuine reachability/ambiguity failure) — never for the disabled case, which must not invoke the gate at all.
- [ ] 5.2 Write failing MockMvc tests (model on `ComponentFieldOverridesPatchTest.kt`, `RMSClient` mocked):
  - [ ] 5.2.1 Unchanged resend of the current value/range is never blocked, even when it disagrees with ACTUAL.
  - [ ] 5.2.2 Writing a value that matches ACTUAL is always permitted, including when it resolves an existing warning.
  - [ ] 5.2.3 Writing a disagreeing value into an intersecting non-null ACTUAL range is rejected.
  - [ ] 5.2.4 Updating only a field override's `versionRange` (value unchanged) onto ACTUAL-covered, disagreeing territory is rejected the same way.
  - [ ] 5.2.5 DEFAULT write is rejected when it disagrees with ACTUAL anywhere, including a range already fully shadowed by an existing override.
  - [ ] 5.2.6 DEFAULT becomes permanently unwritable once 2+ disagreeing ACTUAL values exist across the component's history — decided/accepted behavior (see design.md's Risks section), no shadow-aware exception is being built for it.
  - [ ] 5.2.7 Independent per-attribute gating: a Java disagreement never blocks a Maven-only write for the same range, and vice versa.
  - [ ] 5.2.8 With RMS enabled and reachable: an ambiguous/failed live RMS call (404, timeout, 5xx, connection failure) rejects the write, but only when the write actually changes `build.javaVersion`/`build.mavenVersion` — a confirmed empty-builds response is the only thing that permits it.
  - [ ] 5.2.9 With RMS unreachable: a write that touches an unrelated field, or resends `javaVersion`/`mavenVersion` unchanged, still succeeds normally — this must be tested as its own case, not assumed from 5.2.8.
  - [ ] 5.2.10 With RMS integration disabled: writes to `build.javaVersion`/`build.mavenVersion` succeed unconditionally, because the gate is never invoked — a distinct case from 5.2.8/5.2.9, not to be conflated with either.
  - [ ] 5.2.11 `deleteFieldOverride` succeeds regardless of ACTUAL — no gate call at all.
  - [ ] 5.2.12 Recreating the same range with the same, still-disagreeing value afterward is rejected like any other write.
  - [ ] 5.2.13 A write to a component whose build system is not `MAVEN`/`GRADLE` is never gated — no RMS call at all, regardless of ACTUAL data that might exist.
- [ ] 5.3 Implement `RMSOverrideGate`:
  - [ ] 5.3.1 Live RMS call, unfiltered (same shape as the sweep's), but the disagreement check evaluates only the one attribute being written.
  - [ ] 5.3.2 The unified three-condition rule: effective change + intersects non-null ACTUAL + disagrees.
  - [ ] 5.3.3 Strict fail-closed on ambiguity, but only while the feature is enabled.
  - [ ] 5.3.4 When RMS integration is disabled/unconfigured, short-circuit to "permit" before making any call — never call RMS and then decide, never throw `RMSUnavailableException`.
  - [ ] 5.3.5 When the component's build system is not `MAVEN`/`GRADLE`, short-circuit to "permit" before making any call — same short-circuit shape as the disabled case (5.3.4), just a different reason for it.
- [ ] 5.4 Wire the gate into call sites:
  - [ ] 5.4.1 `ComponentManagementServiceImpl.updateComponent` (base config, `ALL_VERSIONS` range).
  - [ ] 5.4.2 `createFieldOverride`.
  - [ ] 5.4.3 `updateFieldOverride`, evaluated against the post-write range/value.
  - [ ] 5.4.4 The bulk field-overrides apply-plan.
  - [ ] 5.4.5 Each call site invokes the gate only when the write would actually change the effective value/range.
  - [ ] 5.4.6 Explicitly do **not** wire it into `deleteFieldOverride` or `createComponent` — a new component's key can never collide with prior RMS history, since components are archived, not hard-deleted (a key is never freed up for reuse).
- [ ] 5.5 Add a timeout budget for the gate's RMS call:
  - [ ] 5.5.1 Explicit, tight timeout — shorter than the write endpoint's overall request timeout.
  - [ ] 5.5.2 Evaluate the gate as early as possible in the write path, given `ComponentManagementServiceImpl`'s class-level `@Transactional` scope, to minimize how long a DB connection/lock is held waiting on the network call.
- [ ] 5.6 Confirm tests pass.
- [ ] 5.7 Write a failing test: after `RMSOverrideGate` rejects a write with `RMSRegisteredValueConflictException`, that component's entry in `RMSBuildParametersService`'s cache is refreshed using the data from the same live call — not left stale until the next scheduled sweep.
- [ ] 5.8 Implement the targeted refresh from 5.7: on rejection, update the one component's cache entry directly (no full sweep triggered).
- [ ] 5.9 Confirm tests pass.

## 6. Docs

- [ ] 6.1 Add the new v4 response fields and error responses to the OpenAPI surface, then regenerate the committed spec: run `:components-registry-service-server:generateOpenApiDocs` to refresh `components-registry-service-server/src/main/resources/openapi/v4.json`.
- [ ] 6.2 Confirm `OpenApiV4SpecTest`'s drift gate passes against the regenerated spec.

## 7. Finalization

- [ ] 7.1 Run the full test suite and static/lint checks.
- [ ] 7.2 Confirm all new code lives under `service/rms/` — decided, not a top-level `rms/` package — so no ArchUnit rule change is needed.
- [ ] 7.3 Confirm these write surfaces were left ungated, per the stated out-of-scope boundaries:
  - [ ] 7.3.1 The Git/DSL import path (`ImportServiceImpl`).
  - [ ] 7.3.2 `deleteFieldOverride`.
  - [ ] 7.3.3 `createComponent`.
- [ ] 7.4 Add an informational (not warning) startup log line when RMS integration is disabled, so it reads as "not configured" rather than being mistaken for the feature not existing.
- [ ] 7.5 Confirm `ECLIPSE_MAVEN` components are excluded (decided: not treated as Maven-like) — test coverage should assert this explicitly, not just fall out of a `!= MAVEN && != GRADLE` check by accident.
- [ ] 7.6 Confirm two deliberately-not-fixed gaps stay documented, not silently dropped from review: null-always-breaks-a-run (design.md Decision 2 / Risks) and the build-system-change bypass (design.md Decision 13 / Risks).
