# ADR-020: Component-key format depends on `clientCode`, enforced at create and rename only

## Status
Accepted. Implemented per [SYS-095](../requirements-common.md).

## Context

New components are expected to follow a strict lowercase-kebab key convention
(`[a-z][a-z0-9-]*`). Client-specific components break it for one reason: they are keyed
by their client code, and a `clientCode` matches `[A-Z_0-9]+`, so it may contain `_`.
The data already shows exactly one shape for this — of the 42 components that have both
an underscore in the key and a `clientCode`, all 42 are `lower(clientCode) + "-" + tail`
with no underscore past the prefix.

Two things made this awkward to fix. First, CRS validated no key characters at all
(only `clientCode` had a format rule), so the convention lived solely in the Portal's
create form and any API client could write anything. Second, 214 of 997 production keys
(160 with uppercase, 54 with a dot) do not satisfy the convention and never will.

## Decision

One field's legal charset depends on another field's value: a component key is plain
kebab, **or** the lowercased effective `clientCode` as a leading prefix — and `_` is
legal only inside that prefix. The prefix relaxes the charset, not the letter start: a
`clientCode` may begin with a digit or an underscore, a key may not. The rule is enforced **only when a key is chosen**: on
create, and on rename. An existing key is never re-validated.

"Effective `clientCode`" is the value that is or stays persisted, not the value on the
wire: post-`stripIfHidden` on create, the stored value on rename. That keeps the
invariant *an underscore in a key is always backed by a `clientCode` stored on that
component* — including for a field-config-hidden code, which is really there even
though nobody can see it.

## Consequences

- The 214 non-conforming legacy keys keep saving, because ordinary updates don't
  re-validate the key. The rule constrains new names only.
- The DSL import path writes via `componentRepository.save(...)`, bypassing
  `createComponent`, so legacy imports are unaffected — by design, not by accident.
- Rename is validated too. Without that, "create `abc`, rename to `a_b_c`" would bypass
  the rule entirely.
- Changing or clearing a `clientCode` afterwards does **not** re-check the key, so a key
  can outlive the code that justified its underscore. Accepted deliberately: the
  alternative makes `clientCode` un-editable for 42 real components, and the key remains
  a valid identifier either way. Revisited by the separate "change client code" feature.
- A future reader will find a validator that reads a second field. That is the point of
  this ADR: it is not an accident, and it is not a candidate for "simplification" into a
  single regex.
