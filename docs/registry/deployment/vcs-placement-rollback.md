# Runbook: rolling back across the VCS placement migration (`V8__`)

`V8__add_vcs_entry_placement.sql` adds `source_path` and `checkout_directory` to
`vcs_settings_entries` and sets `checkout_directory = name` on secondary entries (`sort_order > 0`).
This runbook covers a rollback to the release before it and the repair after rolling forward again.
No code is involved; all steps are SQL against the service database.

## What a rollback does

The previous release starts on the migrated schema: Spring Boot 3.2.2 ships Flyway 9, whose default
`ignoreMigrationPatterns=*:future` accepts an applied newer migration, and `ddl-auto: validate`
ignores the extra columns.

The previous release does not keep placement. Its `replaceVcsEntries` recreates a row's entries on
any VCS write, so both columns become NULL for every row written during the rollback window. Names
survive (the Portal sends the stored name). The previous release never writes a `checkout_directory`,
so no primary entry gets one.

## 1. Before rolling back: snapshot placed entries

```sql
select component_configuration_id, sort_order, vcs_path, source_path, checkout_directory
from vcs_settings_entries
where source_path is not null or checkout_directory is not null;
```

Keep the result until step 4 is done.

## 2. After rolling forward: find secondary entries without a checkout directory

```sql
select component_configuration_id, sort_order
from vcs_settings_entries
where sort_order > 0 and checkout_directory is null;
```

## 3. Repair them with the migration's rule (secondary entries only)

```sql
update vcs_settings_entries
set checkout_directory = name
where checkout_directory is null and sort_order > 0;
```

## 4. Re-enter lost Source Paths and check names

- Re-enter any `source_path` from the step 1 snapshot that is missing now (through the Portal, or by
  SQL keyed on `component_configuration_id` + `sort_order`).
- An entry added during the rollback window is named `main`. If it is a secondary and its row's
  primary, or another secondary, is also named `main`, step 3 yields a duplicate name and the row's
  next VCS save fails with `400 vcsEntries[<i>].checkoutDirectory: …`. Set that entry's Checkout
  Directory by hand in the Portal.
