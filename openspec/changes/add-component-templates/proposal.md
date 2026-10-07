## Why

- Registering a standard client component takes the full create wizard:
  - every field is typed by hand, by someone who knows the registry's conventions;
  - yet the component is set up the same way for every client, apart from a few values.
- Delivery & Support engineers cannot register these components without R&D.
- Automation, such as an onboarding job, can only create a component by building the whole
  create request itself.
- Component profiles now come from configuration (`add-component-profiles`). `kind: template` is
  reserved there and reported as "templates are not supported yet".
- This change gives that kind its meaning:
  - a template fixes most fields and asks for the few that differ;
  - the registry builds the component and checks it.

## What Changes

**Templates in the profile configuration**
- A `kind: template` entry under `components-registry.component-profiles` is parsed. It has:
  - `title`, `description`, `order` and `version`;
  - a fixed `classification` (no `ask`);
  - `parameters`;
  - `fields`: create-request path → value, with expressions in a small subset of Jinja syntax
    (`{{ NAME }}`, `| lower`, `| upper`), parsed by the registry itself, without a Jinja engine;
  - `overridable` paths;
  - optional field `rules`, the same rules a profile carries.
- Every template is checked on load and reload. A broken template:
  - is `failed`, with every problem, each naming the parameter or field;
  - is not offered;
  - never blocks a load or a reload.
- The profile listing returns live templates next to the regular profiles, in one order, with
  their version.

**Parameters**
- Four types: `text`, `select`, `crs-list` (build systems, escrow generation modes, labels) and
  `person`.
- A client code is a `select` whose options the template lists by hand; the registry has no list
  of client codes (see Out of scope).
- `GET /rest/api/4/component-templates/{id}` describes a live template's parameters:
  - each `crs-list` parameter comes with the list's current values;
  - a `current-user` default comes back as the caller.
- Submitted values are checked with every parameter check (P1–P9); each failure names its
  parameter.
- An absent parameter takes its default; an empty optional parameter adds nothing.

**Rendering**
- One renderer turns a template, checked values and overrides into a create request:
  - following rendering rules R1–R8;
  - recording which parameters each field came from.
- Fields the template leaves unset get the installation's `component-defaults`:
  - applied by the registry, because it builds a template's component, as the Portal builds a
    regular one;
  - only the fields the Portal's create wizard pre-fills today, so a template component looks
    the same from the Portal and from the API;
  - without the Portal's own fallbacks (branch `master`, a built-in full version format); a
    template that needs one fails its load check instead.
- Regular creates are unchanged: the Portal keeps pre-filling its wizard, and a regular create
  through the API still gets no defaults.

**Dry run and create**
- `POST /rest/api/4/component-templates/{id}/dry-run`:
  - checks the parameters, renders, and checks the template's field rules;
  - runs today's create on the rendered request, in a transaction that is always rolled back;
  - returns the rendered component, each field's source parameters, and every problem found,
    each naming the parameters to change or marked as a template problem.
- `POST /rest/api/4/component-templates/{id}/components`:
  - runs the same steps, and commits the create only when nothing failed;
  - takes the Jira task key and comment as today's create does: the key must match the pattern
    when given, and may be blank;
  - writes the audit entry any create writes; the component keeps no link to the template.
- Overrides:
  - accepted only for paths the template lists in `overridable`;
  - who may override is one decision in `ProfileAvailability`; in this change, every user who
    may use the template may.

**Administrator read**
- `GET /rest/api/4/admin/component-profiles` returns:
  - every configured entry, live or failed, with its definition, problems and configuration text;
  - the configuration version in use;
  - the outcome of the last reload.

## Affected areas

- `components-registry-service-server`:
  - a new `template` package: model, parser, expressions, parameter checks, renderer, dry run;
  - a new `ComponentTemplateControllerV4`;
  - the profile parser, catalog, listing and availability;
  - `AdminControllerV4`.
- `ComponentManagementServiceImpl.createComponent` is called unchanged; the dry run wraps it.
- **Listing response:**
  - `kind` can now be `template`;
  - entries gain `version`, absent for a regular profile;
  - a client that assumed every entry is `regular` must branch on `kind`.
- **Reload response:** a valid template entry is now `live`; only a broken one is `failed`.
- v4 contract change → `api-changelog.md`, regenerated `v4.json`, `functional-spec.md`, ADR-016.
- service-config: the first template is a separate change, after this one.

## Out of scope

- **Delivery & Support restriction** — creating only from templates, and not overriding, waits
  for the D&S role mapping. This change keeps the seam it will use (`ProfileAvailability`).
- **Reporting every failure of today's create rules at once** — those rules stop at the first
  failure; the dry run reports that one, worded as the create words it.
- **The Portal-only create rules** (TD-025):
  - the Portal applies them to a template's rendered component on review; the registry does not;
  - the required Jira task key joins that list: the Portal requires it, the registry accepts a
    blank one, on a template create as on any create.
- **A `client-codes` list**:
  - the registry has no authoritative list; `/meta/client-codes` returns only the codes
    components already use, so a new client's code would never pass;
  - the list belongs to another service; reading it from there is recorded as tech debt;
  - until then, a template lists client codes as a `select`'s `options`, and a new client means
    adding its code to the template.
- **Template editing in the Portal, linking a component to its template, systems and security
  groups as template fields** — excluded by the business requirements.
- **No-db mode** — create needs the database; the template endpoints exist only with it, like the
  create endpoint.

## Rollout note

- Deploy needs no service-config change. With no template configured, behavior is the
  `add-component-profiles` behavior, except that the reload and listing responses can carry
  templates.
- A template that fails its load checks after deploy is reported by the reload and the
  administrator read, and is simply not offered.
