# TD-027: A template dry run reports one failure of today's create rules at a time

## Status

Open · P3 · accepted by the add-component-templates change.

## Context

A template dry run runs today's create on the rendered request, in a transaction that is always
rolled back (`TemplateDryRun`). `ComponentManagementServiceImpl.createComponent` checks about 25
rules and throws on the first that fails, so the dry run sees only that one. Parameter problems and
the template's own rule problems are all reported; only the create step stops at one.

## The limit

A creator whose input breaks two create rules — say a taken key and a Jira project and prefix
already in use — sees the first, fixes it, runs the dry run again and only then sees the second.

## Removal options

- **Collect failures in the create.** Rework the create's checks to add to a list instead of
  throwing, and throw once at the end with every failure. Touches the busiest code path and every
  message test; the Portal's inline field errors would need to accept several messages.
- **Pre-check the common rules in the dry run.** Run the cheap, independent checks (key format and
  uniqueness, display-name uniqueness, Jira project and prefix uniqueness) before the create step.
  Duplicates rules the create owns, so they can drift.

## References

- `openspec/changes/add-component-templates/design.md` (Decision 8, Risks)
- `service/impl/TemplateDryRun.kt`, `util/CreateFailureFields.kt`
