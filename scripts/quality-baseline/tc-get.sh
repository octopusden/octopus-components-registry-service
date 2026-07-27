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
# TC_URL is intentionally not baked in: the host is internal and does not belong in this repository.
# Take it from the team's CI bookmark and export it in your shell.
#
# Requires: macOS `security` with a generic password stored under the service name `tc-rest-token`
# (personal access token; TeamCity guest auth is disabled).

set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "usage: TC_URL=<base-url> $0 <rest-path>" >&2
    echo "example: $0 \"app/rest/builds?locator=buildType:<ID>,branch:main,count:3\"" >&2
    exit 2
fi

if [ -z "${TC_URL:-}" ]; then
    echo "TC_URL is not set — export the TeamCity base URL (see the team's CI bookmark)." >&2
    exit 2
fi

rest_path=${1#/}
base_url=${TC_URL%/}

if ! token=$(security find-generic-password -s tc-rest-token -w 2>/dev/null); then
    echo "no Keychain entry for service 'tc-rest-token' — create one with your TeamCity token:" >&2
    echo "  security add-generic-password -s tc-rest-token -a \"\$USER\" -w" >&2
    exit 3
fi

# `--config -` reads options from stdin, keeping the Authorization header out of the command line.
# `--fail-with-body` makes an HTTP error a non-zero exit while still showing TeamCity's error payload.
printf 'header = "Authorization: Bearer %s"\n' "$token" |
    curl --config - \
        --silent --show-error --fail-with-body \
        --header 'Accept: application/json' \
        "$base_url/$rest_path"
