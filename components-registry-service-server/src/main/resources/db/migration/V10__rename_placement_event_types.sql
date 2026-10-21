-- The TeamCity placement Diff/Sync jobs are now "checkout layout" jobs; keep their recorded events under the new type names.
UPDATE service_event SET event_type = 'TEAMCITY_CHECKOUT_LAYOUT_DIFF' WHERE event_type = 'TEAMCITY_PLACEMENT_DIFF';
UPDATE service_event SET event_type = 'TEAMCITY_CHECKOUT_LAYOUT_SYNC' WHERE event_type = 'TEAMCITY_PLACEMENT_SYNC';
