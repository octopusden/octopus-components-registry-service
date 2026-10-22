#!/bin/bash
# Self-test for check-migrations-append-only.sh: each case builds a throwaway
# repository with a base commit and a commit under test.
set -euo pipefail
exec </dev/null

CHECK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-migrations-append-only.sh"
DIR=components-registry-service-server/src/main/resources/db/migration
work=$(mktemp -d -t b1b-migrations)
trap 'rm -rf "$work"' EXIT
failures=0

git_() { git -c user.name=t -c user.email=t@t -c commit.gpgsign=false "$@"; }

# A fresh repository whose base commit holds V1, V2 and sub/V3; cds into it.
new_repo() {
  rm -rf "$work/repo" && mkdir -p "$work/repo/$DIR" && cd "$work/repo"
  git_ init -q -b main
  for v in 1 2; do echo "select $v;" >"$DIR/V${v}__step_$v.sql"; done
  mkdir -p "$DIR/sub" && echo "select 3;" >"$DIR/sub/V3__step_3.sql"
  echo "notes" >"$DIR/README.md"
  git_ add -A && git_ commit -qm base
}

commit() { git_ add -A && git_ commit -qm change && git rev-parse HEAD; }

# expect <pass|fail> <name> <base> <checked>
expect() {
  if (cd "$work/repo" && "$CHECK" "$3" "$4") >/dev/null 2>&1; then got=pass; else got=fail; fi
  if [ "$got" = "$1" ]; then echo "ok   $2"; else echo "FAIL $2 (expected $1, got $got)"; failures=$((failures + 1)); fi
}

# Each case: start from the base, change the tree, check base..change.
case_() {
  local want=$1 name=$2; shift 2
  new_repo
  local base; base=$(git rev-parse HEAD)
  "$@"
  expect "$want" "$name" "$base" "$(commit)"
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
add_low()     { echo "select 3;" >"$DIR/V3__again.sql"; }
add_same()    { echo "a" >"$DIR/V4__a.sql"; mkdir -p "$DIR/sub" && echo "b" >"$DIR/sub/V4__b.sql"; }
readme_only() { echo "more" >>"$DIR/README.md"; }

case_ fail "modified migration"                 modify
case_ fail "deleted migration"                  delete
case_ fail "renamed within the directory"       rename_in
case_ fail "moved out of the directory"         move_out
case_ fail "edited in a subdirectory"           subdir_edit
case_ fail "moved into a subdirectory"          into_sub
case_ fail "added with a leading zero"          add_zero
case_ fail "added with a non-integer version"   add_dotted
case_ fail "added with an uppercase description" add_upper
case_ fail "added at or below the base maximum" add_low
case_ fail "two added with the same version"    add_same
case_ pass "added next version"                 add_ok
case_ pass "added next version in a subdirectory" add_sub_ok
case_ pass "non-migration file in the directory" readme_only

# A version that landed on main twice is caught even when the PR adds nothing.
new_repo
echo "dup" >"$DIR/V2__duplicate.sql"
dup_base=$(commit)
echo "x" >README.md
expect fail "duplicate already on the base" "$dup_base" "$(commit)"

# Stale branch: main gains V4 after the branch point. Against the merge commit
# the PR adds nothing and passes; against the branch head V4 looks deleted.
new_repo
git_ switch -qc feature && echo "x" >README.md && head=$(commit)
git_ switch -q main && echo "select 4;" >"$DIR/V4__on_main.sql" && main_tip=$(commit)
git_ merge -q --no-edit feature && merge=$(git rev-parse HEAD)
expect pass "stale branch, checked at the merge commit" "$main_tip" "$merge"
expect fail "stale branch, checked at the branch head"  "$main_tip" "$head"

# Main gains V5 after the branch point and the PR adds V4: out of order once
# merged. Caught against the merge commit's first parent, which is why the job
# uses it rather than the branch point.
new_repo
branch_point=$(git rev-parse HEAD)
git_ switch -qc feature && echo "select 4;" >"$DIR/V4__on_branch.sql" && commit >/dev/null
git_ switch -q main && echo "select 5;" >"$DIR/V5__on_main.sql" && commit >/dev/null
git_ merge -q --no-edit feature && merge=$(git rev-parse HEAD)
expect fail "below a version main added since, checked at merge^1" "$merge^1" "$merge"
expect pass "below a version main added since, checked at the branch point" "$branch_point" "$merge"

new_repo
expect fail "missing base commit"    0000000000000000000000000000000000000000 "$(git rev-parse HEAD)"
expect fail "empty checked commit"   "$(git rev-parse HEAD)" ""

[ "$failures" -eq 0 ]
