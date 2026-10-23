#!/bin/bash
# Self-test for check-migrations-append-only.sh: each case builds a throwaway
# repository with a base commit and a commit under test.
set -euo pipefail
# No user or system git config.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t

CHECK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-migrations-append-only.sh"
DIR=components-registry-service-server/src/main/resources/db/migration
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

# A fresh repository whose base commit holds V1, V2 and sub/V3; cds into it.
new_repo() {
  rm -rf "$work/repo" && mkdir -p "$work/repo/$DIR" && cd "$work/repo"
  git init -q -b main
  for v in 1 2; do echo "select $v;" >"$DIR/V${v}__step_$v.sql"; done
  mkdir -p "$DIR/sub" && echo "select 3;" >"$DIR/sub/V3__step_3.sql"
  echo "notes" >"$DIR/README.md"
  git add -A && git commit -qm base
}

commit() { git add -A && git commit -qm change && git rev-parse HEAD; }

# expect pass <name> <base> <checked>
# expect fail <name> <base> <checked> <text the failure must print>
expect() {
  local out got
  if out=$(cd "$work/repo" && "$CHECK" "$3" "$4" 2>&1); then got=pass; else got=fail; fi
  if [ "$got" != "$1" ]; then
    echo "FAIL $2 (expected $1, got $got)"; failures=$((failures + 1))
  elif [ "$1" = fail ] && ! grep -qF -- "$5" <<<"$out"; then
    echo "FAIL $2 (failed without '$5')"; failures=$((failures + 1))
  else
    echo "ok   $2"
  fi
}

# Each case: start from the base, change the tree, check base..change.
case_() {
  local want=$1 name=$2 change=$3 text=${4:-}
  new_repo
  local base; base=$(git rev-parse HEAD)
  "$change"
  expect "$want" "$name" "$base" "$(commit)" "$text"
}

modify()      { echo "-- edit" >>"$DIR/V2__step_2.sql"; }
delete()      { git rm -q "$DIR/V2__step_2.sql"; }
rename_in()   { git mv "$DIR/V2__step_2.sql" "$DIR/V2__renamed.sql"; }
move_out()    { git mv "$DIR/V2__step_2.sql" V2__step_2.sql; }
subdir_edit() { echo "-- edit" >>"$DIR/sub/V3__step_3.sql"; }
into_sub()    { git mv "$DIR/V2__step_2.sql" "$DIR/sub/V2__step_2.sql"; }
add_ok()      { echo "select 4;" >"$DIR/V4__add_x.sql"; echo "x" >README.md; }
add_sub_ok()  { echo "select 4;" >"$DIR/sub/V4__add_x.sql"; }
add_zero()    { echo "select 4;" >"$DIR/V04__add_x.sql"; }
add_dotted()  { echo "select 4;" >"$DIR/V4.1__add_x.sql"; }
add_upper()   { echo "select 4;" >"$DIR/V4__Add_X.sql"; }
add_accent()  { echo "select 4;" >"$DIR/V4__é.sql"; }
add_lower_v() { echo "select 4;" >"$DIR/v4__add_x.sql"; }
add_long()    { echo "select 4;" >"$DIR/V1234567890123456789__add_x.sql"; }
add_low()     { echo "select 3;" >"$DIR/V3__again.sql"; }
add_same()    { echo "a" >"$DIR/V4__a.sql"; mkdir -p "$DIR/sub" && echo "b" >"$DIR/sub/V4__b.sql"; }
readme_only() { echo "more" >>"$DIR/README.md"; }

changed="existing migration changed"
bad_name="unsupported migration name"
case_ fail "modified migration"                   modify      "$changed (M)"
case_ fail "deleted migration"                    delete      "$changed (D)"
case_ fail "renamed within the directory"         rename_in   "$changed (D)"
case_ fail "moved out of the directory"           move_out    "$changed (D)"
case_ fail "edited in a subdirectory"             subdir_edit "$changed (M)"
case_ fail "moved into a subdirectory"            into_sub    "$changed (D)"
case_ fail "added with a leading zero"            add_zero    "$bad_name"
case_ fail "added with a non-integer version"     add_dotted  "$bad_name"
case_ fail "added with an uppercase description"  add_upper   "$bad_name"
case_ fail "added with a non-ASCII description"   add_accent  "$bad_name"
case_ fail "added with a 19-digit version"        add_long    "$bad_name"
case_ fail "added with a lowercase v prefix"      add_lower_v "$bad_name"
case_ fail "added at or below the base maximum"   add_low     "is not above V3"
case_ fail "two added with the same version"      add_same    "version 4 appears more than once"
case_ pass "added next version"                   add_ok
case_ pass "added next version in a subdirectory" add_sub_ok
case_ pass "non-migration file in the directory"  readme_only

# A version that landed on main twice is caught even when the PR adds nothing.
new_repo
echo "dup" >"$DIR/V2__duplicate.sql"
dup_base=$(commit)
echo "x" >README.md
expect fail "duplicate already on the base" "$dup_base" "$(commit)" "version 2 appears more than once"

# A migration name the version rules cannot read fails even when it is
# already on the base.
new_repo
echo "x" >"$DIR/V12_1__x.sql"
odd_base=$(commit)
echo "x" >README.md
expect fail "unsupported name already on the base" "$odd_base" "$(commit)" "unsupported migration name"

# Next free version counts what the PR adds, not only the base.
new_repo
nf_base=$(git rev-parse HEAD)
echo "x" >"$DIR/V4__a.sql"
echo "x" >"$DIR/V3__low.sql"
expect fail "next free version counts added ones" "$nf_base" "$(commit)" "Next free version: V5__"

# Stale branch: main gains V4 after the branch point. Against the merge commit
# the PR adds nothing and passes; against the branch head V4 looks deleted.
new_repo
git switch -qc feature && echo "x" >README.md && head=$(commit)
git switch -q main && echo "select 4;" >"$DIR/V4__on_main.sql" && main_tip=$(commit)
git merge -q --no-edit feature && merge=$(git rev-parse HEAD)
expect pass "stale branch, checked at the merge commit" "$main_tip" "$merge"
expect fail "stale branch, checked at the branch head"  "$main_tip" "$head" "$changed (D)"

# Main gains V5 after the branch point and the PR adds V4: out of order once
# merged. Caught against the merge commit's first parent, which is why the job
# uses it rather than the branch point.
new_repo
branch_point=$(git rev-parse HEAD)
git switch -qc feature && echo "select 4;" >"$DIR/V4__on_branch.sql" && commit >/dev/null
git switch -q main && echo "select 5;" >"$DIR/V5__on_main.sql" && commit >/dev/null
git merge -q --no-edit feature && merge=$(git rev-parse HEAD)
expect fail "below a version main added since, checked at merge^1" "$merge^1" "$merge" "is not above V5"
expect pass "below a version main added since, checked at the branch point" "$branch_point" "$merge"

new_repo
expect fail "missing base commit"  0000000000000000000000000000000000000000 "$(git rev-parse HEAD)" "bad object"
expect fail "empty checked commit" "$(git rev-parse HEAD)" "" "checked sha"

[ "$failures" -eq 0 ]
