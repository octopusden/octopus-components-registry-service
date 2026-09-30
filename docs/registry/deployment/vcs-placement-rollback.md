# Runbook: rolling back across the VCS placement migrations (`V8__`, `V9__`)

`V8__add_vcs_entry_placement.sql` adds `source_path` and `checkout_directory` to
`vcs_settings_entries` (and sets `checkout_directory = name` after the first entry of multi-entry
rows); `V9__add_build_working_directory.sql` adds `build_working_directory` to
`component_configurations`. This runbook covers a rollback to a release before them and the repair
after rolling forward again. No code is involved; all steps are SQL against the service database.

## What a rollback does

The previous release starts on the migrated schema: Spring Boot 3.2.2 ships Flyway 9, whose default
`ignoreMigrationPatterns=*:future` accepts applied newer migrations, and `ddl-auto: validate`
ignores the extra columns.

- **Build Working Directory**: the previous release never writes `build_working_directory`, so the
  column keeps its values unless a row is deleted. Nothing to repair.
- **Entry placement**: the previous release recreates a row's entries on any VCS write, so
  `source_path` and `checkout_directory` become NULL for every row whose VCS entries it writes.
  Names survive (the Portal sends the stored name).
- Rolling back to the revision 2 image instead of the release before `V8__`: rows with a Checkout
  Directory on their first entry, or with every entry placed, fail their next v4 VCS write until
  rolled forward. Their data stays intact.

## 1. Before rolling back: snapshot placement

```sql
create table vcs_placement_snapshot as
select component_configuration_id, vcs_path, sort_order, source_path, checkout_directory
from vcs_settings_entries
where source_path is not null or checkout_directory is not null;
```

Keep the table (or an export of it) until step 2 is done.

## 2. After rolling forward: restore placement from the snapshot

Match on (`component_configuration_id`, `vcs_path`, `sort_order`): `sort_order` is part of the key
because one repository may appear twice in a row. Only rows the previous release rewrote differ.

```sql
update vcs_settings_entries e
set source_path = s.source_path, checkout_directory = s.checkout_directory
from vcs_placement_snapshot s
where e.component_configuration_id = s.component_configuration_id
  and e.vcs_path = s.vcs_path
  and e.sort_order = s.sort_order
  and (e.source_path is distinct from s.source_path or e.checkout_directory is distinct from s.checkout_directory);
```

Do **not** re-apply `V8__`'s `checkout_directory = name` rule as a repair: since revision 3 a later
entry may legitimately have no Checkout Directory.

## 3. Rows written during the window that the snapshot does not cover

Entries added or re-pointed during the rollback window are not in the snapshot and keep what the
previous release wrote (no placement). A row that ends up with more than one entry without a
Checkout Directory fails its next v4 VCS write with `400 vcsEntries[<i>].checkoutDirectory: …` until
an editor places its entries in the Portal. To list them:

```sql
select component_configuration_id, count(*)
from vcs_settings_entries
where checkout_directory is null
group by component_configuration_id
having count(*) > 1;
```

A row whose Build Working Directory no longer fits its entries also fails its next v4 VCS write
with `400 buildWorkingDirectory: …`: no entry is at the checkout root, and the Build Working
Directory's first segment is no entry's Checkout Directory. To list them:

```sql
select c.id, c.build_working_directory
from component_configurations c
where c.build_working_directory is not null
  and not exists (
    select 1 from vcs_settings_entries e
    where e.component_configuration_id = c.id and e.checkout_directory is null)
  and not exists (
    select 1 from vcs_settings_entries e
    where e.component_configuration_id = c.id
      and e.checkout_directory = split_part(c.build_working_directory, '/', 1));
```

Drop `vcs_placement_snapshot` once the repair is verified.
