## Purpose

End-to-end outcome that lets a Component keep its sources in several VCS Roots, or in one
directory of a shared repository, and still get a working build chain from registry data alone.
Component behaviour (validation, contracts, generation rules, UI) is owned by the component
changes listed in the proposal; this spec covers what needs several of them together. Checkout layout of
existing configurations is a separate capability,
[`teamcity-checkout-layout-sync`](../teamcity-checkout-layout-sync/spec.md)
(ADR-023), not specified here.

## ADDED Requirements

### Requirement: A multi-root Component builds from registry data alone

A Component whose VCS Roots are placed in the registry SHALL get, from the generator and without
manual edits, a build chain whose compile build checks out every root at its checkout layout, runs in the
Build Working Directory, and runs Sonar analysis, including the target-branch fetch, of the
repository that holds the Build Working Directory.

#### Scenario: One root at the checkout root, one in a Checkout Directory (component-A-shaped)
- **WHEN** a test Component is saved in the registry with root A (no Checkout Directory, no Source
  Path) listed second and root B (Checkout Directory `feature`) listed first, no Build Working
  Directory, and its chain is generated and its compile build runs
- **THEN** the compile build checks out A whole at the checkout root, with its `.git` there, and B
  at `feature`; no `WORK_DIR` override is set; the releng steps' command line carries
  `-Dcomponent-config.dir=<checkout directory>`; the target-branch fetch runs at the checkout
  root; and the Sonar parameters take the commit and the project and repository keys from A, not B
- **AND** no version-format WARNING is logged and the version is calculated as for a single-root
  chain

#### Scenario: Both roots in Checkout Directories with a Build Working Directory (pilot-shaped)
- **GIVEN** the `BUILD_VERSION_FORMAT_FILE` template parameter (ADR-022 decision 9) is
  already applied to the compile templates as the rollout prerequisite
- **WHEN** a test Component is saved with root A (Checkout Directory `core`) and root B (Checkout
  Directory `feature`), listed B first, with Build Working Directory `core/mapper`, and its chain
  is generated and its compile build runs
- **THEN** the build holds A at `core` and B at `feature`; `WORK_DIR` is
  `<checkout directory>/core/mapper`; the releng steps' command line carries
  `-Dcomponent-config.dir=<checkout directory>/core/mapper`; the target-branch fetch runs in
  `core/mapper`; the Sonar parameters take the commit and keys from A; `BUILD_VERSION_FORMAT_FILE`
  is `core/mapper/build-version-format.properties`; *Calculate Build Version* reads it from there
  and the version calculates successfully, with no WARNING and no fallback to
  `BUILD_NUMBER_FORMAT`

#### Scenario: Every root in a Checkout Directory without a Build Working Directory
- **WHEN** an editor saves the pilot-shaped Component (roots in `core` and `feature`) in the Portal
  without a Build Working Directory
- **THEN** the registry rejects the save with `buildWorkingDirectory: required when every VCS
  entry has a Checkout Directory`, the Portal shows it on the Build Working Directory field, and no
  chain can be generated from such data

#### Scenario: Root with Checkout Directory and Source Path
- **WHEN** a root has Checkout Directory `feature` and Source Path `data`
- **THEN** the compile build holds only `feature/data` from that repository

#### Scenario: Root with Source Path only
- **WHEN** a root has Source Path `mapper` and no Checkout Directory, with Build Working Directory
  `mapper`, and its compile build runs
- **THEN** the build holds only `mapper` from that repository, and the location of its `.git`
  (inside `mapper` or at the checkout root) is recorded against ADR-022's expectation, with the
  target-branch fetch outcome

#### Scenario: Roots in a Checkout Directory survive an agent clean
- **WHEN** a build of the generated compile configuration of the component-A-shaped chain runs again
  after a branch change of the root at the checkout root, with `cleanBuild=false` set on that one
  configuration for this check (a per-configuration setting on the test project, no template
  change)
- **THEN** the other root's directory is checked out and present; if the agent's clean deleted it,
  the result is recorded and the fix of ADR-022 (`agentCleanFilesPolicy=IGNORED_ONLY` on the VCS
  root attached at the checkout root) is applied

### Requirement: Unplaced single-root Components are unchanged end to end

A single-root Component with no checkout layout field and no Build Working Directory SHALL see no change
across registry, generator and Sonar automation other than the registry-driven default branch.

#### Scenario: Existing single-root shape
- **WHEN** a chain is generated for a single-root Component without checkout layout whose registry
  branch is `master`
- **THEN** its v2 VCS settings are byte-identical to before, the root is attached without checkout
  rules, the version-format file and `component.yml` are read from the checkout root, and Sonar
  analysis completes as before

### Requirement: Chain-mismatch warning reaches the editor

A change of the base VCS entries or the base Build Working Directory saved in the Portal on a
Component with a linked TeamCity project SHALL succeed and show the editor the registry's warning
that the build chain must be recreated.

#### Scenario: Root added in the Portal
- **WHEN** an editor adds a placed VCS Root in the Portal to a Component with a linked TeamCity
  project and saves
- **THEN** the save succeeds and the Portal shows the registry's chain-mismatch warning

### Requirement: Existing checkout layout comes from the CRS Diff/Sync jobs

Checkout layout of existing multi-root configurations SHALL come from the CRS Diff/Sync jobs specified
in [`teamcity-checkout-layout-sync`](../teamcity-checkout-layout-sync/spec.md)
(ADR-023): the derivation rules, status vocabulary, per-component selective write and "changed
since diff" guard are specified there, not duplicated here.

#### Scenario: Component A imported
- **WHEN** Diff finds root A (a compile configuration attaches it with no checkout rule) and root B
  (attached with `+:. => root-b`), `WORK_DIR` unset, while the registry still holds V8's guess
  (root A in a Checkout Directory equal to its name)
- **THEN** Diff reports component A RESOLVED, with root A having no Checkout Directory, root B
  having Checkout Directory `root-b` and no Build Working Directory; after Sync the registry
  returns exactly these values and root A keeps its name
