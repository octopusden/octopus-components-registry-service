## Context

`ComponentEntity.archived` is set today with no checks. Two paths reach it from the API: `deleteComponent`, CRS's soft delete, which is what the Portal's Archive button calls; and `updateComponent`, which honours `archived` on the request. Two more set it without being retirements — `createComponent` and the bulk registry import.

Everything the check needs is already stored. `VcsSettingsEntryEntity.vcsPath` holds each repository URL in the form octopus-vcs-facade takes directly as `sshUrl`, so CRS never parses a project key or slug and never branches on Bitbucket versus Gitea. `VersionLineEntity` links a component to its TeamCity projects. `VersionLineRepository` already has both directions of the TeamCity lookup: `findDistinctLinkedProjectIds` and `findByProjectIdsWithComponent`, the latter with `JOIN FETCH vl.component`.

`jiraProjectKey` and `jiraVersionPrefix` are not two scalar columns to read off one row. `ComponentConfigurationEntity` holds one `BASE` row per component plus, optionally, per-version-range `SCALAR_OVERRIDE` rows — a component can carry a different effective Jira project, a different effective version prefix, or both, for a subset of its own version range, on top of its base configuration. `computeEffectiveJiraPairs` (`util/JiraEffectivePairs.kt`) already resolves this into the set of effective `(project key, version prefix)` pairs the registry's own cross-component uniqueness check runs against; this change reuses that resolution rather than reading raw rows, and checks every pair the component carries, not only the base one — see decision 15.

Two things are genuinely new. CRS has **no issue-tracker client at all** — not the octopus one, not Atlassian's. And the TeamCity read the gate needs is not the read the sync performs.

The sharing problem is the substantive design content. A TeamCity project, a repository or an issue-tracker project can serve several components; where a live component still uses one, it must not be archived. So for the component being retired, "this target is not archived" is the correct end state, and a gate that demanded otherwise would make the component permanently unarchivable.

## Goals / Non-Goals

**Goals**

- A caller can find out, before archiving, whether a component's retirement steps were carried out.
- A caller can tell, per target, what state the external system is in — including which targets are deliberately still running.
- A system that could not be consulted never reads as a target that was checked and found unarchived.

**Non-Goals**

- Performing any archive in an external system, or holding any write permission in one.
- Refusing a write. No existing write path changes behaviour; the answer is advisory.
- Untangling `archived`'s two meanings (retired / soft-deleted).

## The response contract

The Portal builds against this shape and it is recorded identically in that repo's change. Any divergence here must be mirrored there.

```
GET rest/api/4/components/{idOrName}/archive-readiness
     permission: DELETE_COMPONENTS

{
  "ready": false,
  "entries": [
    {
      "targetKind": "JIRA_ISSUES" | "JIRA_PROJECT" | "TEAMCITY_PROJECT" | "REPOSITORY",
      "targetId":   "<project key[:version prefix] | project key | TC project id | repository url>",
      "targetUrl":  "<deep link>" | null,
      "outcome":    "PASSED" | "FAILED" | "UNKNOWN",
      "reason":     "<why it failed or could not be read>" | null,
      "sharedWith": ["<component name>", ...],
      "openIssues": [ { "key": "...", "summary": "..." }, ... ]
    }
  ]
}
```

`openIssues` is populated only on `JIRA_ISSUES` entries, and `sharedWith` is always empty there. `targetUrl` is nullable so a caller renders identity as text rather than a broken link. `ready` is CRS's verdict; callers gate on it rather than deriving one, so that an outcome value a caller does not recognise can never unblock it.

The number of `JIRA_ISSUES` and `JIRA_PROJECT` entries is not fixed at zero-or-one-of-each. A component with version-range overrides on its Jira configuration carries one `JIRA_ISSUES` entry per effective `(project key, version prefix)` pair, and one `JIRA_PROJECT` entry per distinct project key among those pairs — see decision 15. The common case, no override, still produces exactly one of each.

Because a component can now carry more than one `JIRA_ISSUES` entry, `targetId` on that kind SHALL identify the pair, not the prefix alone — a bare prefix is not unique across entries (two different projects can each have their own null-prefix "default bucket" pair, and both would render the same otherwise-empty `targetId`). It is the project key, plus the prefix when one is set, joined so the pair reads unambiguously.

A component with an issue-tracker project key produces **two** entries, `JIRA_ISSUES` and `JIRA_PROJECT`, from the same project. They are decided by different rules and can disagree — the common retirement-in-progress state is `JIRA_ISSUES: FAILED` alongside `JIRA_PROJECT: PASSED`.

## Decisions

### 1. Three outcomes, with sharing carried as data rather than a fourth

An earlier draft had a fourth outcome for a target deliberately left alone. It was collapsed into `PASSED` with a populated `sharedWith`, because the gate only ever needs to know whether something blocks, and sharing never blocks.

Precisely: sharing excuses the *target's* state. Every entry's subject is a target several components can share, and for all of them a populated `sharedWith` and `PASSED` coincide — with one exception, which decision 15 splits out rather than special-cases.

The information is not discarded, and `sharedWith` is a list of names rather than a sentence in `reason`, because two consumers act on it: the Portal names the components so an operator is not shown an unqualified success for a target that is still running, and the follow-up work that performs the archives must name every target it skips. Neither can be driven from prose.

### 2. `UNKNOWN` is a first-class outcome and fails closed

`Repository.archived` from octopus-vcs-facade `3.0.36` is a nullable `Boolean`, documented as *"treat null as unknown, not as 'not archived'"*. Folding that into `FAILED` would report an unreachable system as a target needing work, sending someone to archive something that may already be archived. Folding it into `PASSED` would retire a component on no evidence.

So it is its own outcome, it blocks, and it names the system in `reason`.

### 3. The TeamCity read is a new call, not a wider `fields` list on the existing one

The sync's call is scoped by `parameter:(name:COMPONENT_NAME)` — deliberately, since only such projects link to a component. But archiving a TeamCity project archives its whole subtree, and a descendant need not carry that parameter. The sync's result set therefore cannot see the projects most at risk.

The gate issues its own call using the `affectedProject` locator, which `ProjectLocator` in teamcity-client `2.0.98` already exposes alongside `parentProject`, `archived` and `id`. No upstream change and no version bump: the DTO already carries `archived`, `parentProjectId`, `parentProject` and `projects`.

Verified against the live server on three projects of very different sizes: `affectedProject` returns the full recursive descendant set, matching a set computed independently from the raw hierarchy, and does **not** include the queried project itself. The implementation therefore adds it back explicitly.

Adding `parentProjectId` to the sync's own `PROJECT_FIELDS` would be worthwhile so the hierarchy is stored during sync, but it is deliberately **not** part of this change: the gate does not depend on it, and this change promises that no existing path changes behaviour. Widening the sync's field list changes what the sync fetches and stores on every run, which is exactly the kind of change the additive-only constraint excludes. It is left for whoever wants the stored hierarchy for its own sake.

`archived` is already requested in `PROJECT_FIELDS`, so the sync path is not exposed to the "archived is always false when the field is not asked for" trap. The gate's own call must request it explicitly, since it is a separate call with its own field list.

### 4. Sharing is intersected the cheap way round

One project high in the tree returned 2789 descendants on the live server — the hierarchy is very shallow at the top. Feeding thousands of ids into an `IN` clause to find the few that are registered is the wrong direction.

Instead: take the registry's linked project ids, which are bounded by the number of components, and intersect them with the descendant set. `findDistinctLinkedProjectIds` already provides the first half and `findByProjectIdsWithComponent` the second, with the component eagerly fetched so the result does not fan out into per-row queries.

### 5. Prefix matching on project ids was rejected on evidence

The cheap alternative to asking TeamCity — compare id prefixes to infer ancestry — was tested against the live server and fails for 444 of 2817 projects, about one in six. A child's id frequently does not begin with its parent's id.

The failure is in the dangerous direction: the gate would report that nothing else is affected while a live component's project sat underneath, and the archive that follows would take it down.

### 6. Nothing is enforced at write time — the check sits in front of archiving, not inside it

`deleteComponent`, `updateComponent`, `createComponent` and the bulk import all keep their current behaviour. A caller asks for readiness, applies the verdict, and then issues the archive request it already issues today.

The alternative was considered and declined. Enforcing would have meant gating `deleteComponent` — and since that is CRS's soft delete, gating it would make deleting a mistakenly created component require its retirement steps to be complete: archive its repositories, close its issues, for a component created five minutes earlier by mistake. Gating only `updateComponent` instead would leave the primary archive route open, since that route is the delete endpoint.

What this costs is stated plainly in the risks below rather than designed around: a caller that skips the check can still archive, and a caller's verdict can go stale between its check and its write.

### 7. One sharing computation, used by both this check and the archiving that follows

The follow-up work asks the identical question, and its answer decides whether it archives a target. If the two are computed separately they will diverge, and the divergence puts an irreversible operation on the wrong side of a disagreement. One helper.

### 8. Read-only, so no new permissions on the service account

Nothing here writes to an external system. The service account needs no project-admin rights in TeamCity, the VCS or the issue tracker. Those rights are needed by the follow-up work that performs the archives, which is why that work is separate and carries its own security review rather than arriving with this change.

### 9. "Archived" for the issue-tracker project is a configured category set

There is no archived flag on an issue-tracker project. Retirement is recorded by reclassifying the project into a category, and the octopus client's `Project` carries exactly that: `key` and `projectCategory`. So the check reads the category and compares it against a set of category names that mean retired.

That set is configuration, not a constant. The names belong to the issue tracker, and renaming a category there must not require a CRS release to keep the gate honest. Configuration also makes the check testable without a live tracker.

Unset means `UNKNOWN`, not `PASSED`. An empty configured set is indistinguishable from "no category matches", and the second reading would report every project as unarchived while the first would need a reason to say so — reporting `UNKNOWN` with that reason is the only option that does not invent evidence. This mirrors decision 2.

**What the real procedure is, and how much of it this sees.** Retiring an issue-tracker project is five steps: rename the project to `X <name> [ARCHIVE]`, set its category to `X Archive`, then switch its issue-type, workflow, permission and notification schemes. Only the first two are observable here. The scheme changes are administrative settings, readable only with issue-tracker administrator rights — which decision 8 deliberately withholds from the service account. Checking them would mean asking for admin read in a third external system for a check that is advisory anyway, and would drag this change into the security review that the archiving follow-up is supposed to carry.

**Of the two observable steps, only the category is matched.** The rename is not checked. A project name is free text, so any matcher is guessing at spacing, bracketing and capitalisation, and it fails in the annoying direction: a `FAILED` on a project that was correctly retired but written `[Archive]` sends someone to redo work already done, and the natural response to that is to stop trusting the gate. The category is picked from a fixed list in the tracker's UI, so exact matching is sound. That the octopus client's `Project` DTO exposes `projectCategory` and not the name is convenient rather than causal — the reasoning would hold either way, and it is why the Atlassian client is taken for issue search only.

The cost is stated in the risks: `PASSED` here means the marker is set, not that the procedure was completed. A project recategorised but never reschemed passes. That is the honest limit of a read-only check, and the spec says so rather than letting the entry imply more.

### 10. Repository targets are compared canonically but consulted verbatim

`vcsPath` is free text entered per component, so the same repository can be recorded two ways — `ssh://` versus `https://`, with or without `.git`, differing in case. Sharing compares those, and a comparison that misses an equivalence fails in the dangerous direction: the target reads as used by nobody and gets reported `FAILED`-then-archived, taking down a live component's repository.

So comparison canonicalises (scheme, trailing `.git`, trailing slash, host and path case) while the call to vcs-facade passes the stored string through untouched. Canonicalising the outbound URL too would put CRS in the business of knowing what each hosting platform accepts, which is precisely what `getRepository(sshUrl)` exists to avoid.

### 11. A permanently unreadable target would otherwise deadlock the component forever

`UNKNOWN` blocks and there is no override, so any target whose state can never be read makes its component permanently unarchivable. Three real cases produce that: the repository was deleted rather than archived, the build project no longer exists, and the integration credential expired with nobody fixing it. Retrying accomplishes nothing in all three.

They are not one problem. The first two are a **misclassification**: a system positively reporting that a target does not exist has given a definite answer, and a deleted repository is not live infrastructure — which is the only thing the check actually asks about. Absence is a stronger end state than archived, so it passes. The third is genuinely unknown and genuinely permanent, and needs an operational remedy rather than a per-target one.

One interaction needs stating explicitly: absence and sharing are two different rules, and both can be true of the same target at once — a project can be deleted from the external system while the registry still lists another live component's version line pointing at it, because that registry row was never cleaned up after the deletion. When absence is what decided the outcome, sharing does not also get to speak on the same entry: `sharedWith` is empty on an absence-`PASSED` entry, even though the sharing computation would return a hit if asked. A populated `sharedWith` earned by a rule other than sharing would break the same invariant decision 15 states for the issue-tracker split — one entry, one rule — so it is held to here too, not just there.

### 12. Absence is only trustworthy on a system proved live, so each system is probed once

A hardened hosting platform answers "not found" for a target that exists but the credential may not see — deliberately, so probing cannot enumerate private repositories. Absent and not-permitted then arrive indistinguishably.

This is not hypothetical in the chain we depend on. `BitbucketService.findRepository` catches the client's `NotFoundException` and returns `null`; `VcsManagerImpl.getRepository` turns that `null` into the facade's own `NotFoundException`, which the error handler maps to 404. Both meanings are flattened into that one catch block, so by the time the answer reaches CRS the distinction is gone. Reading the status code more carefully cannot recover it — the information no longer exists.

So the check establishes, once per readiness call per system, that the system answers and the credential works. Then a 404 on a specific target is trustworthy as absence. If the probe fails, every target of that system is `UNKNOWN` under one reason about the system.

"System" here is not always one client. The issue tracker is reached through two independent connections — the Atlassian client for issue search (`JIRA_ISSUES`) and the octopus client for project metadata (`JIRA_PROJECT`) — sharing one base URL and one configured/unconfigured switch but nothing else. A credential scoped to search and not to project reads, or the reverse, fails one and not the other. Probing them as a single unit would report a working client `UNKNOWN` because its sibling failed, which is the exact false-blocking decision 13 exists to prevent. So each client is probed on its own: a failed Atlassian probe makes every `JIRA_ISSUES` entry `UNKNOWN` without touching `JIRA_PROJECT`, and the reverse for the octopus client.

Without the probe, a credential that silently lost its permissions would make every target look deleted and every component look ready — the exact failure this check exists to prevent, occurring quietly across the whole registry. The probe is what makes "absent passes" safe rather than reckless.

The probe and the target reads are not atomic: permissions could be revoked in between. The window is milliseconds and the coincidence required is specific, but it is not zero, and it is accepted rather than engineered away.

The architecturally cleaner fix is upstream — stop flattening the two meanings in `findRepository`. That is a separate ticket and another release; this decision solves it from CRS's side without waiting on one.

### 13. Not configured and configured-but-failing are different facts

An unconfigured system contributes no entries at all. A configured system that fails contributes `UNKNOWN` entries and blocks.

This is the remedy for the permanently broken credential: if an integration cannot be fixed, turning it off restores archiving, and that only works if "off" means "not checked" rather than "blocks forever". Collapsing the two would also make the check undeployable until every environment is configured.

The distinction is house precedent, not invention. `TeamcityProperties` already defaults to a blank base URL that is inert and attempts no connection, and the RMS change in this repo records the same lesson explicitly — that separating "integration disabled" from "the dependency failed" was a necessary fix, because treating them identically made that feature unsafe to deploy.

### 14. A target CRS cannot route anywhere is a registry problem, not an outage

A repository URL on a host no configured provider serves resolves to nothing — the facade raises a distinct "no configured VCS service for this URL" condition rather than a not-found. It is `UNKNOWN`, but its reason must say the recorded URL is unresolvable, not that the VCS system is unavailable.

The actions are opposite: an outage is waited out, this is corrected by editing the component. A reason blaming the system sends someone to investigate a system that is working perfectly.

### 15. Open issues and the issue-tracker project are two entries, not one entry with two signals

An earlier draft had a single `JIRA_PROJECT` entry answering two questions: are this component's issues closed, and has the project been retired. The two obey opposite rules. Open issues are scoped to this component's own version prefix — nobody else's component causes them and nobody else can close them, so sharing cannot excuse them. The project is one object shared by every component using it, and if a live component still uses it, "not retired" is the correct end state — so sharing must excuse it.

Carrying both on one entry means its `outcome` is a mix of two verdicts. A caller reading `FAILED` with a populated `sharedWith` cannot tell whether the failure is the issues or the project without re-deriving the rule client-side, which is exactly what the `ready` verdict exists to prevent callers from doing. The Portal would have to explain a target that is simultaneously "still has work" and "deliberately left running".

So they are separate entries — `JIRA_ISSUES` and `JIRA_PROJECT` — each with one rule and one subject. The invariant that falls out is worth more than the extra entry: **every entry's outcome is decided by exactly one rule, and `sharedWith` is empty on every entry sharing cannot excuse.**

Splitting also forces two questions the combined entry hid, and both turned out to need correcting once checked against the actual configuration model.

**A component's Jira configuration is a set of pairs, not one pair.** `ComponentConfigurationEntity` layers per-version-range `SCALAR_OVERRIDE` rows over one `BASE` row; a component can have a different effective project key, a different effective prefix, or both, active for a slice of its own version range at the same time as its base configuration — the registry's own `computeEffectiveJiraPairs` exists precisely because it must handle a *set* of effective `(project key, prefix)` pairs per component, not a single one, for cross-component uniqueness. Checking only the base pair would leave an old range's still-live, still-unretired project unchecked whenever a newer range's override shadowed it — the same "looks safe, isn't" failure the TeamCity descendant check and the repository canonicalisation exist to close elsewhere in this design. So the check enumerates every effective pair the component carries, reusing that resolution rather than reading `ComponentConfigurationEntity.jiraProjectKey`/`jiraVersionPrefix` off one row: one `JIRA_ISSUES` entry per pair, one `JIRA_PROJECT` entry per distinct project key among those pairs (two of a component's own ranges sharing one project is one project, checked once). The ordinary case — no override — still produces exactly one of each, unchanged from the original design.

**A null prefix is not a data problem.** The original framing here was wrong: it read a null `jiraVersionPrefix` as an unscoped, unrecoverable case and reported it `UNKNOWN` with a "fix your registry data" reason. `JiraEffectivePairs.kt` documents the opposite explicitly — `(project key, null)` is *"a real prod shape"*, the pair belonging to the component that legitimately owns the project's default, unprefixed bucket. Telling that component's operator to add a prefix would be wrong advice for what is likely the common case, not an anomaly.

When no other pair claims that project key at all, there is nothing to distinguish, and the pair's scope is the whole project — every issue in it is this pair's, because nobody else has a legitimate claim to any of them.

When another pair does claim the same project key, the null-prefix pair's scope is every open issue whose recorded version carries **no prefix at all** — a bare version string. This is a direct, positive test on the issue's own recorded value: it does not enumerate the other pairs registered on the project key, does not need their exact prefix strings, and its cost does not grow as a project accumulates more sharing components. That was a deliberate choice over the alternative (excluding every other pair's specific registered prefix): the exclusion form needs this pair's check to know every sibling on its project key, a lookup that scales with sharing; the bare-pattern form needs none of that.

The trade-off, accepted deliberately: an issue whose recorded version matches **neither** a bare pattern **nor** any pair's registered prefix — a typo'd prefix, or a leftover value from a since-removed override — is counted by no pair's entry at all. It is invisible rather than misattributed. The alternative (exclusion) would have counted it toward the null-prefix pair instead — a false `FAILED` on the wrong component, annoying but at least still blocking something — so this is a real, named cost of the simpler mechanism, not a corner case being quietly designed away. It is accepted because the registry-data anomaly it depends on (an issue whose version matches no pair CRS knows about) is expected to be rare, and because the bare-pattern test's independence from the sibling set is worth more in the common case than the coverage it gives up in the rare one.

`UNKNOWN` remains reserved for what still cannot be resolved by inspecting the issue at all: two pairs both claiming a null prefix on the same project key, which the registry's uniqueness rule is expected to prevent from occurring in the first place.

The cost is that the Portal renders four target kinds instead of three, that the count of `JIRA_ISSUES`/`JIRA_PROJECT` entries is no longer fixed at zero-or-one-each, and that this shape must be mirrored in the Portal's change.

### 16. A target reached by more than one registry row still gets exactly one entry

Nothing stops a component's own two version lines from pointing at the same TeamCity project — a project building more than one artifact for the same component is a real shape, not an edge case invented for this document. A repository could equally end up recorded twice if `VcsSettingsEntryEntity` ever carries a duplicate row for the same component.

Either way, the assembler groups by the target's own identity — TeamCity project id, canonical repository URL, issue-tracker project key — before it produces entries, never by the registry row that discovered it. A component's own duplicate references collapse into the one entry the sharing helper (decision 4) and the entry rules already assume exists. The alternative — one entry per registry row — would print the same target twice with no field telling a caller they name the same thing, and would double-count it in `ready`'s inputs for no reason.

### 17. Not configured, per client

Decision 13 draws "not configured" versus "configured but failing" at the level of a system. For the issue tracker that line is drawn per client, consistent with decision 12's per-client probing: the Atlassian client and the octopus client are each either configured or not, independently, even though today's rollout turns them on together. An environment that configures project reads but not issue search — unlikely today, not impossible — gets `JIRA_PROJECT` entries and no `JIRA_ISSUES` entries, rather than one flag deciding both.

## Case matrix (quick reference)

Every case the check distinguishes, by target kind. "System" below means the specific connection for that entry — the VCS system for `REPOSITORY`, TeamCity for `TEAMCITY_PROJECT`, the Atlassian client for `JIRA_ISSUES`, the octopus client for `JIRA_PROJECT` (decision 17).

**Cross-cutting, applies to every target kind:**

| Case | Outcome | `sharedWith` | Reason names |
|---|---|---|---|
| System not configured | *(no entry at all)* | — | — |
| Configured, liveness probe fails | `UNKNOWN` | empty | the system, once, not per target (decision 12) |
| Registry does not record this target for the component | *(no entry at all)* | — | — |
| Two registry rows on this component resolve to the same target | *(one entry, not two)* | — | — (decision 16) |

**`REPOSITORY`:**

| Case | Outcome | `sharedWith` | Reason names |
|---|---|---|---|
| Reported archived | `PASSED` | empty (unless also decision below) | — |
| Reported not archived, no live component shares it | `FAILED` | empty | — |
| Reported not archived, a live component shares it (canonical-URL match, decision 10) | `PASSED` | that component(s) | — |
| Reported absent, system proved live | `PASSED` | **empty always** (decision 11/16 addendum) | target no longer exists |
| Reported absent, system *not* proved live | `UNKNOWN` | empty | the system |
| Read fails ambiguously (could be absence or permission) | `UNKNOWN` | empty | the system |
| URL matches no configured VCS provider | `UNKNOWN` | empty | the recorded URL is unresolvable (registry data, decision 14) |

**`TEAMCITY_PROJECT`:** same rows as `REPOSITORY`, with "descendant of the project is used by a live component" also counted as sharing (decision 5), resolved via `affectedProject`, never by id-prefix inference.

One `JIRA_ISSUES` entry and, separately, one `JIRA_PROJECT` entry exist **per effective `(project key, version prefix)` pair the component carries** — base configuration plus every version-range override, resolved the same way the registry's own cross-component uniqueness check resolves them (decision 15). A component with no override still has exactly one pair, so the common case is still exactly one of each entry; `JIRA_PROJECT` entries are further deduplicated by project key, so two of a component's own pairs sharing one project key produce one `JIRA_PROJECT` entry, not two.

**`JIRA_ISSUES`** (scoped to one pair; sharing never applies — `sharedWith` is always empty):

| Case | Outcome | Reason names |
|---|---|---|
| An issue is open under the pair's scope | `FAILED` | — (issue itself returned in `openIssues`) |
| No issue open under the pair's scope | `PASSED` | — |
| Issue scoped to a *different* pair — another component's, or another of this component's own ranges | *(not counted, not returned)* | — |
| Prefix is null, no other pair claims the same project key | `PASSED`-eligible: scope is the whole project, outcome follows normally | — |
| Prefix is null, another pair claims the same project key with its own prefix | `PASSED`-eligible: scope is issues whose version is a bare, unprefixed string, outcome follows normally | — |
| An issue's version matches neither a bare pattern nor any pair's registered prefix | *(counted by no pair — accepted, not `UNKNOWN`)* | — |
| Two pairs both claim a null prefix on the same project key | `UNKNOWN` | null-prefix conflict on this project key (registry data, decision 15) |
| No `jiraProjectKey` at all | *(no entry)* | — |

**`JIRA_PROJECT`** (one per distinct project key; shared per the ordinary rule; category is the only signal, the `[ARCHIVE]` name marker is never read — decision 9):

| Case | Outcome | `sharedWith` |
|---|---|---|
| Category is in the configured retired set | `PASSED` | empty unless also shared |
| Category not retired, no live component shares the project | `FAILED` | empty |
| Category not retired, a live component shares the project | `PASSED` | that component(s) |
| No retired category configured at all | `UNKNOWN` (never `PASSED`) | empty |
| No `jiraProjectKey` at all | *(no entry)* | — |

`ready` is `false` iff at least one entry above is `FAILED` or `UNKNOWN`; entries that don't exist (unconfigured system, target not recorded) never contribute to that check.

## Risks / Trade-offs

**A permanently broken, configured integration still blocks everything.** Absence now passes and an unconfigured system is skipped, but a configured system that fails keeps blocking every component with a target on it until someone fixes it or turns it off. There is no per-component escape, by design — the escape is operational. Whether that is sufficient without an audited override is an open question, deliberately left open rather than answered here.

**The verdict is advisory, so a caller can ignore it.** Anyone calling the archive endpoints directly archives an unready component exactly as they do today. Accepted deliberately: the existing archive path was not to be touched. It means the check raises the floor for callers that use it and does nothing for callers that do not.

**A verdict goes stale the moment it is returned.** A caller reads readiness, shows it, waits for a human, then archives. An issue can be reopened in between, and nothing re-checks. This is the direct cost of not enforcing at write time. Callers that care should request readiness as late as they can before archiving.

**The check costs at least three external calls, and more for a component with Jira version-range overrides.** Which is why it is its own endpoint and never attached to the component detail response — a detail view must not wait on external systems whose count is not even fixed. A component with N distinct effective Jira pairs costs one issue-search call and, per distinct project key among them, one project-category read — in practice N is small (the number of version ranges with a Jira override, typically zero or one), but it is not capped by the endpoint.

**Enumerating every version range's Jira pair costs correctness against simplicity.** The alternative — checking only the base configuration's pair — was considered and rejected: it would silently skip an older, still-active, unretired project sitting behind a version-range override, the same class of miss the TeamCity descendant check and the repository canonicalisation exist to close. The cost accepted instead is a variable number of `JIRA_ISSUES`/`JIRA_PROJECT` entries per component (decision 15) that the Portal must render as a list rather than a fixed pair, and a resolution step (`computeEffectiveJiraPairs`) that must be reused, not reimplemented, or the two call sites will disagree about how many pairs a component has.

**A default-bucket `JIRA_ISSUES` entry can silently miss a malformed issue.** The null-prefix pair's scope is a bare-version-pattern match, chosen over excluding every sibling's registered prefix specifically so the query's cost does not grow with how many components share the project (decision 15). The cost of that choice: an issue whose recorded version matches neither the bare pattern nor any pair's exact registered prefix — a typo'd prefix, a leftover from a removed override — is not attributed to the null-prefix pair, or to any pair. It is invisible rather than (safely) misattributed, which is the direction this design avoids elsewhere. Accepted here because the anomaly it depends on is expected to be rare, and reversible by fixing the malformed `fixVersion` in the tracker directly should it ever surface.

**The Atlassian REST Java client is heavyweight.** It is taken because the octopus `jira-client` has no issue search of any kind — its `JiraClient` interface is `createIssue`, `updateIssue`, `getAssignable`, `getProject`, `getActiveSprint`, `moveIssuesToSprint`, `addRemoteLink` — and counting open issues is the check's core question. The project category, which the octopus client's `Project` DTO carries and the Atlassian one does not, comes from the octopus client. So both are registered, each for exactly one read, following a pattern already working elsewhere in this ecosystem.

**The vcs-facade client version is confirmed.** `3.0.36` is required for `getRepository(sshUrl)` and a nullable `Repository.archived`; nothing already in use here has that shape — at `3.0.27`, `VcsFacadeClient` has no `getRepository` at all, and `Repository` — in the transitively-pulled `vcsfacade:common`, not in `client` — has only `sshUrl`, `link` and `avatar`. `3.0.36` was pulled from Maven Central directly and decompiled: it is the published `latest`/`release`, `VcsFacadeClient.getRepository(String)` exists (`GET rest/api/2/repository?sshUrl={sshUrl}`), and `Repository.getArchived()` returns a boxed `Boolean`. Task 1.1 is now a pin-and-wire step, not an open verification.

**`affectedProject` was verified by observation, not documentation.** Three projects, sizes 5, 24 and 2789, each matching an independently computed descendant set, and none including itself. That is good evidence, not a guarantee across TeamCity versions. The implementation adds the project itself explicitly rather than relying on the observed exclusion staying true.

**`archived` still means two things.** A soft delete and a retirement set the same flag. Not enforcing sidesteps the consequence rather than resolving it: had the delete path been gated, deleting a mistakenly created component would have required its retirement steps to be complete. The collision remains, and untangling it is the data-model change this scope excludes.
