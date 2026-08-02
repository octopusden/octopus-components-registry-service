#!/usr/bin/env bash
# Tests for spec-gate.sh. Builds throwaway git repos per case so the commit
# ordering the gate inspects is real history, not a mock.
#
# Run: bash .github/scripts/spec-gate.test.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/spec-gate.sh"

PASS=0
FAIL=0

# Keep these in exact parity with the spec-gate job env in each repo's
# merge-gate.yml. They are duplicated there because the script is configured
# by environment; this suite is what stops the two copies drifting.
PORTAL_BEHAVIOR_RE='^(src/main/(kotlin|resources)/|frontend/(src/|vite\.config\.ts$|index\.html$))'
PORTAL_BEHAVIOR_EXCLUDE_RE='(\.test\.(ts|tsx)$|^frontend/src/lib/api/(schema\.d\.ts|v4\.json)$|^frontend/src/test(-fixtures)?/)'
PORTAL_SPEC_RE='^(docs/features/|docs/architecture\.md$|docs/adr/|openspec/)'

CRS_BEHAVIOR_RE='^([^/]+/src/main/(kotlin|java|resources)/|components-registry-automation/data/)'
CRS_BEHAVIOR_EXCLUDE_RE='(/(application|bootstrap)-(test|ft-db)\.yml$|/openapi/v4\.json$)'
CRS_SPEC_RE='^(docs/registry/(prd|technical-design|api-changelog)\.md$|docs/registry/.*-spec\.md$|docs/registry/requirements-.*\.md$|docs/registry/adr/|docs/features/|openspec/)'

# use_crs — switch the current case to the CRS-shaped configuration.
use_crs() {
  export BEHAVIOR_RE="$CRS_BEHAVIOR_RE"
  export BEHAVIOR_EXCLUDE_RE="$CRS_BEHAVIOR_EXCLUDE_RE"
  export SPEC_RE="$CRS_SPEC_RE"
}

export BASE_REF='main'

# run_case — temp repo with one commit on main, cwd moved into it, env reset
# to the Portal-shaped config.
run_case() {
  local dir
  dir="$(mktemp -d)"
  cd "$dir" || exit 1
  git init -q -b main .
  git config user.email t@example.com
  git config user.name test
  mkdir -p docs
  echo seed > docs/seed.md
  git add -A && git commit -qm seed
  git checkout -qb feature

  unset PR_BODY PR_LABELS
  export BEHAVIOR_RE="$PORTAL_BEHAVIOR_RE"
  export BEHAVIOR_EXCLUDE_RE="$PORTAL_BEHAVIOR_EXCLUDE_RE"
  export SPEC_RE="$PORTAL_SPEC_RE"
}

# commit <message> <path>... — write each path and commit them together.
commit() {
  local msg="$1"; shift
  local p
  for p in "$@"; do
    mkdir -p "$(dirname "$p")"
    echo "change $RANDOM" >> "$p"
  done
  git add -A && git commit -qm "$msg"
}

# expect <expected-rc> <case name>
expect() {
  local want="$1" name="$2" out rc
  out="$("$GATE" 2>&1)"; rc=$?
  if [[ "$rc" == "$want" ]]; then
    PASS=$((PASS + 1))
    printf 'ok   %s\n' "$name"
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL %s (want rc=%s, got rc=%s)\n' "$name" "$want" "$rc"
    printf '%s\n' "$out" | sed 's/^/     | /'
  fi
}

# 1. Docs-only PR: nothing to gate.
run_case
commit "docs" docs/features/foo.md
expect 0 "docs-only PR passes"

# 2. The happy path the rule exists for: spec lands, then implementation.
run_case
commit "spec" docs/features/foo.md
commit "impl" frontend/src/pages/Foo.tsx
expect 0 "spec commit before code commit passes"

# 3. Spec and code in one commit — written together, not agreed first.
run_case
commit "both" docs/features/foo.md frontend/src/pages/Foo.tsx
expect 1 "spec and code in the same commit fails spec-first"

# 4. The failure mode we are actually chasing: spec written at the end.
run_case
commit "impl" frontend/src/pages/Foo.tsx
commit "spec" docs/features/foo.md
expect 1 "code commit before spec commit fails spec-first"

# 5. No spec at all.
run_case
commit "impl" frontend/src/pages/Foo.tsx
expect 1 "behaviour change with no spec delta fails spec-delta"

# 6. The label opt-out.
run_case
commit "impl" frontend/src/pages/Foo.tsx
export PR_LABELS='["dependencies","no-spec-impact"]'
expect 0 "the no-spec-impact label skips both gates"

# 7. The PR body is not an opt-out channel at all. A ticked box in the body was
# the old escape hatch and is deliberately inert now — deciding whether one is
# "really ticked" meant parsing markdown, which leaked seven false opt-outs.
run_case
commit "impl" frontend/src/pages/Foo.tsx
export PR_BODY=$'- [x] no-spec-impact\n'
expect 1 "a ticked box in the PR body does not opt out"

# 8. Generated files are not behaviour.
run_case
commit "regen" frontend/src/lib/api/schema.d.ts frontend/src/lib/api/v4.json
expect 0 "generated api types alone are not a behaviour change"

# 9. Tests are not behaviour.
run_case
commit "tests" frontend/src/pages/Foo.test.tsx
expect 0 "test-only change is not a behaviour change"

# 10. Backend behaviour is gated too.
run_case
commit "impl" src/main/kotlin/Foo.kt
expect 1 "backend behaviour change with no spec delta fails"

# 11. Merge commits from the base branch must not be read as code commits.
run_case
commit "spec" docs/features/foo.md
git checkout -q main
commit "base moves" docs/other.md
git checkout -q feature
git merge -q --no-edit main
commit "impl" frontend/src/pages/Foo.tsx
expect 0 "merge commit in range does not break spec-first"

# 11b. BFF config is behaviour: gateway routes, CSRF and security live in
# src/main/resources, not in Kotlin.
run_case
commit "route change" src/main/resources/application.yaml
expect 1 "portal BFF configuration is gated"

# 12. CRS-shaped config: module-prefixed source roots.
run_case
use_crs
commit "impl" components-registry-service-server/src/main/kotlin/Foo.kt
expect 1 "CRS module source root is gated"

# 13. Same CRS config, spec-first satisfied by a requirements doc.
run_case
use_crs
commit "spec" docs/registry/requirements-common.md
commit "impl" components-registry-service-server/src/main/kotlin/Foo.kt
expect 0 "CRS requirements doc before code passes"

# 14. CRS has Java modules alongside Kotlin ones.
run_case
use_crs
commit "impl" components-registry-api/src/main/java/Foo.java
expect 1 "CRS java module source root is gated"

# 15. A Flyway migration changes observable schema, so it is behaviour.
run_case
use_crs
commit "migration" components-registry-service-server/src/main/resources/db/migration/V99__foo.sql
expect 1 "CRS flyway migration is gated"

# 16. Service configuration is behaviour: the role-to-permission mapping that
# decides who may edit what lives in application.yml, not in Kotlin.
run_case
use_crs
commit "permissions" components-registry-service-server/src/main/resources/application.yml
expect 1 "CRS service configuration is gated"

# 17. CRS spec-first satisfied by an ADR.
run_case
use_crs
commit "adr" docs/registry/adr/020-foo.md
commit "impl" components-registry-service-server/src/main/kotlin/Foo.kt
expect 0 "CRS ADR before code passes"

# 18. The SPA build config ships real decisions — the production base path and
# the deliberate absence of sourcemaps (dist/assets is served permitAll).
run_case
commit "build config" frontend/vite.config.ts
expect 1 "portal vite config is gated"

# 19. index.html ships on every page load.
run_case
commit "shell" frontend/index.html
expect 1 "portal index.html is gated"

# 20. Test scaffolding under frontend/src is not behaviour. Without this the
# gate demands a spec for editing a contract fixture.
run_case
commit "fixtures" frontend/src/test-fixtures/portal-info.contract.json frontend/src/test/setup.ts
expect 0 "portal test fixtures and setup are not behaviour"

# 21. CRS test-profile configuration lives in src/main/resources but only
# configures the test and functional-test profiles.
run_case
use_crs
commit "test profile" components-registry-service-server/src/main/resources/application-test.yml
expect 0 "CRS test-profile config is not behaviour"

run_case
use_crs
commit "ft profile" components-registry-service-server/src/main/resources/bootstrap-ft-db.yml
expect 0 "CRS functional-test bootstrap is not behaviour"

# 22. The server's OpenAPI document is generated from the code that is already
# gated; a springdoc-driven reformat on its own is not a behaviour change.
# Mirrors the Portal's exclusion of its vendored copy.
run_case
use_crs
commit "regen spec" components-registry-service-server/src/main/resources/openapi/v4.json
expect 0 "CRS generated openapi document is not behaviour on its own"

# 23. api-changelog.md is the document CRS tells contributors to update on a v4
# surface change; it must satisfy spec-delta.
run_case
use_crs
commit "changelog" docs/registry/api-changelog.md
commit "impl" components-registry-service-server/src/main/kotlin/Foo.kt
expect 0 "CRS api-changelog satisfies the spec gate"

# --- the automation module ---------------------------------------------------
# Still deployed. Its runtime configuration sits outside src/main, so the
# module-source rule alone never sees it.

# 24a. Runtime config: supported groupIds and systems, version-name and
# product-type mappings — all observable behaviour.
run_case
use_crs
commit "supported systems" components-registry-automation/data/components-registry-service.yaml
expect 1 "automation runtime config is gated"

# 24b. The OKD template describes an ephemeral stand (a postgres pod with a
# deadline), not service behaviour — same class as the Portal's infra/dev.
run_case
use_crs
commit "stand template" components-registry-automation/okd/components-registry.yaml
expect 0 "automation OKD template is not behaviour"

# 24c. TeamCity metarunners are build steps.
run_case
use_crs
commit "build step" components-registry-automation/metarunners/OctopusComponentsRegistryAutomation.xml
expect 0 "automation metarunners are not behaviour"

# --- escape-hatch abuse ------------------------------------------------------
# The label is the only thing that can switch the gate off, so anything that is
# not that exact label must fail to trigger it.

# 25. A namespaced label merely ends in the token; it is a different label.
run_case
commit "impl" frontend/src/pages/Foo.tsx
export PR_LABELS='["area:no-spec-impact","bug"]'
expect 1 "a namespaced label does not opt out"

# 26. One label whose name contains commas is one label, not three.
run_case
commit "impl" frontend/src/pages/Foo.tsx
export PR_LABELS='["foo,no-spec-impact,bar"]'
expect 1 "a label containing the token between commas does not opt out"

# 27. Non-string noise in the array must not opt out on its own.
run_case
commit "impl" frontend/src/pages/Foo.tsx
export PR_LABELS='[null,7,{"name":"no-spec-impact"}]'
expect 1 "non-string label entries do not opt out"

# 27a. A large label array must not make the gate spin. The obvious blank-check
# on the raw value is quadratic in bash 3.2 and cost 15 seconds at 800 labels;
# GitHub allows up to 100 per PR, so this stays well inside a realistic bound.
run_case
commit "impl" frontend/src/pages/Foo.tsx
export PR_LABELS="$(awk 'BEGIN { printf "["; for (i = 0; i < 400; i++) printf "\"label-%d\",", i; printf "\"no-spec-impact\"]" }')"
started=$(date +%s)
expect 0 "a large label array opts out without spinning"
if [[ $(( $(date +%s) - started )) -gt 5 ]]; then
  FAIL=$((FAIL + 1))
  printf 'FAIL a large label array took over 5s\n'
fi

# 28. The marker as a label in its own right still does, alongside noise.
run_case
commit "impl" frontend/src/pages/Foo.tsx
export PR_LABELS='[null,"bug","NO-SPEC-IMPACT"]'
expect 0 "the exact label opts out regardless of case or neighbours"

# --- ordering loopholes ------------------------------------------------------

# 31. Behaviour smuggled into a conflict resolution. The file exists nowhere
# else on the branch, so skipping merge commits would hide it and pass a PR
# whose spec was written afterwards.
run_case
commit "branch point" docs/unrelated.md
git checkout -q main
commit "main moves" docs/mainside.md
git checkout -q feature
git merge -q --no-ff --no-commit main >/dev/null 2>&1
mkdir -p frontend/src/pages
echo "smuggled" > frontend/src/pages/Bar.tsx
git add -A
git commit -qm "Merge main into feature"
commit "spec afterwards" docs/features/bar.md
expect 1 "behaviour inside a merge commit is not invisible to spec-first"

# seed_adr_on_base — the spec must pre-exist on the base branch, otherwise the
# "rename" reads as an addition in the three-dot diff and proves nothing.
seed_adr_on_base() {
  git checkout -q main
  commit "seed adr" docs/adr/001-x.md
  git branch -qf feature main
  git checkout -q feature
}

# 32. Renaming an unrelated spec file is not writing a spec.
run_case
seed_adr_on_base
git mv docs/adr/001-x.md docs/adr/002-x.md
git commit -qm "renumber adr"
commit "impl" frontend/src/pages/Foo.tsx
expect 1 "a pure rename of a spec does not satisfy spec-delta"

# 33. A rename that also edits the file is a real spec change.
run_case
seed_adr_on_base
git mv docs/adr/001-x.md docs/adr/002-x.md
echo "new requirement" >> docs/adr/002-x.md
git add -A
git commit -qm "renumber and revise adr"
commit "impl" frontend/src/pages/Foo.tsx
expect 0 "a rename with real edits satisfies spec-delta"

# 34. Moving a spec into openspec/ while editing it is a spec change. Git
# compacts the rename to "{docs/adr => openspec}/001-x.md", which anchors on
# neither path — this is the shape the OpenSpec migration will produce.
run_case
seed_adr_on_base
mkdir -p openspec
git mv docs/adr/001-x.md openspec/001-x.md
echo "new requirement" >> openspec/001-x.md
git add -A
git commit -qm "move adr into openspec and revise"
commit "impl" frontend/src/pages/Foo.tsx
expect 0 "a cross-directory spec move with edits satisfies spec-delta"

# 35. A binary spec asset is a real change; numstat reports "-" for its counts
# and must not be read as zero.
run_case
git checkout -q main
mkdir -p docs/adr
printf 'seed\000binary' > docs/adr/diagram.png
git add -A
git commit -qm "seed diagram"
git branch -qf feature main
git checkout -q feature
printf 'changed\000binary\000content' > docs/adr/diagram.png
git add -A
git commit -qm "revise diagram"
commit "impl" frontend/src/pages/Foo.tsx
expect 0 "a binary spec asset counts as a spec change"

# 36. A path is not a rename just because it contains git's arrow notation.
# A directory may legitimately be named with " => " in it; rewriting such a path
# would forge a spec the PR never touched, passing both checks with no spec at
# all anywhere in the repository.
run_case
mkdir -p "misc/results => docs/features"
echo decoy > "misc/results => docs/features/backup.md"
git add -A
git commit -qm "add a file under an oddly named directory"
commit "impl" frontend/src/pages/Foo.tsx
expect 1 "a path containing the rename arrow is not treated as a spec"

# 24. Parity with the workflow. The gate is configured by environment, so the
# regexes above exist twice: here and in merge-gate.yml. Nothing else would
# notice them drifting apart — a widened path in one copy and not the other
# means the suite proves a gate the CI does not actually run.
cd "$SCRIPT_DIR/../.." || exit 1
WORKFLOW='.github/workflows/merge-gate.yml'
yaml_env() { sed -n "s/^ *$1: '\(.*\)'\$/\1/p" "$WORKFLOW"; }

wf_behavior=$(yaml_env BEHAVIOR_RE)
wf_exclude=$(yaml_env BEHAVIOR_EXCLUDE_RE)
wf_spec=$(yaml_env SPEC_RE)

if [[ "$wf_behavior" == "$PORTAL_BEHAVIOR_RE" ]]; then
  want_exclude="$PORTAL_BEHAVIOR_EXCLUDE_RE"; want_spec="$PORTAL_SPEC_RE"; flavour=portal
elif [[ "$wf_behavior" == "$CRS_BEHAVIOR_RE" ]]; then
  want_exclude="$CRS_BEHAVIOR_EXCLUDE_RE"; want_spec="$CRS_SPEC_RE"; flavour=crs
else
  want_exclude=''; want_spec=''; flavour=''
fi

if [[ -z "$flavour" ]]; then
  FAIL=$((FAIL + 1))
  printf 'FAIL workflow BEHAVIOR_RE matches neither constant\n     | workflow: %s\n' "$wf_behavior"
elif [[ "$wf_exclude" != "$want_exclude" || "$wf_spec" != "$want_spec" ]]; then
  FAIL=$((FAIL + 1))
  printf 'FAIL workflow (%s) has drifted from the constants in this suite\n' "$flavour"
  [[ "$wf_exclude" == "$want_exclude" ]] || printf '     | BEHAVIOR_EXCLUDE_RE\n     |   workflow: %s\n     |   suite:    %s\n' "$wf_exclude" "$want_exclude"
  [[ "$wf_spec" == "$want_spec" ]] || printf '     | SPEC_RE\n     |   workflow: %s\n     |   suite:    %s\n' "$wf_spec" "$want_spec"
else
  PASS=$((PASS + 1))
  printf 'ok   workflow (%s) config matches this suite\n' "$flavour"
fi

printf '\n%s passed, %s failed\n' "$PASS" "$FAIL"
[[ "$FAIL" == 0 ]]
