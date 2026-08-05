## 1. Range-collapsing utility (minor-version-aware, per attribute)

- [ ] 1.1 Write failing unit tests for `RmsBuildRangeCollapser` covering: builds are grouped by **minor version** (`major.minor`, via `NumericVersionFactory`/`IVersionInfo`) before collapsing by value; a minor with zero builds is free wherever it falls (before the first build, after the last one, or between two data-bearing minors) — including the specific case of two data-bearing minors that agree in value with an empty minor between them (the empty one must still be free, not silently bridged); adjacent, equal-valued minors merge into one rendered range; only the single highest data-bearing minor's range is open-ended; a single-build minor renders as `[x]`; Java `"1.8"`/`"8"` compare as equal (generalizing `JavaVersion.isEight` from `ToolVersion.kt` into a full major-version-extraction rule, including the defensive case of a longer form like `"17.0.9"` reading as major version 17); Maven values compare via the existing Maven version comparator, not raw string equality; a version string CRS's own `NumericVersionFactory` cannot parse is excluded/flagged rather than silently mis-ordered via the fallback comparator.
- [ ] 1.2 Implement `components-registry-service-server/.../util/RmsBuildRangeCollapser.kt` as two independent passes (Java, Maven), each minor-grouped then value-collapsed, reusing `VersionRangePartition`'s internal `Segment`/`render`/`parseSegment` helpers.
- [ ] 1.3 Confirm tests pass; no Spring context required (pure function).

## 2. RMS client

- [ ] 2.1 Add `RmsProperties` (`@ConfigurationProperties(prefix = "release-management-service")`) with `enabled` + blank-URL-means-unconfigured gates; register in `ApplicationConfig.kt`.
- [ ] 2.2 Write failing tests for `RmsClient` (mocked HTTP) covering: a `javaVersionPresent=true` call and a separate `mavenVersionPresent=true` call against `GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE`, each parsing `component`/`version`/`status`/`buildParameters.javaVersion`/`buildParameters.mavenVersion`; a confirmed empty-builds 200 response is distinguished, in the client's return type, from any error response (404, timeout, 5xx, connection failure) — the two call sites (display sweep vs. write gate) must be able to treat these differently.
- [ ] 2.3 Implement `RmsClient` as a thin blocking `RestClient` wrapper with a locally-owned DTO — no dependency on RMS's published `client` artifact. Support querying a single attribute (for the write gate) as well as both (for the sweep).
- [ ] 2.4 Confirm tests pass.

## 3. Sweep + cache (display)

- [ ] 3.1 Write failing tests for `RmsBuildParametersService` covering: a full sweep populates the cache with `generatedAt`/`lastAttemptAt` and both attributes' minor-version-aware collapsed ranges; a failed sweep retains previous data and sets `refreshError`; single-flight guard rejects an overlapping trigger; a per-component RMS failure marks only that component unavailable without failing the whole sweep; a concurrency bound and per-call timeout budget are respected (2 calls × N components does not run unbounded). Model on `TeamcityValidationRepeatedRunIntegrationTest.kt`.
- [ ] 3.2 Implement `RmsBuildParametersService` (sweep orchestration + `@Volatile` cache) and `RmsRefreshScheduler` (adaptive-cadence scheduled trigger), mirroring Portal's `ValidationService`/its scheduler.
- [ ] 3.3 Confirm tests pass.

## 4. Display wiring

- [ ] 4.1 Add `RegisteredBuildParametersDtos`: a per-attribute ACTUAL range list + named warning entries (sub-range + ACTUAL value) for detail view, and a normalized max-value rollup shape for summary view.
- [ ] 4.2 Add the rollup fields to `ComponentSummaryResponse` and the full range-list + warning fields to `ComponentDetailResponse`.
- [ ] 4.3 Write failing tests covering: the summary rollup is the maximum, normalized version number across all ACTUAL ranges for that attribute (using the Java major-version-extraction and Maven comparator from task 1.1, not raw string comparison) — including a test asserting this rollup can reflect a numerically-higher-but-superseded line (documented limitation, not a bug — assert the value, don't assert it matches the "current" line); the detail response's warnings name the correct disagreeing sub-range(s) and ACTUAL value(s), including a row that intersects multiple disagreeing ACTUAL ranges (multiple warning entries); a matching (non-disagreeing) row shows no warning; ACTUAL and warnings report unavailable only for a component with no successful sweep ever, and are served from stale-but-good cached data otherwise.
- [ ] 4.4 Implement `attachRegisteredBuildParameters` (detail) and the summary rollup computation in `ComponentManagementServiceImpl`, mirroring the existing `attachTeamcityValidations` post-processing pattern; implement warning computation (intersect each DEFAULT/OVERRIDDEN row against ACTUAL, per disagreeing sub-range).
- [ ] 4.5 Confirm tests pass.

## 5. Write-gate (unified rule)

- [ ] 5.1 Add `RmsRegisteredValueConflictException` and `RmsUnavailableException`; map both in `ControllerExceptionHandler.kt` to distinguishable HTTP statuses. Ensure the disabled/unconfigured case logs/reports distinctly from a genuine RMS-unreachable failure.
- [ ] 5.2 Write failing MockMvc tests (model on `ComponentFieldOverridesPatchTest.kt`, `RmsClient` mocked) covering the unified rule end to end:
  - Unchanged resend of the current value/range is never blocked, even when it disagrees with ACTUAL.
  - Writing a value that matches ACTUAL is always permitted, including when it resolves an existing warning.
  - Writing a disagreeing value into an intersecting non-null ACTUAL range is rejected.
  - Updating only a field override's `versionRange` (value unchanged) onto ACTUAL-covered, disagreeing territory is rejected the same way.
  - DEFAULT write is rejected when it disagrees with ACTUAL anywhere, including a range already fully shadowed by an existing override — and a test asserting that DEFAULT becomes permanently unwritable once 2+ disagreeing ACTUAL values exist across the component's history (decided/accepted behavior, not a bug — see design.md's Risks section; no shadow-aware exception is being built for this).
  - Independent per-attribute gating (a Java disagreement never blocks a Maven-only write for the same range, and vice versa).
  - `createComponent`'s `baseConfiguration.build.javaVersion`/`mavenVersion` is gated the same as an update.
  - An ambiguous/failed live RMS call (404, timeout, 5xx, connection failure, or an unparseable build version among the results) rejects the write — only a confirmed empty-builds response permits it.
  - RMS integration disabled/unconfigured rejects the write.
  - `deleteFieldOverride` succeeds regardless of ACTUAL (no gate call at all); recreating the same range with the same, still-disagreeing value afterward is rejected like any other write (delete does not grant a right to restore a disagreeing value).
- [ ] 5.3 Implement `RmsOverrideGate`: live, single-attribute RMS call; unified three-condition rule (effective change + intersects non-null ACTUAL + disagrees); strict fail-closed on ambiguity; treats disabled/unconfigured the same as unreachable but logs distinctly.
- [ ] 5.4 Wire the gate into `ComponentManagementServiceImpl.createComponent`, `updateComponent` (base config, `ALL_VERSIONS` range), `createFieldOverride`, `updateFieldOverride` (evaluated against the post-write range/value), and the bulk field-overrides apply-plan — each call only when the write would actually change the effective value/range. Explicitly do **not** wire it into `deleteFieldOverride`.
- [ ] 5.5 Add an explicit, tight timeout budget for the gate's RMS call, shorter than the write endpoint's overall request timeout, and evaluate it as early as possible in the write path given `ComponentManagementServiceImpl`'s class-level `@Transactional` scope (minimize how long a DB connection/lock is held waiting on the network call).
- [ ] 5.6 Confirm tests pass.

## 6. Docs

- [ ] 6.1 Regenerate the v4 OpenAPI spec (if this repo checks one in) to include the new response fields and the new error responses.

## 7. Finalization

- [ ] 7.1 Run full test suite and static/lint checks.
- [ ] 7.2 Resolve, or explicitly re-flag for the team, the open ArchUnit package-naming question from `design.md` (`service/rms/` vs. a dedicated top-level `rms/` package).
- [ ] 7.3 Confirm the Git/DSL import path (`ImportServiceImpl`) and `deleteFieldOverride` were both left untouched, per the stated out-of-scope boundaries.
- [ ] 7.4 Confirm the rollout note (RMS config must land in/before this deploy in every environment) is captured somewhere operators will see it before this ships.
