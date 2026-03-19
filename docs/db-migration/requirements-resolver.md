# Resolver Behavior Requirements

## Status

**Draft** | Date: 2026-03-18

---

## Summary Table

| ID | Title | Priority | Layer | Status |
|----|-------|----------|-------|--------|
| RES-001 | All Jira component version ranges returned correctly | High | integration-test | ⏳ Git-only |
| RES-002 | Supported group IDs returned | Medium | integration-test | ⏳ Git-only |
| RES-003 | Component-to-product-type mapping | Medium | integration-test | ⏳ Git-only |
| RES-004 | Version names configuration | Medium | integration-test | ⏳ Git-only |
| RES-005 | Dependency alias mapping | Medium | integration-test | ⏳ Git-only |
| RES-006 | Full component tree (V1 API) | High | integration-test | ⏳ Git-only |
| RES-007 | Sub-component with parent reference (V1) | High | integration-test | ⏳ Git-only |
| RES-008 | Detailed component with build/VCS/Jira | High | integration-test | ⏳ Git-only |
| RES-009 | Versioned component across 11 version formats | High | integration-test | ⏳ Git-only |
| RES-010 | Batch version query | Medium | integration-test | ⏳ Git-only |
| RES-011 | VCS settings across 10 version formats | High | integration-test | ⏳ Git-only |
| RES-012 | VCS settings for hotfix branches | High | integration-test | ⏳ Git-only |
| RES-013 | Distribution with GAV, security groups | High | integration-test | ⏳ Git-only |
| RES-014 | Build tools resolution | Medium | integration-test | ⏳ Git-only |
| RES-015 | Jira component version (8 parametrized cases) | High | integration-test | ⏳ Git-only |
| RES-016 | Jira component by project key + version | High | integration-test | ⏳ Git-only |
| RES-017 | Jira components for a project | Medium | integration-test | ⏳ Git-only |
| RES-018 | Jira version ranges by project | Medium | integration-test | ⏳ Git-only |
| RES-019 | Distributions by Jira project | Medium | integration-test | ⏳ Git-only |
| RES-020 | VCS settings by project (5 cases) | High | integration-test | ⏳ Git-only |
| RES-021 | Distribution by project (4 cases) | Medium | integration-test | ⏳ Git-only |
| RES-022 | Archived flag per component | Medium | integration-test | ⏳ Git-only |
| RES-023 | Maven artifact parameters (3 cases) | Medium | integration-test | ⏳ Git-only |
| RES-024 | Find component by single artifact | High | integration-test | ⏳ Git-only |
| RES-025 | Find components by artifacts batch (V3) | High | integration-test | ⏳ Git-only |

---

## Requirements

### RES-001: All Jira component version ranges returned correctly

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/common/jira-component-version-ranges` returns the complete set of
Jira component version ranges matching the expected JSON fixture.

**Preconditions:**
- Components registry is loaded with test data containing multiple Jira-configured components
- Expected fixture `expected-data/jira-component-version-ranges.json` is present

**Acceptance criteria:**
1. Response collection matches expected JSON fixture (bidirectional containment)
2. Ranges are sorted by `componentName + versionRange`
3. No extra or missing ranges compared to fixture

**Test method:** `BaseComponentsRegistryServiceTest.testGetAllJiraComponentVersionRanges` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-002: Supported group IDs returned

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/common/supported-groups` returns the configured set of supported
Maven group IDs.

**Preconditions:**
- Configuration defines supported groups `org.octopusden.octopus` and `io.bcomponent`

**Acceptance criteria:**
1. Response contains exactly `["io.bcomponent", "org.octopusden.octopus"]` (sorted)
2. No additional or missing group IDs

**Test method:** `BaseComponentsRegistryServiceTest.testGetSupportedGroupIds` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-003: Component-to-product-type mapping

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/common/component-product-mapping` returns correct ProductTypes
per component.

**Preconditions:**
- Component `TEST_PT_K_DB` is configured with product type `PT_K`

**Acceptance criteria:**
1. Response map contains `{"TEST_PT_K_DB": "PT_K"}`
2. No extra entries in the mapping

**Test method:** `BaseComponentsRegistryServiceTest.testGetComponentProductMapping` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-004: Version names configuration

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/common/version-names` returns configured serviceBranch, service,
and minor names.

**Preconditions:**
- Version names are configured in the registry

**Acceptance criteria:**
1. `serviceBranch = "serviceCBranch"`
2. `service = "serviceC"`
3. `minor = "minorC"`

**Test method:** `BaseComponentsRegistryServiceTest.testVersionNames` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-005: Dependency alias mapping

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/common/dependency-aliases` returns alias-to-component mapping.

**Preconditions:**
- Dependency aliases `alias1 -> sub1` and `alias2 -> sub2` are configured

**Acceptance criteria:**
1. Response map equals `{"alias1": "sub1", "alias2": "sub2"}` (sorted)
2. No extra or missing aliases

**Test method:** `BaseComponentsRegistryServiceTest.testGetDependencyAliasToComponentMapping` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-006: Full component tree (V1 API)

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/1/components/{name}` returns the complete component with distribution,
escrow, metadata, labels, and system information.

**Preconditions:**
- Component `TESTONE` exists with displayName `"Test ONE display name"`, componentOwner `"adzuba"`
- Component has distribution, escrow, releaseManager, securityChampion, system, clientCode, labels configured

**Acceptance criteria:**
1. Response contains correct scalar fields: name, displayName, componentOwner
2. `distribution` includes explicit/external flags, GAV, securityGroups, docker
3. `escrow` includes buildTask, providedDependencies, additionalSources, isReusable, generation
4. `releaseManager = "user"`, `securityChampion = "user"`
5. `system` contains `["ALFA", "CLASSIC"]`
6. `clientCode = "CLIENT_CODE"`, `releasesInDefaultBranch = false`, `solution = true`
7. `copyright = "companyName1"`, `labels` contains `"Label2"`

**Test method:** `BaseComponentsRegistryServiceTest.testGetComponentV1` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-007: Sub-component with parent reference (V1)

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/1/components/{subName}` returns a sub-component with correct
parentComponent reference.

**Preconditions:**
- Sub-component `versions-api` exists with parent `TESTONE`

**Acceptance criteria:**
1. Response contains `parentComponent = "TESTONE"`
2. Sub-component inherits parent's system, clientCode, releaseManager, securityChampion, copyright, labels
3. Distribution and other fields are correctly populated for the sub-component

**Test method:** `BaseComponentsRegistryServiceTest.testGetSubComponentV1` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-008: Detailed component with build/VCS/Jira

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/components/{name}/versions/{ver}` returns a DetailedComponent
with buildSystem, VCS settings, Jira component version, distribution, escrow,
build parameters, and detailed version breakdown.

**Preconditions:**
- Component `TESTONE` exists with version `1`
- Component has MERCURIAL VCS, PROVIDED build system, Jira project key `TESTONE`

**Acceptance criteria:**
1. `buildSystem = PROVIDED`
2. `vcsSettings` contains a root with `vcsPath = "ssh://hg@mercurial/test-component"`, `type = MERCURIAL`
3. `jiraComponentVersion` has correct projectKey, displayName, componentVersionFormat
4. `detailedComponentVersion` includes line, minor, release, RC, build, hotfix versions
5. `distribution`, `escrow`, `buildParameters` (javaVersion, tools) are correctly populated
6. `deprecated = false`, `buildFilePath = "build"`

**Test method:** `BaseComponentsRegistryServiceTest.testGetDetailedComponent` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-009: Versioned component across 11 version formats

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/components/{name}/versions/{ver}/detailed-version` returns the
correct DetailedComponentVersion for all 11 version format variants: build (`3.0.0`),
Jira-prefixed build (`sub1k-3.0.0`), line (`3`), Jira-prefixed line (`sub1k-3`),
minor (`3`), Jira-prefixed minor (`sub1k-3`), RC (`3.0_RC`), Jira-prefixed RC
(`sub1k-3.0_RC`), Jira-prefixed release (`sub1k-3.0`), release (`3.0`), hotfix (`3.0.0-0`).

**Preconditions:**
- Component `SUB` exists with version prefix `sub1k-`
- All version types resolve to the same DetailedComponentVersion

**Acceptance criteria:**
1. All 11 version strings resolve to the same `DetailedComponentVersion`
2. `component = "PPROJECT WITH CLIENT COMPONENT"`
3. Each version type (line, minor, build, RC, release, hotfix) has correct `ComponentRegistryVersion`

**Test method:** `BaseComponentsRegistryServiceTest.testGetDetailedComponentVersion` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-010: Batch version query

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
POST `/rest/api/2/components/{name}/detailed-versions` returns correct version
mapping for a batch request.

**Preconditions:**
- Component `SUB` exists with version `3.0.0`

**Acceptance criteria:**
1. Request with `[BUILD_VERSION]` returns `DetailedComponentVersions` with mapping `{"3.0.0": <expected>}`
2. Response matches expected `DETAILED_COMPONENT_VERSION`

**Test method:** `BaseComponentsRegistryServiceTest.testGetDetailedComponentVersions` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-011: VCS settings across 10 version formats

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `.../versions/{ver}/vcs-settings` returns correct VCS path, branch, tag,
and repositoryType for each of the 10 non-hotfix version format variants.

**Preconditions:**
- Component `SUB` has VCS root: `vcsPath = "ssh://hg@mercurial/sub"`, `branch = "v2"`, `tag = "SUB-{version}"`, `type = MERCURIAL`

**Acceptance criteria:**
1. All 10 version formats (build, Jira-build, line, Jira-line, minor, Jira-minor, RC, Jira-RC, Jira-release, release) return the same `VCSSettingsDTO`
2. `vcsPath = "ssh://hg@mercurial/sub"`, `branch = "v2"`, `tag = "SUB-3.0.0"`, `type = MERCURIAL`
3. `hotfixBranch = "hotfix/3.0.0"`

**Test method:** `BaseComponentsRegistryServiceTest.testGetVCSSettings` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-012: VCS settings for hotfix branches

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `.../versions/{hotfixVer}/vcs-settings` returns hotfix-specific VCS
configuration with the hotfix version in the tag.

**Preconditions:**
- Component `SUB` has hotfix version `3.0.0-0` and Jira-prefixed hotfix `sub1k-3.0.0-0`

**Acceptance criteria:**
1. Both `HOTFIX_VERSION` and `JIRA_HOTFIX_VERSION` return the same `VCSSettingsDTO`
2. `tag = "SUB-3.0.0-0"` (hotfix version in tag)
3. `hotfixBranch = "hotfix/3.0.0"` (hotfix branch)
4. Other fields (vcsPath, branch, type) unchanged from non-hotfix

**Test method:** `BaseComponentsRegistryServiceTest.testGetVCSSettingsForHotfix` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-013: Distribution with GAV, security groups

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `.../versions/{ver}/distribution` returns distribution with explicit/external
flags, GAV artifacts string, and security groups.

**Preconditions:**
- Component `TEST_COMPONENT3` with version `1.0.108.11` has explicit distribution

**Acceptance criteria:**
1. `explicit = true`
2. `external = true`
3. `securityGroups` contains `"vfiler1#group"`
4. `gav` contains multi-artifact string with expression placeholders (`$major-$minor-$service-$fix`)

**Test method:** `BaseComponentsRegistryServiceTest.testGetDistribution` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-014: Build tools resolution

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `.../versions/{ver}/build-tools` returns configured build tools for the component.

**Preconditions:**
- Component `TEST_COMPONENT_BUILD_TOOLS` with version `1.0.0` has build tools configured

**Acceptance criteria:**
1. Response contains `OracleDatabaseToolBean` with `version = "11.2"`
2. Response contains `PTKProductToolBean` with `version = "03.49"`

**Test method:** `BaseComponentsRegistryServiceTest.testGetBuildTools` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-015: Jira component version (8 parametrized cases)

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `.../versions/{ver}/jira-component` returns correct Jira component version
for various component/version combinations, including sub-components and multiple
version formats.

**Preconditions:**
- Components `TESTONE` and `versions-api` exist with Jira configuration
- Test cases cover: `TESTONE/1.0`, `versions-api` with versions `versions-api.1.0`, `1`, `1.2`, `versions-api.1.2`, `1.2_RC`, `1.2.3`, `1.2-0003`, `prefix-1.2.3-suffix`, `1.2.3.4`, `prefix-1.2.3.4-suffix`

**Acceptance criteria:**
1. Each of the 8+ parametrized cases returns a `JiraComponentVersionDTO` matching the corresponding expected JSON fixture
2. Version resolution handles prefixed, suffixed, RC, and hotfix formats

**Test method:** `BaseComponentsRegistryServiceTest.testGetJiraComponentVersion` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-016: Jira component by project key + version

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/projects/{key}/versions/{ver}` returns a JiraComponentVersion
matching expected data for project-based queries.

**Preconditions:**
- Project `TESTONE` with versions `1.0`, `versions-api.1.0_RC`, `versions-api.1.0`

**Acceptance criteria:**
1. `TESTONE/1.0` returns the expected Jira component version fixture
2. `TESTONE/versions-api.1.0_RC` and `TESTONE/versions-api.1.0` resolve to the correct sub-component fixture
3. All responses match expected JSON fixtures

**Test method:** `BaseComponentsRegistryServiceTest.testGetJiraComponentByProjectAndVersion` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-017: Jira components for a project

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/projects/{key}/jira-components` returns the set of component
names belonging to the project.

**Preconditions:**
- Project `SUB` has sub-components: `SUB`, `client`, `commoncomponent-test`, `SUB_WITH_SIMPLE_VERSION_FORMAT`

**Acceptance criteria:**
1. Response set, when sorted, equals `["SUB", "SUB_WITH_SIMPLE_VERSION_FORMAT", "client", "commoncomponent-test"]`
2. No extra or missing component names

**Test method:** `BaseComponentsRegistryServiceTest.testGetJiraComponentsByProject` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-018: Jira version ranges by project

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/projects/{key}/jira-component-version-ranges` returns the version
range set matching expected JSON fixture.

**Preconditions:**
- Project `SUB` has Jira component version ranges defined
- Expected fixture `expected-data/sub-jira-component-version-ranges.json` is present

**Acceptance criteria:**
1. Response set, sorted by componentName, matches expected fixture
2. No extra or missing version ranges

**Test method:** `BaseComponentsRegistryServiceTest.testGetJiraComponentVersionRangesByProject` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-019: Distributions by Jira project

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/projects/{key}/component-distributions` returns the distribution
map matching expected JSON fixture.

**Preconditions:**
- Project `SUB` has component distributions configured
- Expected fixture `expected-data/component-distributions.json` is present

**Acceptance criteria:**
1. Response map entries, sorted by key, match expected fixture entries
2. Each component's `DistributionDTO` is correct

**Test method:** `BaseComponentsRegistryServiceTest.testGetComponentsDistributionsByJiraProject` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-020: VCS settings by project (5 cases)

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/projects/{key}/versions/{ver}/vcs-settings` returns correct VCS
settings for project-based queries across 5 parametrized cases.

**Preconditions:**
- Project `SUB` with versions: `sub1k-1.0.0`, `sub1k-1.0.0-0`, `hlk-1.0`, `hlk-1.0_RC`, `hlk-1.0.0`

**Acceptance criteria:**
1. Each of the 5 project/version combinations returns a `VCSSettingsDTO` matching the corresponding expected JSON fixture
2. Hotfix and non-hotfix versions produce correct branch/tag values
3. RC version (`hlk-1.0_RC`) resolves correctly

**Test method:** `BaseComponentsRegistryServiceTest.testGetVCSSettingForProject` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-021: Distribution by project (4 cases)

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/projects/{key}/versions/{ver}/distribution` returns correct
distribution for project-based queries across 4 parametrized cases.

**Preconditions:**
- Projects `SUB` (version `hlk-1.0.0`) and `TESTONE` (versions `1.0`, `1.0_RC`, `versions-api.1.0`)

**Acceptance criteria:**
1. Each of the 4 project/version combinations returns a `DistributionDTO` matching the corresponding expected JSON fixture
2. Sub-component distribution (`versions-api.1.0`) differs from parent distribution
3. RC version (`1.0_RC`) returns the same distribution as release version (`1.0`)

**Test method:** `BaseComponentsRegistryServiceTest.testGetDistributionForProject` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-022: Archived flag per component

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
Component archived status is correctly returned for archived, non-archived, and
archived-with-display-name components.

**Preconditions:**
- `ARCHIVED_TEST_COMPONENT` (version `3.0.0`) is archived
- `NON_ARCHIVED_TEST_COMPONENT` (version `3.0.0`) is not archived
- `ARCHIVED_TEST_COMPONENT_WITH_DISPLAY_NAME` (version `3.0.0`) is archived

**Acceptance criteria:**
1. `ARCHIVED_TEST_COMPONENT` returns `archived = true`
2. `NON_ARCHIVED_TEST_COMPONENT` returns `archived = false`
3. `ARCHIVED_TEST_COMPONENT_WITH_DISPLAY_NAME` returns `archived = true`

**Test method:** `BaseComponentsRegistryServiceTest.testGetArchivedForProject` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-023: Maven artifact parameters (3 cases)

**Priority:** Medium
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
GET `/rest/api/2/components/{name}/maven-artifacts` returns artifact configuration
matching expected JSON for each of the 3 parametrized cases.

**Preconditions:**
- Components `test-release`, `sub-component1`, `sub-component2` have Maven artifact configurations

**Acceptance criteria:**
1. Each component returns a `Map<String, ComponentArtifactConfigurationDTO>` matching the corresponding expected JSON fixture
2. Artifact parameters include correct groupId, artifactId patterns

**Test method:** `BaseComponentsRegistryServiceTest.testGetComponentArtifactsParameters` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-024: Find component by single artifact

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
POST `/rest/api/2/components/find-by-artifact` resolves an artifact to the correct
VersionedComponent.

**Preconditions:**
- Artifact dependency defined in `sub-component2-artifact.json`
- Expected result in `expected-data/sub-component2-versioned-component.json`

**Acceptance criteria:**
1. Request with the test artifact returns the expected `VersionedComponent`
2. Component name and version match the expected fixture

**Test method:** `BaseComponentsRegistryServiceTest.testFindByArtifact` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)

---

### RES-025: Find components by artifacts batch (V3)

**Priority:** High
**Test layer:** integration-test
**Status:** ⏳ Git-only

**Description:**
POST `/rest/api/3/components/find-by-artifacts` resolves multiple artifacts to
correct ArtifactComponentsDTO.

**Preconditions:**
- Artifact set defined in `sub1-sub2-sub3-artifacts.json`
- Expected result in `expected-data/sub1-sub2-sub3-artifact-components.json`

**Acceptance criteria:**
1. Request with the test artifact set returns `ArtifactComponentsDTO` with correct `artifactComponents`
2. Each artifact is resolved to the correct component
3. Response order matches expected fixture

**Test method:** `BaseComponentsRegistryServiceTest.testFindByArtifactsV3` via `ComponentsRegistryServiceControllerTest` (Git), `DbBackedComponentsRegistryServiceControllerTest` (DB — planned)
