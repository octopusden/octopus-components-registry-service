-- Change metadata captured at save time: the Jira task that motivated the change
-- and a free-text comment. Both optional; stored on the audit row (not the
-- component). The index backs `GET /audit/recent?jiraTaskKey=...` search.
ALTER TABLE audit_log ADD COLUMN jira_task_key VARCHAR(255);
ALTER TABLE audit_log ADD COLUMN change_comment TEXT;
CREATE INDEX idx_audit_jira_task_key ON audit_log(jira_task_key);
