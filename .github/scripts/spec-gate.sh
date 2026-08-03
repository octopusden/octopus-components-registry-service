#!/usr/bin/env bash
# spec-gate.sh — enforces that a behaviour change carries a spec, and that the
# spec was agreed *before* the code was written.
#
# Two independent checks, both scoped to the commits this PR adds:
#
#   spec-delta  A PR that changes behaviour must also change a spec document.
#               Catches the drift where code moves and the doc stays behind.
#
#   spec-first  The first commit touching a spec must come strictly before the
#               first commit touching behaviour. Catches the weaker failure
#               where the spec is present but was written afterwards to
#               describe whatever got built — a record, not an agreement.
#
# Both are skipped when the PR carries the no-spec-impact label.
#
# Configuration is entirely by environment so the script stays byte-identical
# across repositories; only the workflow that calls it differs.
#
#   BASE_REF              base to diff against, e.g. origin/main   (required)
#   BEHAVIOR_RE           ERE selecting behaviour-change paths     (required)
#   BEHAVIOR_EXCLUDE_RE   ERE subtracted from the above            (optional)
#   SPEC_RE               ERE selecting spec paths                 (required)
#   PR_LABELS             pull request labels, a JSON array        (optional)
#   ESCAPE                escape marker, default no-spec-impact    (optional)
#
# Requires git, awk and jq. All three are preinstalled on GitHub-hosted runners.
# If jq is absent the label opt-out simply never fires and the gate stays
# enforced, which is the safe direction to fail in.
set -uo pipefail

BASE_REF="${BASE_REF:?BASE_REF is required}"
BEHAVIOR_RE="${BEHAVIOR_RE:?BEHAVIOR_RE is required}"
SPEC_RE="${SPEC_RE:?SPEC_RE is required}"
BEHAVIOR_EXCLUDE_RE="${BEHAVIOR_EXCLUDE_RE:-}"
PR_LABELS="${PR_LABELS:-}"
ESCAPE="${ESCAPE:-no-spec-impact}"

# select <include-re> [exclude-re] — filter a newline list on stdin, dropping
# blanks so an empty result stays genuinely empty rather than one blank line.
select_paths() {
  local include="$1" exclude="${2:-}"
  grep -E "$include" | { if [[ -n "$exclude" ]]; then grep -vE "$exclude"; else cat; fi; } | grep -v '^$'
}

behaviour_paths() { select_paths "$BEHAVIOR_RE" "$BEHAVIOR_EXCLUDE_RE"; }
spec_paths() { select_paths "$SPEC_RE"; }

# die <check> <detail> — every failure exit goes through here, so a gate that
# cannot do its job reports that rather than falling through to success.
die() {
  printf 'spec-gate: FAILED (%s)\n\n%s\n' "$1" "$2" >&2
  exit 1
}

# --- escape hatch ------------------------------------------------------------
# The opt-out is a label, and only a label.
#
# It also used to read a ticked checkbox out of the PR body. That cost five
# review rounds and seven false opt-outs: prose merely mentioning the marker,
# unticked boxes, boxes inside code fences, boxes inside HTML comments, boxes in
# indented blocks, and finally a list-versus-code state machine that went stale
# across blank lines and fences. Each fix opened the next hole, because deciding
# whether a checkbox is "really ticked" means deciding how the body renders, and
# that is a markdown parser. This script is not going to be one.
#
# A label carries no such ambiguity. It is structured data, it is visible in the
# PR header and in list views, applying it is a deliberate act recorded in the
# timeline, and a reviewer can apply it rather than the author self-declaring.
# Switching the gate off should look like that.
#
# The marker must be a label in its own right: a word-boundary match would
# accept the namespaced "area:no-spec-impact", and splitting on punctuation
# would accept a single label whose name contains commas. Both are different
# labels that merely contain the token.
#
# If jq is missing, the input is empty, or the value is not an array, the
# comparison simply fails and the gate stays enforced — the safe direction. jq
# already exits non-zero on empty, blank and `[]` input, so no guard precedes
# it: the obvious `[[ -n "${PR_LABELS//[[:space:]]/}" ]]` is quadratic in bash
# 3.2, where 800 labels took 15 seconds of spinning CPU.
if printf '%s' "$PR_LABELS" | jq -e --arg e "$ESCAPE" '
    type == "array" and any(.[]; type == "string" and (ascii_downcase == ($e | ascii_downcase)))
  ' >/dev/null 2>&1; then
  echo "spec-gate: skipped — PR carries the ${ESCAPE} label."
  exit 0
fi

# --- what this PR changes ----------------------------------------------------
# Behaviour counts a path however it appears; a spec has to have really changed.
#
# Either way this is path-based, and path-based checking cannot tell whether the
# spec a PR touched has anything to do with the behaviour it changed. That last
# mile is the reviewer's job; the gate only guarantees there is something to
# review.
#
# Ask git for machine-readable status rather than parsing --numstat, whose
# rename notation ("docs/{a.md => b.md}", "{docs => openspec}/a.md") is display
# text: an ordinary path that merely contains " => " is indistinguishable from
# it, and rewriting such a path can forge a spec that was never touched. Here
# the rename destination is its own field, so nothing has to be inferred.
#
# A pure rename (R100 — identical content) is not a spec change; renumbering an
# unrelated ADR must not stand in for writing one. Any other status counts,
# including binary edits, which --numstat reports as "-" and which arithmetic
# would silently read as zero.
#
# Combined diffs (merge commits) use two-letter statuses such as "AA"; those
# fall through to the single-path branch, which is correct.
spec_candidate_paths() {
  awk -F'\t' '
    $1 == "R100"                  { next }
    $1 ~ /^R[0-9]+$/ && NF >= 3   { print $3; next }
    NF >= 2                       { print $2 }
  '
}

# git_paths — every path-listing call goes through here so that quoting is
# consistent and deliberate. `core.quotePath=false` stops git escaping
# non-ASCII, which it does by default: `docs/features/архитектура.md` would
# otherwise arrive quoted and be rejected below as unreadable. Control
# characters are still quoted, which is exactly the set we want to refuse.
git_paths() { git -c core.quotePath=false "$@"; }

# git C-quotes any path holding a control character, so it reaches us as
# "src/x\tY.tsx" — leading quote and all. Every path pattern in this gate is
# anchored, so such a path matches nothing and would slip through as "no
# behaviour change". Refuse to judge instead of judging wrongly.
reject_quoted_paths() {
  local paths="$1" context="$2" quoted
  quoted=$(printf '%s\n' "$paths" | grep '^"' || true)
  [[ -z "$quoted" ]] && return 0
  die "unreadable path" "$(
    printf 'git had to quote these %s paths, which means they contain control\n' "$context"
    printf 'characters. This gate matches paths by anchored pattern and cannot\n'
    printf 'classify a quoted path, so it will not pretend to have checked them:\n\n'
    printf '%s\n' "$quoted" | sed 's/^/  /'
    printf '\nRename them.\n'
  )"
}

# Every git invocation is checked. An unresolvable base or a failed diff yields
# an empty list, and an empty list reads as "nothing changed" — the gate would
# report success precisely when it knows least. Note the `|| die` has to sit in
# the parent shell: a die inside $( ) would only exit the subshell.
git rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null ||
  die "configuration" "BASE_REF (${BASE_REF}) does not resolve to a commit.
The workflow must fetch the base branch before running this gate."

changed=$(git_paths diff --name-only "${BASE_REF}...HEAD") ||
  die "git" "git diff against ${BASE_REF} failed; refusing to rule on an incomplete diff."
name_status=$(git_paths diff --name-status -M "${BASE_REF}...HEAD") ||
  die "git" "git diff --name-status against ${BASE_REF} failed; refusing to rule on an incomplete diff."

reject_quoted_paths "$changed" "changed"

changed_behaviour=$(printf '%s\n' "$changed" | behaviour_paths)
changed_spec=$(printf '%s\n' "$name_status" | spec_candidate_paths | spec_paths)

if [[ -z "$changed_behaviour" ]]; then
  echo "spec-gate: no behaviour change in this PR — nothing to gate."
  exit 0
fi

# --- check 1: spec-delta -----------------------------------------------------
if [[ -z "$changed_spec" ]]; then
  cat >&2 <<EOF
spec-gate: FAILED (spec-delta)

This PR changes behaviour but updates no specification:

$(printf '%s\n' "$changed_behaviour" | sed 's/^/  /')

Update the document that describes how this behaves, or mark the PR
${ESCAPE} if it genuinely changes no observable behaviour.
EOF
  exit 1
fi

# --- check 2: spec-first -----------------------------------------------------
first_spec=-1
first_behaviour=-1
idx=0
# Collect the revisions up front so a failure here is caught in the parent
# shell; inside the loop's process substitution it would be invisible.
revs=$(git rev-list --reverse "${BASE_REF}..HEAD") ||
  die "git" "git rev-list ${BASE_REF}..HEAD failed; refusing to rule on an incomplete history."

while read -r sha; do
  [[ -n "$sha" ]] || continue

  files=$(git_paths show --pretty=format: --name-only "$sha") ||
    die "git" "git show failed for ${sha}; refusing to rule on an incomplete history."
  commit_status=$(git_paths show --pretty=format: --name-status -M "$sha") ||
    die "git" "git show --name-status failed for ${sha}; refusing to rule on an incomplete history."
  reject_quoted_paths "$files" "committed"

  if [[ $first_behaviour -lt 0 ]] && [[ -n "$(printf '%s\n' "$files" | behaviour_paths)" ]]; then
    first_behaviour=$idx
    behaviour_sha=$sha
  fi
  if [[ $first_spec -lt 0 ]] &&
    [[ -n "$(printf '%s\n' "$commit_status" | spec_candidate_paths | spec_paths)" ]]; then
    first_spec=$idx
    spec_sha=$sha
  fi
  idx=$((idx + 1))
  # Merge commits are NOT skipped. For a clean merge `git show` reports no files
  # and the commit is inert here, but a conflict resolution can carry real edits
  # that exist nowhere else on the branch — skipping merges would make that code
  # invisible to the ordering check and hand back a false pass.
done <<EOF
$revs
EOF

if [[ $first_behaviour -ge 0 && ( $first_spec -lt 0 || $first_spec -ge $first_behaviour ) ]]; then
  if [[ $first_spec -eq $first_behaviour ]]; then
    reason="the spec and the implementation landed in the same commit ($(git log -1 --format='%h %s' "$behaviour_sha"))"
  else
    reason="the implementation ($(git log -1 --format='%h %s' "$behaviour_sha")) came before the spec${spec_sha:+ ($(git log -1 --format='%h %s' "${spec_sha}"))}"
  fi
  cat >&2 <<EOF
spec-gate: FAILED (spec-first)

The specification must be committed, and agreed, before the implementation —
but $reason.

Split the change: commit the spec on its own first, then the code. If you
squashed locally, re-split before pushing; GitHub's squash-on-merge is fine,
this gate reads the branch commits, not the merge result.
EOF
  exit 1
fi

echo "spec-gate: ok — spec committed before implementation."
