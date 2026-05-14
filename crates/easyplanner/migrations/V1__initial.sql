CREATE TABLE tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    description TEXT NOT NULL,
    calendar_expr TEXT NOT NULL,
    next_occurrence_unix INTEGER,
    created_at_unix INTEGER NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_tasks_due ON tasks (next_occurrence_unix)
WHERE enabled = 1 AND next_occurrence_unix IS NOT NULL;
