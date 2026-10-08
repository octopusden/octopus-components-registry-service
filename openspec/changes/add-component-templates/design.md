## Context

**Profiles, as `add-component-profiles` left them**
- `ComponentProfilesSource` reads `components-registry.component-profiles` as flattened keys.
- `ComponentProfileParser` parses them into a `ProfileLoad`.
- `ComponentProfileCatalog` keeps the live profiles, swapped whole on a usable load.
- A `kind: template` entry is failed with "templates are not supported yet".
- `ProfileAvailability.evaluate(profile)` decides who may use a profile; the listing
  (`ComponentProfileControllerV4`) and `ProfileCreateCheck` both ask it.
- `CreateRequestPaths` lists the free-text paths a rule may name, and reads them from a
  `ComponentCreateRequest`. It was kept for this change, which renders a request.

**Today's create** (`ComponentManagementServiceImpl.createComponent`, one `@Transactional` method)
- About 25 checks, each throwing on the first failure:

  | Failure | Exception | Status |
  |---|---|---|
  | A value check | `IllegalArgumentException` | 400 |
  | Field editability | `ResponseStatusException` | 403 / 422 |
  | Cross-component conflict, after `saveAndFlush` | `CrossComponentConflictException` | 409 |

- Many messages start with the field (`name: …`); the Portal shows that prefix as an inline field
  error.
- The audit row is written by `AuditEventListener` at `BEFORE_COMMIT`, so a rolled-back create
  writes none.
- IDs are UUIDs, so a rolled-back create uses up no sequence.
- The only remote call is the employee-service active check, which passes when the service is down.
- `jiraTaskKey` and `changeComment` travel in the request body and land only on the audit row. A
  blank key is accepted; the Portal requires one.

**Defaults and lists**
- `component-defaults` lives in the registry's configuration and is served to the Portal
  (`GET /config/component-defaults`).
- For a regular create, the Portal builds the request and pre-fills it from those defaults. The
  registry never applies them, so a create through the API gets none.
- The registry's lists:

  | List | Source | Endpoint |
  |---|---|---|
  | Build systems | Enum | `/meta/build-systems` |
  | Escrow generation modes | Enum | `/meta/escrow-generations` |
  | Labels | `label` dictionary table | `/meta/labels/dictionary` |
  | Client codes | None; `findDistinctClientCodes()` returns the codes components already use | `/meta/client-codes` |

- A create checks a client code only against the pattern `[A-Z_0-9]+`.

## Example

A template for a client plugin (`packageType` is the create-request field):

```yaml
components-registry:
  component-profiles:
    client-plugin:
      kind: template
      title: Client plugin
      description: A plugin built for one client.
      version: 3
      order: 100
      classification: { solution: false, external: true, explicit: false }
      parameters:
        CLIENT_CODE: { label: Client code, type: select, options: [ACME, GLOBEX] }
        PLUGIN_CODE:
          label: Plugin code
          type: text
          pattern: "^[A-Z][A-Z0-9]{2,15}$"
          message: "3–16 upper-case letters or digits, starting with a letter."
        PLUGIN_NAME: { label: Plugin name, type: text }
        COMPONENT_OWNER: { label: Component owner, type: person, default: current-user }
      fields:
        name: "{{ CLIENT_CODE | lower }}-plugin-{{ PLUGIN_CODE | lower }}"
        displayName: "{{ PLUGIN_NAME | upper }} for {{ CLIENT_CODE | upper }}"
        componentOwner: "{{ COMPONENT_OWNER }}"
        clientCode: "{{ CLIENT_CODE | upper }}"
        labels: [plugin]
        artifactIds:
          - groupPattern: "org.example.plugins.{{ CLIENT_CODE | lower }}"
            mode: EXPLICIT
            artifactTokens: ["{{ PLUGIN_CODE | lower }}"]
        baseConfiguration:
          build: { buildSystem: GRADLE, buildTasks: build }
          vcsEntries:
            - vcsPath: "ssh://git@git.example.com/clients/{{ CLIENT_CODE | lower }}/plugin.git"
          jira:
            projectKey: PLUGINS
            versionPrefix: "{{ CLIENT_CODE | lower }}-plugin-{{ PLUGIN_CODE | lower }}"
          escrow: { generation: UNSUPPORTED }
      overridable: ["baseConfiguration.vcsEntries[0].vcsPath", baseConfiguration.jira.projectKey]
```

Flattened, as `ComponentProfilesSource` hands it over:

- `client-plugin.parameters.CLIENT_CODE.options[0]`
- `client-plugin.fields.artifactIds[0].groupPattern`
- `client-plugin.fields.labels[0]`
- `client-plugin.overridable[1]`

A field key is the create-request path itself.

A dry run with `CLIENT_CODE=ACME`, `PLUGIN_CODE=CORE`, `PLUGIN_NAME=Core API`, owner defaulted:

| Step | Result |
|---|---|
| Parameter checks | All pass; `COMPONENT_OWNER` takes the caller, `jdoe` |
| Render | `name: acme-plugin-core`, `displayName: CORE API for ACME`, `clientCode: ACME`, `labels: [plugin]`, … |
| Defaults | `baseConfiguration.jira.versionFormat`, the VCS tag and branch come from `component-defaults` |
| Sources | `name` ← `CLIENT_CODE`, `PLUGIN_CODE`; `labels` ← none (fixed) |
| Create, rolled back | `name: a component with name 'acme-plugin-core' already exists` |
| Reported | Problem on `name`, parameters `CLIENT_CODE`, `PLUGIN_CODE`; `valid: false` |

## Goals / Non-Goals

**Goals:**
- A template is data in service-config: checked on load, reloadable, never half-applied with the
  profiles next to it.
- One request turns parameter values into a component through exactly today's create.
- A dry run that passes means the create passes, unless something changed in between.
- Every problem a creator can fix names the parameter to change.

**Non-Goals:**
- Changing today's create rules, or making them collect every failure.
- Deciding which users are Delivery & Support.
- Applying the Portal-only create rules in the registry (TD-025).

## Decisions

### 1. Templates are entries of the same configuration and the same snapshot

- `ComponentProfileParser` hands a `kind: template` entry to `TemplateParser`, which returns a
  `ComponentTemplate` or the entry's problems.
- `ProfileLoad` gains `templates`. `usable` is unchanged, so a failed template never blocks a load.
- The catalog swaps profiles and templates together, as one snapshot. A reload that fails on a
  regular profile keeps the previous templates too.
- The catalog also keeps, for the administrator read (Decision 11):
  - the last load's outcome;
  - each entry's raw keys.

### 2. Field keys are create-request paths, read as flattened keys

- A field key is the path as `ComponentProfilesSource` flattens it, so YAML nesting and lists need
  no bracket notation: `fields.artifactIds[0].groupPattern`, `fields.labels[0]`.
- `TemplateFields` lists every path a template may set, with its field kind:

  | Field kind | Paths |
  |---|---|
  | Free text | `CreateRequestPaths.PATHS` |
  | CRS value | `baseConfiguration.build.buildSystem`, `baseConfiguration.escrow.generation` |
  | Person | `componentOwner` |
  | Free-text list | `artifactIds[0].artifactTokens` |
  | CRS list | `labels` |
  | People list | `releaseManager`, `securityChampion` |
  | Fixed choice | `artifactIds[0].mode`, `baseConfiguration.packages[0].packageType` |

- A list field is the indexed keys under it (`labels[0]`, `labels[1]`), in index order.
- Classification comes from `classification`, never from `fields`.

### 3. Expressions: a small subset of Jinja, our own parser

- The syntax is a subset of Jinja, so it looks familiar to template authors:
  `{{ NAME }}`, `{{ NAME | lower }}`, `{{ NAME | upper }}`; spaces inside the braces are optional.
- Parsed by `TemplateExpression`, not by a Jinja engine (such as Jinjava):
  - the subset needs no engine, and no new dependency;
  - knowing exactly which parameters each field uses, on load, is what the load checks and the
    parameter attribution rely on; `{% if %}` and `{% for %}` would make that known only at render
    time.
- Any other Jinja construct (`{% … %}`, `{# … #}`, another filter, an expression other than a
  parameter name) fails the template on load.
- A further filter, such as `trim` or `replace`, can be added later as one more row in the spec;
  templates written for the current subset stay valid.
- Parsed once, on load, into literal and parameter parts.
- A CRS value, a Person, or an item of a CRS list or people list takes a parameter whole:
  `{{ NAME }}` and nothing else, no filter. A free-text list item is free text.

### 4. Load checks

- The parser applies the spec's load checks; every problem is collected.
- Required fields: today's create rules plus the Portal-only required fields, so a template
  component is complete whichever client creates it:

  | Classification | Required |
  |---|---|
  | Any | `name`, `componentOwner`, build system, Jira project key, full version format (`baseConfiguration.jira.versionFormat`) |
  | Any, unless the build system needs no VCS | VCS path, branch and tag |
  | Explicit and external | `displayName`, `releaseManager`, `securityChampion` |
  | Explicit and external, unless the build system is `WHISKEY` | At least one of: Maven GAV (group and artifact pattern), Docker image name, package name |

  - A build system taken from a parameter counts as needing VCS and as not `WHISKEY`.
  - Copyright is left to the dry run: today's create requires it only when a copyright catalog is
    configured, a runtime setting.
- A required field counts as produced when:
  - the template fixes it with non-blank text, or a list field has a fixed item; or
  - it uses a required parameter; or
  - `component-defaults` supplies it at load (Decision 6's table), also when the template sets it
    from an optional parameter, since rendering then falls back to the default.

  The Portal's fallback values do not count.
- Build systems that need no VCS skip the VCS fields, as the Portal does: `PROVIDED`,
  `ESCROW_PROVIDED_MANUALLY`, `ESCROW_NOT_SUPPORTED`, `WHISKEY`, `BS2_0`.
- Fixed values are checked on load against static lists only: build systems, escrow generation
  modes, fixed choices.
- Fixed labels are checked by the dry run, against the labels dictionary at that moment, and
  reported as template problems. Why not on load:
  - the dictionary is database rows that change at runtime;
  - the catalog loads without the database.
- A client code, fixed or chosen from a `select`, is checked by today's create, for its pattern.
- People and uniqueness are not checked on load.

### 5. Parameter checks are pure, with two ports

- `ParameterChecker.check(template, submitted, caller) → ParameterValues(values, problems)` runs
  P1–P9:
  - `values`: by parameter, defaults applied, blanks and duplicates dropped — what rendering uses;
  - `problems`: every failure, each with the parameter name and the check id.
- P7 asks a `ListValues` port:
  - labels: the dictionary, from the database;
  - build systems and escrow generation modes: the enums;
  - no `client-codes` list (Decision 13).
- P8 asks an `EmployeeStatus` port, backed by the `EmployeeDirectoryService` the create already
  uses, with the same `ActiveStatus`:
  - inactive or unknown fails;
  - unavailable or disabled passes, as on create.
- Blank values are dropped and a value given twice counts once, for any parameter.
- A parameter absent, empty or only blank takes its default; `current-user` is the caller's login from
  `CurrentUserResolver`.

### 6. Rendering is pure and records sources

- `TemplateRenderer.render(template, values, overrides, defaults, jiraTaskKey, changeComment)
  → RenderedTemplate(request, fields, sources, overridden)`:
  - `fields`: every set path and its rendered value;
  - `sources`: each set path → the parameters it used; a fixed, defaulted or overridden value has
    none;
  - `overridden`: the paths an override replaced; the dry-run response returns them.
- Order:
  1. R1–R5, field by field;
  2. `component-defaults` on fields still unset (R7);
  3. overrides (R8).
- Which defaults apply to a template (copyright, VCS) is `ComponentDefaultsSeed.applicable`; the
  renderer and the load check of the template's own rules both use it.
- Why the registry applies the defaults:
  - whoever builds the component fills its unset fields — the Portal for a regular create, the
    registry for a template;
  - so a template component is the same from the Portal and from automation calling the API.
- The fields are the ones the Portal's create wizard pre-fills (`initialValues` in
  `createFormModel.ts`), each applied only when the default is non-blank:

  | Path | `component-defaults` key |
  |---|---|
  | `baseConfiguration.build.buildSystem` | `buildSystem`, unless deprecated (`BS2_0`) |
  | `displayName` | `componentDisplayName` |
  | `copyright` | `copyright`, only for an explicit, external template |
  | `baseConfiguration.jira.projectKey` | `jira.projectKey` |
  | `baseConfiguration.jira.versionFormat` | `jira.componentVersionFormat.versionFormat` |
  | `baseConfiguration.jira.lineVersionFormat` | `jira.componentVersionFormat.lineVersionFormat`, else the minor format |
  | `baseConfiguration.jira.minorVersionFormat` | `jira.componentVersionFormat.minorVersionFormat`, else the line format |
  | `baseConfiguration.jira.releaseVersionFormat` | `jira.componentVersionFormat.releaseVersionFormat` |
  | `baseConfiguration.jira.buildVersionFormat` | `jira.componentVersionFormat.buildVersionFormat`, only when it differs from the release format |
  | `baseConfiguration.escrow.generation` | `escrow.generation` |
  | `baseConfiguration.vcsEntries[0].tag` | `vcs.tag`, only when the build system needs VCS |
  | `baseConfiguration.vcsEntries[0].branch` | `vcs.branch`, only when the build system needs VCS |

- The Portal's own fallbacks are not copied: full version format `$versionPrefix-$baseVersionFormat`
  and branch `master`. A template that gets such a field neither from itself nor from
  `component-defaults` fails its load check (Decision 4), naming the field.
- The request carries the template's classification, the Jira task key and the comment. It has no
  `profile`, so `ProfileCreateCheck` does not apply.

### 7. Field rules are checked on the rendered request

- A template's rules are checked with `CreateRequestPaths.read` on the rendered request:
  - every rule is checked;
  - every failure is collected and attributed through `sources`.
- Not inside `createComponent`, because a rule failure must:
  - name parameters;
  - not hide the first failure of today's checks.

### 8. The dry run is today's create, rolled back

- One endpoint, `POST …/component-templates/{id}/components`, serves both; the `dryRun` query
  parameter, default `true`, picks the dry run:
  - one body and one set of steps, so the dry run cannot drift from the create;
  - a caller that leaves the flag out never creates a component by accident; creating takes an
    explicit `dryRun=false`.
- The admin migrate endpoint's `dryRun` defaults to `false`; this one differs on purpose, because
  it is the endpoint automation and the Portal call on every review step.

- The endpoint first checks that the user may use the template (`ProfileAvailability`, 403), and
  may override when the request has overrides (403).
- `TemplateDryRun` runs:
  1. parameter checks — stop on failure;
  2. render;
  3. field rules;
  4. today's `createComponent(rendered)`, inside a `TransactionTemplate` whose status is set
     rollback-only, so it always rolls back, without an `UnexpectedRollbackException`.
- Everything the create writes is rolled back with it: the component row, the label dictionary,
  the component-source row, the audit event at `BEFORE_COMMIT`. A template sets no TeamCity
  projects, so the TeamCity dictionary is never touched.
- A failure from `createComponent` is reported as a problem, its message unchanged: an
  `IllegalArgumentException` (400), a `ResponseStatusException` (403, 422), a `NotFoundException`
  (404) or a `CrossComponentConflictException` (409). A unique-index violation from a concurrent
  create is a problem on `name`. Any other exception propagates.
- Today's create stops at its first failure, so the dry run reports at most one of those. Every
  parameter problem and every rule problem is reported.

### 9. A create failure is attributed to fields by its message

- `TemplateProblems` turns a rule failure, an unknown fixed label or a create failure into a problem
  through the rendered template's `sources`; `CreateFailureFields` reads the field a create message
  names.

- `CreateFailureFields` maps the field a create message starts with to the template paths it
  concerns: `name:`, `displayName:`, `artifactIds:`, `componentOwner '…'`, the Jira project and
  version prefix conflict, …
- Each prefix today's create emits gets a test.
- Paths become parameters through `sources`:
  - of the paths a message concerns, only those the template set are named, or all of them when
    it set none;
  - a path set only by fixed values or defaults, or left unset, makes the problem a template
    problem; an overridden path never does;
  - a message no prefix matches is reported without a field.

### 10. Create from a template commits only a clean dry run

- `POST …/components?dryRun=false` runs Decision 8's steps; only the create step is
  transactional:
  - its transaction commits when nothing failed, rule and label problems included;
  - on a problem it answers 422 with the dry run's body, and nothing is created.
- The Jira task key and comment follow today's create: the key must match today's pattern when
  given, and may be blank. A dry run checks the key's pattern too, so a malformed key surfaces
  before the create. Requiring it is a Portal rule, recorded with the other Portal-only
  create rules in TD-025.
- An unknown, failed or removed template → 404, at the moment of the call.
- Overrides:
  - on a path not in `overridable` → 400 naming the path;
  - from a user `ProfileAvailability` says may not override → 403;
  - in this change every user who may use the template may override; the Delivery & Support
    change replaces that answer.

### 11. Administrator read

- `GET /rest/api/4/admin/component-profiles` on `AdminControllerV4` (`canImport()`, database mode).
- Per entry:
  - id, kind, status and problems;
  - the parsed definition, when live;
  - the configuration text: the entry's raw keys dumped back to YAML with SnakeYAML
    (`EntryYaml`), so a failed entry shows what was read.
- The configuration version: the `config.client.version` property Spring Cloud Config sets;
  absent when not served by a config server.
- The last reload's outcome: applied, or failed with its problems. When it failed, the entries
  shown are the ones still in use.

### 12. Availability gains templates and overrides

- `ProfileAvailability` gains `evaluate(template)` and `mayOverride(template)`, and is no longer a
  `fun interface`.
- `PermissionProfileAvailability`:
  - a template is usable with `CREATE_COMPONENTS`;
  - overriding is allowed whenever the template is usable.

### 13. Client codes are a `select` the template lists

- `crs-list` takes `build-systems`, `escrow-generation` and `labels`. There is no `client-codes`
  list.
- A template that offers client codes uses a `select` parameter and lists the codes in its
  `options`, as in the example. P6 then accepts only those codes.
- Why not the in-use codes: `findDistinctClientCodes()` knows only clients that already have a
  component, so a new client's first component — the main use of a client template — would always
  fail.
- The client-code list belongs to another service. Reading it from there, as a `client-codes`
  `crs-list` the Portal's form can offer, is recorded as tech debt.
- A new client means a configuration change adding its code to the template's options, reviewed
  and reloaded like any template change.

## Out of Scope

- See proposal.
- Technically: no migration, no new table, and no change to `createComponent`'s checks or to the
  update, import and field-override paths.

## Risks / Trade-offs

- **The dry run reports one failure of today's create rules at a time.**
  - A creator fixes it and runs again to see the next.
  - Accepted: collecting them means reworking every check of the busiest code path; parameter
    and rule problems, which name parameters, are all reported.
  - TD-027.
- **Attribution depends on message prefixes.**
  - A message reworded in `createComponent` loses its field and is shown without parameters.
  - Mitigated by one test per prefix; the problem is still reported.
- **A dry run holds the new component's row until it rolls back.**
  - Two concurrent dry runs for the same key can wait on each other's unique index.
  - Accepted: a dry run lasts milliseconds, plus the employee-service calls.
- **Passing a dry run does not guarantee the create.**
  - Someone may take the key in between, or a label may be removed.
  - Accepted: the create runs every check again and fails cleanly; a concurrent create that wins
    the unique index is reported as a problem on `name`.
- **Client codes are maintained by hand in each template.**
  - A new client needs a template change before its first component.
  - Two templates can list different codes.
  - Accepted: the registry has no list of its own, and the owning service is not connected yet.
    TD-028 (Decision 13).
- **The Jira task key is optional on a template create through the API.**
  - Automation can create a component without one.
  - Accepted: it keeps today's create rule; the Portal requires it. TD-025.
- **Fixed labels are checked late.**
  - A template with a label that does not exist loads as live, and fails every dry run as a
    template problem.
  - Accepted: the list changes at runtime, so a load-time check could not keep a template valid
    anyway.
- **A template cannot set `copyright`.**
  - It is not a template field; an explicit, external template gets it only from
    `component-defaults.copyright`.
  - Where copyright is shown and a copyright folder is configured, today's create requires it for
    an explicit, external component, so such a template fails every dry run without that default.
  - Accepted: an installation that hides copyright strips it and never requires it; a `copyright`
    field can be added to the field table when a template needs its own.
- **Rules read the rendered request, not the stored entity.**
  - A rule on a field the installation hides sees the rendered value, although the create drops
    it.
  - Accepted: a template that sets a hidden field is a template problem; the dry run's create step
    still reports it as today.
