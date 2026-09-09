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
- [x] 1.2 Add the vcs-facade client dependency once 1.1 confirms the shape, pinned in `gradle.properties` like every other external version
- [x] 1.3 ~~Add the Atlassian REST Java client (`jira-rest-java-client-api`, `-core`) and `io.atlassian.fugue:fugue`~~ — superseded during review: that dependency pulled a transitive Jersey 2 client that repeatedly conflicted with Spring Cloud Netflix Eureka's own Jersey-presence detection and `jakarta.ws.rs.ext.RuntimeDelegate` resolution. Shipped instead as `org.octopusden.octopus.components.registry.server.jira.JiraIssueSearchClient`, a small client built on Spring's own `RestClient` against Jira's REST API directly, with minimal DTOs (`JiraSearchResponse`/`JiraSearchIssue`/`JiraSearchIssueFields`/`JiraFixVersionRef`) — no dedicated dependency beyond `spring-boot-starter-web`, already on the classpath
- [x] 1.4 Add the octopus `jira-client` — needed only for `getProject`'s `projectCategory`, which the issue-search client does not expose. Published under the `octopus-external-systems-clients.version` already pinned for `teamcity-client`, so this adds a module, not a version
- [x] 1.5 Register both issue-tracker clients as beans, inert when no base URL is configured, following the existing `TeamcityProperties` pattern of a blank default that does not attempt a connection
- [x] 1.6 Add the configured set of issue-tracker categories that mean "retired", alongside the tracker's base URL. Default empty (so an unconfigured deployment yields `UNKNOWN`, not a silent pass); the live value is the single literal category `X Archive`
- [x] 1.7 `./gradlew qualityStatic` clean with the new dependencies on the classpath

## 2. Sharing helper

- [x] 2.1 Failing test: a repository used by exactly one component reports no sharing
- [x] 2.2 Failing test: a repository used by a second, non-archived component reports that component
- [x] 2.3 Failing test: a repository used by a second, archived component reports no sharing
- [x] 2.4 Failing test: the component being checked never appears in its own result
- [x] 2.5 Failing test: repository URLs that differ only by scheme, a `.git` suffix, a trailing slash, or case are treated as the same target — canonicalisation is for comparison only and never changes the URL sent to vcs-facade (see 4.5)
- [x] 2.6 Failing test: a TeamCity project used by a second, non-archived component reports that component
- [x] 2.7 Failing test: a TeamCity project whose *descendant* is used by a non-archived component reports that component
- [x] 2.8 Failing test: a descendant whose project id does not begin with the ancestor's id is still detected — no prefix inference
- [x] 2.9 Failing test: the intersection runs registry-ids-against-descendants, so a descendant set of thousands does not produce a query over thousands of ids
- [x] 2.10 Failing test: an issue-tracker project key claimed by a second, non-archived component reports that component
- [x] 2.11 Implement the helper as one unit with three target kinds, over `findDistinctLinkedProjectIds` and `findByProjectIdsWithComponent`
- [ ] 2.12 **Deferred, not shipped.** An ArchUnit rule matching on raw calls to `findByProjectIdsWithComponent`/`findDistinctLinkedProjectIds` was added and then removed during review: those two general-purpose queries already have legitimate callers outside `SharingHelper` (the unrelated TeamCity-validation feature), so the rule's premise ("SharingHelper is the only caller") was false the day it was written and would have failed the build immediately. See [TD-021](../../../../docs/registry/tech-debt/021-sharing-helper-archunit-gate.md) for what a correctly-scoped rule needs to match on instead.

## 3. TeamCity descendant lookup

- [x] 3.1 Failing test: the lookup issues an `affectedProject` locator query for the given project id
- [x] 3.2 Failing test: the queried project itself is present in the returned set even though the API omits it
- [x] 3.3 Failing test: the request asks for `archived` explicitly — a `fields` list that omits it silently yields null
- [x] 3.4 Failing test: a failure from TeamCity surfaces as unresolved, never as an empty descendant set
- [x] 3.5 Implement, separate from the sync's fetcher — the sync's `parameter:(name:COMPONENT_NAME)` locator cannot see unparameterised descendants. Do not touch the sync's `PROJECT_FIELDS`: widening it changes what every sync run fetches and stores, which this change's additive-only constraint excludes, and the gate does not depend on it

## 4. The four entry kinds

- [x] 4.1 Failing test: a repository reporting `archived = true` yields `COMPLETED`
- [x] 4.2 Failing test: `archived = false` with no sharing yields `NOT_COMPLETED`
- [x] 4.3 Failing test: `archived = null` yields `UNKNOWN` with a reason naming the system
- [x] 4.4 ~~Failing test: a repository the facade reports as absent yields `COMPLETED` with a reason saying it no longer exists — on a system proved live. Absence is a definite answer, and a deleted repository is not live infrastructure~~ — reversed during review: a hosting platform may return the same 404 for a private/inaccessible repository as for one that no longer exists, and CRS has no way to confirm its VCS credential has instance-wide read access that would rule that out. A repository the facade reports as absent (`NotFoundException`) now yields `UNKNOWN` unconditionally, never `COMPLETED` — see design.md decisions 11/12
- [x] 4.4a ~~Failing test: the same absent report on a system *not* proved live yields `UNKNOWN`, never `COMPLETED`~~ — moot after 4.4's reversal: an absent report is `UNKNOWN` regardless of whether the system was proved live
- [x] 4.4b Failing test: a read failing in a way that could be either absence or a permission problem yields `UNKNOWN`
- [x] 4.5 Failing test: `vcsPath` is passed through to `getRepository` unmodified — no project-key or slug parsing, no per-platform branching
- [x] 4.6 Failing test: an archived TeamCity project yields `COMPLETED`; an unarchived, unshared one yields `NOT_COMPLETED`
- [x] 4.7 Failing test: a TeamCity project whose state could not be read yields `UNKNOWN`
- [x] 4.7a Failing test: a TeamCity project reported absent, on a proved-live system, yields `COMPLETED` with a reason saying it no longer exists
- [x] 4.7b Failing test: a repository URL no configured provider serves yields `UNKNOWN` whose reason names the recorded URL as unresolvable, and does not say the VCS system is unavailable
- [x] 4.8 Failing test: the `JIRA_ISSUES` entry for a given effective pair — an issue open under that pair's scope yields `NOT_COMPLETED`, and the issue is returned on the entry as structured data
- [x] 4.9 Failing test: `JIRA_ISSUES` still `NOT_COMPLETED` when another live component uses the same project key, and its `sharedWith` is empty — sharing never applies to this entry
- [x] 4.10 Failing test: no open issue under the pair's scope yields `COMPLETED`
- [x] 4.11 Failing test: an issue scoped to a *different* pair — another component's, or another of this component's own version ranges — is neither returned nor blocking
- [x] 4.12 Failing test: a pair with a null prefix and no other pair claiming the same project key is scoped to the whole project, and yields `COMPLETED`/`NOT_COMPLETED` normally — a null prefix alone SHALL NOT yield `UNKNOWN`
- [x] 4.12a Failing test: a pair with a null prefix and another pair claiming the same project key with its own registered prefix is scoped to issues whose recorded version carries no prefix at all — a bare version string — decided without reading the other pair's specific prefix
- [x] 4.12b Failing test: two pairs both claiming a null prefix on the same project key yield `UNKNOWN` on both, naming the conflict as registry data — the one genuine anomaly left in this area
- [x] 4.12c Failing test: an issue whose recorded version matches neither a bare pattern nor any pair's registered prefix is counted by no pair's entry and blocks nobody — an accepted trade-off (design decision 15), not a bug to work around by falling back to exclusion
- [x] 4.13 Failing test: the `JIRA_PROJECT` entry — the category from `getProject(key).projectCategory` matched against the configured retired set yields `COMPLETED`; changing the configured set changes the outcome with no code change
- [x] 4.13a Failing test: the category is the only signal — a project whose category matches yields `COMPLETED` even with no `[ARCHIVE]` marker in its name, and a project whose name carries the marker but whose category does not match yields `NOT_COMPLETED`. The project name is never read
- [x] 4.13b Failing test: two of a component's own effective pairs sharing one project key produce exactly one `JIRA_PROJECT` entry for it, not two
- [x] 4.14 Failing test: `JIRA_PROJECT` not retired but shared yields `COMPLETED` and lists the sharing component; not retired and unshared yields `NOT_COMPLETED`
- [x] 4.15 Failing test: with no retired category configured, `JIRA_PROJECT` is `UNKNOWN` with a reason saying so, never `COMPLETED`
- [x] 4.16 Failing test: an issue-tracker outage yields `UNKNOWN` on every `JIRA_ISSUES` and `JIRA_PROJECT` entry the component has, and is not read as "no open issues"
- [x] 4.17 Failing test: two of a component's effective pairs are decided independently — one pair's project retired with an open issue on that pair yields that pair's `JIRA_ISSUES: NOT_COMPLETED` alongside its own `JIRA_PROJECT: COMPLETED`, while the other pair's entries reflect its own, unrelated state
- [x] 4.18 Implement the four checks behind one internal interface, so the assembler does not know which system answered. `JIRA_ISSUES` and `JIRA_PROJECT` are two checks over one client, run once per effective pair (issues) or once per distinct project key (project) — not one check returning two verdicts, and not one run per component

## 4a. The component's Jira scope is a set of effective pairs

Why this exists: a component's Jira configuration is not one `(project key, prefix)` pair — a base row plus per-version-range overrides can each claim a different pair, and all are live at once. See design decision 15 and ADR-018 (decoupled version model). Checking only the base pair would silently skip an older range's still-active, unretired project.

- [x] 4a.1 Failing test: a component with no version-range override on its Jira configuration resolves to exactly one effective pair
- [x] 4a.2 Failing test: a component with a version-range override on `jira.projectKey` resolves to two effective pairs, one per project key
- [x] 4a.3 Failing test: a component with a version-range override on `jira.versionPrefix` only resolves to two effective pairs sharing one project key
- [x] 4a.4 Failing test: the resolution reuses the registry's existing effective-pair computation (`computeEffectiveJiraPairs` / the same call `ComponentManagementServiceImpl` already makes for cross-component uniqueness), not a reimplementation reading raw `ComponentConfigurationEntity` rows
- [x] 4a.5 Failing test: detecting whether *any* other pair claims a null-prefix pair's project key (to choose whole-project vs bare-pattern scope) checks every other component's pairs AND every other version range of this same component — but never needs their specific prefix strings, only whether one exists and whether it is also null
- [x] 4a.6 Implement the enumeration as the entry point the assembler calls to discover Jira targets, before any external read; the assembler produces one `JIRA_ISSUES` entry per pair and one `JIRA_PROJECT` entry per distinct project key among them (task 4.13b)

## 4b. Liveness probe and unconfigured systems

Why this exists: `UNKNOWN` blocks and there is no override, so a target whose state can never be read makes its component permanently unarchivable. See design decisions 11-14, 17.

- [x] 4b.1 Failing test: each system is probed once per readiness call, not once per target
- [x] 4b.2 Failing test: a failed probe makes every target of that system `UNKNOWN` under one reason identifying the system, not one reason per target
- [x] 4b.3 Failing test: one system failing its probe leaves the other systems' entries carrying their real outcomes
- [x] 4b.4 Failing test: liveness is established without reading the component's own targets first, so a component with a single target still gets a real outcome
- [x] 4b.5 Failing test: an unconfigured system contributes no entries at all, and their absence does not make `ready` false
- [x] 4b.6 Failing test: a configured system that fails contributes `UNKNOWN` entries and makes `ready` false — not configured and configured-but-failing never share an outcome
- [x] 4b.7 Failing test: systems are configured independently, so one being unconfigured does not affect another's entries
- [x] 4b.7a ~~Failing test: the issue tracker's two connections — issue search and project read — are probed independently; a failure on one leaves the other's entries carrying real outcomes~~ — revised during review: the octopus `JiraClient` (project-read) has no call that isn't scoped to a project/issue/sprint, so it cannot be probed on its own. Since both clients share one base URL/credentials, `jiraProjectLive` is derived from the issue-search probe instead — a failure on issue-search now correctly makes `JIRA_PROJECT` entries `UNKNOWN` too, rather than reporting project-read live purely because it is configured
- [x] 4b.7b Failing test: the issue tracker's two connections are configured independently; one unconfigured while the other is configured checks only the configured one's entries
- [x] 4b.9 Failing test: an `UNKNOWN` from an unreachable system classifies `SYSTEM_UNAVAILABLE`
- [x] 4b.10 Failing test: an `UNKNOWN` from an unresolvable recorded URL, or from two pairs both claiming a null prefix on one project key, classifies `REGISTRY_DATA`
- [x] 4b.11 Failing test: the issue-tracker project entry with no retired category configured classifies `NOT_CONFIGURED`
- [x] 4b.12 Failing test: a `COMPLETED` or `NOT_COMPLETED` entry carries no classification
- [x] 4b.8 Implement the per-system probe, the unconfigured-system omission, and the remedy classification, with the issue tracker's two connections as independent units throughout

## 5. Assembler and verdict

- [x] 5.1 Failing test: every target the component has produces exactly one entry
- [x] 5.1a Failing test: two of a component's own version lines pointing at the same TeamCity project yield exactly one entry, not two — entries are keyed by target identity, not by the registry row that discovered them
- [x] 5.2 Failing test: a component with no VCS entries and no version lines produces no target entries and `ready = true`
- [x] 5.3 Failing test: a component with a null `jiraProjectKey` produces neither issue-tracker entry — an absent target is never a placeholder entry and never blocks
- [x] 5.3a Failing test: a component with one effective Jira pair (no version-range override) produces exactly two issue-tracker entries, `JIRA_ISSUES` and `JIRA_PROJECT`, each appearing once
- [x] 5.3b Failing test: a component with two effective Jira pairs on different project keys produces four issue-tracker entries — two `JIRA_ISSUES`, two `JIRA_PROJECT`
- [x] 5.3c Failing test: a component with two effective Jira pairs sharing one project key (a prefix-only override) produces three issue-tracker entries — two `JIRA_ISSUES`, one `JIRA_PROJECT`
- [x] 5.3d Failing test: two `JIRA_ISSUES` entries on the same component (two effective pairs) never carry the same `targetId`, including when both pairs have a null prefix on different projects
- [x] 5.4 Failing test: all entries passing yields `ready = true`
- [x] 5.5 Failing test: one `NOT_COMPLETED` entry yields `ready = false`
- [x] 5.6 Failing test: one `UNKNOWN` entry yields `ready = false`
- [x] 5.7 Failing test: entries whose only non-passing reason is sharing yield `ready = true`
- [x] 5.8 Failing test: `sharedWith` carries component names, and `reason` is not the carrier of that information
- [x] 5.8a Failing test: `openIssues` is populated only on `JIRA_ISSUES`, and `sharedWith` is empty on every `JIRA_ISSUES` entry
- [x] 5.8b Failing test: an entry that passes because its target is absent reports empty `sharedWith`, even when the registry separately lists another live component referencing that same target — absence, not sharing, decided the outcome
- [x] 5.10 Failing test: assembling readiness mutates nothing
- [x] 5.11 Implement the assembler

## 6. Endpoint

- [x] 6.1 Failing test: `GET rest/api/4/components/{id}/archive-readiness` returns the contract shape
- [x] 6.2 Failing test: the path resolves both a UUID and a component name, like the sibling v4 endpoints
- [x] 6.3 Failing test: a caller without `DELETE_COMPONENTS` is rejected
- [x] 6.4 Failing test: an unknown component yields 404
- [x] 6.5 Implement on `ComponentControllerV4`
- [x] 6.6 Refresh the committed spec with `./gradlew :components-registry-service-server:generateOpenApiDocs` and commit `src/main/resources/openapi/v4.json`. This is not optional: `OpenApiV4SpecTest` is untagged, runs under `test` → `check`, and fails on any drift between the live v4 controllers and the committed file — so 8.1 fails until this is done. Portal vendors that file to generate its types

## 7. No-regression on the untouched write paths

- [x] 7.1 Failing test: the soft delete still archives a component with blocking entries, unchanged — readiness is never consulted
- [x] 7.2 Failing test: an update setting `archived` true still succeeds on a component with blocking entries
- [x] 7.3 Failing test: `createComponent` with `archived` true is unaffected
- [x] 7.4 Failing test: the bulk registry import completes with every external system unreachable
- [x] 7.5 Failing test: requesting readiness archives nothing and changes no component field
- [x] 7.6 Confirm no production code under the existing write paths was modified — this group asserts absence of change, so it is tests only

## 8. Verification

> 8.5-8.16 need a real, reachable TeamCity/Jira/VCS with deliberately-arranged state (a retired
> project, an invalid credential, a deleted repository, ...) and are left unchecked here: nothing
> in this reconciliation pass can confirm they were exercised against real systems, and it would be
> dishonest to tick them off without that evidence. 8.3 is left unchecked for a narrower reason — it
> currently fails in this environment on an unrelated module (`components-registry-service-client`'s
> Testcontainers-based `dbTest`, which needs Docker) that this change never touched.

- [x] 8.1 `./gradlew :components-registry-service-server:test` green
- [x] 8.2 `./gradlew qualityStatic` clean
- [ ] 8.3 `./gradlew qualityCoverage` clean
- [x] 8.4 `./gradlew integrationTest` green — the new client beans must not break fat-JAR startup, including with no external system configured
- [ ] 8.5 Manual, against real systems: a component whose targets are all archived is archived
- [ ] 8.6 Manual: a component with an open issue is refused, and the issue is returned
- [ ] 8.7 Manual: a component sharing a TeamCity project with a live component is archived, and the entry names that component
- [ ] 8.8 Manual: a component sharing a TeamCity *descendant* with a live component is archived, and the entry names that component
- [ ] 8.9 Manual: with TeamCity unreachable, the TeamCity entry is `UNKNOWN` and the archive is refused
- [ ] 8.10 Manual: with the issue tracker unreachable, the archive is refused and the message names the issue tracker
- [ ] 8.11 Manual: confirm `affectedProject` still excludes the queried project on the deployed TeamCity version, and that task 3.2's compensation is therefore still required
- [ ] 8.12 ~~Manual: a component whose repository was **deleted** (not archived) is archivable, and the entry says the repository no longer exists~~ — reversed during review (see 4.4): a deleted repository now reports `UNKNOWN`, not `COMPLETED`, since this cannot be told apart from an inaccessible one
- [ ] 8.13 Manual: with a deliberately invalid VCS credential, every repository entry is `UNKNOWN` under one reason about the integration — and no entry claims a repository is absent
- [ ] 8.14 Manual: with the VCS integration unconfigured, no repository entries appear and archiving is offered
- [ ] 8.15 Manual: confirm on the live tracker that a genuinely retired project's category reads exactly `X Archive`, and that the deployed configuration carries that value — the whole project entry rests on that string being right
- [ ] 8.16 Manual: a project that was recategorised but whose schemes were never switched still reports `COMPLETED`, and confirm with the operator that this limit is understood — the entry attests the marker, not the procedure
- [x] 8.17 Update `docs/registry/functional-spec.md` and the relevant numbered requirements with the gate — done as part of this reconciliation: `functional-spec.md` §10, `requirements-common.md` SYS-095
