## 1. Range-collapsing utility (sequential-run, per attribute)

- [ ] 1.1 Write failing unit tests for `RmsBuildRangeCollapser`:
  - [ ] 1.1.1 Builds are sorted by real version order (existing comparator), then walked once per attribute — no version-format-dependent bucketing of any kind.
  - [ ] 1.1.2 A run starts at the first build carrying a (normalized) non-null value.
  - [ ] 1.1.3 A run extends through consecutive same-valued builds, bridging any stretch where nothing was built at all in between.
  - [ ] 1.1.4 A build with a **different** non-null value ends the current run and starts a new one.
  - [ ] 1.1.5 A build with a **null** value ends the current run and starts no new one.
  - [ ] 1.1.6 A null build between two runs of the *same* value still splits them into two separate ranges — values never bridge across an explicit null observation.
  - [ ] 1.1.7 Only a run containing the single highest-version build in the whole fetched history is open-ended, and only if that build's value is non-null.
  - [ ] 1.1.8 If the highest-version build in the history is null, no run is open-ended — the last non-null run closes at its own last member.
  - [ ] 1.1.9 A single-build run renders as `[x]`.
  - [ ] 1.1.10 Java `"1.8"`/`"8"` compare as equal (generalizing `JavaVersion.isEight` from `ToolVersion.kt` into a full major-version-extraction rule).
  - [ ] 1.1.11 A longer Java form like `"17.0.9"` reads as major version 17 (defensive normalization, not a confirmed real case).
  - [ ] 1.1.12 Maven values compare via the existing Maven version comparator, not raw string equality.
  - [ ] 1.1.13 The Maven token `"LATEST"` is its own distinct value (never equal to a numbered version) and always wins a max comparison against any numbered version.
  - [ ] 1.1.14 A version string CRS's own `NumericVersionFactory` cannot parse is excluded/flagged, not silently mis-ordered via the fallback comparator.
- [ ] 1.2 Implement `components-registry-service-server/.../util/RmsBuildRangeCollapser.kt`:
  - [ ] 1.2.1 Two independent passes — one for Java, one for Maven — each over the same sorted build list.
  - [ ] 1.2.2 A single linear walk per pass: track one "current run" (value + start), close/open runs per the rules above.
  - [ ] 1.2.3 Reuse `VersionRangePartition`'s internal `Segment`/`render`/`parseSegment` helpers for consistent range rendering.
- [ ] 1.3 Confirm tests pass. No Spring context required — this is a pure function.

## 2. RMS client

- [ ] 2.1 Add `RmsProperties`:
  - [ ] 2.1.1 `@ConfigurationProperties(prefix = "release-management-service")` with an `enabled` flag.
  - [ ] 2.1.2 Blank-URL-means-unconfigured as a second gate.
  - [ ] 2.1.3 Register in `ApplicationConfig.kt`'s `@EnableConfigurationProperties`.
- [ ] 2.2 Write failing tests for `RmsClient` (mocked HTTP):
  - [ ] 2.2.1 One call, unfiltered, against `GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE` — no `javaVersionPresent`/`mavenVersionPresent` filter, since null-value builds must be visible to the collapsing algorithm.
  - [ ] 2.2.2 The response parses `component`/`version`/`status`/`buildParameters.javaVersion`/`buildParameters.mavenVersion` for every build, including ones where either field is null.
  - [ ] 2.2.3 A confirmed empty-builds 200 response is distinguished, in the client's return type, from any error response (404, timeout, 5xx, connection failure) — the display sweep and the write gate must be able to treat these two outcomes differently.
- [ ] 2.3 Implement `RmsClient`:
  - [ ] 2.3.1 Thin blocking `RestClient` wrapper with a locally-owned DTO — no dependency on RMS's published `client` artifact.
  - [ ] 2.3.2 One method, used identically by both the sweep and the write gate — no attribute-scoped variant, since both need the same full, unfiltered fetch.
- [ ] 2.4 Confirm tests pass.

## 3. Sweep + cache (display)

- [ ] 3.1 Write failing tests for `RmsBuildParametersService`:
  - [ ] 3.1.1 A full sweep populates the cache with `generatedAt`/`lastAttemptAt` and both attributes' collapsed ranges, computed from the one fetched build list per component.
  - [ ] 3.1.2 A failed sweep retains previous data and sets `refreshError`.
  - [ ] 3.1.3 The single-flight guard rejects an overlapping trigger.
  - [ ] 3.1.4 A per-component RMS failure marks only that component unavailable, without failing the whole sweep.
  - [ ] 3.1.5 A concurrency bound and per-call timeout budget are respected — 1 call × N components does not run unbounded.
  - [ ] 3.1.6 Components whose build system is not `MAVEN`/`GRADLE` are skipped entirely — no RMS call made for them.
- [ ] 3.2 Implement:
  - [ ] 3.2.1 `RmsBuildParametersService` — sweep orchestration + `@Volatile` cache, mirroring Portal's `ValidationService`.
  - [ ] 3.2.2 `RmsRefreshScheduler` — adaptive-cadence scheduled trigger, mirroring Portal's scheduler for it.
- [ ] 3.3 Confirm tests pass.

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
  - [ ] 5.1.1 `RmsRegisteredValueConflictException` and `RmsUnavailableException`.
  - [ ] 5.1.2 Map both in `ControllerExceptionHandler.kt` to distinguishable HTTP statuses.
  - [ ] 5.1.3 `RmsUnavailableException` is only ever thrown while the feature is enabled (a genuine reachability/ambiguity failure) — never for the disabled case, which must not invoke the gate at all.
- [ ] 5.2 Write failing MockMvc tests (model on `ComponentFieldOverridesPatchTest.kt`, `RmsClient` mocked):
  - [ ] 5.2.1 Unchanged resend of the current value/range is never blocked, even when it disagrees with ACTUAL.
  - [ ] 5.2.2 Writing a value that matches ACTUAL is always permitted, including when it resolves an existing warning.
  - [ ] 5.2.3 Writing a disagreeing value into an intersecting non-null ACTUAL range is rejected.
  - [ ] 5.2.4 Updating only a field override's `versionRange` (value unchanged) onto ACTUAL-covered, disagreeing territory is rejected the same way.
  - [ ] 5.2.5 DEFAULT write is rejected when it disagrees with ACTUAL anywhere, including a range already fully shadowed by an existing override.
  - [ ] 5.2.6 DEFAULT becomes permanently unwritable once 2+ disagreeing ACTUAL values exist across the component's history — decided/accepted behavior (see design.md's Risks section), no shadow-aware exception is being built for it.
  - [ ] 5.2.7 Independent per-attribute gating: a Java disagreement never blocks a Maven-only write for the same range, and vice versa.
  - [ ] 5.2.8 With RMS enabled and reachable: an ambiguous/failed live RMS call (404, timeout, 5xx, connection failure, or an unparseable build version) rejects the write, but only when the write actually changes `build.javaVersion`/`build.mavenVersion` — a confirmed empty-builds response is the only thing that permits it.
  - [ ] 5.2.9 With RMS unreachable: a write that touches an unrelated field, or resends `javaVersion`/`mavenVersion` unchanged, still succeeds normally — this must be tested as its own case, not assumed from 5.2.8.
  - [ ] 5.2.10 With RMS integration disabled: writes to `build.javaVersion`/`build.mavenVersion` succeed unconditionally, because the gate is never invoked — a distinct case from 5.2.8/5.2.9, not to be conflated with either.
  - [ ] 5.2.11 `deleteFieldOverride` succeeds regardless of ACTUAL — no gate call at all.
  - [ ] 5.2.12 Recreating the same range with the same, still-disagreeing value afterward is rejected like any other write.
  - [ ] 5.2.13 A write to a component whose build system is not `MAVEN`/`GRADLE` is never gated — no RMS call at all, regardless of ACTUAL data that might exist.
- [ ] 5.3 Implement `RmsOverrideGate`:
  - [ ] 5.3.1 Live RMS call, unfiltered (same shape as the sweep's), but the disagreement check evaluates only the one attribute being written.
  - [ ] 5.3.2 The unified three-condition rule: effective change + intersects non-null ACTUAL + disagrees.
  - [ ] 5.3.3 Strict fail-closed on ambiguity, but only while the feature is enabled.
  - [ ] 5.3.4 When RMS integration is disabled/unconfigured, short-circuit to "permit" before making any call — never call RMS and then decide, never throw `RmsUnavailableException`.
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
- [ ] 7.5 Resolve, or explicitly re-flag for the team, the open `ECLIPSE_MAVEN` question from design.md Decision 13 (treat as Maven-like, or exclude) before implementation depends on an assumed answer.
