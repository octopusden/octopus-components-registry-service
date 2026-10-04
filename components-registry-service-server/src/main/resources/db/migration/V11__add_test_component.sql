-- A synthetic component (e.g. a CVELAB stand component): consumers filter on this flag instead of
-- string-matching the free-text `test-component` label. Components already carrying that label are
-- flagged; the label itself is kept.
ALTER TABLE components ADD COLUMN test_component BOOLEAN NOT NULL DEFAULT false;

UPDATE components SET test_component = true
WHERE id IN (SELECT component_id FROM component_labels WHERE label_code = 'test-component');
