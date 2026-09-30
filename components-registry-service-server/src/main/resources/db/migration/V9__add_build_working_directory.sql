-- Where the build runs on the agent, for the row that supplies a version's VCS entries: a relative
-- path starting inside one of the row's checkout directories, or below the checkout root; NULL = the
-- checkout root.
ALTER TABLE component_configurations ADD COLUMN build_working_directory VARCHAR(255);
