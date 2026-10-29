# TD-030: Template problems find their field by parsing create's messages

## Status

Open · P3 · accepted by the add-component-templates change.

## Context

A template dry run runs today's create and reports its failure as a problem on the template
fields it concerns, and through them on the parameters to change. Create throws plain exceptions
with a message, so `CreateFailureFields` reads the field from the message: a leading `field:`, a
known uniqueness sentence, or an editability message.

## The limit

A message reworded in `ComponentManagementServiceImpl` loses its field: the problem is still
reported, but names no field and no parameter, so the Portal cannot highlight what to change.
`CreateFailureFieldsTest` checks its own copy of each message, not create's, so a rewording keeps
it green. Only a few cases, such as a taken key and the 409 conflict, run through the real create
in `ComponentTemplateControllerV4Test`.

## Removal options

- **Shared message constants.** Move create's messages into constants that create and the test
  both use, so a rewording changes the test with it. Cheap, but it keeps the parsing.
- **Structured failures.** Have create throw an exception carrying the field (for example a
  `FieldValidationException(field, message)`) and map it in the existing exception handler. No
  parsing; touches every check of create and its message tests.

## References

- `util/CreateFailureFields.kt`, `util/TemplateProblems.kt`, `service/impl/TemplateDryRun.kt`
- `openspec/changes/add-component-templates/design.md` (Decision 8, Risks)
