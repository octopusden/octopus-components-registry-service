-- Placement of a VCS entry on the build agent: source_path is the directory of the repository that
-- belongs to the component (relative, NULL = whole repository); checkout_directory is the directory
-- the entry is checked out to (NULL on the primary entry, which goes to the checkout root).
ALTER TABLE vcs_settings_entries ADD COLUMN source_path VARCHAR(255);
ALTER TABLE vcs_settings_entries ADD COLUMN checkout_directory VARCHAR(255);

-- Secondary entries of existing multi-entry rows are checked out under their name.
UPDATE vcs_settings_entries SET checkout_directory = name WHERE sort_order > 0;
