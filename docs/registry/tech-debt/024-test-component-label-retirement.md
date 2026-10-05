# TD-024: Retire the `test-component` label in favour of the `testComponent` flag

## Status

Open. Deferred by SYS-099, which introduced the flag.

## Background

A test component used to be marked only by the free-text label `test-component`. SYS-099 made it
the typed component flag `testComponent` (DB / v4 API); `V11__` set the flag on every component
carrying the label and kept the label, because consumers still match on it — the vulnerability-scope
filter (RNDBPA-104 `inVulnerabilityScope`, which accepts flag OR label) and the Sonar automation
(`SonarExecutionResolver`, label only).

## Workaround applied

Both markers coexist. A v4 create/update of a component that carries the label while
`testComponent` is false succeeds and returns a `warnings` entry
(`ComponentManagementServiceImpl.withTestComponentLabelWarning`) instead of failing.

## When removing

1. Once every consumer reads the flag (RNDBPA-104 drops its label branch; decide whether Sonar
   follows the flag), turn the warning into a `400` (label without flag is rejected).
2. Remove the label from the flagged components (a data migration or v4 PATCHes) and from the DSL
   fixture `TestComponents.groovy`.
3. Delete the warning code and this entry.
