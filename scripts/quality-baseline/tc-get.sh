#!/usr/bin/env bash
#
# Read-only TeamCity REST helper for the quality-baseline measurements
# (docs/registry/quality-baseline.md §7).
#
# Its only job is to keep the credential inside ONE process. The personal token lives in the macOS
# Keychain; a recipe that tells you to print it and paste it into a curl command copies the secret into
# your terminal scrollback, your shell history, an agent's tool output, and any CI log that captures it.
# Here the token is read, handed to curl through a config file on stdin (so it never appears in argv or
# in `ps` output), and discarded when the process exits. It is never printed.
#
# Usage:
#   TC_URL=<teamcity-base-url> scripts/quality-baseline/tc-get.sh "app/rest/server?fields=version"
#
# Pass TC_URL inline on every call. Do not rely on `export`: the documented recipe runs each command as its
# own invocation, and in an agent session every invocation is a fresh shell, so an earlier export is gone.
#
# TC_URL is intentionally not baked in: the host is internal and does not belong in this repository.
# Take it from the team's CI bookmark.
#
# Requires: macOS `security` with the personal access token stored as a generic password (TeamCity guest
# auth is disabled). The service name defaults to `teamcity-token` and can be overridden with
# TC_TOKEN_SERVICE — a hard-coded name is a trap worth avoiding, because a stale one fails exactly like a
# revoked token, and the misdiagnosis costs more than the lookup.
#
# Reading the two failures this can hand you, since they get conflated:
#   * `curl: (22) ... error: 401` plus TeamCity's own body — the host answered and rejected the token.
#     Suspect the wrong Keychain entry before concluding the token was revoked.
#   * `curl: (6) Could not resolve host` — the name did not resolve: VPN down, DNS down, or a wrong host in
#     TC_URL. Exit 6 alone does not tell you which.
# This script prints no status code of its own (no --write-out), so a bare `000` is not something you will
# see here — that belongs to hand-rolled curl invocations.

set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "usage: TC_URL=<base-url> $0 <rest-path>" >&2
    echo "example: $0 \"app/rest/builds?locator=buildType:<ID>,branch:main,count:3\"" >&2
    exit 2
fi

if [ -z "${TC_URL:-}" ]; then
    echo "TC_URL is not set — pass it inline: TC_URL=<base-url> $0 <rest-path> (see the team's CI bookmark)." >&2
    exit 2
fi

rest_path=${1#/}
base_url=${TC_URL%/}

# Two names are tried, not one. `teamcity-token` is what current entries use, but every version of this
# script until now told people to create `tc-rest-token`, so that is what a colleague who followed the
# instructions actually has. Defaulting to a single name would fail for them with the same misleading
# "no entry" message this change exists to remove — it would just move the trap rather than close it.
# TC_TOKEN_SERVICE still overrides both, and then only that name is tried.
token_services=${TC_TOKEN_SERVICE:-"teamcity-token tc-rest-token"}

# Turn xtrace OFF for the rest of this script, and restore it afterwards only if it was on.
# `bash -x tc-get.sh` is the first thing anyone reaches for when TeamCity access misbehaves — and
# tracing echoes EXPANDED arguments, so both the assignment below and the printf that builds the
# Authorization header would print the token in clear text to stderr, into scrollback or a captured
# log. Everything this script does to keep the secret out of argv is undone by that one flag. A
# comment warning against it would not survive contact with someone debugging at speed, so the
# script disables tracing itself rather than asking. The diagnostics worth seeing — which service
# names were tried, what `security` said, what curl said — are printed explicitly and are unaffected.
case $- in
    *x*) __tc_xtrace_was_on=1; set +x ;;
    *)   __tc_xtrace_was_on=0 ;;
esac

# `security` exits non-zero for a missing entry, a locked keychain and a denied access prompt alike, so
# its own message is the only thing that distinguishes them — never assert which it was. Suppressed while
# probing candidates, because "not found" is expected for all but one; the last failure is re-run
# unsuppressed below so its diagnostic reaches the operator. The secret is only ever on stdout.
token=""
for token_service in $token_services; do
    if token=$(security find-generic-password -s "$token_service" -w 2>/dev/null); then
        break
    fi
    token=""
done

if [ -z "$token" ]; then
    for token_service in $token_services; do
        security find-generic-password -s "$token_service" -w >/dev/null || true
    done
    echo "could not read the TeamCity token from any of these Keychain services: $token_services (missing entry, locked keychain, or access denied — see the messages above)." >&2
    printf 'create one:  security add-generic-password -s %s -a "$USER" -w\n' "${token_services%% *}" >&2
    printf 'or point at an existing entry:  TC_URL=%s TC_TOKEN_SERVICE=<service> %s %s\n' "${TC_URL}" "$0" "$rest_path" >&2
    exit 3
fi

# `--config -` reads options from stdin, keeping the Authorization header out of the command line.
# `--fail-with-body` makes an HTTP error a non-zero exit while still showing TeamCity's error payload.
printf 'header = "Authorization: Bearer %s"\n' "$token" |
    curl --config - \
        --silent --show-error --fail-with-body \
        --header 'Accept: application/json' \
        "$base_url/$rest_path"
__tc_rc=$?

# Restore tracing for whatever called us, but only if it was on to begin with.
[ "$__tc_xtrace_was_on" = 1 ] && set -x
exit $__tc_rc
