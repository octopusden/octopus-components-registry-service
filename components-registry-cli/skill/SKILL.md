---
name: crsctl
description: >-
  Query a Components Registry Service (CRS) over its v4 API from the command line. Use when an agent
  needs to look up components, their owners/systems/labels, a component's full detail or as-code
  source, its field-overrides, registry metadata dictionaries (owners, systems, labels, build
  systems, ...), or the audit log. Read commands are anonymous (no login). Stable JSON on stdout
  with -o json, structured JSON errors on stderr, and pinned exit codes for branching.
---

# crsctl — Components Registry Service CLI

`crsctl` is a **read-only** client for a Components Registry Service v4 API. Prefer it over raw
`curl` against `/rest/api/4/...`: it resolves the target URL/token consistently, emits stable JSON,
and uses pinned exit codes so you can branch on the outcome.

In the recipes below, `crsctl` means `java -jar <path-to-shadow-jar>` (build it with
`./gradlew :components-registry-cli:shadowJar`; the jar is at
`components-registry-cli/build/libs/components-registry-cli-1.0-SNAPSHOT.jar`). Always pass a target
with `--env <name>` (or `--crs-url <url>` / `CRS_URL`) and `-o json` for machine output.

## When to use

- "Which components does **alice** own in system **FOO**?" → `components list`
- "Show me everything about component **X**." → `component get`
- "What does component **X** look like as code?" → `component as-code`
- "List the field-overrides on component **X**." → `component overrides`
- "What owners / systems / labels / build-systems exist?" → `meta <kind>`
- "Who am I / what permissions do I have?" → `whoami`
- "What changed recently in the audit log?" → `audit` **(requires login + `ACCESS_AUDIT`)**

## Auth — read this first

- **Anonymous reads need no login:** `components`, `component`, `meta` (except `meta employees`),
  and `whoami` all work with just a target URL.
- **Only these need a credential:** `audit *`, `meta employees`, and an authenticated `whoami`.
- The `login` flow (and therefore `audit` / `meta employees` end-to-end) is currently **gated** on a
  pending Keycloak public device-flow client. Treat an `AUTH_REQUIRED` (exit 4) from those commands
  as "auth not available yet," not a bug in your invocation.

## Output shapes (so your jq matches reality)

| Command | `-o json` shape |
|---------|-----------------|
| `components list` | JSON **array** of component-summary objects |
| `component get` | single component-detail **object** |
| `component as-code` | raw text (not JSON; do not pipe to jq) |
| `component overrides` | JSON **array** of field-override objects |
| `meta <kind>` (dictionaries) | JSON **array of strings** |
| `meta employees` | JSON **array** of `{username, active}` |
| `whoami` (with token) | single `User` **object** |
| `audit recent` / `audit history` | JSON **array** of audit-log rows |

## Exit codes (branch on these)

| Code | Meaning |
|:----:|---------|
| 0 | OK |
| 2 | USAGE — bad flags/args, unresolved target, bad config, HTTP 400 |
| 3 | NOT_FOUND — resource missing (HTTP 404) |
| 4 | AUTH_REQUIRED — login/permission needed (HTTP 401/403, or no credential) |
| 5 | SERVER — server failure or transport error (HTTP 5xx, I/O) |

On failure, stderr carries `{"errorCode": <code or null>, "message": <text>}`.

## Commands

```
crsctl [GLOBAL OPTS] components list [FILTERS] [PAGING]
crsctl [GLOBAL OPTS] component get|as-code|overrides <ID_OR_NAME>
crsctl [GLOBAL OPTS] meta <build-systems|client-codes|escrow-generations|group-keys|java-versions|
                          jira-project-keys|labels|labels-dictionary|maven-versions|owners|
                          parent-component-names|repository-types|systems|systems-dictionary>
crsctl [GLOBAL OPTS] meta employees --search <q>          # auth
crsctl [GLOBAL OPTS] whoami
crsctl [GLOBAL OPTS] audit recent [FILTERS] [PAGING]      # auth (ACCESS_AUDIT)
crsctl [GLOBAL OPTS] audit history <ENTITY_TYPE> <ENTITY_ID> [PAGING]   # auth (ACCESS_AUDIT)
```

Global opts: `--env <name>` | `--crs-url <url>` | `--token <t>` | `-o json|table` | `-v` |
`--insecure-token-store`. Env vars: `CRS_URL`, `CRS_TOKEN`.

`components list` filters: `--search`, `--owner`*, `--system`*, `--product-type`, `--build-system`*,
`--label`*, `--client-code`*, `--solution`, `--jira-project-key`*, `--jira-technical`, `--vcs-path`,
`--production-branch`, `--parent`*, `--group-key`*, `--archived`, `--can-be-parent`,
`--distribution-explicit`, `--distribution-external` (`*` = repeatable). Paging: `--page`, `--size`,
`--sort` (e.g. `name,asc`), `--all`.

## Examples

Find component **names** owned by `alice` in system `FOO` (output is a top-level array):

```
crsctl --env dev components list --owner alice --system FOO -o json | jq -r '.[].name'
```

All non-archived components, every page, as `id  name`:

```
crsctl --env dev components list --archived false --all -o json | jq -r '.[] | "\(.id)\t\(.name)"'
```

Get one component's owner and labels:

```
crsctl --env dev component get my-component -o json | jq '{owner: .componentOwner, labels}'
```

View a component as code (raw text — do **not** pipe to jq):

```
crsctl --env dev component as-code my-component
```

List a component's field-overrides. The argument may be a UUID (used directly) or a name (resolved
to its UUID first):

```
crsctl --env dev component overrides 123e4567-e89b-12d3-a456-426614174000 -o json \
  | jq -r '.[] | "\(.overriddenAttribute)\t\(.rowType)\t\(.versionRange)"'
```

Meta lookups (each is a JSON array of strings):

```
crsctl --env dev meta owners  -o json | jq -r '.[]'
crsctl --env dev meta systems -o json | jq -r '.[]'
crsctl --env dev meta labels  -o json | jq -r '.[]'
```

Who am I (with a token; anonymous prints a static line and makes no call):

```
crsctl --env dev --token "$CRS_TOKEN" whoami -o json \
  | jq '{user: .username, perms: [.roles[].permissions[]] | unique}'
```

Recent audit changes for a user — **requires `crsctl login` and the `ACCESS_AUDIT` permission**
(currently gated; expect exit 4 / `AUTH_REQUIRED` until the auth client is provisioned):

```
crsctl --env dev audit recent --changed-by alice --action UPDATE -o json \
  | jq -r '.[] | "\(.changedAt)\t\(.action)\t\(.entityType)/\(.entityId)"'
```

Audit history for a single entity (also auth-gated):

```
crsctl --env dev audit history COMPONENT my-component -o json | jq '.[].changeDiff'
```

## Branching on results (pseudocode)

JSON results go to stdout; the structured `{"errorCode","message"}` error goes to stderr.
Capture them separately so you can read the error message on failure:

```
out=$(crsctl --env dev component get "$name" -o json 2>/tmp/crsctl.err); rc=$?
case $rc in
  0) echo "$out" | jq .name ;;
  3) echo "no such component: $name" ;;     # NOT_FOUND
  4) echo "need login / permission" ;;      # AUTH_REQUIRED (auth currently gated)
  *) echo "error: $(jq -r .message /tmp/crsctl.err 2>/dev/null)" ;;  # message is on stderr
esac
```
