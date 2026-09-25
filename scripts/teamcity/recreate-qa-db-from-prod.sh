#!/usr/bin/env bash
#
# Replaces the CRS schema of the QA database with a copy of the production one.
#
# Usage (TeamCity script step, run inside a postgres client image):
#   scripts/teamcity/recreate-qa-db-from-prod.sh
#
# Connection settings come from the environment, one set per side:
#   SRC_HOST SRC_PORT SRC_DB SRC_USER SRC_PASSWORD   production (read only)
#   DST_HOST DST_PORT DST_DB DST_USER DST_PASSWORD   QA (rewritten)
#   DB_SCHEMA                                        schema to copy, default components-registry
#   QA_BACKUP_FILE                                   optional; where to keep a gzipped dump of the QA
#                                                    schema as it was before the copy
#
# Production is only ever read:
#   - the only programs given the SRC_* settings are pg_dump and a SELECT-only psql, and every one of
#     those sessions starts with default_transaction_read_only=on, so a write is refused by the server;
#   - pg_dump takes ACCESS SHARE locks only, which block nothing but DDL, and gives up after
#     --lock-wait-timeout instead of queueing;
#   - before anything is written, the script refuses to continue unless the target is a different
#     server from the source (host name and pg_control system_identifier both differ) and the target
#     host name does not mention prod.
# The source credential should be a SELECT-only role; the script warns when it can write.
#
# QA is replaced atomically: the drop of the old schema and the load of the dump run in one
# transaction, so a failure at any point leaves QA exactly as it was. The drop is refused if objects
# outside the schema depend on it, since CASCADE would remove them and the backup covers the schema only. The QA application is not
# restarted and no migrations are run: whoever presses the button redeploys the QA version they need
# (docs/registry/deployment/qa-db-refresh.md).

set -euo pipefail

DB_SCHEMA=${DB_SCHEMA:-components-registry}
LOCK_TIMEOUT=${LOCK_TIMEOUT:-30s}

fail() { echo "ERROR: $*" >&2; exit 1; }

for v in SRC_HOST SRC_PORT SRC_DB SRC_USER SRC_PASSWORD DST_HOST DST_PORT DST_DB DST_USER DST_PASSWORD; do
    [ -n "${!v:-}" ] || fail "$v is not set"
done
# The schema name is spliced into SQL below, so it is restricted to characters that need no escaping.
[[ $DB_SCHEMA =~ ^[a-z0-9_-]+$ ]] || fail "DB_SCHEMA '$DB_SCHEMA' contains unexpected characters"

# Each side gets only its own credentials; the source side is read-only at the session level.
src() {
    PGHOST=$SRC_HOST PGPORT=$SRC_PORT PGDATABASE=$SRC_DB PGUSER=$SRC_USER PGPASSWORD=$SRC_PASSWORD \
        PGOPTIONS='-c default_transaction_read_only=on' "$@"
}
dst() {
    PGHOST=$DST_HOST PGPORT=$DST_PORT PGDATABASE=$DST_DB PGUSER=$DST_USER PGPASSWORD=$DST_PASSWORD "$@"
}
src_sql() { src psql -X -q -A -t -v ON_ERROR_STOP=1 -c "$1"; }
dst_sql() { dst psql -X -q -A -t -v ON_ERROR_STOP=1 -c "$1"; }

# pg_dump -n leaves out extensions, but the schema's own ones (pgcrypto) must travel with it: DROP
# SCHEMA ... CASCADE removes them, and the tables' defaults call their functions.
# Extension names are plain identifiers, so callers split the output into words (SC2086) on purpose.
# Callers capture it in a variable first: under set -e a failed query then aborts the script, where
# inside an argument list it would silently drop the extension from the dump.
extension_args() {
    "$1" "select '--extension=' || extname from pg_extension where extnamespace = '\"$DB_SCHEMA\"'::regnamespace"
}

# --- Guards: never write to the source server ---------------------------------------------------

src_host=$(tr '[:upper:]' '[:lower:]' <<<"$SRC_HOST")
dst_host=$(tr '[:upper:]' '[:lower:]' <<<"$DST_HOST")
[ "$src_host" != "$dst_host" ] || fail "source and target host are the same ($DST_HOST)"
[[ $dst_host != *prod* ]] || fail "target host '$DST_HOST' looks like production"

src_id=$(src_sql "select system_identifier from pg_control_system()")
dst_id=$(dst_sql "select system_identifier from pg_control_system()")
[ -n "$src_id" ] && [ -n "$dst_id" ] || fail "could not read the server identities"
[ "$src_id" != "$dst_id" ] || fail "source and target are the same PostgreSQL cluster ($src_id)"
echo "Source: $SRC_HOST/$SRC_DB (cluster $src_id), target: $DST_HOST/$DST_DB (cluster $dst_id)"

[ "$(src_sql "select exists(select 1 from pg_namespace where nspname = '$DB_SCHEMA')")" = t ] ||
    fail "schema $DB_SCHEMA does not exist on the source"

can_write=$(src_sql "select coalesce(bool_or(has_table_privilege(c.oid, 'INSERT, UPDATE, DELETE')), false)
                     from pg_class c where c.relnamespace = '\"$DB_SCHEMA\"'::regnamespace and c.relkind = 'r'")
if [ "$can_write" = t ]; then
    echo "##teamcity[message text='The production credential can write to $DB_SCHEMA. Only the session-level read-only flag protects production; use a SELECT-only role.' status='WARNING']"
fi

# --- Dump production ----------------------------------------------------------------------------

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dump=$work/prod.sql

src_ext=$(extension_args src_sql)
# shellcheck disable=SC2086
src pg_dump --schema="$DB_SCHEMA" $src_ext --no-owner --no-privileges \
    --lock-wait-timeout="$LOCK_TIMEOUT" --file="$dump"
tail -n 5 "$dump" | grep -q 'PostgreSQL database dump complete' || fail "the dump is incomplete"
echo "Dumped $(wc -c <"$dump") bytes from production"

# --- Keep the QA schema as it was --------------------------------------------------------------

if [ -n "${QA_BACKUP_FILE:-}" ]; then
    qa_has_schema=$(dst_sql "select exists(select 1 from pg_namespace where nspname = '$DB_SCHEMA')")
    if [ "$qa_has_schema" = t ]; then
        mkdir -p "$(dirname "$QA_BACKUP_FILE")"
        dst_ext=$(extension_args dst_sql)
        # shellcheck disable=SC2086
        dst pg_dump --schema="$DB_SCHEMA" $dst_ext --no-owner --no-privileges --lock-wait-timeout="$LOCK_TIMEOUT" |
            gzip >"$QA_BACKUP_FILE"
        echo "Kept the previous QA schema in $QA_BACKUP_FILE ($(wc -c <"$QA_BACKUP_FILE") bytes)"
    else
        echo "QA has no schema $DB_SCHEMA yet, nothing to keep"
    fi
fi

# --- Replace the QA schema in one transaction ---------------------------------------------------

# CASCADE would also drop objects in other schemas that depend on this one (a view, a foreign key, a
# column of its type), and the backup covers this schema only. So everything outside it is counted
# before and after the drop, and any difference aborts the transaction.
outside_objects="select (select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                         where n.nspname not in ('$DB_SCHEMA', 'pg_catalog', 'information_schema', 'pg_toast')
                           and n.nspname !~ '^pg_(temp|toast_temp)_')
                      + (select count(*) from pg_attribute a join pg_class c on c.oid = a.attrelid
                         join pg_namespace n on n.oid = c.relnamespace
                         where a.attnum > 0 and not a.attisdropped
                           and n.nspname not in ('$DB_SCHEMA', 'pg_catalog', 'information_schema', 'pg_toast'))
                      + (select count(*) from pg_constraint k join pg_namespace n on n.oid = k.connamespace
                         where n.nspname not in ('$DB_SCHEMA', 'pg_catalog', 'information_schema'))
                      + (select count(*) from pg_trigger g join pg_class c on c.oid = g.tgrelid
                         join pg_namespace n on n.oid = c.relnamespace where n.nspname <> '$DB_SCHEMA')
                      + (select count(*) from pg_policy y join pg_class c on c.oid = y.polrelid
                         join pg_namespace n on n.oid = c.relnamespace where n.nspname <> '$DB_SCHEMA')
                      + (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                         where n.nspname not in ('$DB_SCHEMA', 'pg_catalog', 'information_schema'))
                      + (select count(*) from pg_type t join pg_namespace n on n.oid = t.typnamespace
                         where n.nspname not in ('$DB_SCHEMA', 'pg_catalog', 'information_schema', 'pg_toast')
                           and n.nspname !~ '^pg_(temp|toast_temp)_')"
{
    printf 'SET client_min_messages = warning;\nSET lock_timeout = %s;\n' "'$LOCK_TIMEOUT'"
    printf '%s AS outside_before \\gset\n' "$outside_objects"
    printf 'DROP SCHEMA IF EXISTS "%s" CASCADE;\n' "$DB_SCHEMA"
    printf '%s = :outside_before AS outside_intact \\gset\n' "$outside_objects"
    printf '\\if :outside_intact\n\\else\n'
    printf "DO \$\$ BEGIN RAISE EXCEPTION 'objects outside the schema %s depend on it; drop them first'; END \$\$;\n" "$DB_SCHEMA"
    printf '\\endif\n'
    cat "$dump"
} | dst psql -X -q -v ON_ERROR_STOP=1 --single-transaction >/dev/null
echo "Replaced schema $DB_SCHEMA on $DST_HOST"
# The QA application keeps running on its own code; this tells its user which schema it now faces.
has_flyway=$(dst_sql "select to_regclass('\"$DB_SCHEMA\".flyway_schema_history') is not null")
if [ "$has_flyway" = t ]; then
    version=$(dst_sql "select version from \"$DB_SCHEMA\".flyway_schema_history
                       where success and version is not null order by installed_rank desc limit 1")
    echo "Production migration version: $version"
fi

# --- Compare row counts -------------------------------------------------------------------------

count_rows="select table_name || ' ' || (xpath('/row/c/text()',
                query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]
            from information_schema.tables
            where table_schema = '$DB_SCHEMA' and table_type = 'BASE TABLE' order by 1"
src_counts=$(src_sql "$count_rows")
dst_counts=$(dst_sql "$count_rows")
if [ "$src_counts" = "$dst_counts" ]; then
    echo "Row counts match on all $(wc -l <<<"$dst_counts" | tr -d ' ') tables"
else
    diff <(echo "$src_counts") <(echo "$dst_counts") || true
    # Production keeps serving writes during the run, so a drift here is reported, not fatal.
    echo "##teamcity[message text='Row counts differ between production and QA after the copy (production may have changed during the run)' status='WARNING']"
fi
