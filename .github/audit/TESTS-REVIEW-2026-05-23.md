# CRS Test-Suite Audit — 2026-05-23

## Scope

- Repository: `octopus-components-registry-service`
- Branch audited: `v3`, local `HEAD` at commit `71fcc9e6` (== `origin/v3` tip after `git fetch origin v3 && git pull --ff-only`)
- Artifact-SHA reconciliation: TC build #3598 (`[h] octopus-components-registry-service / [1.0] Compile & UT [AUTO]`) was assembled on commit `b8b1a022`, which is **5 commits behind** current HEAD (`b95ceb0b`, `bdf83181`, `9352614e`, `59802847`, `71fcc9e6` — all touch only compat-test infra, local-stand scripts, and `.teamcity/settings.kts`). Production-code coverage and per-test durations in the artifact remain valid for the rest of the audit; the only new test class added since the artifact (`VersionSamplerLoaderTest.kt`) is small and does not change findings.
- Audit input artifact: `~/Downloads/_h_octopus-components-registry-service_1.0_Compile_UT_AUTO_2.0.84-3598_artifacts.zip` unpacked under `/tmp/crs-tc-artifacts-3598/`. JaCoCo present for 7 modules; per-module Gradle test HTML reports present for 8 unit-test source sets + `integrationTest` for server.
- Goal: identify removable / mergeable tests on v3 with a primary safety proof per category, and propose a PR-split. Build optimisation is included as an optional Pass 5 — the user explicitly framed the current 10–17 min CI time as "not a problem, large project has many tests".

Paths below are plain code-style references (no Markdown links), per the `feedback_audit_report_pattern` convention.

## Baseline (Phase 0)

All counts produced from the repo root with `_wt/**`, `.claude/worktrees/**`, `**/build/**` excluded (raw `find` from repo root without these excludes includes nested worktree checkouts and triples some numbers — historical Explore-agent figures of "153 kt + 20 groovy + 147 parameterized" came from an un-pruned scan and are NOT the baseline here). The local worktree used to author this audit happens to be at `main/`; the exact directory name doesn't affect reproducibility.

### File counts

- Kotlin test files under `*/src/test/**`: **118** (canonical list: `/tmp/crs-kt-tests.txt`).
- Groovy test files under `*/src/test/groovy/**`: **24** (20 in `component-resolver-core`, 4 in `component-resolver-api`).
- Kotlin files with `@ParameterizedTest`: **18** (canonical: `rg -l '@ParameterizedTest' --glob '*.kt' --glob '!_wt/**' --glob '!.claude/worktrees/**' --glob '!**/build/**' .`).

### Skip annotations on HEAD

- Kotlin `@Disabled` (class-level): **1**.
  - `components-registry-service-server/src/test/kotlin/.../migration/FtDbProfileWriteTest.kt:43` — `@Disabled("Phase 6: depends on auto-migrate seeding — re-enable when MIG-039 lands the §6 import pipeline …")`. 2 `@Test` methods in the class. Comment at `:39` claims the companion `FtDbProfileTest.kt:36` is "already @Disabled for the same reason" — that comment is stale; `FtDbProfileTest` is NOT `@Disabled` (verified by grep on the file).
- Groovy `@Ignore` (method-level): **2**.
  - `component-resolver-core/src/test/groovy/.../escrow/ConfigLoaderTest.groovy:77` — `stressTest()` 10 000-iter perf test.
  - `component-resolver-core/src/test/groovy/.../escrow/resolvers/MavenArtifactResolverTest.groovy:41` — `testProdConfig()` with hard-coded Windows path `C:\projects\escrow\components-registry\...`.
- JUnit `assumeTrue` / `Assumptions.*` runtime skips: present in `components-registry-compat-test/src/test/kotlin/.../CompatibilityTestBase.kt:44`, `TraceReplayCompatTest.kt:220/227/241/276`, `VersionSampler.kt` (gate "compat URLs not configured"). All inside the compat-test module, which is itself task-`SKIPPED` in the standard CI pipeline (build #3598 confirms `Task :components-registry-compat-test:test SKIPPED`).

### TC vs grep reconciliation

TC build #3598 reports `Tests passed: 1772, ignored: 8`. Per-module Gradle HTML totals sum to **1083 tests / 5 ignored** (server-test 725/3, server-integrationTest 3/0, resolver-core 210/2, resolver-api 20/0, registry-api 2/0, automation 12/0, dsl 24/0, service-client 85/0, light-client 2/0). The 1772 vs 1083 (and 8 vs 5) gap likely originates from JUnit XML aggregation counting test invocations differently from the per-class HTML "tests" column (parametrised expansion in JUnit reports is per-invocation in XML; the gradle HTML index sometimes shows the same as method count). This audit does not block on resolving the exact gap — `ignored: 8` vs `5 grep'd` is captured as an open item; the additional 3 ignored most likely sit inside JUnit XML aggregation outside the per-class HTML columns observed.

### Per-module timing (artifact, build #3598)

Module → tests / ignored / duration:
- `components-registry-service-server` (`test`): 725 / 3 / **34.080s**
- `components-registry-service-server` (`integrationTest`): 3 / 0 / **1m26.10s** (FatJar boot)
- `component-resolver-core`: 210 / 2 / **1m16.43s**
- `components-registry-service-client`: 85 / 0 / 3.972s
- `components-registry-dsl`: 24 / 0 / 21.667s
- `component-resolver-api`: 20 / 0 / 1.185s
- `components-registry-automation`: 12 / 0 / 8.309s
- `components-registry-api`: 2 / 0 / 0.721s
- `components-registry-service-light-client`: 2 / 0 / 0.984s

Hot single tests (artifact):
- `org.octopusden.octopus.components.registry.server.FatJarAuthDisabledIntegrationTest`: 2 invocations, **1m3.76s**
- `org.octopusden.octopus.components.registry.server.FatJarStartupIntegrationTest`: 1 invocation, **22.341s**
- `org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoaderTest` (Groovy, in `configuration/loader/` package): 23 invocations, **47.162s**
- `org.octopusden.octopus.escrow.resolvers.BuildToolResolverTest` (Groovy): 9 invocations, **10.919s**
- `org.octopusden.octopus.components.registry.server.service.impl.MigrateHistoryIntegrationTest`: 2 invocations, **6.328s**
- `org.octopusden.octopus.escrow.resolvers.ReleaseInfoResolverTest` (Groovy): 6 invocations, **5.634s**
- `org.octopusden.octopus.components.registry.server.service.impl.GitTagResolverTest`: 4 invocations, **3.412s**
- `org.octopusden.octopus.escrow.resolvers.EscrowConfigurationLoaderTest` (Groovy, in `resolvers/` package): 51 invocations, **3.453s**

Highest-invocation tests (artifact):
- `org.octopusden.octopus.components.registry.server.service.impl.MigrationLifecycleGateTest`: **107** invocations, 0.135s — fast and fine-grained.
- `org.octopusden.octopus.escrow.resolvers.EscrowConfigurationLoaderTest` (Groovy): 51 / 3.453s.
- `org.octopusden.octopus.components.registry.server.mapper.ComponentSummaryMapperTest`: 28 / 0.009s.
- `org.octopusden.octopus.components.registry.server.service.impl.HistoryMigrationJobServiceImplTest`: 27 / 0.066s.
- `org.octopusden.octopus.components.registry.server.mapper.ComponentDetailMapperTest`: 26 / 0.005s.

## Pass 1 — `@Disabled` / `@Ignore` + Groovy legacy

### Skip-annotation resolutions

| Position | Annotation | Reason | Decision | Risk |
|---|---|---|---|---|
| `components-registry-service-server/src/test/kotlin/.../migration/FtDbProfileWriteTest.kt:43` | class-level `@Disabled` | depends on MIG-039 import pipeline; last touched in commit `39c90d13` ("Phase 6: defer FtDbProfileWriteTest to MIG-039") — MIG-039 §6 has since landed in `0c563755`/`51ea4937`, but this specific dependency was not re-enabled in those commits, so the assumption "still blocked" should be verified by an experimental re-enable before deletion | **keep+document** (in ledger) — additionally schedule a small experimental PR to re-enable and observe; if the import pipeline now seeds enough fixture data, the `@Disabled` can be lifted | low |
| `component-resolver-core/src/test/groovy/.../escrow/ConfigLoaderTest.groovy:77` (`stressTest()`) | method-level `@Ignore` | 10 000-iteration perf loop, always disabled | **delete** (the method, not the class — class still has live tests) | low |
| `component-resolver-core/src/test/groovy/.../escrow/resolvers/MavenArtifactResolverTest.groovy:41` (`testProdConfig()`) | method-level `@Ignore` | hard-coded `C:\projects\escrow\...` Windows path, fundamentally not portable | **delete** (the method) | low |

Cleanup task for the audit ledger: the stale comment in `FtDbProfileWriteTest.kt:39` that claims `FtDbProfileTest` is `@Disabled` is wrong (`FtDbProfileTest.kt` has no skip annotation) and should be corrected when the file is next touched.

### Groovy legacy delta (24 files under `*/src/test/groovy/**`)

Analysis: per Groovy file, identify the production class under test, search for a Kotlin "double" (unit + integration), and decide based on whether the Kotlin coverage holds the same assertions.

**Post-review result (PR #291 + deep-dive subagent):** **0 file-level deletes safe now**; all 24 Groovy files become **keep+rewrite** (~2500 LOC port backlog) — port to Kotlin in PR-H before removal. The earlier "8 deletes" verdict from the initial Pass 1 subagent was systematically too lenient: every flagged Groovy file holds at least one unique assertion or unique resource fixture that no Kotlin/Java test asserts at any layer (unit, mapper, controller, or HTTP IT). Copilot review on PR #291 caught 3 such gaps directly (MavenArtifactResolverTest's `artifactId.trim()` fixture, ConfigLoaderTest's ambiguous-config validation paths, JiraParametersResolverTest's 5 untested methods); a follow-up deep-dive on the remaining 5 candidates found the same pattern for ComponentByJiraProjectResolverTest, ToolsInfoResolverTest, ReleaseInfoResolverTest, EscrowModeResolverTest, and BuildParametersTest.

**Safe** removal at file level requires the Kotlin port to land first; this is the PR-H workstream.

Per-file rationale for the 8 originally-flagged-delete files (now demoted):

| Groovy file | Unique coverage that prevents file-level deletion |
|---|---|
| `component-resolver-core/src/test/groovy/.../escrow/ConfigLoaderTest.groovy` | Only test for `ambiguousJiraConfig.groovy`, `ambiguousVCSConfig.groovy`, `invalidModuleConfig.groovy`, `invalidAttributeInSubComponent.groovy` validation error paths (Copilot finding). |
| `component-resolver-core/src/test/groovy/.../escrow/resolvers/MavenArtifactResolverTest.groovy` | Only test using `artifactIdWithWhitespace.groovy` fixture — exercises `artifactId.trim()` in EscrowConfigurationLoader (Copilot finding). |
| `component-resolver-core/src/test/groovy/.../escrow/resolvers/ComponentByJiraProjectResolverTest.groovy` | Only test for `getComponentByJiraProject`, `getVersionControlSystemRootsByJiraProject` (with branch-format + version-range boundaries), `getComponentConfig` — none of these methods exercised by any Kotlin/Java test. |
| `component-resolver-core/src/test/groovy/.../escrow/resolvers/ToolsInfoResolverTest.groovy` | Only assertion `tools == [BuildEnv, AndroidSdk]` with `installScript = "androidSdkInstaller"` through `resolveRelease()`. Other tests use `bcomponent.groovy` but don't assert the full tool list. |
| `component-resolver-core/src/test/groovy/.../escrow/resolvers/ReleaseInfoResolverTest.groovy` | `ReleaseInfoResolver.resolveRelease()` has no Kotlin/Java coverage at all. 5 distinct assertion classes: explicit/external distribution + multi-artifact GAV (`production/Aggregator.groovy`); per-range `hotfixBranch` ("hotfix:1.6"); excluded-distribution error path (`validation/invalid/Aggregator.groovy`); `PTKProductTool` instance through resolver (HTTP layer asserts beans, not internal type); DEB/RPM + GAV mappings (`deb-rpm/Aggregator.groovy`). |
| `component-resolver-core/src/test/groovy/.../escrow/resolvers/JiraParametersResolverTest.groovy` | Removing leaves only `JiraParametersResolverWithConfigTest` covering `resolveComponent(ComponentVersion)`. Untouched: `getComponentByMavenArtifact`, `getComponentByJiraProject`, `getVersionControlSystemRootsByJiraProject`, `isComponentWithJiraParametersExists`, `getComponentConfig()` (Copilot finding). |
| `component-resolver-core/src/test/groovy/.../escrow/resolvers/EscrowModeResolverTest.groovy` | Only test exercising `EscrowGenerationMode.AUTO/MANUAL/UNSUPPORTED` through `ReleaseInfoResolver.resolveRelease()` end-to-end. Loader tests assert `EscrowGenerationMode` at config-load time but not via the resolver pipeline. Fixture `escrowmode/Aggregator.groovy` exclusive. |
| `component-resolver-core/src/test/groovy/.../escrow/configuration/model/BuildParametersTest.groovy` | Only test for `BuildParameters.getSystemPropertiesMap()` parsing of `-Da=b -DjavaVersion=1.8 -Dfindbugs.skip=true`. Zero Kotlin/Java coverage. |

Also demoted from "delete" earlier in the review chain (pre-Copilot):

- `component-resolver-core/src/test/groovy/.../escrow/resolvers/DocConfigurationLoaderTest.groovy` (67 LOC) — `Doc.component` validation assertions are unique to this file.
- `component-resolver-core/src/test/groovy/.../escrow/resolvers/BuildToolResolverTest.groovy` (166 LOC) — `BuildToolsV2CompatTest.kt` runs cross-stand parity (SKIPPED in CI without compat URLs) and `mapper/BuildToolBeansMapperTest.kt` only covers entity→bean. Neither covers product mapping, override-version, empty-tool, distribution entities, or `ignoreRequired`.
- `component-resolver-api/src/test/groovy/.../escrow/config/JiraComponentVersionRangeTest.groovy` (44 LOC, path is `component-resolver-api`, not `core`) — `ComponentRoutingResolverProjectNotFoundTest.kt` only mocks `JiraComponentVersionRange`; does NOT cover `JiraComponentVersionRangeFactory.create(...)` or `range.jiraComponentVersion`.

**Net-net for Pass 1:** the only safe file-level removal that lands on v3 right now is the **two method-level `@Ignore` cleanups** (see Pass 1 skip-annotation table — `ConfigLoaderTest#stressTest()` and `MavenArtifactResolverTest#testProdConfig()`). Everything at file level waits for the Kotlin port (PR-H).

Keep+rewrite — all 24 Groovy files split by module (port to Kotlin in PR-H before removal):

- Resolver-core (`component-resolver-core/src/test/groovy/...`, 20 files):
  `escrow/ConfigLoaderTest.groovy` (post-Copilot demote), `escrow/labels/ValidLabelsTest.groovy`, `escrow/labels/InvalidLabelsTest.groovy`, `escrow/resolvers/ComponentByJiraProjectResolverTest.groovy` (post-Copilot demote), `escrow/resolvers/DocConfigurationLoaderTest.groovy`, `escrow/resolvers/EscrowConfigurationLoaderTest.groovy` (861-LOC — biggest, unique VCS-inheritance + version-range overlap assertions), `escrow/resolvers/EscrowModeResolverTest.groovy` (post-Copilot demote), `escrow/resolvers/BuildToolResolverTest.groovy`, `escrow/resolvers/JiraParametersResolverTest.groovy` (post-Copilot demote), `escrow/resolvers/MavenArtifactResolverTest.groovy` (post-Copilot demote), `escrow/resolvers/ReleaseInfoResolverTest.groovy` (post-Copilot demote), `escrow/resolvers/ToolsInfoResolverTest.groovy` (post-Copilot demote), `escrow/resolvers/VersionResolverTest.groovy`, `escrow/resolvers/RepositoryResolverTest.groovy`, `escrow/copyright/DefaultCopyrightTest.groovy`, `escrow/copyright/NonDefaultCopyrightTest.groovy`, `escrow/configuration/loader/EscrowConfigurationLoaderTest.groovy` (231-LOC, in a different package — the one taking **47.162s** to run; heaviest single test class in the build), `escrow/configuration/model/BuildParametersTest.groovy` (post-Copilot demote), `escrow/configuration/validation/GroovySlurperConfigValidatorTest.groovy`, `escrow/configuration/validation/VersionRangeTest.groovy`.
- Resolver-api (`component-resolver-api/src/test/groovy/...`, 4 files):
  `escrow/ModelConfigPostProcessorTest.groovy`, `escrow/model/DependencyTest.groovy`, `escrow/model/SystemPropertiesParserTest.groovy`, `escrow/config/JiraComponentVersionRangeTest.groovy`.

Note about two `EscrowConfigurationLoaderTest.groovy` files: same class name in two different packages (`escrow/resolvers/` 861 LOC and `escrow/configuration/loader/` 231 LOC). Both genuinely unique — the resolvers-package one is broader (VCS inheritance, version ranges), the loader-package one is the loader's contract test (heavier per-test cost, 47s total). Keep both for now; flag for **consolidation** as part of the Kotlin port (one class testing one prod loader).

## Pass 2 — v1 / v2 / v3 contract-duplicates

Catalog: 13 `*V2CompatTest.kt` files in `components-registry-compat-test/src/test/kotlin/.../compat/` plus 5 mapper-test classes and the resolver pair in `components-registry-service-server/src/test/kotlin/.../`. Each potential pair was compared at the assertion level (PRIMARY proof), with JaCoCo as supporting evidence (only available for non-compat modules — `components-registry-compat-test` was `SKIPPED` in build #3598 and has no JaCoCo output in the artifact, as expected).

### Result: 0 deletes, 0 merges

For every pair (`*V2CompatTest.kt` vs `Controller*Test.kt` / `BaseComponentsRegistryServiceTest.kt`), the assertions are **complementary, not duplicative**:

- The V2CompatTest layer asserts **cross-stand symmetry**: baseline-stand response ≡ candidate-stand response, with diff classification via `CompatibilityTestBase` + `DiffCollector` + `known-deltas.json`. It catches "v3 broke v2's wire format".
- The server-side `Test*` layer asserts **fixture correctness**: a specific input produces a specific known DTO. It catches "the code wrote the wrong value".

These two failure modes are independent. Collapsing them into one parametrised test would conflate "I broke compat with prod" with "I broke a fixture-level invariant" and lose signal. Decision per pair: **keep separate**.

The 5 mapper-test classes (`BuildToolBeansMapperTest.kt`, `ComponentDetailMapperTest.kt`, `ComponentSummaryMapperTest.kt`, `DistributionEntityMapperTest.kt`, `MIG047V4FieldOverrideMapperTest.kt`) each cover a distinct mapper API call. `ComponentDetailMapperTest` and `ComponentSummaryMapperTest` share some fixture-builder code (`minimalComponent()`, `baseConfigFor()`, junction-entity setup) — about ~40 lines of duplicated builder logic, **refactor-only** opportunity (extract `MapperTestFixtures.kt`).

The two `DatabaseComponentRegistryResolver*Test.kt` classes (`DatabaseComponentRegistryResolverTest.kt` 19 invocations + `DatabaseComponentRegistryResolverMavenArtifactsRangeTest.kt` 10 invocations) test **different methods on the same resolver** (`toResolvedEscrowModuleConfig` breadth vs `getMavenArtifactParameters` per-range GAV regression). Genuinely complementary, not duplicates.

### Bottleneck (not deletion-related)

Compat tests require live baseline + candidate stands (`-Pcompat.baseline.url`, `-Pcompat.candidate.url`) and were `SKIPPED` in build #3598. If the candidate stand is not consistently available, signal accumulates silently — see `feedback_compat_test_infra_review_protocol`. The structural pattern is sound; the operability of "always-on compat" is the real follow-up, outside the scope of this audit.

## Pass 3 — Tests for dead / migrated production code

Deleted production classes since 2025-06-01 (from `git log --diff-filter=D --name-only --since='2025-06-01' -- '*.kt' '*.groovy' '*.java'`, filtered to `src/main/**`):

- Group A — schema-v1 JPA entities + repositories (15 classes): `BuildConfigurationEntity`, `ComponentVersionEntity`, `DistributionArtifactEntity`, `DistributionEntity`, `EscrowConfigurationEntity`, `FieldOverrideEntity`, `JiraComponentConfigEntity`, `VcsSettingsEntity`, and their `*Repository` counterparts.
- Group B — misc: `OverrideApplicator`, `TeamcityClient`, `SpaWebConfig`.

For each, grep on `*/src/test/**` for direct imports / type references.

### Result: 0 deletes, 0 redirects

All grep hits resolved to one of:

- A docstring / comment referencing the v1→v2 migration context for provenance (e.g., `components-registry-service-server/src/test/kotlin/.../mapper/ComponentSummaryMapperTest.kt:28-31`, `mapper/DistributionEntityMapperTest.kt:21-34`, `compat/RawArraySorters.kt:93-94`). The actual imports use live v2 entity classes only.
- A comment-only mention in `components-registry-service-server/src/test/kotlin/.../controller/TeamcityResyncControllerTest.kt:45` — the `@MockBean` is `TeamcitySyncJobService` (live), not `TeamcitySyncClient` (deleted).
- A reference to `EscrowConfigurationLoader` (still live at `component-resolver-core/src/main/groovy/.../EscrowConfigurationLoader.groovy`, injected into `ImportServiceImpl.kt:94`).

`@Suppress("UnusedPrivateProperty")` annotations on `@MockBean` fields (e.g., `authServerClient` in multiple tests) are the documented `octopus-quality` exception for required-but-unread Spring beans, not a staleness signal.

The `components-registry-service-light-client/src/test/java/.../ComponentsRegistryServiceLightClientTest.java` and the Kotlin `MockMvcRegistryTestSupport.kt:57` both exercise `getSupportedGroupIds` — but the Java test goes through the Feign HTTP client and the Kotlin support test goes through MockMvc directly. Different transport layers; not duplicates.

`.github/audit/REVIEW-2026-05-22.md` flagged comment-only edits in `GitVsDbValidationTest.kt:388,390` for the docs-cleanup pass — those are not dead-test signals, just stale path references.

## Pass 4 — Parameterised-test bloat

`@ParameterizedTest` files (canonical 18 in Kotlin) + the parametrised Groovy tests in resolver-core. Cross-referenced with per-class invocation counts and durations from the TC artifact.

### Findings

- **`MigrationLifecycleGateTest.kt`** — 107 invocations, 0.135s total (~1.3ms each). High count, but cheap per invocation. The high cardinality maps to a Cartesian product of lifecycle states × commands; each combination is a distinct branch. **Decision: keep.** No bloat to shrink.
- **`ComponentSummaryMapperTest.kt` (28) + `ComponentDetailMapperTest.kt` (26)** — mostly `@ParameterizedTest` over distribution / system / label / rowType variants. Each parameter point asserts a distinct mapper branch. **Decision: keep.** Refactor opportunity is to extract shared fixture builders (Pass 2) — does not reduce invocation count.
- **`EscrowConfigurationLoaderTest.groovy` (`configuration/loader/` pkg)** — 23 invocations, **47.162s** (≈2s each, heaviest single test class in the build). Each invocation loads a Groovy config and validates schema. This is the **single largest opportunity** to reduce wall-clock test time. Two sub-options:
  - **shrink-source** in Pass-E: review whether all 23 fixture configs assert unique paths, drop redundant ones, target 10–12 cases.
  - **split off** into a separate `slowTest` source set that runs only on `mainOrTag` CI build, not on every PR.
- **`EscrowConfigurationLoaderTest.groovy` (`resolvers/` pkg)** — 51 invocations, 3.453s. Healthy throughput; keep.
- **`HistoryMigrationJobServiceImplTest.kt`** — 27 invocations, 0.066s. Fine.
- **`BuildToolResolverTest.groovy`** — 9 invocations, **10.919s** (≈1.2s each). Groovy with Mercurial/CVS branches; one of the historical-flake reasons JUnit parallel is disabled. The post-review demotion (see Pass 1) means this file stays as **keep+rewrite**, not delete — Kotlin doubles do NOT cover its assertions. The 10.9s saving only materialises after a Kotlin port lands.

### Compat-test parametrisation

`VersionSampler.componentVersionPairs` is the multiplier in the 13 V2CompatTests. Build #3598 had the compat task `SKIPPED`, so we have no live invocation counts. The plan said to verify `-Pcompat.sample.size` or similar gate — the relevant property is `compat.sample.size` (read by `VersionSampler`) and `compat.parallelism` (8 threads default per `feedback_compat_test_infra_review_protocol`). Both already in place; no parametrisation bloat to fix.

## Pass 5 — Build quick-wins (optional)

The user explicitly framed 10–17 min CI as "not a problem". This section is informational, not a recommendation to implement now.

Configuration on the audited HEAD `71fcc9e6` (pre-#292 — the state when this audit was written):

- `gradle.properties` had no `org.gradle.caching` or `org.gradle.configuration-cache` — neither build-cache nor configuration-cache was enabled. Heap is capped at `-Xmx2g` (GitHub-runner constraint, see `gradle.properties:5-9`).
- `build.gradle:194-199` explicitly sets `'junit.jupiter.execution.parallel.enabled': false` due to historical flakes in `EscrowConfigurationLoaderTest` + `BuildToolResolverTest` on GH-hosted runners.
- 11 separate `PostgreSQLContainer("postgres:16-alpine")` instances boot across the server module — one per test class. Each container start is on the order of seconds; this is the dominant testcontainer cost.

(PR #292 since landed `org.gradle.caching=true`; see Pass 5 quick-win #1 below for the post-#292 state.)

Quick-wins, ordered by ratio of `expected-saving / risk`:

1. **Build cache**. Lowest risk, easiest to validate (one CI run with vs without, compare TC timings on identical commits). Expected: warm-cache savings on repeat builds, no first-build benefit. **Status:** done in PR #292 (`org.gradle.caching=true`); local validation showed cold 2m 17s → warm 58s. **Configuration cache deferred** — initial attempt to set `org.gradle.configuration-cache=true` failed immediately with 3 serialization problems rooted in `:components-registry-automation:zipMetarunners` (a `Zip` task captures a `Project` reference at configuration time, which Gradle's configuration cache cannot serialise). PR #292 leaves `org.gradle.configuration-cache=false` with an inline comment pointing at the blocker; re-enabling requires refactoring `zipMetarunners` to not capture `Project` at config time. Tracked via memory note.
2. **Shared `PostgreSQLContainer` singleton in `test-common`**. Replace 11 per-class instances with one JVM-wide container (truncate-between-tests via SQL, not container restart). Expected: visible reduction in server test setup time (each container start currently is part of the per-class 5–34s overhead). Risk: needs to keep schema isolation between tests that mutate it — use Flyway `clean` + migrate per test class, or per-test transactional rollback.
3. **New `unitTest` source set with JUnit parallel enabled**. Leave the existing `test` task untouched (its historical flakes are exactly what `parallel.enabled=false` is for). New source set has pure Kotlin unit tests with no Spring / no testcontainers / no Groovy; safe to parallelize. Expected: trim minutes off the mapper/util test runtime. Risk: requires per-class triage to decide which class belongs in `unitTest` vs `test`.
4. **TC build chain split**. Currently `teamcity-snapshot/` defines a single step `clean build publish dockerPushImage`. Splitting into `assemble` → `unitTest ∥ integrationTest ∥ compatTest(gated)` parallel branches on different agents could collapse wall-clock time, at the cost of additional agent slots and per-step setup overhead. **Outside this audit's scope; defer.**

## Cross-branch invariants

Per `project_crs_deploy_via_merge` (v3 → main eventual merge) and `project_crs_compat_consumers` (7 consumer repos on `chore/crs-ft-db` branch), every PR in the split below must hold these invariants:

- No deletion or rename in `test-common/src/main/**` or `test-common/src/testFixtures/**` — only additions. Verify with `git diff origin/v3..HEAD -- test-common/src` (after rebase).
- `compatibilityReporter` task name, `compat.baseline.url` / `compat.candidate.url` / `compat.parallelism` properties, `known-deltas.json` format — none of these change.
- Gradle config changes in `build.gradle` / `gradle.properties` (Pass 5) must apply cleanly on `main`; verify with `git cherry-pick --no-commit` onto local `origin/main` snapshot, then `./gradlew help` + `./gradlew tasks --all`, then `git reset --hard`.
- No renaming of prod or test classes inside PR-A..E. Renames go in separate PRs with explicit "no rename, just delete" justification (consumer-repo compat-test code resolves by class name).

## Proposed PR split

Total work after the post-review pass: **0 Groovy files** safe-to-delete now at file level; **24 ports + then-delete** queued for the PR-H Kotlin-port workstream (~2500 LOC). Near-term concrete wins are limited to method-level `@Ignore` cleanup (~30 LOC) and the build-cache PR (#292, already landed). No Kotlin tests delete in Passes 2/3. The optional `ComponentDetail/Summary` mapper fixture refactor stays as PR-G (~40 LOC).

| PR | Branch | Scope | Files | Risk | Sequencing |
|---|---|---|---|---|---|
| A2 | `chore/tests-ftdb-profile-write-reenable-attempt` | Experimental: lift the class-level `@Disabled` on `FtDbProfileWriteTest.kt`. May revert if MIG-039 still gates it; per memory `project_schema_v2_phase6_remaining`. | 1 | med | optional, independent |
| B | `chore/tests-drop-groovy-legacy-8` (PR #291, now downscoped) | Delete only the two `@Ignore`'d Groovy methods: `ConfigLoaderTest.stressTest()` and `MavenArtifactResolverTest.testProdConfig()` (and the `org.junit.Ignore` imports they were the only users of). Plus fix the stale comment in `FtDbProfileWriteTest.kt:39`. The parent Groovy files stay in place (demoted to keep+rewrite by Copilot review + deep-dive subagent — see Pass 1 table). | 3 | low | independent |
| C | _(not used — Pass 2 produced no delete/merge candidates)_ | — | 0 | — | — |
| D | _(not used — Pass 3 produced no candidates)_ | — | 0 | — | — |
| E | `chore/tests-shrink-loader-fixtures` | Review the 23 fixtures consumed by `escrow/configuration/loader/EscrowConfigurationLoaderTest.groovy` (47s class). If 10+ are pure variations on the same loader branch, drop the redundant fixtures. Otherwise leave it and move the test to a separate `slowTest` task. **Note:** must happen as part of, or after, the Kotlin port of that file (PR-H) — modifying live Groovy fixtures while the file itself is also in flight is too risky. | 1 + fixtures | med | after PR-H entry for this file |
| F | `chore/build-cache-quick-wins` (PR #292) | Add `org.gradle.caching=true` to `gradle.properties`. `org.gradle.configuration-cache=true` was attempted but reverted in the same PR due to `:components-registry-automation:zipMetarunners` capturing `Project` at config time — left as `=false` with an inline comment + follow-up note. Validated locally: cold 2m 17s → warm 58s. | 1 | low | **done** |
| G | `refactor/mapper-fixture-helper` (optional) | Extract `minimalComponent()` / `baseConfigFor()` / junction-entity builders from `ComponentDetailMapperTest.kt` + `ComponentSummaryMapperTest.kt` into `test-common` or a sibling `MapperTestFixtures.kt`. Pure refactor, no assertion changes. | 3 | low | independent |
| H | `chore/tests-port-groovy-to-kotlin` (multi-PR follow-up, scoped per Groovy file) | Port all **24 keep+rewrite** Groovy tests to Kotlin, one or two per PR. After each port lands green, the corresponding Groovy file is deleted in the same PR. Highest priority: the two `EscrowConfigurationLoaderTest.groovy` files (47s + 3.5s combined wall-clock) and the 5 files Copilot flagged as having unique coverage (`ConfigLoaderTest`, `MavenArtifactResolverTest`, `JiraParametersResolverTest`, `ComponentByJiraProjectResolverTest`, `ReleaseInfoResolverTest`). | 1–2 per PR | med | sequential, large effort, separate workstream |

Order: B and G in parallel (low-risk); A2 independent and experimental; PR-F is done. PR-H is the long-term workstream and gates PR-E.

PR-A2 (re-enable attempt for `FtDbProfileWriteTest`) is the only "experimental" PR — explicitly framed as "may revert if still red".

## Verification

For each PR at execution time:

- `./gradlew build` with `AUTH_SERVER` env set, `-x docker* -x oc* -x components-registry-automation:test` per `project_crs_full_build_excludes`. Full build, not partial (`feedback_full_build_before_commit`).
- Independent Sonnet review of the diff before push (`feedback_pr_review_via_subagent`).
- For PR-B (method-level `@Ignore` cleanup): after merge, re-run TC build and verify `Tests passed` is unchanged (`stressTest` and `testProdConfig` were `@Ignore`'d and never counted as passing), and `ignored` drops by exactly **2** (from 8 → 6). `failures: 0` must hold.

- For PR-H (per-file Kotlin port): the PR that ports a Groovy file to Kotlin and removes the original must preserve the same number of assertions. Use the per-file invocation-count table below as the target for the Kotlin replacement, captured from `/tmp/crs-tc-artifacts-3598/reports/component-resolver-core/build/reports/tests/test/index.html`:

  | Groovy file (slated for port) | Invocations | Duration |
  |---|---|---|
  | `escrow/configuration/loader/EscrowConfigurationLoaderTest.groovy` | 23 | **47.162s** (highest priority — wall-clock win after port) |
  | `escrow/resolvers/EscrowConfigurationLoaderTest.groovy` | 51 | 3.453s |
  | `escrow/resolvers/JiraParametersResolverTest.groovy` | 14 | 1.679s |
  | `escrow/ConfigLoaderTest.groovy` | 12 | 0.504s (`stressTest` already removed by PR-B) |
  | `escrow/resolvers/RepositoryResolverTest.groovy` | (see report) | 1.5s |
  | `escrow/resolvers/MavenArtifactResolverTest.groovy` | 10 | 0.509s (`testProdConfig` already removed by PR-B) |
  | `escrow/resolvers/BuildToolResolverTest.groovy` | 9 | **10.919s** (second-highest wall-clock win) |
  | `escrow/resolvers/ReleaseInfoResolverTest.groovy` | 6 | 5.634s |
  | `escrow/resolvers/ComponentByJiraProjectResolverTest.groovy` | 5 | 0.508s |
  | `escrow/resolvers/EscrowModeResolverTest.groovy` | 4 | 0.354s |
  | `escrow/resolvers/ToolsInfoResolverTest.groovy` | 1 | 0.039s |
  | `escrow/configuration/model/BuildParametersTest.groovy` | 1 | 0.005s |
  | …plus other resolver-core + resolver-api files (see Pass 1 list) | | |

  After each port-PR, `Tests passed` should remain the same (or increase if the Kotlin port adds boundary cases the original Groovy missed), and `ignored` should not increase.

- For PR-E (loader-fixture shrink, after that file is ported): count the exact invocations dropped at the **ported** Kotlin replacement of `escrow/configuration/loader/EscrowConfigurationLoaderTest.groovy` and assert the post-merge TC totals match. Additionally run `./gradlew :components-registry-compat-test:test` against a local stand (`project_crs_compat_consumers`) to confirm baseline-vs-candidate parity, since `EscrowConfigurationLoader` is the integration boundary the compat-test consumes.
- For PR-F: one CI run cache-on vs cache-off on the same commit; compare TC build durations (not local timings).
- Cross-branch check before merge: `git cherry-pick --no-commit` of each PR onto an `origin/main` snapshot, then `./gradlew help` + `./gradlew tasks --all`. Reset with `git reset --hard` after the check. This guards the `main`-branch invariants documented above.

## Open items not actioned in this audit

- TC `ignored: 8` vs grep'd 5 — 3 invocations unaccounted for. Likely method-level annotations on additional `@Test` methods within FtDbProfileWriteTest or JUnit XML aggregation differences. Not blocking the deletes proposed above.
- `FtDbProfileWriteTest` re-enable: blocked since `39c90d13`. Experimental PR-A2 will say whether MIG-039 §6 actually unblocks it now (`51ea4937`, `0c563755` landed §6.1-6.8 + import pipeline, but never re-enabled this test).
- Compat-test always-on environment: orthogonal to test count, but mentioned by Pass 2 as the actual signal-loss risk on the v3 cutover. Tracked separately via `feedback_compat_test_infra_review_protocol`.
