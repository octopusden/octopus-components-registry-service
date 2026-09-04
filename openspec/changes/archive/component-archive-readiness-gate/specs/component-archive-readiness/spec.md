## Purpose

Defines CRS's archive-readiness check: what it consults, how it decides each target's outcome, and how a target shared with a live component is reported.

The check is read-only in both directions. It never archives anything in an external system, needs no write permission in any of them, and does not change how a component's `archived` flag is written — the existing write paths are untouched. Callers ask for readiness and decide for themselves whether to proceed.

Reading readiness is governed by `DELETE_COMPONENTS`, the same permission that governs archiving. Restoring an archived component is governed by `ARCHIVE_COMPONENTS` and is out of scope.

## ADDED Requirements

### Requirement: Readiness is exposed as a per-target answer plus a single verdict

`GET rest/api/4/components/{idOrName}/archive-readiness` SHALL return one entry per target the check covers — each of the component's open-issues scopes, each of its issue-tracker projects, each of its TeamCity projects, each of its repositories — and a `ready` verdict that is true when no entry blocks.

Every entry SHALL be governed by exactly one rule. An entry SHALL NOT carry two independently-decided signals, because a caller then cannot read the outcome without also knowing which of the two produced it. The component's open issues and its issue-tracker project are therefore separate entries, decided separately, even though both are read from the same system and the same project.

Each entry SHALL carry the target's kind, its identity, its outcome, a reason when it does not simply pass, and the live components that still use the target. The endpoint SHALL accept the same id-or-name path form as the other v4 component endpoints, and SHALL require `DELETE_COMPONENTS`.

The verdict SHALL be computed from the entries by CRS so that callers never have to. An outcome that blocks SHALL make `ready` false.

A target the registry does not record for the component SHALL produce no entry — a component with no VCS entries, no version lines, or no issue-tracker project key is simply checked on fewer targets. An absent target SHALL NOT produce a placeholder entry and SHALL NOT block.

A target reached through more than one registry row on the same component — two version lines pointing at the same TeamCity project, for instance — SHALL still produce exactly one entry, keyed by the target's own identity rather than by the row that discovered it.

#### Scenario: Every target is reported

- **WHEN** a component has an issue-tracker project, two TeamCity projects and one repository
- **THEN** the response carries four entries, each naming its target

#### Scenario: The verdict follows the entries

- **WHEN** every entry passes
- **THEN** `ready` is true

#### Scenario: One blocking entry makes the component not ready

- **WHEN** one entry blocks and all others pass
- **THEN** `ready` is false

#### Scenario: A component with no repositories and no build projects is ready

- **WHEN** a component has no VCS entries and no version lines
- **THEN** the response carries no target entries for them and `ready` is true

#### Scenario: A component with no issue-tracker project key is ready

- **WHEN** a component records no issue-tracker project key
- **THEN** the response carries neither issue-tracker entry, and their absence does not make `ready` false

#### Scenario: A component's own duplicate reference is not double-counted

- **WHEN** a component has two version lines that reference the same TeamCity project
- **THEN** the response carries exactly one entry for that project

#### Scenario: An unknown component is not found

- **WHEN** readiness is requested for an id or name no component has
- **THEN** the request yields 404, not an empty readiness answer

#### Scenario: The endpoint requires the archive permission

- **WHEN** a caller without `DELETE_COMPONENTS` requests readiness
- **THEN** the request is rejected

#### Scenario: Readiness never mutates

- **WHEN** readiness is requested for any component
- **THEN** no component field changes and nothing is archived in any external system

### Requirement: An outcome is COMPLETED, NOT_COMPLETED, or UNKNOWN

Each entry's outcome SHALL be one of exactly three values. `COMPLETED` means nothing on the entry blocks — the target was read and is archived, or it is not archived but is still needed by a live component, or it no longer exists. `NOT_COMPLETED` means the entry was read and something on it blocks. `UNKNOWN` means the entry's state could not be determined.

The three ways an entry can pass mean different things about the external system — retired, deliberately still running, gone — so each SHALL carry a reason distinguishing it. A caller must be able to tell them apart without inspecting the external system itself.

`NOT_COMPLETED` and `UNKNOWN` SHALL both block, and SHALL be reported distinguishably — an unreachable system is not evidence that a target is unarchived, and the two call for different action. `UNKNOWN` SHALL carry a reason naming the system that could not be consulted.

There SHALL NOT be a separate outcome for a shared target; see the sharing requirement.

#### Scenario: An archived target passes

- **WHEN** a repository reports as archived
- **THEN** its entry's outcome is `COMPLETED`

#### Scenario: A live target fails

- **WHEN** a repository reports as not archived and no live component shares it
- **THEN** its entry's outcome is `NOT_COMPLETED`

#### Scenario: An unreadable target is UNKNOWN, not NOT_COMPLETED

- **WHEN** the repository's archived state comes back unresolved
- **THEN** its entry's outcome is `UNKNOWN`, not `NOT_COMPLETED`, and its reason names the system

#### Scenario: An unreachable system blocks

- **WHEN** one entry's outcome is `UNKNOWN` and every other entry passes
- **THEN** `ready` is false

### Requirement: Each external system is proved live once per readiness call

Before any target's state is read from an external system, the check SHALL establish that the system is reachable and that CRS's credential for it still works, once per readiness call per system.

When that fails, every target belonging to that system SHALL be `UNKNOWN` under **one** reason describing the system — not one mystery reason per target. A broken integration is a property of the system, and reporting it N times as N unreadable targets hides the fact that nothing is wrong with the component at all.

Establishing liveness SHALL NOT require a call specific to the component being checked, so a component with a single target is not left with nothing to corroborate against.

Where a system is reached through more than one independent connection — the issue tracker's issue-search client and its project-read client are two such connections sharing one base URL — each connection SHALL be proved live on its own. A failure on one connection SHALL NOT make the other connection's entries `UNKNOWN`.

#### Scenario: A dead integration is reported once, about the system

- **WHEN** the credential for the VCS system no longer works and the component has three repositories
- **THEN** all three entries are `UNKNOWN` and their reason identifies the VCS integration, not three separate target problems

#### Scenario: One system failing does not taint the others

- **WHEN** the VCS system cannot be reached and TeamCity and the issue tracker both answer
- **THEN** only the repository entries are `UNKNOWN`, and the other entries carry their real outcomes

#### Scenario: Liveness does not depend on the component's own targets

- **WHEN** a component has exactly one repository and the VCS system is live
- **THEN** liveness is established without reading that repository first, and the entry carries its real outcome

#### Scenario: The issue tracker's two connections are probed independently

- **WHEN** the issue-search connection cannot be reached but the project-read connection can
- **THEN** every `JIRA_ISSUES` entry is `UNKNOWN` and every `JIRA_PROJECT` entry carries its real outcome

### Requirement: A target the system reports absent passes only when absence cannot be confused with a permission failure

When an external system positively reports that a target does not exist, the entry SHALL be `COMPLETED` with a reason stating the target no longer exists — **but only for target kinds where that report is known not to be confusable with a permission or credential problem.** A deleted build project is not live infrastructure, which is what the check asks about — it is a stronger end state than archived, not an unresolved one. TeamCity's project-read is such a target kind.

The VCS repository target kind is not: a hosting platform may answer "not found" for a repository that exists but the credential may not see — a deliberate configuration in hardened installations, so that probing cannot enumerate private repositories — and CRS has no way to confirm its configured VCS credential is provisioned with instance-wide read access that would rule this out. So for `REPOSITORY`, an absent report SHALL be `UNKNOWN`, regardless of whether the VCS system was proved live in the same call: liveness proves the credential authenticates, not that it can see every specific repository. Absence SHALL never be inferred from a report whose meaning is uncertain for that target kind.

This SHALL apply only when the relevant system was proved live in the same readiness call in the first place — on a system not proved live, an absent report SHALL be `UNKNOWN` regardless of target kind, same as any other read on that system.

An entry that passes because its target is absent SHALL report an empty `sharedWith`, even if the registry separately still records a live component referencing that same target. Absence, not sharing, decided this outcome, and an entry's outcome SHALL be decided by exactly one rule — the same invariant that governs every other entry.

#### Scenario: A deleted repository does not pass

- **WHEN** the VCS system is proved live and reports that the component's repository does not exist
- **THEN** the entry is `UNKNOWN`, not `COMPLETED` — this system's absent report cannot be told apart from a permission failure

#### Scenario: A deleted build project passes

- **WHEN** TeamCity is proved live and reports that the component's project does not exist
- **THEN** the entry is `COMPLETED` and its reason states the project no longer exists

#### Scenario: Absent on an unproved system is UNKNOWN

- **WHEN** TeamCity was not proved live and reports that a project does not exist
- **THEN** the entry is `UNKNOWN`, not `COMPLETED`

#### Scenario: A permission failure is never read as absence

- **WHEN** a target read fails in a way that could be either absence or a permission problem
- **THEN** the entry is `UNKNOWN`

#### Scenario: An absent build project does not block

- **WHEN** the only non-archived target is a TeamCity project the system reports as absent, on a proved-live system
- **THEN** `ready` is true

#### Scenario: Absence does not borrow sharing's field

- **WHEN** a TeamCity project is reported absent on a system proved live, and the registry separately still records a live component referencing that same project
- **THEN** the entry is `COMPLETED` for absence, and `sharedWith` is empty

### Requirement: A system that is not configured contributes no entries

When CRS has no configuration for an external system, the check SHALL omit that system's targets entirely rather than reporting them as `UNKNOWN`.

Not configured and configured-but-failing are different facts and SHALL NOT share an outcome. Treating them alike makes the check impossible to deploy before every environment is configured, and gives an operator no way out of an integration that is permanently broken: turning it off is the remedy, and that remedy only exists if "off" means "not checked" rather than "blocks forever".

Where a system is reached through more than one independent connection, each connection SHALL be configured independently: the issue tracker's issue-search connection and its project-read connection each being on or off SHALL determine `JIRA_ISSUES` and `JIRA_PROJECT` separately, not as one switch for both.

#### Scenario: An unconfigured system is not checked

- **WHEN** no VCS integration is configured and the component has two repositories
- **THEN** the response carries no repository entries, and their absence does not make `ready` false

#### Scenario: A configured but failing system still blocks

- **WHEN** the VCS integration is configured and cannot be reached
- **THEN** its entries are `UNKNOWN` and `ready` is false

#### Scenario: Systems are configured independently

- **WHEN** the issue tracker is configured and TeamCity is not
- **THEN** both issue-tracker entries are checked and reported, and no TeamCity entry appears

### Requirement: A target the system cannot resolve at all reads as a registry problem

When a target recorded on the component cannot be resolved to any system CRS knows how to consult — a repository URL on a host no configured VCS provider serves, for instance — the entry SHALL be `UNKNOWN` with a reason identifying it as a registry-data problem rather than an outage.

The action differs: an outage is waited out, whereas this is corrected by editing the component. A reason that says the system is unavailable sends someone to check a system that is working fine.

#### Scenario: An unservable repository URL reads as a data problem

- **WHEN** a component records a repository URL that no configured VCS provider serves
- **THEN** the entry is `UNKNOWN` and its reason identifies the recorded URL as unresolvable, not the VCS system as unavailable

#### Scenario: A data problem does not implicate a healthy system

- **WHEN** one repository URL is unresolvable and another is read successfully
- **THEN** only the unresolvable entry is `UNKNOWN`, and no reason states that the VCS system is unavailable

### Requirement: An UNKNOWN entry classifies the remedy it needs

Every `UNKNOWN` entry SHALL carry, alongside its prose reason, a classification of what would resolve it: the system was unavailable, the registry's own data cannot be resolved, or the required configuration is absent. A caller SHALL be able to act on that classification without parsing the reason text.

The three need opposite actions. An unavailable system is waited out and retried. A registry-data problem never resolves by retrying and is corrected by editing the component. Missing configuration is corrected in CRS. Offering one remedy for all three means offering advice that can never succeed for two of them.

The classification SHALL be absent on `COMPLETED` and `NOT_COMPLETED`, where the outcome already implies what to do.

#### Scenario: An unreachable system classifies as unavailable

- **WHEN** an entry is `UNKNOWN` because its system could not be consulted
- **THEN** its classification says the system was unavailable

#### Scenario: An unresolvable recorded URL classifies as registry data

- **WHEN** an entry is `UNKNOWN` because no configured provider serves the recorded repository URL
- **THEN** its classification says the registry data cannot be resolved, not that the system was unavailable

#### Scenario: A null-prefix conflict classifies as registry data

- **WHEN** an open-issues entry is `UNKNOWN` because two pairs both claim a null prefix on one project key
- **THEN** its classification says the registry data cannot be resolved

#### Scenario: A missing retired-category configuration classifies as not configured

- **WHEN** the issue-tracker project entry is `UNKNOWN` because no retired category is configured
- **THEN** its classification says the configuration is absent, not that the system was unavailable

#### Scenario: A passing or failing entry carries no classification

- **WHEN** an entry is `COMPLETED` or `NOT_COMPLETED`
- **THEN** it carries no remedy classification

### Requirement: A target still used by a live component is not blocked by being unarchived, and the sharing is reported

When a target is also used by a component that is not archived, the entry SHALL list those components, and the target's own archived state SHALL NOT block — for a target a live component still needs, "not archived" is the correct end state, not a failure.

Sharing applies only to entries whose subject is a shared target — the issue-tracker project, a TeamCity project, a repository. It SHALL NOT apply to an entry whose subject is scoped to the component being checked; the open-issues entry is the only such entry, and its `sharedWith` SHALL always be empty. Another component using the same issue-tracker project does not close this component's issues, so there is nothing for sharing to excuse.

The listed components SHALL be structured data — the component names — not prose inside the reason. Two consumers read it: the Portal, to tell the operator the target was left running, and the follow-up work that performs the archives, to name every target it will skip.

The component being checked SHALL NOT appear in its own entry's list, and archived components SHALL NOT appear.

#### Scenario: A shared, unarchived target passes

- **WHEN** a TeamCity project is not archived and one live component other than this one uses it
- **THEN** its entry's outcome is `COMPLETED` and that component is listed

#### Scenario: Several sharing components are all listed

- **WHEN** two live components other than this one use the same repository
- **THEN** both are listed on that entry

#### Scenario: The component being checked is not listed as sharing its own target

- **WHEN** a target is used only by the component being checked
- **THEN** its entry lists no components

#### Scenario: An archived component does not count as sharing

- **WHEN** the only other component using a target is itself archived
- **THEN** that component is not listed, and the target's outcome follows its own archived state

#### Scenario: Sharing does not make a component ready on its own

- **WHEN** one target is shared with a live component and another target is not archived and unshared
- **THEN** the shared target passes, the unshared target fails, and `ready` is false

### Requirement: A repository target is matched by canonical URL, and consulted by the stored one

Two registry entries SHALL count as the same repository when their URLs differ only in ways that do not change which repository they name — scheme, a trailing `.git`, a trailing slash, or letter case in the host and path. Without this, the same repository recorded two ways in two components reads as two targets, and a live component's use of it is missed — a miss in the dangerous direction, since the entry would then report unshared and `NOT_COMPLETED` rather than shared.

Canonicalisation SHALL apply to comparison only. The URL sent to the VCS system SHALL be the one stored on the component, unmodified, so CRS never parses a project key or a slug and never branches on the hosting platform.

#### Scenario: The same repository recorded two ways is one target

- **WHEN** a live component records the same repository as the checked component, differing only by scheme, a `.git` suffix, a trailing slash, or case
- **THEN** that component is listed as sharing the repository

#### Scenario: The stored URL is what is consulted

- **WHEN** a repository's archived state is read
- **THEN** the URL stored on the component is passed to the VCS system unchanged, whatever canonical form was used for comparison

### Requirement: A TeamCity project's whole subtree is considered

Archiving a TeamCity project archives everything beneath it, so the sharing check for a TeamCity target SHALL consider that project's descendants as well as the project itself. Descendants SHALL be resolved from TeamCity, not inferred from project id text.

Project ids do not reliably encode the hierarchy — a substantial minority of projects on the live server have an id that does not begin with their parent's id — so a prefix comparison would misjudge sharing in the dangerous direction, reporting that nothing else is affected while a live component's project sits underneath.

#### Scenario: A descendant belonging to a live component is detected

- **WHEN** the component's TeamCity project has a descendant project used by a live component
- **THEN** that component is listed on the TeamCity entry and the entry passes

#### Scenario: Sharing is not inferred from the id

- **WHEN** a live component's TeamCity project is a descendant whose id does not begin with the ancestor's id
- **THEN** it is still detected as sharing

#### Scenario: The project itself is included

- **WHEN** a live component uses exactly the same TeamCity project, with no descendants involved
- **THEN** that component is listed

#### Scenario: TeamCity being unreachable is UNKNOWN, not "no descendants"

- **WHEN** the descendant lookup cannot be completed
- **THEN** the TeamCity entry's outcome is `UNKNOWN`, and it is not reported as unshared

### Requirement: A component's issue-tracker scope is every project it effectively uses, not only its current default

A component's Jira configuration is not a single scalar pair. A base configuration applies to the whole component, and a version-range-scoped override can layer a different project key, a different version prefix, or both, over a slice of the component's own version range — and both the base pair and every effective override pair are live at once; nothing retires an old range's pair when a newer range's override shadows it.

The check SHALL enumerate every distinct effective `(project key, version prefix)` pair the component carries — across its base configuration and every version-range override — using the same resolution the registry already uses to enforce uniqueness of these pairs across components, rather than reading a single row's columns directly. Checking only the base pair SHALL NOT be sufficient: it would let an older version range's still-active, still-unretired project pass unnoticed, which is the same dangerous-direction failure the TeamCity descendant check and the repository canonicalisation rules exist to close elsewhere in this capability.

The check SHALL produce one `JIRA_ISSUES` entry per distinct effective pair, and one `JIRA_PROJECT` entry per distinct project key among those pairs — a project key claimed by two pairs with different prefixes (two of the component's own ranges sharing one project) is one project, checked once.

#### Scenario: A second version range's project is also checked

- **WHEN** a component's base configuration uses one issue-tracker project and a version-range override uses a different one
- **THEN** the response carries a `JIRA_ISSUES` entry and a `JIRA_PROJECT` entry for each of the two projects

#### Scenario: Two ranges sharing one project produce one project entry

- **WHEN** two of a component's version ranges have different effective version prefixes but the same effective project key
- **THEN** the response carries one `JIRA_PROJECT` entry for that project key, and a separate `JIRA_ISSUES` entry for each of the two prefixes

#### Scenario: A component with one Jira pair is unaffected

- **WHEN** a component's issue-tracker configuration has no version-range override
- **THEN** the response carries exactly one `JIRA_ISSUES` entry and one `JIRA_PROJECT` entry

### Requirement: Each open-issues entry is scoped to one effective pair, and never excused

One `JIRA_ISSUES` entry reports the issues still open under one of the component's effective `(project key, version prefix)` pairs. Its subject is that pair's own unfinished work, not a target other components share, so no sharing SHALL excuse it and its `sharedWith` SHALL always be empty.

The open issues SHALL be returned on the entry as structured data so a caller can list them, rather than only counted.

When the pair's version prefix is set, the entry SHALL be scoped by that prefix and SHALL NOT return an issue scoped to a different prefix in the same project.

When the pair's version prefix is null, the pair holds the project's default, unprefixed bucket. A null prefix SHALL NOT be treated as missing or invalid data — the registry's own uniqueness rule already treats `(project key, null)` as one legitimate claim among the claims on a shared project, on the same footing as any non-null prefix.

When no other pair claims that project key at all, the scope SHALL be the whole project's open issues — nothing else has a legitimate claim to any of them. When another pair does claim the same project key, the entry's scope SHALL be every open issue whose recorded version carries no prefix at all — a bare version string — decided by inspecting that issue's own recorded value, and SHALL NOT require enumerating the other pairs' specific registered prefixes.

An issue whose recorded version matches neither a bare pattern nor any pair's registered prefix SHALL be counted by no pair's entry. This is accepted: it is not resolved by falling back to excluding the other pairs' exact prefixes.

The entry SHALL be `UNKNOWN`, with a reason naming it as a registry-data problem, only where the scope is genuinely undecidable — two pairs both claiming a null prefix on the same project key, a state the registry's own uniqueness rule is expected to prevent from ever occurring. A null prefix on its own SHALL NOT produce `UNKNOWN`.

#### Scenario: An open issue blocks

- **WHEN** an issue is open under one of the component's effective pairs
- **THEN** that pair's `JIRA_ISSUES` entry is `NOT_COMPLETED` and the issue is returned on it

#### Scenario: Sharing does not excuse open issues

- **WHEN** an issue is open under one of the component's effective pairs and another live component uses the same project key
- **THEN** that entry is still `NOT_COMPLETED`, and its `sharedWith` is empty

#### Scenario: No open issue passes

- **WHEN** no issue is open under a pair's scope
- **THEN** that pair's entry is `COMPLETED`

#### Scenario: Another pair's open issue does not block

- **WHEN** the issue-tracker project has an open issue scoped to a different pair — another component's, or another of this component's own version ranges
- **THEN** it is not returned on this pair's entry and does not make it block

#### Scenario: A sole claim on a project is scoped by the whole project

- **WHEN** a pair has no version prefix and no other pair — this component's own or another component's — claims the same project key
- **THEN** that pair's entry is scoped to every open issue in the project

#### Scenario: A default-bucket claim is scoped by a bare version pattern

- **WHEN** a pair has no version prefix and another pair claims the same project key with its own registered prefix
- **THEN** that pair's entry counts an open issue only if its recorded version carries no prefix at all, independent of the other pair's specific prefix

#### Scenario: An unmatched version is counted by nobody

- **WHEN** an issue's recorded version matches neither a bare pattern nor any pair's registered prefix
- **THEN** it is not returned on any pair's entry, and no pair is blocked by it

#### Scenario: A genuine null-prefix conflict is a data problem

- **WHEN** two pairs both claim no version prefix against the same project key
- **THEN** each pair's `JIRA_ISSUES` entry is `UNKNOWN`, naming the conflict as registry data to correct

### Requirement: The issue-tracker project entry reports the project's own retired state

One `JIRA_PROJECT` entry per distinct project key among the component's effective pairs SHALL report whether that project has been retired. Its subject is the project, which several components (or several of this component's own version ranges) can share, so it SHALL follow the ordinary sharing rule: a project another live component still uses SHALL be `COMPLETED` with those components listed, whatever its own state.

An issue-tracker project has no archived flag. Its retired state is recorded by reclassifying it into a designated project category — `X Archive` on the live tracker. CRS SHALL read the project's category and compare it against a configured set of categories that mean "retired". That set SHALL be configuration, not a constant compiled into CRS — the category names are the issue tracker's data and can be renamed there without a CRS release. When no such category is configured, this entry SHALL be `UNKNOWN` with a reason saying so, never `COMPLETED`.

The category SHALL be the only signal this entry reads. The retirement procedure also renames the project to carry an `[ARCHIVE]` marker, and that rename SHALL NOT be checked: a project name is free text, so matching it would fail on ordinary variation in spacing, bracketing or spelling, and a false `NOT_COMPLETED` here sends someone to redo a step that was already done. The category is a controlled vocabulary chosen from a fixed list, which is what makes it safe to match exactly.

`COMPLETED` on this entry SHALL mean the observable marker is set, not that the whole retirement procedure was carried out. The procedure's remaining steps — the issue-type scheme, the workflow scheme, the permission scheme and the notification scheme — are administrative settings readable only with issue-tracker administrator rights, which this check deliberately does not hold. They SHALL NOT be checked, and the entry SHALL NOT be presented as evidence that they were done.

#### Scenario: The category alone decides the outcome

- **WHEN** the project's category is a configured retired category but its name carries no `[ARCHIVE]` marker
- **THEN** the project entry is `COMPLETED`

#### Scenario: A rename without a recategorisation does not pass

- **WHEN** the project's name carries the `[ARCHIVE]` marker but its category is not a configured retired category
- **THEN** the project entry is `NOT_COMPLETED`

#### Scenario: A retired project passes

- **WHEN** the project's category is one of the configured retired categories
- **THEN** the project entry is `COMPLETED`

#### Scenario: A shared project's state is excused

- **WHEN** the project is not retired and another live component uses it
- **THEN** the project entry is `COMPLETED` and that component is listed

#### Scenario: An unretired, unshared project blocks

- **WHEN** the project is not retired and no live component shares it
- **THEN** the project entry is `NOT_COMPLETED`

#### Scenario: The retired categories are configuration

- **WHEN** the configured set of retired categories is changed
- **THEN** the project entry's outcome follows the new set without a code change

#### Scenario: No configured retired category is UNKNOWN, not retired

- **WHEN** no retired category is configured
- **THEN** the project entry is `UNKNOWN` with a reason saying so, and does not pass on that basis

#### Scenario: The two issue-tracker entries are decided independently

- **WHEN** an issue is open under one of the component's effective pairs and that pair's project is retired
- **THEN** that pair's `JIRA_ISSUES` entry is `NOT_COMPLETED`, its `JIRA_PROJECT` entry is `COMPLETED`, and `ready` is false

### Requirement: An entry that owes work names who owes it

Every entry that does not report `COMPLETED` SHALL name which party owns the remaining work. An entry reporting `COMPLETED` SHALL name none, because nothing is owed.

The assignment SHALL follow the target kind rather than the outcome's details. Open issues belong to the component's own people — nobody else can judge whether an issue may be closed. Every other target is infrastructure the platform team administers, so archiving a repository, archiving a build project and recategorising an issue-tracker project belong to that team, as does anything unreadable: an unavailable system, an unresolvable recorded URL and an absent configuration are all theirs to resolve.

This SHALL be a discrete value, not prose inside the reason, so a caller can group or filter by it without parsing text.

#### Scenario: Open issues are the component's own work

- **WHEN** an open-issues entry reports `NOT_COMPLETED`
- **THEN** it names the component owner as responsible

#### Scenario: A repository is the platform team's work

- **WHEN** a repository entry reports `NOT_COMPLETED`
- **THEN** it names the platform team as responsible

#### Scenario: A build project is the platform team's work

- **WHEN** a TeamCity project entry reports `NOT_COMPLETED`
- **THEN** it names the platform team as responsible

#### Scenario: An issue-tracker project is the platform team's work

- **WHEN** an issue-tracker project entry reports `NOT_COMPLETED`
- **THEN** it names the platform team as responsible

#### Scenario: Anything unreadable is the platform team's work

- **WHEN** any entry reports `UNKNOWN`, whatever its kind or classification
- **THEN** it names the platform team as responsible

#### Scenario: A completed entry owes nothing

- **WHEN** an entry reports `COMPLETED`
- **THEN** it names no responsible party

### Requirement: The check does not change how the archived flag is written

Readiness SHALL be a separate read. The write paths that set `archived` — the soft delete, component update, creation, and the bulk registry import — SHALL keep their current behaviour, SHALL NOT evaluate readiness, and SHALL NOT be refused by it.

A caller archives by asking for readiness first and then issuing the archive request it already issues today. CRS supplies the verdict; the caller applies it.

#### Scenario: The soft delete is unchanged

- **WHEN** the soft delete is called for a component with blocking entries
- **THEN** the component is archived, exactly as it is today, and readiness is not evaluated

#### Scenario: Component update is unchanged

- **WHEN** an update sets `archived` to true on a component with blocking entries
- **THEN** the update succeeds and readiness is not evaluated

#### Scenario: Creation and import are unchanged

- **WHEN** a component is created with `archived` true, or the bulk registry import sets the flag
- **THEN** readiness is not evaluated, and an unreachable external system cannot cause either to fail

#### Scenario: Requesting readiness archives nothing

- **WHEN** readiness is requested for a component that is ready
- **THEN** the component is not archived by that request

### Requirement: Sharing is determined by one shared computation

The sharing determination used by the readiness check SHALL be the same computation any other feature uses to answer whether a target is still needed by a live component. It SHALL NOT be reimplemented per call site.

Two implementations of the same question will eventually disagree, and the disagreement means the gate reports a target as safe while the code that performs the archives reports it as shared, or the reverse — with an irreversible operation in between.

The follow-up work that performs the archives does not exist yet, so this requirement is enforced structurally rather than by comparing two answers: the sharing determination SHALL live in exactly one unit, and no other production code SHALL query the registry for which components use a target.

#### Scenario: Sharing has exactly one implementation

- **WHEN** production code needs to know which live components use a target
- **THEN** it obtains that from the single sharing unit, and no second implementation of the query exists

#### Scenario: The readiness check does not compute sharing itself

- **WHEN** the readiness check reports a target's sharing
- **THEN** the components it lists are exactly those the sharing unit returned for that target
