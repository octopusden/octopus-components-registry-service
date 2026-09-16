# TD-022: `JiraComponentVersionRange` equality omits `componentName`, so components vanish from the range endpoints

## Status

Open. Found while implementing [ADR-021](../adr/021-effective-jira-display-name.md); **not** caused by it.

## Symptom

`GET /rest/api/2/common/jira-component-version-ranges` (and the per-project variant) can silently
omit a component. Not an error, not a log line — the component is simply absent from a `Set`.

Observed on the `common` test dataset: `ARCHIVED_TEST_COMPONENT_WITH_DISPLAY_NAME` declares
`jira { projectKey = "TEST_ARCHIVED" }`, so it must contribute a range, but it appears in neither
expected-data fixture and did not appear in the response. Both fixtures encoded the loss as if it
were correct.

## Mechanism

`component-resolver-api/.../JiraComponentVersionRange.java` — **`componentName` is in neither
`equals` nor `hashCode`**:

```java
equals():   versionRange, jiraComponent, distribution, vcsSettings
hashCode(): versionRange, jiraComponent, distribution, vcsSettings
```

So two ranges belonging to *different components* compare equal whenever those four fields match —
which is common for components sharing a Jira project, a version range and a distribution shape.
`DatabaseComponentRegistryResolver.getAllJiraComponentVersionRanges` collects into a `Set`, and
`CommonControllerV2` collapses a second time into `Set<JiraComponentVersionRangeDTO>`. Whichever
element arrives second is discarded, and the component it belonged to disappears from the endpoint.

A second, interacting defect makes the loss **non-deterministic**: `JiraComponent.equals` excludes
`displayName` while `JiraComponent.hashCode` includes it (see the `octopus-releng-lib` fix). Two
ranges can therefore be `equals` yet hash to different buckets, so whether the collapse happens at
all depends on whether a display name is set.

That is how ADR-021 surfaced this: giving those components a non-null Jira display name changed
their `hashCode`, and one previously-swallowed component reappeared. The fixture was corrected to
include it, and the mechanism is recorded here rather than in the ADR because it is a separate bug.

## Why it was not fixed in that PR

`JiraComponentVersionRange` lives in this repository, in the `component-resolver-api` module — but
that module is published and consumed elsewhere (escrow-generator, components-automation, the Jira
utils client), so adding `componentName` to the equality contract changes collection semantics for
every downstream consumer. The blast radius is cross-repo even though the edit is local, and it is
unrelated to what the display-name PR was about, so it needs its own change and its own review.

Note this is a **different repository** from the `JiraComponent` `equals`/`hashCode` fix
(`octopus-releng-lib`): the two cannot be one pull request.

Note that fixing only the `JiraComponent` `equals`/`hashCode` asymmetry is **not sufficient** — it
makes the collapse deterministic rather than removing it. Both are needed.

## Fix

1. Add `componentName` to `JiraComponentVersionRange.equals` and `hashCode`.
2. Land the `octopus-releng-lib` `JiraComponent.hashCode` alignment so equality and hashing agree.
3. Re-derive both `jira-component-version-ranges` fixtures from the corrected output and diff against
   the current ones — every entry that appears is a component the endpoints were dropping.
4. Check whether any consumer relies on the current cross-component collapse before shipping.

## Risk if left

Silent, data-dependent omission from a public v2 endpoint. It is invisible to the compat gate too:
both baseline and candidate drop the same element, so the diff is empty and the gate stays green.

**Partially observable since ADR-021.** `displayName = null` is what made the dropped components
identical to their siblings, so resolving the effective name separates some of them and they
reappear in the candidate — the gate now sees an `ARRAY_SIZE_MISMATCH` where it previously saw
nothing. `Adr021RangeRecovery` (compat-test) confirms each such addition against the collapse
story and names the recovered components in `summary.md`. That is a symptom becoming visible, not
a fix: components whose siblings ALSO gained no name still collapse, and the equality contract is
still wrong. Fixing it here remains the work described above.

**That visibility is temporary, and depends on a library version.** The collapse comes apart only
because `JiraComponent.hashCode` includes `displayName` while its `equals` excludes it: once the
names differ the pair lands in different `HashSet` buckets and both survive, even though they are
still `equals`. octopus-releng-lib **#17 repairs that contract** — merged to `main`, but **not yet
released**: the newest tag is `v2.0.8`, which predates it, and this repository pins
`releng-lib.version=2.0.8`.

So the moment CRS bumps to a release containing #17, both sides hash identically again, the pair
collapses on **both** sides, the `ARRAY_SIZE_MISMATCH` disappears and the ADR-021 recovery
known-delta goes dormant (harmlessly — it only matches when the rule emits its marker). The
components become invisible again. Anyone reading a green gate after such a bump should not conclude
this debt was paid; it was only re-hidden. Sequence the fix here **before or with** the library bump,
and see also [TD-023](023-distribution-equality-compares-nothing.md), which widens the blast radius
of the same collapse.
