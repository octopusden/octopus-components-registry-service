# TD-025: Create rules the Portal checks and the registry does not

## Status

Open · P2 · not addressed by the add-component-profiles change, which moved only the solution key
rules into the registry · planned as a separate change.

## Context

The Portal's create wizard rejects a new component unless:

- the Jira project key and the full version format are set;
- a VCS path is set, with a branch and a tag, unless the build system needs no VCS (`BS2_0`,
  `PROVIDED`, `ESCROW_PROVIDED_MANUALLY`, `ESCROW_NOT_SUPPORTED`, `WHISKEY`);
- the VCS path is an `ssh://` URL on the configured Bitbucket host;
- every artifact-ownership group ID starts with a supported prefix;
- an explicit and external component has a complete distribution coordinate (Docker image name,
  package name).

`POST /rest/api/4/components` checks none of these.

## The limit

Any client calling the registry directly — automation, scripts, and later the template create path —
can create a component the Portal would have refused: no Jira project, no VCS root, a VCS root on
another host, an ownership group outside the supported prefixes, or a distribution without a name.

## Removal options

- **Enforce the rules on create in the registry**, with field-prefixed `400` messages as the other
  create errors, keeping the Portal's checks as fast feedback. Updates, field overrides and the
  import stay unchanged so existing components remain editable. The behavior change for API clients
  must be announced in the changelog.
- **Express them as profile field rules.** Covers only pattern-shaped rules (VCS host, group prefix)
  and only creates that name a profile — see [TD-024](024-solution-key-rules-only-on-profile-creates.md).
  Not a substitute for the conditional rules (VCS needed only for some build systems).

## References

- Portal: `createFormModel.ts`, `buildCreateRequest.ts`, `vcsHost.ts`
- `ComponentManagementServiceImpl.createComponent`, `validateMalformedFieldRules`
- `openspec/changes/add-component-profiles/proposal.md` (Out of scope)
