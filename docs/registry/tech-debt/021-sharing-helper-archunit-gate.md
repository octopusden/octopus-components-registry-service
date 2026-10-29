# TD-021: Re-enable a sharing-computation-duplication ArchUnit gate once it can be scoped correctly

## Status

Open. Deferred in the component-archive-readiness-gate change (PR #483); the rule is present in
`ArchitectureFitnessTest` only as a commented-out sketch referencing this entry.

## Background

The archive-readiness feature introduced `sharingHelperIsOnlyCodeThatQueriesComponentTargetUsage`,
an ArchUnit rule making `SharingHelper` the only caller of `VersionLineRepository`'s
`findByProjectIdsWithComponent` and `findDistinctLinkedProjectIds`. The intent was to prevent
ad-hoc duplicate sharing-computation logic from leaking into controllers or other services as the
codebase grows.

A PR review flagged two problems with the rule as written:

1. Its premise was already false when it was added: `TeamcityValidationQueryService.kt` (via
   `componentsByProject`) and `TeamcityValidationService.kt` (via
   `findDistinctLinkedProjectIdsSafely`) already call these two methods directly, for the
   unrelated TeamCity-validation feature (mapping projects to their owning components, and
   sweeping stale validation rows) — not for sharing computation. The rule would have failed the
   build immediately if ever enabled.
2. The rule was scoped to "any caller of these two general-purpose repository queries", not to
   "duplicate sharing-computation logic" specifically. Those two methods are legitimately reusable
   beyond `SharingHelper` — the TeamCity-validation feature is a real, valid second caller, not a
   violation to prevent.

## Target

Do not simply re-enable the original rule once "a second caller" appears — one already exists and
is legitimate. Re-enable a rule here only once it can be scoped around the actual risk: another
piece of code re-deriving "which live components still use this target" (the sharing question
`SharingHelper` answers), as opposed to any use of these two general-purpose project-id queries.
That likely means matching on the sharing-specific shape of the computation (e.g., a method that
also excludes an archived/excluded component id) rather than matching on the raw repository calls.
