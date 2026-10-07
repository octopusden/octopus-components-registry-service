# TD-028: The registry has no list of client codes

## Status

Open · P3 · accepted by the add-component-templates change.

## Context

A client code is checked on create only against the pattern `[A-Z_0-9]+`.
`GET /rest/api/4/components/meta/client-codes` returns the codes components already use
(`findDistinctClientCodes()`), not the clients that exist. The list of clients belongs to another
service, which the registry does not call.

A template therefore offers client codes as a `select` parameter whose `options` the template
lists by hand. A `crs-list` of client codes is refused on load.

## The limit

- A new client needs a template change, reviewed and reloaded, before its first component.
- Two templates can list different codes for the same clients.
- A wrong code passes when it matches the pattern and the template lists it.

## Removal options

- **Read the list from the service that owns client codes**, and offer it as a `client-codes`
  `crs-list` the Portal's form can show. Needs a client for that service, a cache, and a decision on
  what happens when it is unavailable (fail open, as the employee check does, or refuse).

## References

- `openspec/changes/add-component-templates/design.md` (Decision 13, Risks)
- `template/ComponentTemplate.kt` (`TemplateList`)
