# TD-029: Create checks no column lengths

## Status

Open · P3 · found by the add-component-templates change.

## Context

`ComponentManagementServiceImpl.createComponent` checks a key's characters but not its length,
and nothing limits the display name, client code or Jira project key. The database does:
`VARCHAR(255)` for the first three and `VARCHAR(50)` for the project key. The Portal sets no
length limits on these inputs either.

## The limit

A value longer than its column passes every create check and fails at the database. The create
answers `409` "Data integrity violation: duplicate or invalid data", naming no field, so the
creator cannot tell which value to shorten. A template dry run reports the same message as a
problem on no field; a `text` parameter without `max-length` makes it easy to hit.

## Removal options

- **Check lengths in the create.** Compare each free-text value with its column's length and fail
  with a message that leads with the field, as other create checks do. The dry run then names the
  field and its parameters with no change. The limits are written twice, in the schema and in code.
- **Read the limits from the entity.** Put `@Column(length = …)` or `@Size` on the entities and
  validate against them. One source for the limit, but the migrations still define the columns.

## References

- `components-registry-service-server/src/main/resources/db/migration/V1__schema.sql`
- `service/impl/ComponentManagementServiceImpl.kt`, `service/impl/TemplateDryRun.kt`
- `openspec/changes/add-component-templates/design.md` (Decision 8, Risks)
