ALTER TABLE tasks ADD COLUMN title TEXT NOT NULL DEFAULT '';

UPDATE tasks SET title = description;

UPDATE tasks SET description = '';
