## 1. Range-collapsing utility

- [ ] 1.1 Write failing unit tests for `RmsBuildRangeCollapser` covering: adjacent builds with equal `(javaVersion, mavenVersion)` merge into one range; a single-build run renders as `[x]`; the last run is right-unbounded; a run boundary changes when either attribute changes independently; null values compare equal to null.
- [ ] 1.2 Implement `components-registry-service-server/.../util/RmsBuildRangeCollapser.kt`, reusing `VersionRangePartition`'s internal `Segment`/`render`/`parseSegment` helpers and the existing `numericVersionComparator` for ordering.
- [ ] 1.3 Confirm tests pass; no Spring context required (pure function).

## 2. RMS client

- [ ] 2.1 Add `RmsProperties` (`@ConfigurationProperties(prefix = "release-management-service")`) mirroring `EmployeeServiceProperties`'s `enabled` + blank-URL-means-unconfigured pattern; register in `ApplicationConfig.kt`.
- [ ] 2.2 Write failing tests for `RmsClient` (mocked HTTP) covering: successful `GET rest/api/1/builds/component/{component}?statuses=RC,RELEASE` parses `component`/`version`/`status`/`buildParameters.javaVersion`/`buildParameters.mavenVersion`; 404 → empty list; other errors/timeouts propagate.
- [ ] 2.3 Implement `RmsClient` as a thin blocking `RestClient` wrapper with a locally-owned DTO — no dependency on RMS's published `client` artifact.
- [ ] 2.4 Confirm tests pass.

## 3. Sweep + cache (display)

- [ ] 3.1 Write failing tests for `RmsBuildParametersService` covering: a full sweep populates the cache with `generatedAt`/`lastAttemptAt`; a failed sweep retains previous data and sets `refreshError`; single-flight guard rejects an overlapping trigger; a per-component RMS failure marks only that component unavailable without failing the whole sweep. Model on `TeamcityValidationRepeatedRunIntegrationTest.kt`.
- [ ] 3.2 Implement `RmsBuildParametersService` (sweep orchestration + `@Volatile` cache) and `RmsRefreshScheduler` (adaptive-cadence scheduled trigger), mirroring Portal's `ValidationService`/its scheduler.
- [ ] 3.3 Confirm tests pass.

## 4. Display wiring

- [ ] 4.1 Add `RegisteredBuildParametersDtos` (`RegisteredBuildParametersRange`, `RegisteredBuildParametersStatus`) in `dto/v4/`.
- [ ] 4.2 Add `registeredBuildParameters` field to `ComponentDetailResponse`.
- [ ] 4.3 Write failing tests asserting a v4 component detail response includes the new field, sourced from `RmsBuildParametersService`'s cache, and that it reports unavailable when the cache marks the component unavailable.
- [ ] 4.4 Implement `attachRegisteredBuildParameters` in `ComponentManagementServiceImpl`, called from `toDetail`, mirroring the existing `attachTeamcityValidations` post-processing pattern.
- [ ] 4.5 Confirm tests pass.

## 5. Write-gate (block override)

- [ ] 5.1 Add `RmsRegisteredValueConflictException` and `RmsUnavailableException`; map both in `ControllerExceptionHandler.kt` to distinguishable HTTP statuses.
- [ ] 5.2 Write failing MockMvc tests (model on `ComponentFieldOverridesPatchTest.kt`, `RmsClient` mocked) covering, for both base-config edits and field-override edits: RMS registered value null for the range → edit allowed; RMS registered value non-null and intersecting → edit rejected with `RmsRegisteredValueConflictException`; RMS unreachable → edit rejected with `RmsUnavailableException`; a registered value on Java does not block a Maven-only edit for the same range, and vice versa.
- [ ] 5.3 Implement `RmsOverrideGate` (live RMS call + collapse + intersect check, fail-closed on any error).
- [ ] 5.4 Wire the gate into `ComponentManagementServiceImpl.updateComponent` (base config, ALL_VERSIONS range), `createFieldOverride`, `updateFieldOverride`, and the bulk field-overrides apply-plan — each call only when the write actually changes the value.
- [ ] 5.5 Confirm tests pass.

## 6. Docs

- [ ] 6.1 Regenerate the v4 OpenAPI spec (if this repo checks one in) to include the new response field and the new error responses.

## 7. Finalization

- [ ] 7.1 Run full test suite and static/lint checks.
- [ ] 7.2 Resolve, or explicitly re-flag for the team, the open ArchUnit package-naming question from `design.md` (`service/rms/` vs. a dedicated top-level `rms/` package).
- [ ] 7.3 Confirm the Git/DSL import path (`ImportServiceImpl`) was left untouched, per the stated out-of-scope boundary.
