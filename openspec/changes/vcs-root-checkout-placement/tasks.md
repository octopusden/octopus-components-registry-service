## 1. Baseline

- [ ] 1.1 Characterization tests from `test/onb-001-baseline` merged into this branch, green

## 2. Implementation (test-first; each step starts with a failing test)

- [ ] 2.1 `V8__` migration + entity columns; migration test on Testcontainers (single- and
      multi-entry rows, primary left without Checkout Directory, a row whose secondary repeats the
      primary's name, a row with an invalid name)
- [ ] 2.2 DSL import back-fill for the secondary entries of multi-entry rows
- [ ] 2.3 v4 request/response fields and round-trip
- [ ] 2.4 Validation rules in `replaceVcsEntries` with `vcsEntries[<i>].<field>: ` errors (base and
      marker rows; `fieldOverrides[<j>].` prefix in a combined PATCH): primary without and
      secondaries with Checkout Directory, final names unique across all entries, fixed reserved
      names, segment regex
- [ ] 2.5 Derived name (secondary: Checkout Directory; primary: previous primary's name, else
      `main`), incl. 2→1, re-pointed and secondary promoted to primary
- [ ] 2.6 Chain-mismatch warning and `warnings` on the detail response
- [ ] 2.7 Groovy model + v2 DTO fields (appended, `@JvmOverloads`); v2 exposure; Groovy
      six-argument call test
- [ ] 2.8 DSL export omits fields; git-vs-db known difference
- [ ] 2.9 `api-changelog.md`, `functional-spec.md`, `schema-spec.md` updated; rollback repair
      runbook in `docs/registry/deployment/`
- [ ] 2.10 Local build (documented command; `docker*`, `oc*` and `:components-registry-automation:test` excluded, coverage gap stated in the report), `dbTest` and `:integrationTest` green
