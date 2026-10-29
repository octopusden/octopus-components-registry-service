# TD-019: Define and guard the DB-source vs legacy-Groovy dependency boundary

## Status

Open. The ArchUnit "`..db..` must not depend on Groovy" forward-guard was **removed** from the
active rules: it guarded a package (`..db..`) that does not exist, while the real DB read path lives
elsewhere and legitimately depends on the escrow model. A guard is only meaningful once the intended
boundary is actually defined.

## Background

The current DB read path is `service.impl.DatabaseComponentRegistryResolver`, and it **intentionally**
imports ~12 `org.octopusden.octopus.escrow.*` types (`EscrowModule`, `EscrowConfigurationLoader`,
`Distribution`, resolvers, …) so its output is byte-compatible with the Git resolver during the
migration. So today:

- there is no `..db..` package;
- the real DB code depends on the escrow (partly Groovy) model on purpose;
- a rule keyed on `..db..` protects nothing real and can create a false sense of a boundary.

## Target

Do the design first, then the guard:

1. Decide (ADR) what the intended long-term boundary is — e.g. a clean DB-backed source that talks to
   its own model and does NOT reach into the legacy escrow/Groovy config types, with an explicit
   anti-corruption seam for the wire-compat mapping.
2. Give that boundary a real home (package/module) once the migration allows dropping the wire-compat
   coupling.
3. THEN add an ArchUnit rule enforcing it, scoped to that real boundary (not a placeholder package
   name), denying dependencies on the legacy escrow/Groovy packages.

## Effort / notes

- Gate this on the migration reaching the point where the DB source no longer needs escrow types for
  compatibility — until then the coupling is a feature, not a violation.
- When added, pair with a focused regression test (a boundary class depending on a Groovy-authored
  escrow type must fail), since Groovy-authored classes compile to plain names.
