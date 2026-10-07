# TD-024: Solution key rules hold only on a create that names a profile

## Status

Open · P3 · limitation accepted by the add-component-profiles change · existing behavior kept for
every client that does not send `profile`.

## Context

The solution key rules — a Solution key contains `-solution`, a DMP Bundle key contains
`dmp-bundle`, a regular key contains neither — are field rules of the profiles in
`components-registry.component-profiles`. `ProfileCreateCheck` applies a profile's rules on
`POST /rest/api/4/components` when the request names that profile.

Nothing else consults them. There is no built-in naming convention, and the profile is not stored on
the component, so later writes have no profile to check against.

## The limit

A key can break the rules through any path that does not name a profile:

- a create without `profile` — any API client, and the Portal until it sends one;
- a rename (`PATCH name`);
- a solution-flag change (`PATCH solution`), which can make `payments-solution` a non-solution or
  `payments` a solution;
- the DSL import.

So the rules describe what the Portal's start page enforces, not an invariant of the registry: a
component's key and solution flag can disagree.

## Removal options

- **A built-in naming convention** on every create, rename and solution-flag change: a solution's key
  contains `solution` or `dmp-bundle`, any other key contains neither. Enforces the invariant
  everywhere, but makes it code rather than configuration, and rejects renames of existing
  components that already break it until their key is fixed — list those first.
- **Record the profile on the component** and re-check its rules on every write that touches a
  ruled field. Keeps the rules configurable, but needs a column, a backfill for existing components
  (which profile was each created with?) and a decision on what a rule change does to them.
- **Require `profile` on every create.** Closes the create path only; renames and flag changes stay
  open. A breaking change for API clients.

## References

- `profile/ProfileCreateCheck.kt`, `profile/CreatedComponent.kt`
- `SolutionKeyWithoutProfileTest` — pins today's behavior for creates without a profile, renames
  and flag changes
- `openspec/changes/add-component-profiles/proposal.md` (Out of scope) and `design.md` (Risks)
