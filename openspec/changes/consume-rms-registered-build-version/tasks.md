## 1. Range-collapsing utility (per attribute)

- [ ] 1.1 Write failing unit tests for `RmsBuildRangeCollapser` covering, per attribute independently: a leading gap (no build before the first value) stays uncovered; a run starts exactly at its first build; a run ends where the next differently-valued build starts; the last run is open-ended (no upper bound); a single-build run renders as `[x]`; null values compare equal to null; a version string CRS's own `NumericVersionFactory` cannot parse is handled explicitly (not silently mis-ordered via the comparator's fallback).
- [ ] 1.2 Implement `components-registry-service-server/.../util/RmsBuildRangeCollapser.kt` as two independent passes (Java, Maven), reusing `VersionRangePartition`'s internal `Segment`/`render`/`parseSegment` helpers and the existing `numericVersionComparator` for ordering.
- [ ] 1.3 Confirm tests pass; no Spring context required (pure function).

## 2. RMS client

- [ ] 2.1 Add `RmsProperties` (`@ConfigurationProperties(prefix = "release-management-service")`) with `enabled` + blank-URL-means-unconfigured gates for the display path; register in `ApplicationConfig.kt`.
- [ ] 2.2 Write failing tests for `RmsClient` (mocked HTTP) covering: a `javaVersionPresent=true` call and a separate `mavenVersionPresent=true` call against `GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE`, each parsing `component`/`version`/`status`/`buildParameters.javaVersion`/`buildParameters.mavenVersion`; a confirmed empty-builds 200 response is distinguished from any error response (404, timeout, 5xx, connection failure) in the client's return type/contract — the client must not collapse these into the same outcome, since the two call sites (display sweep vs. write gate) need to treat them differently (see Decision 5 in design.md).
- [ ] 2.3 Implement `RmsClient` as a thin blocking `RestClient` wrapper with a locally-owned DTO — no dependency on RMS's published `client` artifact.
- [ ] 2.4 Confirm tests pass.

## 3. Sweep + cache (display)

- [ ] 3.1 Write failing tests for `RmsBuildParametersService` covering: a full sweep populates the cache with `generatedAt`/`lastAttemptAt` and both attributes' collapsed ranges; a failed sweep retains previous data and sets `refreshError`; single-flight guard rejects an overlapping trigger; a per-component RMS failure marks only that component unavailable without failing the whole sweep. Model on `TeamcityValidationRepeatedRunIntegrationTest.kt`.
- [ ] 3.2 Implement `RmsBuildParametersService` (sweep orchestration + `@Volatile` cache) and `RmsRefreshScheduler` (adaptive-cadence scheduled trigger), mirroring Portal's `ValidationService`/its scheduler.
- [ ] 3.3 Confirm tests pass.

## 4. Display wiring

- [ ] 4.1 Add `RegisteredBuildParametersDtos`: a per-attribute ACTUAL range list + per-row warning flag for detail view, and a max-value rollup shape for summary view.
- [ ] 4.2 Add the rollup fields to `ComponentSummaryResponse` and the full range-list + warning fields to `ComponentDetailResponse`.
- [ ] 4.3 Write failing tests covering: the summary rollup is the maximum version number across all ACTUAL ranges for that attribute (not the most recent range's value); the detail response's warning flag is set exactly when a DEFAULT/OVERRIDDEN row intersects a disagreeing ACTUAL range, and unset when they agree or don't intersect; ACTUAL reports unavailable when the cache marks the component unavailable.
- [ ] 4.4 Implement `attachRegisteredBuildParameters` (detail) and the summary rollup computation in `ComponentManagementServiceImpl`, mirroring the existing `attachTeamcityValidations` post-processing pattern; implement the warning computation (intersect each DEFAULT/OVERRIDDEN row against ACTUAL, compare values).
- [ ] 4.5 Confirm tests pass.

## 5. Write-gate (block override)

- [ ] 5.1 Add `RmsRegisteredValueConflictException` and `RmsUnavailableException`; map both in `ControllerExceptionHandler.kt` to distinguishable HTTP statuses.
- [ ] 5.2 Write failing MockMvc tests (model on `ComponentFieldOverridesPatchTest.kt`, `RmsClient` mocked) covering: OVERRIDDEN write allowed when ACTUAL is null for that range; OVERRIDDEN write rejected when ACTUAL is non-null and intersecting; DEFAULT write rejected when ACTUAL is non-null anywhere, including a range already fully shadowed by an existing override; a Java-only ACTUAL value does not block a Maven-only write for the same range, and vice versa; an ambiguous/failed live RMS call (404, timeout, 5xx, connection failure) rejects the write — only a confirmed empty-builds response permits it; RMS integration disabled/unconfigured rejects the write; `deleteFieldOverride` succeeds regardless of ACTUAL (no gate call at all).
- [ ] 5.3 Implement `RmsOverrideGate` (live RMS call + collapse + intersect check; strict on ambiguity per Decision 5; treats disabled/unconfigured the same as unreachable per Decision 6).
- [ ] 5.4 Wire the gate into `ComponentManagementServiceImpl.updateComponent` (base config, `ALL_VERSIONS` range), `createFieldOverride`, `updateFieldOverride`, and the bulk field-overrides apply-plan — each call only when the write actually changes the value. Explicitly do **not** wire it into `deleteFieldOverride`.
- [ ] 5.5 Confirm tests pass.

## 6. Docs

- [ ] 6.1 Regenerate the v4 OpenAPI spec (if this repo checks one in) to include the new response fields and the new error responses.

## 7. Finalization

- [ ] 7.1 Run full test suite and static/lint checks.
- [ ] 7.2 Resolve, or explicitly re-flag for the team, the open ArchUnit package-naming question from `design.md` (`service/rms/` vs. a dedicated top-level `rms/` package).
- [ ] 7.3 Confirm the Git/DSL import path (`ImportServiceImpl`) and `deleteFieldOverride` were both left untouched, per the stated out-of-scope boundaries.
