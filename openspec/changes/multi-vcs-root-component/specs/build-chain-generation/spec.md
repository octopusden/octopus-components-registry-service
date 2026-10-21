## Purpose

Defines how the build-chain generator turns a Component's registry VCS Roots and Build Working
Directory into TeamCity VCS roots, checkout rules, attach order, default branches and
per-configuration build parameters.

## ADDED Requirements

### Requirement: One TeamCity VCS root per registry VCS Root

The generator SHALL create one TeamCity VCS root for each registry VCS Root of the Component and
SHALL attach all of them to every build configuration it creates.

The root at registry position 1 SHALL be named `<projectId>_VCS_ROOT`, as today, and the root at
registry position `n` for `n ≥ 2` SHALL be named `<projectId>_VCS_ROOT_<n>`, whatever the attach
order. TeamCity derives the ids; the generator attaches each root by the id TeamCity returns, and
tests pin the names.

#### Scenario: Two registry roots
- **WHEN** a chain is generated for a Component with two VCS Roots
- **THEN** the project holds two TeamCity VCS roots named `<projectId>_VCS_ROOT` and
  `<projectId>_VCS_ROOT_2`
- **AND** the compile, release candidate, checklist and release configurations each attach both

#### Scenario: Single registry root keeps today's name
- **WHEN** a chain is generated for a Component with one VCS Root
- **THEN** exactly one TeamCity VCS root exists, named `<projectId>_VCS_ROOT`

### Requirement: Checkout rule from checkout layout

The generator SHALL attach each root with the rule its Checkout Directory `cd` and Source Path
`sp` give: no rule when both are empty; `+:. => <cd>` with only `cd`; `+:<sp> => <cd>/<sp>` with
both; `+:<sp> => <sp>` with only `sp`. It SHALL log each root's checkout layout and rule.

#### Scenario: Neither field
- **WHEN** a root has no Checkout Directory and no Source Path
- **THEN** every attachment of that root has an empty checkout rule

#### Scenario: Checkout Directory only
- **WHEN** a root has Checkout Directory `feature` and no Source Path
- **THEN** every attachment of that root has checkout rule `+:. => feature`

#### Scenario: Checkout Directory and Source Path
- **WHEN** a root has Checkout Directory `feature` and Source Path `data`
- **THEN** every attachment of that root has checkout rule `+:data => feature/data`

#### Scenario: Source Path only
- **WHEN** a root has Source Path `mapper` and no Checkout Directory
- **THEN** every attachment of that root has checkout rule `+:mapper => mapper`

### Requirement: Attach order follows the Build Working Directory

The generator SHALL attach first the root that holds the Build Working Directory: the root whose
Checkout Directory equals the Build Working Directory's first segment, or, when none does, the root
without a Checkout Directory. With no Build Working Directory it SHALL attach first the root
without a Checkout Directory, and otherwise (not produced by the registry, which then requires a
Build Working Directory) the root at registry position 1. The other roots follow in registry order. The attach order SHALL be logged.

#### Scenario: Root at the checkout root listed second (component-A-shaped)
- **WHEN** the registry lists root B (Checkout Directory `feature`) first and root A (no Checkout
  Directory) second, with no Build Working Directory
- **THEN** every created configuration attaches A first and B second

#### Scenario: Build Working Directory in the second root
- **WHEN** the registry lists root B (Checkout Directory `feature`) first and root A (Checkout
  Directory `core`) second, with Build Working Directory `core/mapper`
- **THEN** every created configuration attaches A first and B second


### Requirement: Default branch from the registry

The generator SHALL set each TeamCity VCS root's default branch to the first `|`-separated
alternative of the root's v2 `branch` value for the chain's version line, trimmed, SHALL keep the
branch specification `+:<default>`, and SHALL log the chosen default branch of every root. The v2
value is never empty: the registry falls back to the repository type's default branch, and has
its placeholders already resolved at the chain's minor version. A value containing `null` (an
unknown placeholder) SHALL be used as is and logged as a warning naming the root and the value.
Placeholders resolved to `0` at the minor version cannot be told apart in v2 and are not warned
about.

#### Scenario: List value
- **WHEN** the registry returns branch `main|release/1.2` for the chain's version line
- **THEN** the VCS root's default branch is `main` and its branch specification is `+:<default>`

#### Scenario: Placeholder resolved to a nonexistent branch
- **WHEN** the registry returns branch `release/null` for the chain's version line (a placeholder
  that the version line cannot resolve)
- **THEN** the VCS root's default branch is `release/null` and the command logs a warning naming
  that root and value

### Requirement: Build Working Directory parameters

When the v2 VCS settings carry a `buildWorkingDirectory`, the generator SHALL set, on each
configuration it creates, the configuration parameters `WORK_DIR` and `COMPONENT_CONFIG_DIR` to
`%teamcity.build.checkoutDir%/<buildWorkingDirectory>`. Without one, no parameter is overridden,
whatever the roots' checkout layout. The generator SHALL override no build step.

#### Scenario: Build Working Directory set
- **WHEN** the Build Working Directory is `core/mapper`
- **THEN** `WORK_DIR` and `COMPONENT_CONFIG_DIR` are `%teamcity.build.checkoutDir%/core/mapper` on
  every created configuration, and every inherited step keeps the template's values

#### Scenario: No Build Working Directory
- **WHEN** no Build Working Directory is set and a root has Checkout Directory `feature`
- **THEN** no created configuration gets `WORK_DIR` or `COMPONENT_CONFIG_DIR` from the generator,
  and no step value is overridden

### Requirement: BUILD_VERSION_FORMAT_FILE on configurations that calculate the build version

ADR-022 decision 9 resolves the version-format fallback question: when a Build
Working Directory is set, the generator SHALL set the configuration parameter
`BUILD_VERSION_FORMAT_FILE` to `<buildWorkingDirectory>/build-version-format.properties` on every
configuration it creates that already carries a *Calculate Build Version* step, checked per
configuration; it SHALL set nothing on a configuration without that step, and nothing at all when
there is no Build Working Directory. The generator SHALL override no build step. The parameter
takes effect only once templates `CDGradleBuild` and `CDJavaMavenBuild` read it (a manual, one-time
template change, not applied by the generator, outside this repository);
the generator SHALL log this at INFO, naming the parameter and its value, whenever a Build Working
Directory is set.

#### Scenario: Build Working Directory set, configuration calculates the build version
- **WHEN** a chain is generated for a Component with Build Working Directory `core/mapper`, and the
  created compile configuration has a *Calculate Build Version* step
- **THEN** that configuration's `BUILD_VERSION_FORMAT_FILE` is `core/mapper/build-version-format.properties`,
  the command logs an INFO naming the parameter and this value, and the chain is created

#### Scenario: Build Working Directory set, configuration does not calculate the build version
- **WHEN** a chain is generated for a Component with a Build Working Directory, and a created
  configuration (for example Release) has no *Calculate Build Version* step
- **THEN** that configuration gets no `BUILD_VERSION_FORMAT_FILE`

#### Scenario: No Build Working Directory
- **WHEN** a chain is generated for a Component without a Build Working Directory
- **THEN** no created configuration gets `BUILD_VERSION_FORMAT_FILE`, and nothing is logged about it

### Requirement: Current compile template ids

The generator SHALL instantiate compile configurations from `CDGradleBuild` for Gradle-family build
systems and `CDJavaMavenBuild` for Maven.

#### Scenario: Maven Component
- **WHEN** a chain is generated for a Maven Component
- **THEN** the compile configuration is based on template `CDJavaMavenBuild`

### Requirement: Unsupported shapes fail before creation

The generator SHALL fail without creating any TeamCity object when more than one root has no
Checkout Directory, when the same repository appears in more than one root, when a root's type is
not Git, or when a Checkout Directory equals the value of the project parameter
`RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR` (a helper clone directory of the chain templates,
known only to TeamCity). All checks run before the project is created; the reserved value is read
from the parent project (`parentProjectId`, as `JDK_VERSION` is read today), since the new project
does not exist yet.

#### Scenario: Two roots at the checkout root
- **WHEN** two registry roots of the Component have no Checkout Directory (for example a
  Git-sourced multi-root Component)
- **THEN** the command fails naming both roots and the TeamCity project is not created

#### Scenario: Repeated repository
- **WHEN** two registry roots of the Component point at the same repository
- **THEN** the command fails with an unsupported-operation error and the TeamCity project is not
  created

#### Scenario: Root that is not Git
- **WHEN** a registry root of the Component has a repository type other than Git
- **THEN** the command fails naming that root and the TeamCity project is not created

#### Scenario: Checkout Directory collides with a helper clone directory
- **WHEN** a root's Checkout Directory equals the value of
  `RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR`
- **THEN** the command fails with an unsupported-operation error naming that root and the
  TeamCity project is not created
