ALTER TABLE tasks ADD COLUMN status TEXT NOT NULL DEFAULT 'active';

CREATE INDEX idx_tasks_status_due ON tasks (status, next_occurrence_unix)
WHERE enabled = 1 AND next_occurrence_unix IS NOT NULL;
