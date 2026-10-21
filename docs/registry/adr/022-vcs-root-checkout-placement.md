# ADR-022: Checkout placement of VCS roots is registry data

## Status

Accepted. Implemented in #503 (fields, validation, name derivation, migrations `V8__`/`V9__`).
Moved here from the program repository, where it was recorded as ADR-001; code comments written
before the move cite it as "ADR-001". Decision numbers are unchanged.

## Context

A component may keep its sources in more than one VCS root, or in one directory of a repository
shared with other components. The registry stored an ordered list of VCS roots per configuration
row, but nothing said where each root's sources go on the build agent, which part of a shared
repository belongs to the component, or where the build runs. The build-chain generator
therefore attached only the first root and no checkout rules; teams edited chains by hand, and
hand-edited chains drifted from the registry.

Constraints:

- TeamCity agent-side Git checkout cannot rename a directory: a rule may keep a directory under
  its own name, optionally below a prefix (`+:dir => prefix/dir`), or place a whole repository in
  a directory (`+:. => dir`). Each mapped directory carries its own `.git`. Server-side checkout
  can rename but leaves no `.git`, which breaks Sonar's target-branch fetch and version tooling.
- Release tagging tags the commits recorded when the compile build registered, so a root reaches a
  tag only if the compile configuration attaches it.
- The escrow export writes each root of a multi-root component into `src/<name>/` and silently
  skips a second root with the same name.
- The shared compile templates feed about 1 400 configurations; about 96 of them rely on the
  version-format file staying at the checkout root, so the templates cannot derive paths from
  `WORK_DIR`.
- Existing hand-made chains place roots in any order: the root at the checkout root is not always
  the first registry entry, and some chains have no root at the checkout root at all.

## Decision

1. **VCS roots are peers; each one is placed on its own.** Each VCS entry carries two optional
   fields. *Source Path* (`sourcePath`) is the directory inside the repository that belongs to the
   component (empty = the whole repository). *Checkout Directory* (`checkoutDirectory`) is a single
   directory name under which the root is checked out; it is optional on any root, a single-root
   row included. At most one root of a row has no Checkout Directory; that root is checked out at
   the checkout root, at any position in the list. The Checkout Directory is a prefix, never a
   rename, matching what agent-side checkout can do.

   | Checkout Directory | Source Path | Checkout rule |
   |---|---|---|
   | empty | empty | none (whole repository at the checkout root) |
   | set | empty | `+:. => <Checkout Directory>` |
   | set | set | `+:<Source Path> => <Checkout Directory>/<Source Path>` |
   | empty | set | `+:<Source Path> => <Source Path>` |

2. **The Build Working Directory is its own field.** Each configuration row, base and per-range,
   carries an optional `buildWorkingDirectory`: a relative path, empty = the checkout root. Its
   first segment equals (case-sensitively) some root's Checkout Directory, or the row has a root
   without one. Empty is valid only when a root without a Checkout Directory exists, so a row whose
   roots all have one requires a Build Working Directory. There is no "primary" root: the Build
   Working Directory says where the build runs, and the root that contains it is the component's
   own repository for Sonar.
3. **The registry stores placement, not tool syntax.** The generator derives TeamCity checkout
   rules and parameters from the fields; the registry holds no checkout-rule strings.
4. **`name` is derived and unique.** A root with a Checkout Directory is named by it, on every
   write. A root without one keeps the stored name of the row's previous entry with the same
   repository (`vcsPath`, Git compared case-insensitively), otherwise it is named `main`. Final
   names are unique case-insensitively across the row. A `name` in a request is ignored. So an
   existing root keeps its name as long as it stays at the checkout root.
5. **Placement values come from TeamCity, not from a guess.** Migration `V8__` set
   `checkoutDirectory := name` on every entry after the first of existing multi-root rows. That is
   only the fallback; the real values are read from the working TeamCity chains by the Diff/Sync
   jobs of [ADR-023](023-teamcity-placement-diff-sync.md).
6. **The generator sets per-configuration parameters only; shared templates and standard steps
   stay unchanged.** It attaches every root with its rule: first the root that contains the Build
   Working Directory; with no Build Working Directory, the root without a Checkout Directory;
   otherwise the first root. When a Build Working Directory is set, it sets `WORK_DIR` and
   `COMPONENT_CONFIG_DIR` to `%teamcity.build.checkoutDir%/<Build Working Directory>`; otherwise
   neither. The default branch of each generated VCS root is the first alternative of the registry
   `branch` for the chain's version line.
7. **Sonar follows the Build Working Directory.** The target-branch fetch runs in `WORK_DIR`; the
   commit stamp is taken from the first build revision whose URL matches a registry root, which
   with the attach order of decision 6 is the root that contains the Build Working Directory.
8. **Scope of automation is creation.** The generator does not reconcile existing chains. A v4
   write that changes roots or the Build Working Directory of a component linked to a TeamCity
   project returns a warning that the chain must be recreated.
9. **Version-format file outside the checkout root.** The shared compile templates (`CDGradleBuild`,
   `CDJavaMavenBuild`) get a template parameter `BUILD_VERSION_FORMAT_FILE`, defaulting to today's
   `build-version-format.properties`, read by their *Calculate Build Version* step. The generator
   sets it to `<Build Working Directory>/build-version-format.properties` on every configuration it
   creates that has that step, only when a Build Working Directory is set. The template change is a
   manual prerequisite outside this repository; until it is applied, such a chain fails at
   *Calculate Build Version*, and the generator logs this.

## Security

The three fields are user-supplied and become checkout rules and parameter values, i.e. paths on
the build agent. The registry is the trust boundary (`VcsPlacementValidator`):

- Checkout Directory matches `^[A-Za-z0-9_][A-Za-z0-9._-]*$` (one segment, no leading dot, no `/`).
- Source Path and Build Working Directory are relative and `/`-separated, each segment matching
  `^[A-Za-z0-9._-]+$` and none `.` or `..`.
- Each is at most 255 characters.
- Reserved Checkout Directories: `report-templates`, `sonar-config`, `target`, `sonar-report`,
  compared ignoring case.

So no field can escape the checkout directory, be absolute, or inject rule or parameter syntax
(`=>`, `+:`, `%`, `;`, whitespace). A rejected write returns 400 naming the field
(`vcsEntries[<i>].checkoutDirectory`, `vcsEntries[<i>].sourcePath`, `buildWorkingDirectory`,
prefixed with `fieldOverrides[<j>].` for a per-range row).

## Alternatives considered

- **The first root always at the checkout root**, a "primary" root whose Source Path is the Build
  Working Directory. Implemented on QA, then rejected: any root may be placed in a subfolder, and
  real chains put a later registry entry at the checkout root.
- **Guess placement from registry order.** Wrong for those chains; kept only as the `V8__`
  fallback.
- **Raw checkout-rule strings in the registry.** Puts TeamCity syntax into the registry, cannot be
  validated or reused by the escrow export, and invites rename rules agent-side checkout rejects.
- **Checkout Directory as a rename.** Unsupported by agent-side checkout.
- **Placement only in TeamCity, maintained by hand.** The status quo that drifted.
- **The generator overriding the inherited *Calculate Build Version* step.** Rejected: standard
  steps stay as the templates define them.
- **Templates derive the version-format file from `WORK_DIR`.** Changes behaviour for the ~96
  configurations that read it from the checkout root.
- **Free-form `name` with a uniqueness rule.** A changed Checkout Directory would leave a stale
  name another root could collide with.

## Consequences

- Positive: generated chains match the registry for multi-root and shared-repository components,
  in the layouts existing chains actually use.
- Positive: a root that stays at the checkout root keeps its name, so the escrow layout changes
  only when a root moves into a Checkout Directory.
- Negative: `WORK_DIR` and `COMPONENT_CONFIG_DIR` set per configuration no longer follow a later
  change of the template default.
- Negative: a root added after the chain was created is not attached, therefore not checked out,
  tagged or analysed, until the chain is recreated. The registry warns.
- Negative: a client that sends a second root without a Checkout Directory is rejected (400) — the
  Portal must expose the field before the registry rules reach the same environment.
- Residual: a Checkout Directory can clash with a directory of the repository at the checkout
  root. The registry cannot see repository content.
- Carried risk: a root the chain does not attach is left untagged, without a warning, by the
  release tooling.
