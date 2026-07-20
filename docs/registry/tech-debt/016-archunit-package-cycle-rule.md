# TD-016: Enable the ArchUnit no-package-cycles fitness rule

## Status

Open. Deferred in the ArchUnit fitness-functions change (PR #439); the rule is present in
`ArchitectureFitnessTest` only as a commented-out sketch (Rule 4) referencing this entry.

## Background

`docs/registry/non-functional-spec.md` §5.3 lists "no cyclic dependencies between the server's
top-level slices" as a target architecture rule. It is the one rule from that section not active
today.

The server module currently has one large package cycle spanning most slices
(`config -> dto -> service -> ...`). Turning the rule on as a `FreezingArchRule` would record a
~586 KB baseline whose per-edge text is invalidated by any import reshuffle, producing frequent
false CI failures while guarding almost nothing — the accepted violations would dwarf what the rule
protects. So the cycle rule is intentionally NOT enabled yet.

A CodeRabbit thread on PR #439 raised the same point (freezing the current tangle is not useful);
this TD is the tracked resolution.

## Target

1. Break the package tangle into acyclic slices (extract interfaces / move DTOs / invert the
   config→service dependency, as appropriate) until the module is free of package cycles.
2. Re-enable Rule 4 in `ArchitectureFitnessTest` **unfrozen**:

   ```kotlin
   @ArchTest
   val serverSlicesMustBeFreeOfCycles: ArchRule =
       slices().matching("$BASE_PACKAGE.(*)..").should().beFreeOfCycles()
   ```

   An unfrozen rule (no baseline) is the goal — the point is zero cycles, not a frozen snapshot.

## Effort / notes

- The decoupling is the real work; flipping the rule on is a one-line change once cycles are gone.
- Do not freeze this rule as a shortcut: a frozen cycle baseline is brittle and low-value (that is
  exactly why it was deferred).
