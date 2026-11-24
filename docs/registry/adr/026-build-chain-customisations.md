# ADR-026: Build chain customisations go into `octopus-teamcity-automation` and the registry; no post-creation patching

## Status

Proposed — **amends [ADR-023](023-teamcity-checkout-layout-diff-sync.md)** for components
that build inside a shared repository (decision 4).

It follows [ADR-025](025-component-provisioning.md), where
`octopus-component-provisioning-service` creates a component's build chain through
`octopus-teamcity-automation-service`, with options from the provisioning configuration
selected by the component's origin. Request fields and their validation belong to the
automation service's spec, which links here.

## Context

Two kinds of customisation are applied to build chains today **after** the generator has
created them, by steps that run only when the chain is created through a TeamCity metarunner
that wraps the generator's CLI:

- **Deploy and UAT stages for solutions.** A second metarunner step inserts a `Deploy to QA`
  build configuration from a deploy template and an empty `UAT` configuration between
  Compile and RC/Release, and rewires the dependencies. It decides which solutions get them
  by probing the repository for an Ansible or Helm layout.
- **A multi-root layout patch.** For a family of components that build from their own
  repository plus one repository shared by the whole family, a build triggered after every
  chain creation patches Compile: a checkout rule placing the component's repository in a
  subdirectory, an every-branch branch spec, the shared VCS root, the Gradle working
  directory, and a disabled code-analysis parameter step.

Every behaviour here is a generic chain shape. What is organisation-specific is settings
(template ids, stage names, a parent project) and data (which components use which layout).
A chain created through the automation service's API, as ADR-025 does, runs neither step.

## Decision

### 1. No post-creation customisation service

A service that patches chains after creation is what made today's steps fragile: guessing
build configuration ids from names, detaching templates, and running after every chain
creation. Instead:

- **Generic mechanisms** go into `octopus-teamcity-automation`, with defaults that keep
  today's output for every existing caller.
- **Which component gets what** is set by the provisioning configuration (ADR-025).
- **Component facts** go into the registry, and the generator reads them, as it already does
  for VCS roots since
  [octopus-teamcity-automation#78](https://github.com/octopusden/octopus-teamcity-automation/pull/78).

If a truly internal step appears later, the provisioning service calls it after the
automation service returns, using the ids in `BuildChainResult`. It never works from TeamCity
triggers or id guessing.

### 2. Extra stages are a list in the create request

The create request takes an optional `extraStages` list, inserted in order between Compile
and the next stage (RC, or Release when there is no RC):

```yaml
extraStages:
  - name: "Deploy to QA [Manual]"
    template: <deploy template id>  # optional; without it an empty build configuration
    checkout: compile               # Compile's VCS roots and checkout rules, or none
    parametersFromCompile: [DISTRIBUTION_ARTIFACTS_COORDINATES, BUILD_VERSION]
  - name: "UAT"
```

```
Compile → Deploy to QA → UAT → RC → Release      (extra stages, RC created)
Compile → Deploy to QA → UAT → Release           (no RC)
Compile → RC → Release                           (no extra stages; unchanged)
```

- **A list expresses today's shape**, one templated stage and one empty; a boolean cannot.
- **The service stays generic.** Stage names and template ids stay out of the code; a caller
  without the list gets today's chain.
- **Who gets which stages is set in the provisioning configuration**, under
  `buildChain.extraStages`, never by the caller: the provisioning API has no field for it, so
  every caller gets the same chain for the same kind of component.
- **A new stage needs no release** of the automation service.
- `BuildChainResult` and `BuildChainDTO` return the created stages' build type ids.
- **Checkout.** `checkout: compile` gives the stage all of Compile's VCS roots and checkout
  rules, and so the same branches. `checkout: none` gives it no roots, so, like RC and
  Release, it runs on the default branch. There is no "first root": a component has no
  primary root ([ADR-022](022-vcs-root-checkout-layout.md)).
- **Limits**, so it does not grow into a chain language: insert only, at one position, in
  linear order; no conditions, and nothing beyond passing named parameters from Compile;
  built during creation, never applied to an existing chain; the whole request is validated
  before anything is created; edits inside a stage are not part of it.
- **Solutions.** The solution provisioning configuration carries the list; others carry none.
  Ansible and Helm solutions share one origin and one chain, and differ only in the
  repository template picked by CRS label (ADR-025), so the repository probe goes away. A
  solution that is neither, which gets no stages today, gets them: accepted.
- **UAT stays** in the list until the owners of existing solution chains say what it is for.
  Removing it later is one line of provisioning configuration.
- **Expand, then contract.** The metarunner first passes the list itself; the post-creation
  step is removed only after a functional test shows the new stage has the right checkout
  and parameters.

### 3. The default branch spec is a deployment setting

- **The code default stays `+:<default>`**
  ([octopus-teamcity-automation#50](https://github.com/octopusden/octopus-teamcity-automation/pull/50)),
  so an open-source user who upgrades for another reason gets the same chain.
- **`BuildChainConfig` gets a default branch spec** next to the template ids. A deployment may
  set it to `+:refs/heads/*`, so Compile builds every branch without each caller asking.
- **A request option `branchSpec` overrides it**, and a provisioning configuration may set it
  per kind of component.
- It applies to the roots attached to Compile and to any extra stage with
  `checkout: compile`. RC, Checklist and Release have no roots: they run on the default
  branch and pick Compile's default-branch build through their snapshot dependency.
- The root's `branch` stays the registry's branch, so TeamCity still treats it as
  `<default>`.
- **Existing chains are not changed.**
- **Shared roots.** With decision 4, every component of a family gets the shared repository as
  a root. With an every-branch spec and a VCS trigger on Compile, a push to any branch of the
  shared repository could start Compile in all of them. Whether the Compile templates have a
  VCS trigger is checked before the setting is turned on for such a family.

### 4. A component that builds from two repositories lists both in the registry

Some components build from their own repository plus a repository shared by the whole
family. The registry lists both as VCS roots ([ADR-022](022-vcs-root-checkout-layout.md)):

- its own repository, with a Checkout Directory;
- the shared repository, with its own Checkout Directory;
- a Build Working Directory inside the shared one.

The generator already turns these into the chain's checkout and working directory. So a
chain built from the registry is right from the start, and no patch is needed.

- **New components** are registered this way from the start.
- **Existing components** get the shared repository added to their registry entry once,
  before the other chain creators are removed. Otherwise their next chain, for example for a
  new version line, would be built without the shared repository, and nothing would patch it
  any more.
  - This is a one-off, reviewed change to the registry, not Sync. Sync still never adds a
    root.
  - It is owned by the team that runs the provisioning service, which also removes the other
    chain creators.
  - The owners of the release tooling agree to it first, because their commit attribution and
    `vcs-path` lookups change, not only the registry.
  - Chains that already exist are not changed.
- **A commit in the shared repository counts for every component that lists it.** Release
  tooling links commits to a component through its roots, and a search by the shared
  repository's path returns every component that lists it. This is accepted: the shared
  repository is part of what each of them builds, so a change in it is a change to each.
- **This amends [ADR-023](023-teamcity-checkout-layout-diff-sync.md).** ADR-023 says adding a
  missing shared repository to a component is not a fix, because of that attribution. For
  components that build inside a shared repository, it is now registered as a root, and the
  attribution is accepted. For any other shared repository, such as tooling the build only
  uses, ADR-023 still holds.
- **The shared repository is never created.** When the provisioning service (ADR-025) sets up
  a new component, the family's provisioning configuration marks the shared repository
  `useExisting: true`.
- **Each component gets its own TeamCity VCS root for the shared repository**, instead of
  sharing one. Accepted.
- **The version-format file.** In such a chain the build runs inside the shared repository's
  directory, so the file that sets the version is not at the usual place. The generator passes
  its location as `BUILD_VERSION_FORMAT_FILE`, but the compile template must read it. Each
  template used by such chains is checked, and changed if needed, first.

### 5. Template steps disabled by a patch need a condition first

The layout and the branch spec cover all but one of today's patch steps. The last disables a
code-analysis parameter step for the family. A chain created through the API would run that
step, which every existing chain of the family has disabled, for a reason nobody has
recorded. So:

- first, the owners of those components say why it is disabled;
- then the step gets a condition in the compile template, as the other code-analysis steps
  already have, and the family sets the parameter;
- **until then, that family keeps its existing creation path** and its patch.

## Consequences

### Positive
- No post-creation patching and no dependency on TeamCity id formats.
- The chain shape is decided in one place, during creation, from registry data and the
  provisioning configuration.
- Every caller gets the same chain for the same kind of component.
- Open-source users get extra stages and a configurable branch spec with no organisation
  names in the code, and no change unless they opt in.
- Today's stages are kept, so nothing that depends on them breaks.
- The old steps retire only after their replacements are proven.
- The registry describes how multi-root components are actually built.

### Negative
- A family with a disabled template step keeps its old creation path until the step is
  understood and conditioned.
- Multi-root registry entries have to be maintained.
- Existing components of such a family need a one-off registry update before the old chain
  creators are removed.
- A commit in a shared repository counts for every component that lists it.
- One root per component for a shared repository instead of one shared root.
- A stage list is a small chain language; the limits in decision 2 have to be defended.
- While the old and new paths coexist, the old step must skip chains the new one already
  built.
- With every branch built, Compile runs more often if its template has a VCS trigger.
- A feature-branch Compile build can reach RC if someone runs RC as a custom build and picks
  it by hand. A normal RC run does not.

## Alternatives considered

- **A post-creation customisation service.** See decision 1.
- **Keep the post-creation step in the metarunner.** Least work, but ADR-025 creates chains
  through the API, which never runs it, and the chain would still be rewired after creation.
- **A boolean "create Deploy stage" flag set by the Portal.** It cannot express the empty UAT
  stage, puts stage names and template ids in the code, and only the Portal would know when
  to set it.
- **A regular-versus-DMP criterion.** It would give the Deploy stage to solutions whose
  layout the deploy template does not expect.
- **Keep the repository probe.** It needs `git` and SSH on the runner, and reads `HEAD` rather
  than the registered branch. The origin says the same thing.
- **Drop the UAT stage now.** Deferred until its owners say what it is for.
- **Change the code default to every branch.** It reverses a deliberate default for every
  open-source user, and building every branch is an organisation's CI choice.
- **Leave the disabled template step out of scope.** Nothing would disable it on new chains.

## References

- [ADR-022](022-vcs-root-checkout-layout.md): VCS roots and checkout layout
- [ADR-023](023-teamcity-checkout-layout-diff-sync.md): TeamCity checkout layout Diff and Sync
- [ADR-025](025-component-provisioning.md): component provisioning
- `octopus-teamcity-automation` `BuildChainCreator`, `VcsRootPlacement`, `GitVcsRootSpec`,
  `BuildChainConfig`;
  [#50](https://github.com/octopusden/octopus-teamcity-automation/pull/50) (default branch only),
  [#78](https://github.com/octopusden/octopus-teamcity-automation/pull/78) (VCS roots and
  working directory from the registry)
