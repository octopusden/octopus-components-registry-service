-- MIG-039: add separate column for escrow.buildTask, distinct from build.buildTasks
ALTER TABLE component_configurations ADD COLUMN IF NOT EXISTS escrow_build_task TEXT;
