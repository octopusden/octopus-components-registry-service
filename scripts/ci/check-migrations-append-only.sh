#!/bin/bash
# Fail when a PR changes an applied Flyway migration or adds one Flyway would
# reject or apply out of order.
#
# Production runs Flyway validate: a changed checksum or a missing applied
# version stops the service from starting. So between the PR base and the
# commit under test:
#   - an existing V*.sql under db/migration/ (any depth: Flyway scans
#     recursively) may not be modified, removed, renamed or moved out;
#   - an added one must be named V<n>__<description>.sql, with an integer
#     version of at most 18 digits and no leading zero;
#   - its version must be above every version in the base, since production
#     does not apply out-of-order migrations;
#   - no version may appear twice in the tree under test.
# Repeatable R__ and Java/Kotlin migrations are out of scope: there are none.
#
# Usage: check-migrations-append-only.sh <base-sha> <checked-sha>
#   Run inside the repository; any revision expression works. <checked-sha>
#   is the PR merge commit and <base-sha> its first parent: against the branch
#   head, a branch that predates a migration on main would look like it
#   deletes it.
set -euo pipefail
# Byte-wise [a-z]: a locale may otherwise let accented letters through.
export LC_ALL=C

DIR=components-registry-service-server/src/main/resources/db/migration
BASE=${1:-}
CHECKED=${2:-}
NAME='^V[1-9][0-9]{0,17}__[a-z0-9_]+\.sql$'

for sha in "$BASE" "$CHECKED"; do
  if [ -z "$sha" ] || ! git cat-file -e "$sha^{commit}" 2>/dev/null; then
    echo "::error::Commit '$sha' is missing or not fetched; cannot check migrations."
    exit 1
  fi
done
echo "base:    $BASE"
echo "checked: $CHECKED"

# NUL-separated plumbing output, so no path is quoted or split. Written to
# files because a variable cannot hold NUL and a failing git must stop here.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git diff-tree -r -z --no-renames --name-status "$BASE" "$CHECKED" -- "$DIR" >"$tmp/changes"
git ls-tree -r -z --name-only "$BASE" -- "$DIR" >"$tmp/base"
git ls-tree -r -z --name-only "$CHECKED" -- "$DIR" >"$tmp/checked"

# Lowercase v too: Flyway skips a v14__x.sql silently, so it must fail by name.
versioned() { [[ $1 == "$DIR"/* && ${1##*/} == [Vv]*.sql ]]; }
# Version of a V<digits>__ name, or nothing when it has none (or too many digits).
version_of() {
  local name=${1##*/}
  if [[ $name =~ ^V([0-9]{1,18})__ ]]; then echo $((10#${BASH_REMATCH[1]})); fi
}

errors=()
changed_existing=

max=0
while IFS= read -r -d '' path; do
  versioned "$path" || continue
  v=$(version_of "$path")
  if [ -n "$v" ] && [ "$v" -gt "$max" ]; then max=$v; fi
done <"$tmp/base"

while IFS= read -r -d '' status && IFS= read -r -d '' path; do
  versioned "$path" || continue
  if [ "$status" != "A" ]; then
    errors+=("existing migration changed ($status): $path")
    changed_existing=1
  elif [[ ! ${path##*/} =~ $NAME ]]; then
    errors+=("bad name, expected V<n>__<lowercase_description>.sql: $path")
  elif [ "$(version_of "$path")" -le "$max" ]; then
    errors+=("version $(version_of "$path") is not above V$max, the highest on the base: $path")
  fi
done <"$tmp/changes"

: >"$tmp/versions"
while IFS= read -r -d '' path; do
  versioned "$path" || continue
  v=$(version_of "$path")
  if [ -n "$v" ]; then printf '%s\t%s\n' "$v" "$path" >>"$tmp/versions"; fi
done <"$tmp/checked"
while IFS= read -r v; do
  errors+=("version $v appears more than once: $(awk -F'\t' -v v="$v" '$1 == v { printf "%s ", $2 }' "$tmp/versions")")
done < <(cut -f1 "$tmp/versions" | sort -n | uniq -d)

if [ ${#errors[@]} -eq 0 ]; then
  echo "ok — migrations are append-only, well named and in order."
  exit 0
fi

printf '%s\n' "${errors[@]}" >&2
echo >&2
if [ -n "$changed_existing" ]; then
  cat >&2 <<'MSG'
Applied migrations are immutable: production runs Flyway validate, and a changed,
removed or renamed version stops the service from starting. Revert the change and
add it as a new migration instead.
MSG
fi
echo "Next free version on the base: V$((max + 1))__<lowercase_description>.sql" >&2
echo "::error::Flyway migration check failed: ${errors[0]}"
exit 1
