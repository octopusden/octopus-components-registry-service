# crsctl

`crsctl` is a **read-only** command-line client for the Components Registry Service (CRS) **v4 API**.
It is built from this module (`components-registry-cli`) as a self-contained executable fat jar.

It speaks the v4 REST surface (`/rest/api/4/...` plus `/auth/me`), renders results as JSON or a
plain table, and is designed to be **scriptable**: stable JSON on stdout, structured JSON errors on
stderr, and a small, pinned set of process exit codes.

---

## Status

| Capability | Status |
|------------|--------|
| Anonymous reads — `components`, `component`, `meta` (except `meta employees`), `whoami` | **Works out of the box.** No login or token required. |
| `login` / `logout` / `audit` / `meta employees` | **Gated.** These need an authenticated identity. The device-flow `login` depends on a pending Keycloak **public device-flow client** (Part C spike) that does not exist yet, so the auth path is **not usable end-to-end** today. |
| Release / Homebrew tap / macOS notarization | **Separate follow-up.** Not part of this module yet. |

Anonymous read commands are fully functional now. Everything under the "Auth (gated)" heading below
is implemented in the CLI but blocked on the OIDC client provisioning.

---

## Build & run

Produce the fat jar:

```
./gradlew :components-registry-cli:shadowJar
```

The shadow plugin is configured with an empty archive classifier, so the artifact is:

```
components-registry-cli/build/libs/components-registry-cli-1.0-SNAPSHOT.jar
```

Run it:

```
java -jar components-registry-cli/build/libs/components-registry-cli-1.0-SNAPSHOT.jar --help
```

Throughout this document `crsctl` is shorthand for `java -jar <path-to-shadow-jar>`. A wrapper
shell alias is left to the (separate) release/packaging follow-up.

---

## Targeting a registry (env profiles)

`crsctl` never guesses a registry URL. Resolution precedence:

- **URL:** `--crs-url` flag → `CRS_URL` env → named profile (`--env`, else the config `defaultProfile`)
- **Token:** `--token` flag → `CRS_TOKEN` env → none (anonymous)

If no URL can be resolved from any source the command exits with `USAGE` (2) and a clear message
rather than defaulting to a possibly-wrong registry.

### Config file

A JSON config file holds named profiles. Location:

- macOS: `~/Library/Application Support/crsctl/config.json`
- otherwise: `$XDG_CONFIG_HOME/crsctl/config.json`, else `~/.config/crsctl/config.json`

A missing config file is tolerated (you can run entirely off `--crs-url` / `CRS_URL`). Shape:

```json
{
  "defaultProfile": "dev",
  "profiles": {
    "dev": {
      "crsUrl": "https://crs.dev.example",
      "keycloakIssuer": "https://idp.dev.example/realms/registry",
      "clientId": "crsctl-public"
    },
    "prod": {
      "crsUrl": "https://crs.example"
    }
  }
}
```

`crsUrl` is the only field required for read commands. `keycloakIssuer` / `clientId` are only
consumed by the (gated) auth layer; a bare `--crs-url` cannot supply them.

---

## Global options

| Option | Meaning |
|--------|---------|
| `--env <name>` | Named config profile to target. |
| `--crs-url <url>` | CRS base URL. Overrides `--env` and `CRS_URL`. |
| `--token <token>` | Bearer token. Overrides `CRS_TOKEN`. Anonymous if omitted. |
| `-o, --output json\|table` | Output format. Default `table`. |
| `-v, --verbose` | Verbose diagnostics. |
| `--insecure-token-store` | Store the refresh token in a plaintext file (mode 0600) instead of the system keychain. (Auth path only.) |
| `--version` | Print the CLI version (`1.0-SNAPSHOT`). |
| `--help` | Show help for any command or subcommand. |

`CRS_URL` and `CRS_TOKEN` are the recognized environment variables.

---

## Command surface

### `components list`

List components, optionally filtered. Anonymous; no token required.

Filters (repeatable ones may be passed multiple times):

| Option | Repeatable | Notes |
|--------|:---------:|-------|
| `--search <text>` | | Free-text across name / displayName. |
| `--owner <owner>` | yes | Component owner. |
| `--system <code>` | yes | System code. |
| `--product-type <type>` | | |
| `--build-system <bs>` | yes | |
| `--label <label>` | yes | |
| `--client-code <code>` | yes | |
| `--solution <true\|false>` | | |
| `--jira-project-key <key>` | yes | |
| `--jira-technical <true\|false>` | | |
| `--vcs-path <path>` | | |
| `--production-branch <branch>` | | |
| `--parent <name>` | yes | Parent component name. |
| `--group-key <key>` | yes | |
| `--archived <true\|false>` | | |
| `--can-be-parent <true\|false>` | | |
| `--distribution-explicit <true\|false>` | | |
| `--distribution-external <true\|false>` | | |

Paging:

| Option | Notes |
|--------|-------|
| `--page <n>` | Zero-based page number. |
| `--size <n>` | Page size. |
| `--sort <spec>` | e.g. `name,asc` (repeatable). |
| `--all` | Fetch every page (ignores `--page`; loops until the last page, bounded by a safety cap). |

**Output:** with `-o json` the command emits a **JSON array** of component-summary objects (the page
envelope's `content`, unwrapped). With `-o table` it prints columns `ID  NAME  OWNER  SYSTEM`.

### `component get <ID_OR_NAME>`

Fetch a component's full detail by id (UUID) or name. Anonymous.
`-o json` emits a single `ComponentDetailResponse` object.

### `component as-code <ID_OR_NAME>`

Print the component's as-code (Groovy-style) source verbatim. Output is raw `text/plain`; the
`-o` format is not applied.

### `component overrides <ID_OR_NAME>`

List a component's field-overrides. The endpoint requires the component's UUID; if the argument is
already a UUID it is used directly, otherwise it is resolved to a UUID via a `component get` lookup
first. `-o json` emits a **JSON array** of field-override objects.

### `meta <subcommand>`

Registry metadata / dictionary lookups. Each returns a **JSON array of strings** under `-o json`:

```
meta build-systems          meta labels                 meta repository-types
meta client-codes           meta labels-dictionary      meta systems
meta escrow-generations     meta maven-versions         meta systems-dictionary
meta group-keys             meta owners
meta java-versions          meta parent-component-names
meta jira-project-keys
```

`meta employees --search <q>` — **(auth)** search employees. Requires a token; on 401/403 exits
`AUTH_REQUIRED` (4). Emits a JSON array of `{username, active}` objects.

### `whoami`

Show the current identity. With no resolvable credential it prints a static anonymous line and
exits 0 **without** any HTTP call. With a token it calls `GET /auth/me` and renders the `User`
object (`username`, `groups`, `roles[]` with `permissions`).

### `audit` — (auth, gated)

Query the audit log. Every subcommand **requires login and the `ACCESS_AUDIT` permission**; with no
resolvable credential it fails fast with `AUTH_REQUIRED` (4) before any HTTP call.

- `audit recent` — recent entries, with filters `--entity-type`, `--entity-id`, `--changed-by`,
  `--action`, `--source`, `--from`, `--to`, `--include-migrated <true|false>`, plus
  `--page` / `--size` / `--sort`.
- `audit history <ENTITY_TYPE> <ENTITY_ID>` — history for one entity, with `--include-migrated` plus
  `--page` / `--size` / `--sort`.

Both emit a **JSON array** of audit-log rows under `-o json`.

### `login` / `logout` — (auth, gated)

See "Auth (gated)" below.

---

## Agent contract

`crsctl` is built to be driven by scripts and AI agents.

- **stdout** carries the result. With `-o json` it is stable, parseable JSON: a JSON **array** for
  list-shaped commands (`components list`, `component overrides`, `meta *`, `audit *`), a single
  JSON object for `component get` and `whoami` (with a token), and raw text for `component as-code`.
- **stderr** carries a structured error object on failure, with a fixed shape:

  ```json
  {"errorCode": "<code or null>", "message": "<human message>"}
  ```

  For server (`CrsApiException`) errors the server's `errorCode` / `errorMessage` are surfaced; for
  local errors `errorCode` is `null`.
- **exit code** signals the outcome so callers can branch without parsing text.

### Exit codes

| Code | Name | When |
|:----:|------|------|
| 0 | `OK` | Success. |
| 2 | `USAGE` | Bad invocation: unknown flags, missing args, unresolved target (no URL), bad config, HTTP 400. |
| 3 | `NOT_FOUND` | Resource does not exist (HTTP 404). |
| 4 | `AUTH_REQUIRED` | Authentication/authorization required or insufficient (HTTP 401/403, or no credential for an auth-required command). |
| 5 | `SERVER` | Server-side failure or transport problem (HTTP 5xx, I/O error). |

---

## Auth (gated)

> **Gated:** the device-flow login depends on a pending Keycloak **public device-flow client**
> (Part C spike). Until that client exists, `login` / `audit` / `meta employees` cannot complete
> end-to-end. The commands are implemented and the contract below is final.

- **Login (device authorization grant, RFC 8628):** `crsctl --env <env> login` resolves the
  `keycloakIssuer` + `clientId` from the active profile, prints a verification URL and a user code,
  polls until you authorize in a browser, and stores the resulting refresh token. It deliberately
  does **not** auto-open a browser.
- **`--offline`:** `crsctl login --offline` requests the `offline_access` scope so the provider
  returns a long-lived refresh token for non-interactive refresh between invocations.
- **Credential storage:** the refresh token is stored in the system keychain by default. Pass the
  global `--insecure-token-store` to store it in a plaintext file (mode 0600) instead.
- **Logout (revoke-then-clear):** `crsctl logout` revokes the stored refresh token at the provider
  (RFC 7009) and then removes it locally. Revocation is best-effort — if it fails, the local token
  is still cleared and the command exits 0 with a warning.
- **What needs auth:** anonymous reads need no login. Only `audit *`, `meta employees`, and an
  authenticated `whoami` consume a credential. `audit` additionally requires the **`ACCESS_AUDIT`**
  permission on the server.

---

## See also

- `skill/SKILL.md` — a Claude Code Skill describing when and how an agent should reach for `crsctl`,
  with copy-pasteable `jq` recipes.
