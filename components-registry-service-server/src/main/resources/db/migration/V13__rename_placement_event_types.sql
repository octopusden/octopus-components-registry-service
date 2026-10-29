-- Rename stored placement event types to checkout layout.
UPDATE service_event SET event_type = 'TEAMCITY_CHECKOUT_LAYOUT_DIFF' WHERE event_type = 'TEAMCITY_PLACEMENT_DIFF';
UPDATE service_event SET event_type = 'TEAMCITY_CHECKOUT_LAYOUT_SYNC' WHERE event_type = 'TEAMCITY_PLACEMENT_SYNC';
