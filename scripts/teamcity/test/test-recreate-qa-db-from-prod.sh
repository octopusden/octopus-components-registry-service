#!/usr/bin/env bash
# Self-test for scripts/teamcity/recreate-qa-db-from-prod.sh against two throwaway PostgreSQL containers
# standing in for production (src) and QA (dst). Needs docker; touches no real database.
# The roles mirror the real setup: production is read by a role holding only pg_read_all_data, and QA
# is rewritten by a non-superuser that owns the schema and its extension and may CREATE in the database.
#
#   1. a copy replaces the stale target schema, extension included, and leaves the source unchanged;
#      the old target schema is kept as a gzipped dump, and the copied migration version is logged;
#   2. a target that is the source under the same host name is refused;
#   3. a target that is the source under ANOTHER host name is refused (cluster identity check);
#   4. a target host that mentions prod is refused;
#   5. a target table held by another session makes the copy roll back, leaving the target intact;
#   6. a production credential that can write is reported as a warning;
#   7. a target where objects outside the schema depend on it is refused, since CASCADE would drop
#      them and the backup covers the schema only;
#   8. a target table locked exclusively fails the run quickly (the QA backup dump does not wait).
#
# Usage:
#   bash scripts/teamcity/test/test-recreate-qa-db-from-prod.sh   → exit 0 on green
set -uo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE=${POSTGRES_IMAGE:-postgres:17}
NET=crs-qa-refresh-test-$$
# Under the repository rather than $TMPDIR: Docker Desktop/Colima share only the home directory with
# containers, and macOS keeps $TMPDIR under /var/folders.
mkdir -p "$SCRIPTS/../../build"
OUT=$(mktemp -d "$SCRIPTS/../../build/qa-refresh-test.XXXXXX")
fail=0

cleanup() { docker rm -f "$NET-src" "$NET-dst" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1; rm -rf "$OUT"; }
trap cleanup EXIT

docker network create "$NET" >/dev/null
for side in src dst; do
    docker run -d --name "$NET-$side" --network "$NET" -e POSTGRES_USER=crs -e POSTGRES_PASSWORD=pw \
        -e POSTGRES_DB=crs "$IMAGE" >/dev/null
done
for side in src dst; do
    until docker exec "$NET-$side" pg_isready -U crs -d crs -q 2>/dev/null; do sleep 1; done
    sleep 2 # the entrypoint restarts the server once after init
    until docker exec "$NET-$side" pg_isready -U crs -d crs -q 2>/dev/null; do sleep 1; done
done

sql() { docker exec "$NET-$1" psql -U crs -d crs -X -q -A -t -v ON_ERROR_STOP=1 -c "$2"; }
sql src 'create schema "components-registry"; create extension pgcrypto schema "components-registry";
         create table "components-registry".components(id uuid default "components-registry".gen_random_uuid(), seq bigserial, name text);
         insert into "components-registry".components(name) select g::text from generate_series(1, 5) g;
         select setval(pg_get_serial_sequence($$"components-registry".components$$, $$seq$$), 42);
         create table "components-registry".flyway_schema_history(installed_rank int, version text, success bool);
         insert into "components-registry".flyway_schema_history values (1, $$9$$, true), (2, $$10$$, true), (3, $$11$$, false);'
sql src 'create role ro login password $$pw$$; grant pg_read_all_data to ro;'
sql dst 'create role qa login password $$pw$$; grant create on database crs to qa;
         set role qa;
         create schema "components-registry"; create extension pgcrypto schema "components-registry";
         create table "components-registry".stale(id uuid default "components-registry".gen_random_uuid());'

# run <dst-host> [extra docker args...]; SRC_USER/DST_USER default to the least-privileged roles
run() {
    local host=$1; shift
    rm -f "$OUT/qa-before.sql.gz"
    docker run --rm --network "$NET" "$@" -v "$SCRIPTS:/s:ro" -v "$OUT:/out" -e QA_BACKUP_FILE=/out/qa-before.sql.gz \
        -e SRC_HOST="$NET-src" -e SRC_PORT=5432 -e SRC_DB=crs -e SRC_USER="${SRC_USER:-ro}" -e SRC_PASSWORD=pw \
        -e DST_HOST="$host" -e DST_PORT=5432 -e DST_DB=crs -e DST_USER="${DST_USER:-qa}" -e DST_PASSWORD=pw \
        -e LOCK_TIMEOUT=2s "$IMAGE" bash /s/recreate-qa-db-from-prod.sh >"$OUT/run.log" 2>&1
}
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; cat "$OUT/run.log"; fail=1; fi; }

run "$NET-dst"
check "copy succeeds" '[ $? -eq 0 ]'
check "a read-only production role raises no warning" '! grep -q "can write" "$OUT/run.log"'
check "target has the source rows" '[ "$(sql dst "select count(*) from \"components-registry\".components")" = 5 ]'
check "stale target table is gone" '[ "$(sql dst "select to_regclass(\$\$\"components-registry\".stale\$\$) is null")" = t ]'
check "sequence position travelled (read by the pg_read_all_data role)" '[ "$(sql dst "select last_value from \"components-registry\".components_seq_seq")" = 42 ]'
check "extension travelled with the schema" '[ "$(sql dst "select count(*) from pg_extension where extname = \$\$pgcrypto\$\$")" = 1 ]'
check "source is unchanged" '[ "$(sql src "select count(*) from \"components-registry\".components")" = 5 ]'
check "old target schema is kept as a gzipped dump" 'gunzip -c "$OUT/qa-before.sql.gz" | grep -q "CREATE TABLE \"components-registry\".stale"'
check "the kept dump restores on its own, extension included" 'docker exec "$NET-dst" psql -U crs -d crs -q -c "create database restore_check" &&
    { echo "SET client_min_messages = warning;"; gunzip -c "$OUT/qa-before.sql.gz"; } |
    docker exec -i "$NET-dst" psql -U crs -d restore_check -X -q -v ON_ERROR_STOP=1 --single-transaction >/dev/null'
check "copied migration version is logged" 'grep -q "Production migration version: 10$" "$OUT/run.log"'

run "$NET-src"
check "same host is refused" '[ $? -ne 0 ] && grep -q "host are the same" "$OUT/run.log"'

# Valid credentials for the aliased server, so only the cluster identity check can stop the run.
DST_USER=crs run qa-alias --add-host "qa-alias:$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$NET-src")"
check "same cluster under another name is refused" '[ $? -ne 0 ] && grep -q "same PostgreSQL cluster" "$OUT/run.log"'

run "prod-db"
check "prod-looking target is refused" '[ $? -ne 0 ] && grep -q "looks like production" "$OUT/run.log"'

sql src 'insert into "components-registry".components(name) values ($$new$$)'
docker exec -d "$NET-dst" psql -U crs -d crs -c 'begin; lock table "components-registry".components in access share mode; select pg_sleep(30);'
sleep 1
run "$NET-dst"
check "locked target fails" '[ $? -ne 0 ]'
check "locked target keeps its old rows" '[ "$(sql dst "select count(*) from \"components-registry\".components")" = 5 ]'

sql dst "select pg_terminate_backend(pid) from pg_stat_activity where query like '%pg_sleep%' and pid <> pg_backend_pid()" >/dev/null
SRC_USER=crs run "$NET-dst"
check "a production credential that can write is reported" '[ $? -eq 0 ] && grep -q "credential can write" "$OUT/run.log"'

sql dst 'create view public.outside_view as select * from "components-registry".components;
         grant select on public.outside_view to qa;'
run "$NET-dst"
check "a dependent object outside the schema is refused" '[ $? -ne 0 ] && grep -q "outside the schema" "$OUT/run.log"'
check "the outside object survives" '[ "$(sql dst "select to_regclass(\$\$public.outside_view\$\$) is not null")" = t ]'
sql dst 'drop view public.outside_view;'

sql src 'insert into "components-registry".components(name) values ($$newer$$)'   # source 7, target 6
docker exec -d "$NET-dst" psql -U crs -d crs -c 'begin; lock table "components-registry".components in access exclusive mode; select pg_sleep(90);'
sleep 1
started=$(date +%s)
run "$NET-dst"
# shellcheck disable=SC2034 # rc is read inside the eval'd check below
rc=$?
elapsed=$(( $(date +%s) - started ))
check "an exclusively locked target fails without waiting (${elapsed}s)" '[ $rc -ne 0 ] && [ $elapsed -lt 45 ]'
check "the exclusively locked target keeps its rows" '[ "$(sql dst "select count(*) from \"components-registry\".components")" = 6 ]'
sql dst "select pg_terminate_backend(pid) from pg_stat_activity where query like '%pg_sleep%' and pid <> pg_backend_pid()" >/dev/null

check "source is still unchanged apart from the test inserts" '[ "$(sql src "select count(*) from \"components-registry\".components")" = 7 ]'
exit $fail
