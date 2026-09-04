> `components-registry-service-server` only. No schema change, no migration.
>
> Test-first: each task that changes behaviour starts with a failing test.
> `./gradlew :components-registry-service-server:test` for the unit loop,
> `./gradlew qualityStatic` and `./gradlew qualityCoverage` before reporting a
> group done.
>
> **Additive only.** No existing endpoint or service method changes behaviour.
> The check is a new endpoint; `deleteComponent`, `updateComponent`,
> `createComponent` and `ImportServiceImpl` are untouched, and group 7 asserts
> that. The verdict is advisory — CRS refuses no write.
>
> External systems are stubbed throughout groups 1-7. Group 8 is the only place
> a real TeamCity / issue tracker / vcs-facade is needed.
>
> Portal builds against the response contract in `design.md`. If that shape
> changes here, mirror it in
> `octopus-components-management-portal/openspec/changes/component-archive-readiness-gate/design.md`.

## 1. Dependencies

- [x] 1.1 **Confirmed.** `org.octopusden.octopus.vcsfacade:client:3.0.36` (Maven Central `latest`/`release`) publishes `VcsFacadeClient.getRepository(String)` (`GET rest/api/2/repository?sshUrl={sshUrl}`) and `Repository.getArchived()` returns a boxed (nullable) `Boolean` — verified by downloading and inspecting both `client-3.0.36.jar` and `common-3.0.36.jar` directly. `Repository` lives in `org.octopusden.octopus.vcsfacade:common`, pulled transitively by `client`. Neither the method nor the field exists at `3.0.27`, the version already resolvable locally at the time this was written
- [ ] 1.2 Add the vcs-facade client dependency once 1.1 confirms the shape, pinned in `gradle.properties` like every other external version
- [ ] 1.3 Add the Atlassian REST Java client (`jira-rest-java-client-api`, `-core`) and `io.atlassian.fugue:fugue`, pinned in `gradle.properties`
- [ ] 1.4 Add the octopus `jira-client` — needed only for `getProject`'s `projectCategory`, which the Atlassian client does not expose. It is published under the `octopus-external-systems-clients.version` already pinned for `teamcity-client`, so this adds a module, not a version
- [ ] 1.5 Register both issue-tracker clients as beans, inert when no base URL is configured, following the existing `TeamcityProperties` pattern of a blank default that does not attempt a connection
- [ ] 1.6 Add the configured set of issue-tracker categories that mean "retired", alongside the tracker's base URL. Default empty (so an unconfigured deployment yields `UNKNOWN`, not a silent pass); the live value is the single literal category `X Archive`
- [ ] 1.7 `./gradlew qualityStatic` clean with the new dependencies on the classpath

## 2. Sharing helper

- [ ] 2.1 Failing test: a repository used by exactly one component reports no sharing
- [ ] 2.2 Failing test: a repository used by a second, non-archived component reports that component
- [ ] 2.3 Failing test: a repository used by a second, archived component reports no sharing
- [ ] 2.4 Failing test: the component being checked never appears in its own result
- [ ] 2.5 Failing test: repository URLs that differ only by scheme, a `.git` suffix, a trailing slash, or case are treated as the same target — canonicalisation is for comparison only and never changes the URL sent to vcs-facade (see 4.5)
- [ ] 2.6 Failing test: a TeamCity project used by a second, non-archived component reports that component
- [ ] 2.7 Failing test: a TeamCity project whose *descendant* is used by a non-archived component reports that component
- [ ] 2.8 Failing test: a descendant whose project id does not begin with the ancestor's id is still detected — no prefix inference
- [ ] 2.9 Failing test: the intersection runs registry-ids-against-descendants, so a descendant set of thousands does not produce a query over thousands of ids
- [ ] 2.10 Failing test: an issue-tracker project key claimed by a second, non-archived component reports that component
- [ ] 2.11 Implement the helper as one unit with three target kinds, over `findDistinctLinkedProjectIds` and `findByProjectIdsWithComponent`
- [ ] 2.12 Add an ArchUnit rule in the existing `ArchitectureFitnessTest` asserting the sharing unit is the only production code that queries the registry for which components use a target — this is what makes "one computation" enforceable before the follow-up work that would otherwise duplicate it exists

## 3. TeamCity descendant lookup

- [ ] 3.1 Failing test: the lookup issues an `affectedProject` locator query for the given project id
- [ ] 3.2 Failing test: the queried project itself is present in the returned set even though the API omits it
- [ ] 3.3 Failing test: the request asks for `archived` explicitly — a `fields` list that omits it silently yields null
- [ ] 3.4 Failing test: a failure from TeamCity surfaces as unresolved, never as an empty descendant set
- [ ] 3.5 Implement, separate from the sync's fetcher — the sync's `parameter:(name:COMPONENT_NAME)` locator cannot see unparameterised descendants. Do not touch the sync's `PROJECT_FIELDS`: widening it changes what every sync run fetches and stores, which this change's additive-only constraint excludes, and the gate does not depend on it

## 4. The four entry kinds

- [ ] 4.1 Failing test: a repository reporting `archived = true` yields `COMPLETED`
- [ ] 4.2 Failing test: `archived = false` with no sharing yields `NOT_COMPLETED`
- [ ] 4.3 Failing test: `archived = null` yields `UNKNOWN` with a reason naming the system
- [ ] 4.4 Failing test: a repository the facade reports as absent yields `COMPLETED` with a reason saying it no longer exists — **on a system proved live**. Absence is a definite answer, and a deleted repository is not live infrastructure
- [ ] 4.4a Failing test: the same absent report on a system *not* proved live yields `UNKNOWN`, never `COMPLETED`
- [ ] 4.4b Failing test: a read failing in a way that could be either absence or a permission problem yields `UNKNOWN`
- [ ] 4.5 Failing test: `vcsPath` is passed through to `getRepository` unmodified — no project-key or slug parsing, no per-platform branching
- [ ] 4.6 Failing test: an archived TeamCity project yields `COMPLETED`; an unarchived, unshared one yields `NOT_COMPLETED`
- [ ] 4.7 Failing test: a TeamCity project whose state could not be read yields `UNKNOWN`
- [ ] 4.7a Failing test: a TeamCity project reported absent, on a proved-live system, yields `COMPLETED` with a reason saying it no longer exists
- [ ] 4.7b Failing test: a repository URL no configured provider serves yields `UNKNOWN` whose reason names the recorded URL as unresolvable, and does not say the VCS system is unavailable
- [ ] 4.8 Failing test: the `JIRA_ISSUES` entry for a given effective pair — an issue open under that pair's scope yields `NOT_COMPLETED`, and the issue is returned on the entry as structured data
- [ ] 4.9 Failing test: `JIRA_ISSUES` still `NOT_COMPLETED` when another live component uses the same project key, and its `sharedWith` is empty — sharing never applies to this entry
- [ ] 4.10 Failing test: no open issue under the pair's scope yields `COMPLETED`
- [ ] 4.11 Failing test: an issue scoped to a *different* pair — another component's, or another of this component's own version ranges — is neither returned nor blocking
- [ ] 4.12 Failing test: a pair with a null prefix and no other pair claiming the same project key is scoped to the whole project, and yields `COMPLETED`/`NOT_COMPLETED` normally — a null prefix alone SHALL NOT yield `UNKNOWN`
- [ ] 4.12a Failing test: a pair with a null prefix and another pair claiming the same project key with its own registered prefix is scoped to issues whose recorded version carries no prefix at all — a bare version string — decided without reading the other pair's specific prefix
- [ ] 4.12b Failing test: two pairs both claiming a null prefix on the same project key yield `UNKNOWN` on both, naming the conflict as registry data — the one genuine anomaly left in this area
- [ ] 4.12c Failing test: an issue whose recorded version matches neither a bare pattern nor any pair's registered prefix is counted by no pair's entry and blocks nobody — an accepted trade-off (design decision 15), not a bug to work around by falling back to exclusion
- [ ] 4.13 Failing test: the `JIRA_PROJECT` entry — the category from `getProject(key).projectCategory` matched against the configured retired set yields `COMPLETED`; changing the configured set changes the outcome with no code change
- [ ] 4.13a Failing test: the category is the only signal — a project whose category matches yields `COMPLETED` even with no `[ARCHIVE]` marker in its name, and a project whose name carries the marker but whose category does not match yields `NOT_COMPLETED`. The project name is never read
- [ ] 4.13b Failing test: two of a component's own effective pairs sharing one project key produce exactly one `JIRA_PROJECT` entry for it, not two
- [ ] 4.14 Failing test: `JIRA_PROJECT` not retired but shared yields `COMPLETED` and lists the sharing component; not retired and unshared yields `NOT_COMPLETED`
- [ ] 4.15 Failing test: with no retired category configured, `JIRA_PROJECT` is `UNKNOWN` with a reason saying so, never `COMPLETED`
- [ ] 4.16 Failing test: an issue-tracker outage yields `UNKNOWN` on every `JIRA_ISSUES` and `JIRA_PROJECT` entry the component has, and is not read as "no open issues"
- [ ] 4.17 Failing test: two of a component's effective pairs are decided independently — one pair's project retired with an open issue on that pair yields that pair's `JIRA_ISSUES: NOT_COMPLETED` alongside its own `JIRA_PROJECT: COMPLETED`, while the other pair's entries reflect its own, unrelated state
- [ ] 4.18 Implement the four checks behind one internal interface, so the assembler does not know which system answered. `JIRA_ISSUES` and `JIRA_PROJECT` are two checks over one client, run once per effective pair (issues) or once per distinct project key (project) — not one check returning two verdicts, and not one run per component

## 4a. The component's Jira scope is a set of effective pairs

Why this exists: a component's Jira configuration is not one `(project key, prefix)` pair — a base row plus per-version-range overrides can each claim a different pair, and all are live at once. See design decision 15 and ADR-018 (decoupled version model). Checking only the base pair would silently skip an older range's still-active, unretired project.

- [ ] 4a.1 Failing test: a component with no version-range override on its Jira configuration resolves to exactly one effective pair
- [ ] 4a.2 Failing test: a component with a version-range override on `jira.projectKey` resolves to two effective pairs, one per project key
- [ ] 4a.3 Failing test: a component with a version-range override on `jira.versionPrefix` only resolves to two effective pairs sharing one project key
- [ ] 4a.4 Failing test: the resolution reuses the registry's existing effective-pair computation (`computeEffectiveJiraPairs` / the same call `ComponentManagementServiceImpl` already makes for cross-component uniqueness), not a reimplementation reading raw `ComponentConfigurationEntity` rows
- [ ] 4a.5 Failing test: detecting whether *any* other pair claims a null-prefix pair's project key (to choose whole-project vs bare-pattern scope) checks every other component's pairs AND every other version range of this same component — but never needs their specific prefix strings, only whether one exists and whether it is also null
- [ ] 4a.6 Implement the enumeration as the entry point the assembler calls to discover Jira targets, before any external read; the assembler produces one `JIRA_ISSUES` entry per pair and one `JIRA_PROJECT` entry per distinct project key among them (task 4.13b)

## 4b. Liveness probe and unconfigured systems

Why this exists: `UNKNOWN` blocks and there is no override, so a target whose state can never be read makes its component permanently unarchivable. See design decisions 11-14, 17.

- [ ] 4b.1 Failing test: each system is probed once per readiness call, not once per target
- [ ] 4b.2 Failing test: a failed probe makes every target of that system `UNKNOWN` under one reason identifying the system, not one reason per target
- [ ] 4b.3 Failing test: one system failing its probe leaves the other systems' entries carrying their real outcomes
- [ ] 4b.4 Failing test: liveness is established without reading the component's own targets first, so a component with a single target still gets a real outcome
- [ ] 4b.5 Failing test: an unconfigured system contributes no entries at all, and their absence does not make `ready` false
- [ ] 4b.6 Failing test: a configured system that fails contributes `UNKNOWN` entries and makes `ready` false — not configured and configured-but-failing never share an outcome
- [ ] 4b.7 Failing test: systems are configured independently, so one being unconfigured does not affect another's entries
- [ ] 4b.7a Failing test: the issue tracker's two connections — issue search and project read — are probed independently; a failure on one leaves the other's entries carrying real outcomes
- [ ] 4b.7b Failing test: the issue tracker's two connections are configured independently; one unconfigured while the other is configured checks only the configured one's entries
- [ ] 4b.9 Failing test: an `UNKNOWN` from an unreachable system classifies `SYSTEM_UNAVAILABLE`
- [ ] 4b.10 Failing test: an `UNKNOWN` from an unresolvable recorded URL, or from two pairs both claiming a null prefix on one project key, classifies `REGISTRY_DATA`
- [ ] 4b.11 Failing test: the issue-tracker project entry with no retired category configured classifies `NOT_CONFIGURED`
- [ ] 4b.12 Failing test: a `COMPLETED` or `NOT_COMPLETED` entry carries no classification
- [ ] 4b.8 Implement the per-system probe, the unconfigured-system omission, and the remedy classification, with the issue tracker's two connections as independent units throughout

## 4c. Responsibility

- [ ] 4c.1 Add `responsibility` to the entry DTO as `COMPONENT_OWNER | F1_TEAM | null` — a discrete value, not prose in `reason`, so a caller can group and filter by it
- [ ] 4c.2 Failing test: a `NOT_COMPLETED` open-issues entry names `COMPONENT_OWNER` — only the component's own people can judge whether an issue may be closed
- [ ] 4c.3 Failing test: a `NOT_COMPLETED` repository, TeamCity or issue-tracker-project entry names `F1_TEAM`
- [ ] 4c.4 Failing test: any `UNKNOWN` entry names `F1_TEAM`, whatever its kind or `reasonKind` — unavailable systems, unresolvable URLs and absent configuration are all the platform team's
- [ ] 4c.5 Failing test: a `COMPLETED` entry names nobody
- [ ] 4c.6 Implement, keyed on target kind rather than on the outcome's details

## 5. Assembler and verdict

- [ ] 5.1 Failing test: every target the component has produces exactly one entry
- [ ] 5.1a Failing test: two of a component's own version lines pointing at the same TeamCity project yield exactly one entry, not two — entries are keyed by target identity, not by the registry row that discovered them
- [ ] 5.2 Failing test: a component with no VCS entries and no version lines produces no target entries and `ready = true`
- [ ] 5.3 Failing test: a component with a null `jiraProjectKey` produces neither issue-tracker entry — an absent target is never a placeholder entry and never blocks
- [ ] 5.3a Failing test: a component with one effective Jira pair (no version-range override) produces exactly two issue-tracker entries, `JIRA_ISSUES` and `JIRA_PROJECT`, each appearing once
- [ ] 5.3b Failing test: a component with two effective Jira pairs on different project keys produces four issue-tracker entries — two `JIRA_ISSUES`, two `JIRA_PROJECT`
- [ ] 5.3c Failing test: a component with two effective Jira pairs sharing one project key (a prefix-only override) produces three issue-tracker entries — two `JIRA_ISSUES`, one `JIRA_PROJECT`
- [ ] 5.3d Failing test: two `JIRA_ISSUES` entries on the same component (two effective pairs) never carry the same `targetId`, including when both pairs have a null prefix on different projects
- [ ] 5.4 Failing test: all entries passing yields `ready = true`
- [ ] 5.5 Failing test: one `NOT_COMPLETED` entry yields `ready = false`
- [ ] 5.6 Failing test: one `UNKNOWN` entry yields `ready = false`
- [ ] 5.7 Failing test: entries whose only non-passing reason is sharing yield `ready = true`
- [ ] 5.8 Failing test: `sharedWith` carries component names, and `reason` is not the carrier of that information
- [ ] 5.8a Failing test: `openIssues` is populated only on `JIRA_ISSUES`, and `sharedWith` is empty on every `JIRA_ISSUES` entry
- [ ] 5.8b Failing test: an entry that passes because its target is absent reports empty `sharedWith`, even when the registry separately lists another live component referencing that same target — absence, not sharing, decided the outcome
- [ ] 5.9 Failing test: `targetUrl` is null rather than a fabricated link when one cannot be built
- [ ] 5.10 Failing test: assembling readiness mutates nothing
- [ ] 5.11 Implement the assembler

## 6. Endpoint

- [ ] 6.1 Failing test: `GET rest/api/4/components/{id}/archive-readiness` returns the contract shape
- [ ] 6.2 Failing test: the path resolves both a UUID and a component name, like the sibling v4 endpoints
- [ ] 6.3 Failing test: a caller without `DELETE_COMPONENTS` is rejected
- [ ] 6.4 Failing test: an unknown component yields 404
- [ ] 6.5 Implement on `ComponentControllerV4`
- [ ] 6.6 Refresh the committed spec with `./gradlew :components-registry-service-server:generateOpenApiDocs` and commit `src/main/resources/openapi/v4.json`. This is not optional: `OpenApiV4SpecTest` is untagged, runs under `test` → `check`, and fails on any drift between the live v4 controllers and the committed file — so 8.1 fails until this is done. Portal vendors that file to generate its types

## 7. No-regression on the untouched write paths

- [ ] 7.1 Failing test: the soft delete still archives a component with blocking entries, unchanged — readiness is never consulted
- [ ] 7.2 Failing test: an update setting `archived` true still succeeds on a component with blocking entries
- [ ] 7.3 Failing test: `createComponent` with `archived` true is unaffected
- [ ] 7.4 Failing test: the bulk registry import completes with every external system unreachable
- [ ] 7.5 Failing test: requesting readiness archives nothing and changes no component field
- [ ] 7.6 Confirm no production code under the existing write paths was modified — this group asserts absence of change, so it is tests only

## 8. Verification

- [ ] 8.1 `./gradlew :components-registry-service-server:test` green
- [ ] 8.2 `./gradlew qualityStatic` clean
- [ ] 8.3 `./gradlew qualityCoverage` clean
- [ ] 8.4 `./gradlew integrationTest` green — the new client beans must not break fat-JAR startup, including with no external system configured
- [ ] 8.5 Manual, against real systems: a component whose targets are all archived is archived
- [ ] 8.6 Manual: a component with an open issue is refused, and the issue is returned
- [ ] 8.7 Manual: a component sharing a TeamCity project with a live component is archived, and the entry names that component
- [ ] 8.8 Manual: a component sharing a TeamCity *descendant* with a live component is archived, and the entry names that component
- [ ] 8.9 Manual: with TeamCity unreachable, the TeamCity entry is `UNKNOWN` and the archive is refused
- [ ] 8.10 Manual: with the issue tracker unreachable, the archive is refused and the message names the issue tracker
- [ ] 8.11 Manual: confirm `affectedProject` still excludes the queried project on the deployed TeamCity version, and that task 3.2's compensation is therefore still required
- [ ] 8.12 Manual: a component whose repository was **deleted** (not archived) is archivable, and the entry says the repository no longer exists
- [ ] 8.13 Manual: with a deliberately invalid VCS credential, every repository entry is `UNKNOWN` under one reason about the integration — and no entry claims a repository is absent
- [ ] 8.14 Manual: with the VCS integration unconfigured, no repository entries appear and archiving is offered
- [ ] 8.15 Manual: confirm on the live tracker that a genuinely retired project's category reads exactly `X Archive`, and that the deployed configuration carries that value — the whole project entry rests on that string being right
- [ ] 8.16 Manual: a project that was recategorised but whose schemes were never switched still reports `COMPLETED`, and confirm with the operator that this limit is understood — the entry attests the marker, not the procedure
- [ ] 8.17 Update `docs/registry/functional-spec.md` and the relevant numbered requirements with the gate, recording that the issue-tracker project entry checks one of the procedure's five steps
