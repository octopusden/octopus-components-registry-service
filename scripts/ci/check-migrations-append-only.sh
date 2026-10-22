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
#     version and no leading zero;
#   - its version must be above every version in the base, since production
#     does not apply out-of-order migrations;
#   - no version may appear twice in the tree under test.
# Repeatable R__ migrations are out of scope: there are none, and Flyway
# re-applies them on change by design.
#
# Usage: check-migrations-append-only.sh <base-sha> <checked-sha>
#   Run inside the repository; any revision expression works. <checked-sha>
#   is the PR merge commit and <base-sha> its first parent: against the branch
#   head, a branch that predates a migration on main would look like it
#   deletes it.
set -euo pipefail

DIR=components-registry-service-server/src/main/resources/db/migration
BASE=${1:-}
CHECKED=${2:-}

for sha in "$BASE" "$CHECKED"; do
  if [ -z "$sha" ] || ! git cat-file -e "$sha^{commit}" 2>/dev/null; then
    echo "::error::Commit '$sha' is missing or not fetched; cannot check migrations."
    exit 1
  fi
done
echo "base:    $BASE"
echo "checked: $CHECKED"

# Versioned migrations only: a V*.sql basename at any depth under $DIR.
versioned() { grep -E "^$DIR/(.*/)?V[^/]*\.sql$" || true; }
version_of() { local name=${1##*/}; name=${name#V}; echo $((10#${name%%__*})); }

errors=()

changes=$(git diff --no-renames --name-status "$BASE" "$CHECKED" -- "$DIR")
while IFS=$'\t' read -r status path; do
  [ -n "$path" ] || continue
  [ -n "$(versioned <<<"$path")" ] || continue
  if [ "$status" != "A" ]; then
    errors+=("existing migration changed ($status): $path")
  elif [[ ! ${path##*/} =~ ^V[1-9][0-9]*__[a-z0-9_]+\.sql$ ]]; then
    errors+=("bad name, expected V<n>__<lowercase_description>.sql: $path")
  fi
done <<<"$changes"

base_files=$(git ls-tree -r --name-only "$BASE" -- "$DIR")
checked_files=$(git ls-tree -r --name-only "$CHECKED" -- "$DIR")

max=0
while read -r path; do
  [[ ${path##*/} =~ ^V[0-9]+__ ]] || continue
  v=$(version_of "$path")
  if [ "$v" -gt "$max" ]; then max=$v; fi
done < <(versioned <<<"$base_files")

while IFS=$'\t' read -r status path; do
  [ "$status" = "A" ] || continue
  [[ ${path##*/} =~ ^V[1-9][0-9]*__[a-z0-9_]+\.sql$ ]] || continue
  v=$(version_of "$path")
  if [ "$v" -le "$max" ]; then errors+=("version $v is not above V$max, the highest on the base: $path"); fi
done <<<"$changes"

versions=$(while read -r path; do
  if [[ ${path##*/} =~ ^V[0-9]+__ ]]; then echo "$(version_of "$path") $path"; fi
done < <(versioned <<<"$checked_files"))
while read -r v; do
  [ -n "$v" ] || continue
  errors+=("version $v appears more than once: $(awk -v v="$v" '$1 == v { printf "%s ", $2 }' <<<"$versions")")
done < <(awk '{ print $1 }' <<<"$versions" | sort -n | uniq -d)

if [ ${#errors[@]} -eq 0 ]; then
  echo "ok — migrations are append-only, well named and in order."
  exit 0
fi

printf '%s\n' "${errors[@]}" >&2
cat >&2 <<'MSG'

Applied migrations are immutable: production runs Flyway validate, and a changed,
removed or renamed version stops the service from starting. Revert the change and
add it as the next V<n>__ migration instead.
MSG
echo "::error::Flyway migration check failed: ${errors[0]}"
exit 1
