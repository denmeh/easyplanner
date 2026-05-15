//! SQLite implementation of [`TaskRepository`](super::repository::TaskRepository).
//!
//! WAL + foreign keys on open: WAL reduces writer/reader contention for a single-file mobile DB;
//! foreign keys guard referential integrity if the schema grows relations later.

use std::path::Path;

use refinery::embed_migrations;
use rusqlite::{Connection, params};

use crate::calendar::Calendar;
use crate::model::Timestamp;

use super::repository::{StoreError, TaskRepository, TaskRow};

embed_migrations!("migrations");

/// SQLite-backed [`TaskRepository`].
pub struct SqliteTaskStore {
    conn: Connection,
}

impl SqliteTaskStore {
    /// Creates the file if missing, runs embedded migrations once, then returns a handle.
    /// Callers choose `path` (e.g. app `filesDir`) so tests can use `:memory:` without env wiring.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StoreError> {
        let mut conn = Connection::open(path.as_ref())?;
        conn.execute_batch(
            "
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = WAL;
            ",
        )?;
        migrations::runner().run(&mut conn)?;
        Ok(Self { conn })
    }
}

impl TaskRepository for SqliteTaskStore {
    fn add_task(&mut self, description: String, calendar_expr: &str) -> Result<i64, StoreError> {
        let calendar: Calendar = calendar_expr.parse()?;
        let now = Timestamp::now();
        let next = calendar.next_occurrence(now);
        self.conn.execute(
            "INSERT INTO tasks (description, calendar_expr, next_occurrence_unix, created_at_unix, enabled)
             VALUES (?1, ?2, ?3, ?4, 1)",
            params![
                description,
                calendar_expr,
                next.map(|t| t.as_u64() as i64),
                now.as_u64() as i64,
            ],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    fn tasks_due_before(&self, now: Timestamp) -> Result<Vec<TaskRow>, StoreError> {
        let mut stmt = self.conn.prepare(
            // Partial index idx_tasks_due matches this predicate; order is fire-time, not id.
            "SELECT id, description, calendar_expr, next_occurrence_unix, created_at_unix, enabled
             FROM tasks
             WHERE enabled = 1
               AND next_occurrence_unix IS NOT NULL
               AND next_occurrence_unix <= ?1
             ORDER BY next_occurrence_unix ASC",
        )?;
        let rows = stmt
            .query_map(params![now.as_u64() as i64], |row| {
                Ok(TaskRow {
                    id: row.get(0)?,
                    description: row.get(1)?,
                    calendar_expr: row.get(2)?,
                    next_occurrence_unix: row.get::<_, Option<i64>>(3)?.map(|v| v as u64),
                    created_at_unix: row.get::<_, i64>(4)? as u64,
                    enabled: row.get::<_, i64>(5)? != 0,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    fn list_tasks(&self) -> Result<Vec<TaskRow>, StoreError> {
        let mut stmt = self.conn.prepare(
            // Stable key order: matches typical `ORDER BY id` expectations for UI lists and tests.
            "SELECT id, description, calendar_expr, next_occurrence_unix, created_at_unix, enabled
             FROM tasks
             ORDER BY id ASC",
        )?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TaskRow {
                    id: row.get(0)?,
                    description: row.get(1)?,
                    calendar_expr: row.get(2)?,
                    next_occurrence_unix: row.get::<_, Option<i64>>(3)?.map(|v| v as u64),
                    created_at_unix: row.get::<_, i64>(4)? as u64,
                    enabled: row.get::<_, i64>(5)? != 0,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    fn delete_task(&mut self, id: i64) -> Result<(), StoreError> {
        // Distinguish "no row" from silent success so callers can show a real error instead of guessing.
        let n = self
            .conn
            .execute("DELETE FROM tasks WHERE id = ?1", params![id])?;
        if n == 0 {
            return Err(StoreError::TaskNotFound(id));
        }
        Ok(())
    }

    fn advance_task_after_fire(&mut self, id: i64, fired_at: Timestamp) -> Result<(), StoreError> {
        // Re-read stored expression: the calendar type is not serialized except as this string.
        let calendar_expr: String = self
            .conn
            .query_row(
                "SELECT calendar_expr FROM tasks WHERE id = ?1",
                params![id],
                |row| row.get(0),
            )
            .map_err(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => StoreError::TaskNotFound(id),
                _ => StoreError::Sqlite(e),
            })?;
        let calendar: Calendar = calendar_expr.parse()?;
        let next = calendar.next_occurrence(fired_at);
        let n = self.conn.execute(
            "UPDATE tasks SET next_occurrence_unix = ?1 WHERE id = ?2",
            params![next.map(|t| t.as_u64() as i64), id],
        )?;
        if n == 0 {
            return Err(StoreError::TaskNotFound(id));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::calendar::Calendar;
    use crate::store::{StoreError, TaskRepository};

    fn open_memory() -> SqliteTaskStore {
        let mut store = SqliteTaskStore::open(":memory:").expect("open :memory:");
        migrations::runner()
            .run(&mut store.conn)
            .expect("migrations idempotent");
        store
    }

    #[test]
    fn migrations_apply_and_tasks_table_exists() {
        let store = open_memory();
        let n: i64 = store
            .conn
            .query_row("SELECT COUNT(*) FROM refinery_schema_history", [], |r| {
                r.get(0)
            })
            .expect("refinery history");
        assert!(n >= 1);
    }

    #[test]
    fn add_due_advance_round_trip() {
        let mut store = open_memory();
        let expr = "minutely";
        let cal: Calendar = expr.parse().unwrap();
        let now = Timestamp::now();
        let next = cal.next_occurrence(now).expect("next");
        let id = store.add_task("brush teeth".into(), expr).expect("add");
        let stored_expr: String = store
            .conn
            .query_row(
                "SELECT calendar_expr FROM tasks WHERE id = ?1",
                params![id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(stored_expr, expr);
        let due = store.tasks_due_before(next).expect("due");
        assert!(
            due.iter().any(|t| t.id == id),
            "task should be due at or after its first next occurrence"
        );
        store.advance_task_after_fire(id, next).expect("advance");
        let row: String = store
            .conn
            .query_row(
                "SELECT calendar_expr FROM tasks WHERE id = ?1",
                params![id],
                |r| r.get(0),
            )
            .unwrap();
        let cal2: Calendar = row.parse().unwrap();
        let next2 = cal2.next_occurrence(next).expect("second occurrence");
        let stored: Option<i64> = store
            .conn
            .query_row(
                "SELECT next_occurrence_unix FROM tasks WHERE id = ?1",
                params![id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(stored, Some(next2.as_u64() as i64));
    }

    #[test]
    fn next_occurrence_column_matches_calendar_for_specific_expr() {
        let mut store = open_memory();
        let expr = "*-*-* 12:00:00";
        let cal: Calendar = expr.parse().unwrap();
        let now = Timestamp::now();
        let expected_next = cal.next_occurrence(now).expect("next");
        let id = store.add_task("lunch".into(), expr).unwrap();
        let stored: Option<i64> = store
            .conn
            .query_row(
                "SELECT next_occurrence_unix FROM tasks WHERE id = ?1",
                params![id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(stored, Some(expected_next.as_u64() as i64));
    }

    #[test]
    fn list_tasks_returns_all_rows() {
        let mut store = open_memory();
        store.add_task("a".into(), "minutely").unwrap();
        store.add_task("b".into(), "minutely").unwrap();
        let list = store.list_tasks().expect("list");
        assert_eq!(list.len(), 2);
        let descs: Vec<_> = list.iter().map(|r| r.description.as_str()).collect();
        assert!(descs.contains(&"a"));
        assert!(descs.contains(&"b"));
    }

    #[test]
    fn delete_task_removes_row() {
        let mut store = open_memory();
        let id = store.add_task("gone".into(), "minutely").unwrap();
        store.delete_task(id).expect("delete");
        let list = store.list_tasks().unwrap();
        assert!(list.iter().all(|r| r.id != id));
        assert_eq!(
            store.delete_task(id).unwrap_err().to_string(),
            StoreError::TaskNotFound(id).to_string()
        );
    }
}
