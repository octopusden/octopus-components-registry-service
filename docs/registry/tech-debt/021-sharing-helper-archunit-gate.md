# TD-021: Re-enable the SharingHelper single-caller ArchUnit gate once a second sharing consumer exists

## Status

Open. Deferred in the component-archive-readiness-gate change (PR #483); the rule is present in
`ArchitectureFitnessTest` only as a commented-out sketch referencing this entry.

## Background

The archive-readiness feature introduced `sharingHelperIsOnlyCodeThatQueriesComponentTargetUsage`,
an ArchUnit rule making `SharingHelper` the only caller of `VersionLineRepository`'s two
project-id-based sharing queries. The intent was to prevent ad-hoc duplicate sharing-computation
logic from leaking into controllers or other services as the codebase grows.

A PR review flagged this as premature: today there is exactly one caller (`SharingHelper` itself)
and no second feature that queries component-target usage by any other path. An ArchUnit rule
guarding against a violation that cannot currently occur adds maintenance surface (another frozen
ratchet baseline to carry, another rule to explain) without protecting anything yet — there is
nothing else in the codebase this rule could catch today.

## Target

Re-enable the rule once a second real caller of cross-component target-usage queries appears (or
is about to be added) — at that point the gate has an actual duplicate-logic risk to prevent, and
freezing it is enabling a real protection rather than a speculative one.

```kotlin
@ArchTest
val sharingHelperIsOnlyCodeThatQueriesComponentTargetUsage: ArchRule =
    FreezingArchRule.freeze(
        ArchRuleDefinition
            .noClasses()
            .that()
            .doNotBelongToAnyOf(SharingHelper::class.java)
            .and()
            .resideOutsideOfPackage("..test..")
            .should()
            .callMethod(
                VersionLineRepository::class.java,
                "findByProjectIdsWithComponent",
                Collection::class.java,
            ).orShould()
            .callMethod(
                VersionLineRepository::class.java,
                "findDistinctLinkedProjectIds",
            ).because("SharingHelper is the single sharing computation unit (design decision 7)"),
    )
```
