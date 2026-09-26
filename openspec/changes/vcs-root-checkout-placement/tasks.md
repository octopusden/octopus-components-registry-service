## 1. Baseline

- [x] 1.1 Characterization tests from `test/onb-001-baseline` merged into this branch, green

## 2. Revision 2 parts that stand (implemented)

- [x] 2.1 `V8__` migration + entity columns; migration test on Testcontainers
- [x] 2.2 DSL import back-fill after the first entry of multi-entry rows
- [x] 2.3 v4 request/response entry fields and round-trip; blank = absent
- [x] 2.4 `vcsEntries[<i>].<field>: ` errors with the `fieldOverrides[<j>].` prefix in a combined
      PATCH; segment regex, fixed reserved names ignoring case, 255-character limit
- [x] 2.5 Chain-mismatch warning and `warnings` on the detail response
- [x] 2.6 Groovy model + `VersionControlSystemRootDTO` fields (appended; explicit six-parameter
      constructor with `@JsonCreator(mode = DISABLED)`); v2 exposure; Groovy six-argument call test
- [x] 2.7 DSL export omits the entry fields; git-vs-db and compat known differences

## 3. Revision 3 (test-first; each step starts with a failing test)

- [x] 3.1 Checkout Directory optional on every entry, single-entry rows included; at most one entry
      without it (second one → `vcsEntries[<i>].checkoutDirectory`); revision 2's "must be empty on
      the primary" and "required on a secondary" rules removed
- [x] 3.2 Name rule: Checkout Directory, else the previous name of the same repository, else
      `main`; unique ignoring case, reported on the later entry
- [x] 3.3 `V9__add_build_working_directory.sql` + entity column; migration test on Testcontainers
      (V8 and V9 from an empty database and from a V8 database)
- [x] 3.4 v4 `buildWorkingDirectory` on the base request/response and the `vcs.settings` marker
      payload (`rejectExtraneousMarkerFields`), blank = absent, round-trip
- [x] 3.5 Build Working Directory validation (shape, 255, inside a placed entry, required when
      every entry has a Checkout Directory), also when only it changes; `buildWorkingDirectory: ` errors, prefixed `fieldOverrides[<j>].` in a combined PATCH
- [x] 3.6 Groovy `VCSSettings` (incl. `equals`/`hashCode`/`toString`) +
      `VCSSettingsDTO.buildWorkingDirectory` (appended, property-level
      NON_NULL, explicit two-parameter constructor with `@JsonCreator(mode = DISABLED)`); DB mapper
      and v2 controller; v2 byte identity when unset; Groovy two-argument call test
- [x] 3.7 Chain-mismatch warning also on a base `buildWorkingDirectory`
- [x] 3.8 DSL export omits `buildWorkingDirectory`; git-vs-db known difference; compat known-delta
      widened to every root (`\[\d+\]`, typed comparator at any index) plus
      `buildWorkingDirectory`; `api-compat-deltas.md` VCS placement entry rewritten for revision 3
- [ ] 3.9 `api-changelog.md`, `functional-spec.md`, `schema-spec.md` updated; rollback runbook in
      `docs/registry/deployment/vcs-placement-rollback.md` rewritten for revision 3 (restore from
      snapshot by (configuration, repository, `sort_order`), not V8's rule); `V8__`'s SQL comment
      ("NULL on the primary entry") is immutable, so `schema-spec.md` and `functional-spec.md`
      carry the revision 3 wording; comment on PR #481 that it becomes `V10__`
- [ ] 3.10 Local build (documented command; `docker*`, `oc*` and
      `:components-registry-automation:test` excluded, coverage gap stated in the report),
      `dbTest` and `:integrationTest` green; QA redeploy
