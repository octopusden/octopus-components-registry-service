# ADR-025: Component provisioning: VCS actions in `octopus-vcs-facade`, the sequence in a new `octopus-component-provisioning-service`

## Status

Proposed.

This ADR is the decision log for component provisioning across services. Recording it in CRS
does not make CRS the runtime owner: the provisioning service owns the sequence, and CRS only
records each component's origin (decision 2). Endpoint contracts and state tables belong to
the owning repositories' specs (`openapi.yaml` and a README section on behaviour), each linking
here. A choice only one repository cares about, such as how vcs-facade stores the marker per
provider, goes into that repository's spec, not into a new ADR.

[ADR-026](026-build-chain-customisations.md) covers how the build chain is shaped.

## Vocabulary

- **Component registration**: creating the component record in CRS. ("Registration" alone
  means registering a build in release management.)
- **Provisioning**: creating the infrastructure behind a registered component: its
  repositories and its build chain.
- **Onboarding**: the user-facing process in the Portal: component registration, then
  provisioning.
- **Origin**: the CRS profile, or the component template and its version, that a component
  was created from.
- **Provisioning configuration**: a named, versioned catalogue entry that says what to create
  for a component of one origin: its repository entries, template parameter mappings and
  build chain options. "Configuration" is never used alone: it also means the component's
  configuration in CRS and a TeamCity build configuration.
- **Version line**: the line of a component's versions a build chain is created for. The
  automation service's request field for it is `minorVersion`.
- **Repository** and **VCS root** are not one-to-one: two VCS roots of a component may point
  at the same repository with different source paths ([ADR-022](022-vcs-root-checkout-layout.md)).

## Context

The Portal's component templates are meant to give a new component a working start: a
repository with approved initial content, and a TeamCity build chain. CRS and the Portal are
open source. The only existing service that creates repositories from templates is internal
to one organisation, Bitbucket-only, and not deployable outside it. If the Portal called it,
an internal service would sit on the critical path of an open-source feature.

That service does two different jobs:

| Job | What it covers | Where it belongs |
|---|---|---|
| **VCS actions** | Check that a project exists; create and populate a repository | `octopus-vcs-facade`, the facade over Bitbucket and Gitea. It already has write actions (tags, pull requests), and its client library already creates and deletes repositories on both providers |
| **Repository content** | A catalogue of approved templates; downloading a template zip by Maven coordinates and validating it | Not in a VCS facade. A facade that knows about Maven artifacts is no longer only a facade |

Creating the repository is one of two provisioning steps. The other is the build chain,
created through `octopus-teamcity-automation-service`. The chain needs the repository, so the
steps have an order, and either can fail after the other has succeeded. Something has to own
that order and know the state of each step, for the Portal and for every other caller: a
TeamCity metarunner, `crsctl`, a script.

Today nothing records which kind of component a user chose. Per the functional spec (7.4,
7.5), "the component keeps no link to its template", so anything downstream has to guess the
kind again from labels and flags.

## Decision

```
                         Components Portal / any API client
                                        │  (user token, TokenRelay)
                                        ▼
                   octopus-component-provisioning-service
   ┌──────────────────────────────────────────────────────────────────────┐
   │ Provisioning API ── Authorization ── Coordinator ── Status Reader    │
   │                                       │                              │
   │        Configuration Selector ── Repository Initializer              │
   │        (provisioning catalogue)    Build Chain Initializer           │
   └──────────────────────────────────────────────────────────────────────┘
        │ caller token        │ template zip     │ create / read     │ create / read
        ▼                     ▼                  ▼                   ▼
       CRS             Maven repository   octopus-vcs-facade   octopus-teamcity-
  (component, origin,                            │             automation-service
   canEdit)                                      ▼                   ▼
                                         Bitbucket / Gitea        TeamCity
```

### 1. `octopus-vcs-facade` gets VCS actions only

- **It knows nothing about templates, Maven, artifacts or components.** It learns three
  actions, addressed by `sshUrl` like its existing endpoints: does the project or
  organisation exist; read a repository's state; create a repository, optionally with initial
  content given as a zip in the request and committed as given.
- **A marker says who a repository was created for.** The caller passes it as an opaque
  string. vcs-facade writes it **in the call that creates the repository** (the repository
  description on Bitbucket and Gitea), and again as a trailer on the root commit, so a crash
  between "create" and "mark" cannot leave a repository nobody recognises. A provider that
  cannot store it in the create call gets no provisioning.
- **The marker holds the whole creation context**: the component's CRS id (the UUID, not the
  name), the provisioning configuration id and version, whether content is expected, the
  target branch, the template chosen, and a hash of the template parameter values. Anything
  that changes what the first commit would be is in it.
- **Create decides by what is already there.** Missing: create, mark, push. Carrying the
  request's marker: finish what is missing, or answer that it is done. Anything else, with or
  without content: a conflict, and nothing is written.
- **It never writes to a repository without the request's marker, and never deletes one.**
  There is no public delete endpoint. Concurrent or repeated requests are settled by reading
  the state again, never by rolling back.
- **It checks only what is needed to commit safely**: the zip opens, has no `.git` entry and
  no path that escapes the repository. Executable bits are kept.
- **Allowed targets.** vcs-facade checks the target against its own per-environment
  allow-list of projects and organisations, on top of the caller's checks.
- **Off by default.** When enabled, the new endpoints require a Keycloak token with a
  repository-create permission, mapped from roles through `octopus-security.roles` as in
  CRS; only service accounts get it. They use a separate credential with repository-create
  rights. Existing endpoints are unchanged.

### 2. CRS records each component's origin

- **On create, CRS records the origin**, and the component detail returns it: a **profile**
  by its id (`regular-external`, `regular-internal`, `solution`, `dmp-bundle` today; profiles
  have no version), or a **component template** by its id and version.
- `regular` is a *kind* of profile, not an id. Nothing selects by kind.
- **Users cannot set or change an origin**: it decides what gets created.
- **A component without an origin is the default**, and nothing requires it to change. Such
  a component gets nothing automatically; a caller must name a provisioning configuration
  that the catalogue marks `selectable`.
- **Backfilling is a reviewed one-off migration**, owned by the team that runs the
  provisioning service, where the kind can be derived from what CRS already holds (the
  solution flag, the external or internal flag, the name).

### 3. A new `octopus-component-provisioning-service` owns provisioning

A new octopusden repository. For a registered component it authorises the request, selects
the provisioning configuration, builds the repository content, runs the steps in order
(repositories, then the build chain), and reports the state of each step. It never talks to
Bitbucket, Gitea or TeamCity directly.

- **No database in the first phase.** The state of each step is read from the markers on
  the resources it created, so it is the same for every caller and survives a restart.
- **The API** is a status read, a provision call that is safe to repeat (each step does only
  what its state says is missing), and a catalogue read. States are `not-started`,
  `in-progress`, `done`, `existing` (a resource this service did not create), `conflict`
  (with what was found) and `unknown` (a read failed).
- **Read first, then prepare, then write.** Authorise; select the provisioning configuration
  and check the request's fields; read the state of every resource; download and fill a
  template only for a repository that still needs it; check every planned change; only then
  write. A finished or reused repository needs no template access.
- **Failures before the first write create nothing**, and an input problem (422) is kept
  distinct from a resource that exists but was not created for this component (409).

#### Authorization

All three are required, and nothing is created if any fails or cannot be evaluated:

1. **The caller holds the provisioning permission**, mapped from Keycloak roles.
2. **The caller may edit this component**: CRS's per-user `canEdit` on the component detail,
   read **with the caller's token**, which the Portal's `TokenRelay` route delivers.
3. **The target is allowed**: the repository's project or organisation, and the TeamCity
   parent project, are on per-environment allow-lists.

Without 2 and 3, a user with the permission could register a component that points at any
project and make the service account create a repository there.

#### Provisioning configurations

```yaml
- id: solution
  version: 3
  origins: [ { componentTemplate: solution } ]
  repositories:
    - template:
        byLabel:                               # picked by the component's CRS label
          helm: "<groupId>:helm-template:<version>"
          ansible: "<groupId>:ansible-template:<version>"
  buildChain:
    parentProjectId: <TeamCity project id>     # fixed; leave out to let the caller choose
    extraStages: [ ... ]                       # ADR-026; never open
```

- **Two selection levels.** The origin selects the provisioning configuration; within it,
  repository entries are selected by repository URL.
- **One origin maps to exactly one provisioning configuration.** No rules on other CRS fields,
  no fallback by profile kind. Two for one origin is a catalogue error at load time. A
  missing one is a 422, so a forgotten origin never quietly gets an empty repository and a
  guessed build chain; origins that need nothing special map to an explicit open entry.
- **It holds both steps' options**, so the Portal, a metarunner and a script get the same
  result, and a late retry gets the same answer.
- **Each field is fixed or open.** A fixed field may be omitted; a different caller value is
  a 422, never silently replaced. An open field must be supplied (build chain fields only
  while that step is on). Never open: template parameters, which are read from CRS, a
  repository's template, and extra stages.
- **The Portal builds its form from the catalogue read** for the origin and repository URLs
  the user picked. It holds no per-template knowledge of its own.
- **The catalogue is per-environment settings**, not code.

#### The repository step

- **Each distinct repository among the component's VCS roots is handled on its own.** A
  component has no primary VCS root ([ADR-022](022-vcs-root-checkout-layout.md)), and some
  layouts give a component two: its own repository and a shared one used by many components.
- **Repository entries** each have `urlRegex` (which repositories it applies to; first match
  wins, at most one default entry), `useExisting` (fixed or open) and `template` (a GAV, or
  `byLabel`; without it the repository is created empty).
- **An existing repository is an explicit choice, never a fallback.** The caller may choose
  "use existing" for any repository, even one the entry fixes as created: it only creates
  less. A repository fixed as existing, such as a shared one, is never created. Without that
  choice, a repository that lacks this component's marker stops the step with a conflict,
  and the build chain step does not run.
- **"Use existing" does not widen the trust boundary.** A chain builds whatever repositories
  the component's VCS settings in CRS name, and only a user who may edit the component can
  change those. The allow-list guarantees the project, not the content.
- **The template is never typed by the user.** It is fixed, or picked by a CRS label the
  Portal sets at component registration (`helm` or `ansible` for a solution). With no marker
  yet, exactly one listed label must be present, otherwise 422. For an unfinished
  repository, a label that no longer names the recorded template is a conflict, never a
  switch. For a finished one, labels are not read.
- **The target branch comes from CRS only.** Roots of one repository that name different
  branches are a 422.
- **After the first create, the marker is the reference, not CRS.** A finished repository is
  recognised by its recorded context even if CRS changed since. For an unfinished one, a
  recorded input that differs from CRS now is a conflict, and nothing is pushed.
- **One repository's failure does not undo another's.** The build chain step runs only when
  every repository is done or confirmed to exist.

**Templates.** A survey of recently started repositories found no shape shared by every
component, but a few shapes that repeat within one kind of component and differ only in a
few values (names, codes, a `groupId` suffix), including directory names. So one generic
engine serves every template; the content is per kind:

- a template is a zip in a Maven repository with a descriptor declaring its parameters and
  the files to substitute; the team that owns the kind owns its content;
- **only declared parameter names are substituted**; every other `${…}` is left as it is,
  and binary files are never substituted;
- **parameter values are read from CRS**, so nobody types them twice;
- a finished step is recognised by its marker, never by comparing content; an unfinished one
  is completed from the provisioning configuration version recorded in the marker.

#### The build chain step

- **A build chain is identified by its component and version line.** The parent project is
  where it lives, not part of its identity.
- **The project id is fixed by that identity**: built from the component's CRS UUID (so a
  rename does not change it) and the version line. TeamCity project ids are unique across
  the server, so an earlier result is found by one read of that id, and two concurrent
  creates cannot both succeed.
- **Only chains this service created are managed; older ones are only detected.** A chain
  created before it has a TeamCity-generated id. When nothing is at the fixed id, the
  automation service searches the server for a project whose `COMPONENT_NAME` and
  `PROJECT_VERSION` parameters match. A match is reported as `existing`, and nothing is
  created; the service never adopts, moves or repairs it.
- **The older chains are a closed set before the step is turned on.** Every other chain
  creator is removed first, not only paused: TeamCity still lets people run a paused or
  archived configuration by hand. An operator then runs a one-off check that lists every
  older project the search would miss (no `COMPONENT_NAME` or `PROJECT_VERSION`, or a name no
  longer in CRS), and each is fixed or deleted by its owner's decision.
- **The remaining gaps are accepted, not closed.** A chain created later by the CLI or by
  hand, or an older chain whose component is renamed after the check, is not found, and a
  second chain can be created. These are rare manual paths that no check here can cover; an
  operator removes the duplicate.
- **It creates and diagnoses; it never repairs or deletes.** A project at the fixed id under
  another parent, with another provisioning configuration recorded, incomplete, or not
  marked for this component is a conflict for an operator. Deleting a TeamCity project
  removes its configurations and, eventually, its history and artifacts; moving or archiving
  it keeps the id, so neither frees it for a retry.

### 4. The Portal starts provisioning and shows its state

- **Register first, then provision.** The Portal registers the component in CRS, then calls
  the provisioning service with the component id. CRS is the only source of the component's
  data, and a registry rejection never leaves an orphan repository.
- **The Portal holds no sequencing logic.** It shows the state, offers a retry (the same
  call), and shows each conflict with what was found.
- **Registering does not imply provisioning.** Provisioning needs `canEdit`, which a user who
  registers a component for another owner may not have. The Portal then shows provisioning
  as not started, says who may start it, and offers the same action on the component page.
- **It reaches the service as it reaches CRS:** a gateway route with `TokenRelay`
  ([ADR-012](012-portal-architecture.md)). Users never get a role on vcs-facade or the
  automation service.

### 5. `octopus-teamcity-automation-service` changes for the build chain step

Before the build chain step is turned on, the automation service:

- holds the TeamCity credentials in its own settings, as vcs-facade does
  ([octopus-teamcity-automation#89](https://github.com/octopusden/octopus-teamcity-automation/pull/89));
- authenticates inbound calls: a Keycloak permission for create, and allow-lists of parent
  projects and templates;
- accepts the fixed project id in the create call;
- writes a **marker** in the call that creates the project (component CRS id, version line,
  parent, provisioning configuration), and a **completion flag as its last action**;
- offers a **state read** by project id, which also reports matching older projects, and
  refuses a create while one matches;
- accepts **extra stages** in the request ([ADR-026](026-build-chain-customisations.md)).

### 6. Delivery in two phases

1. **Repository step.** Useful on its own; the build chain step is off in settings.
2. **Build chain step**, once decision 5 has shipped, the other chain creators are removed,
   and the one-off check of older chains is done.

The automation service changes run in parallel with phase 1. The existing internal
onboarding service has no production callers, so retiring it needs no migration.

## Consequences

### Positive
- Provisioning needs only octopusden services, so an open-source deployment works end to end.
- One service owns the sequence and its state: every caller gets the same order and recovery,
  and closing a browser tab loses nothing.
- Each other service acts on one system: vcs-facade on VCS, the automation service on
  TeamCity, CRS on the record.
- The kind of component is chosen once, by the user, and recorded in CRS.
- Retries are recognised by who a resource was created for, so later commits, a new template
  version or a CRS edit do not turn a finished step into a conflict.
- The service account cannot be steered outside the allow-lists, and only a user who may edit
  the component can provision it.
- Only vcs-facade holds VCS credentials, and only the automation service holds TeamCity
  credentials. Gitea support comes with it.

### Negative
- A new service, with its own CI and image.
- Six repositories change or are created: `octopus-external-systems-client`, vcs-facade, CRS,
  the automation service, the Portal and the new service.
- vcs-facade changes from read-mostly to creating repositories, with its first authenticated
  endpoints. It receives zip bodies, so it needs a request size limit and temporary disk.
- Provisioning depends on the VCS storing a description in the create call.
- Components without an origin get nothing automatically until it is backfilled or a caller
  names a provisioning configuration.
- Catalogue owners maintain URL regexes for shared repositories, in the right order.
- A create that fails and is never retried leaves an empty, marked repository to delete by
  hand.
- Project ids built from a UUID are not readable; the project name stays the component name.
- A build chain conflict always needs an operator.
- Templates need owners, or they drift from what teams build.
- Provisioning cannot run while CRS is down.

## Alternatives considered

- **The Portal calls the existing internal onboarding service.** Least work, but it puts an
  internal, Bitbucket-only service on the critical path of an open-source feature.
- **The Portal orchestrates**, calling a narrow repository service and the automation service
  directly. Rejected: if the sequence runs in the browser, a closed tab leaves a
  half-provisioned component nothing records, and other callers get no sequence and no
  recovery. A Backend for Frontend shapes calls for one UI; it is not the place for a process
  other callers also need.
- **A forwarding wrapper over a separate repository service.** A hop with no logic. Merged
  with the repository logic, the service has real work.
- **CRS triggers provisioning**, synchronously or from a "component created" event. The
  registry would own a flow that is not about registering components, and the user would not
  see which step failed.
- **vcs-facade also resolves templates.** One service fewer, but the facade would have to know
  Maven coordinates, catalogues and validation.
- **Match rules on CRS fields to pick a template.** They re-derive a choice the user already
  made, and a wrong label silently gives the wrong template. `byLabel` is narrower: the
  origin has already chosen the provisioning configuration, the label is the choice itself,
  and a missing or ambiguous label is a 422.
- **Recognise a retry by the content's tree hash.** It stops recognising the repository after
  a second commit, a new template version or a CRS edit, and forces deterministic content.
- **Mark the repository in a second call after creating it.** A crash between the two leaves a
  repository nobody recognises.
- **Only single-root components in phase 1.** Every multi-root component would always fail.
- **One generic template for every component.** No such shape exists.
- **Create the repository before registering the component.** Values could disagree with what
  CRS then stores, and a registry rejection leaves a repository with no component.
- **Two-step create: create, then a separate commit action.** Callers would roll back
  themselves, which needs a public delete endpoint.

## References

- [ADR-012](012-portal-architecture.md): Portal architecture, gateway and `TokenRelay`
- [ADR-022](022-vcs-root-checkout-layout.md): VCS roots and checkout layout
- [ADR-026](026-build-chain-customisations.md): build chain customisations
- Functional spec 7.4 and 7.5: profiles and component templates
- `ComponentControllerV4` (`canEdit`), `PermissionEvaluator`, `octopus-security.roles`
- Octopus REST API Guidelines (`octopus-base`)
- `octopus-vcs-facade`; `octopus-external-systems-client` Bitbucket and Gitea clients
- `octopus-teamcity-automation`:
  [#89](https://github.com/octopusden/octopus-teamcity-automation/pull/89) (TeamCity connection
  from service settings)
