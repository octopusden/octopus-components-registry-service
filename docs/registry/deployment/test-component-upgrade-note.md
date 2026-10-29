# Upgrade note: `testComponent` flag (SYS-099, `V11__`)

`V11__add_test_component.sql` adds `components.test_component` and sets it on every component that
carries the `test-component` label (the label is kept). Two rules then bind only on writes that
change something, so `V11__` itself cannot fail — but data that already breaks them would be
grandfathered silently. Run these read-only checks against the target database before the rollout.

## Before the rollout

Labelled components (the set `V11__` will flag):

```sql
SELECT c.component_key
FROM components c JOIN component_labels l ON l.component_id = c.id
WHERE l.label_code = 'test-component'
ORDER BY 1;
```

Expected empty — labelled keys outside the default name patterns (`^test-`, `^cvelab-`; adjust to the
installation's `components-registry.test-components.name-patterns`):

```sql
SELECT c.component_key
FROM components c JOIN component_labels l ON l.component_id = c.id
WHERE l.label_code = 'test-component'
  AND c.component_key !~ '^test-' AND c.component_key !~ '^cvelab-';
```

Expected empty — real components whose parent is labelled:

```sql
SELECT child.component_key AS real_component, parent.component_key AS test_parent
FROM components child JOIN components parent ON child.parent_component_id = parent.id
WHERE parent.id IN (SELECT component_id FROM component_labels WHERE label_code = 'test-component')
  AND child.id NOT IN (SELECT component_id FROM component_labels WHERE label_code = 'test-component');
```

Expected empty — real components with a doc link to a labelled one:

```sql
SELECT c.component_key AS real_component, d.doc_component_key AS test_doc
FROM component_doc_links d JOIN components c ON c.id = d.component_id
WHERE d.doc_component_key IN (
        SELECT t.component_key FROM components t JOIN component_labels l ON l.component_id = t.id
        WHERE l.label_code = 'test-component')
  AND c.id NOT IN (SELECT component_id FROM component_labels WHERE label_code = 'test-component');
```

A row in either of the last two queries is a real product referencing test data: fix it (drop the
reference or the label) before the rollout. A labelled key outside the patterns stays flagged and
editable after `V11__`; only flagging it again or renaming it is checked.

## Why `V11__` does not check these itself

A Flyway migration cannot read the service configuration, so it cannot know an installation's name
patterns; and refusing or rewriting data inside a schema migration would block startup on data an
operator has to look at anyway. `V11__` therefore only mirrors the label. The two rules bind on
change (create, a flag flip, a rename, a parent/doc change), so grandfathered data stays readable and
editable. Production check before the rollout: 14 labelled components, all keys match `^test-`, and
no real component references one of them as parent or doc component.

Importing from the DSL (`migrate-component(s)`, import) applies the same label rule, so a fresh
install or a re-migration ends up with the same flags as `V11__`.

## After the rollout

```sql
SELECT count(*) FROM components WHERE test_component;
```

equals the row count of the first query.

## Configuration

`components-registry.test-components.name-patterns` (service-config, list) replaces the defaults.
It is bound at startup — a change needs a restart; `/admin/reload-config` does not rebind it.
