//! SQLite implementation of [`TaskRepository`](super::repository::TaskRepository).
//!
//! WAL + foreign keys on open: WAL reduces writer/reader contention for a single-file mobile DB;
//! foreign keys guard referential integrity if the schema grows relations later.

use std::path::Path;

use refinery::embed_migrations;
use rusqlite::{Connection, Row, params};

use crate::model::Timestamp;

use super::repository::{StoreError, TaskRepository, TaskRow, TaskStatus};

embed_migrations!("migrations");

const TASK_SELECT: &str = "SELECT id, description, calendar_expr, wall_clock_tz, next_occurrence_unix, created_at_unix, enabled, status
             FROM tasks";

fn row_to_task(row: &Row<'_>) -> Result<TaskRow, rusqlite::Error> {
    let status_str: String = row.get(7)?;
    Ok(TaskRow {
        id: row.get(0)?,
        description: row.get(1)?,
        calendar_expr: row.get(2)?,
        wall_clock_tz: row.get(3)?,
        next_occurrence_unix: row.get::<_, Option<i64>>(4)?.map(|v| v as u64),
        created_at_unix: row.get::<_, i64>(5)? as u64,
        enabled: row.get::<_, i64>(6)? != 0,
        status: TaskStatus::from_str(&status_str).map_err(|e| {
            rusqlite::Error::FromSqlConversionFailure(7, rusqlite::types::Type::Text, Box::new(e))
        })?,
    })
}

/// SQLite-backed [`TaskRepository`].
pub struct SqliteTaskStore {
    pub(crate) conn: Connection,
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

    #[cfg(test)]
    pub fn set_next_occurrence_for_test(&self, id: i64, unix: u64) {
        self.conn
            .execute(
                "UPDATE tasks SET next_occurrence_unix = ?1 WHERE id = ?2",
                params![unix as i64, id],
            )
            .expect("set next for test");
    }
}

impl TaskRepository for SqliteTaskStore {
    fn insert_task(
        &mut self,
        description: String,
        calendar_expr: &str,
        wall_clock_tz: Option<String>,
        next_occurrence_unix: Option<u64>,
        created_at_unix: u64,
        enabled: bool,
        status: TaskStatus,
    ) -> Result<i64, StoreError> {
        self.conn.execute(
            "INSERT INTO tasks (description, calendar_expr, wall_clock_tz, next_occurrence_unix, created_at_unix, enabled, status)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                description,
                calendar_expr,
                wall_clock_tz,
                next_occurrence_unix.map(|v| v as i64),
                created_at_unix as i64,
                if enabled { 1 } else { 0 },
                status.as_str(),
            ],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    fn get_task(&self, id: i64) -> Result<TaskRow, StoreError> {
        let sql = format!("{TASK_SELECT} WHERE id = ?1");
        self.conn
            .query_row(&sql, params![id], row_to_task)
            .map_err(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => StoreError::TaskNotFound(id),
                _ => StoreError::Sqlite(e),
            })
    }

    fn tasks_due_before(&self, now: Timestamp) -> Result<Vec<TaskRow>, StoreError> {
        let sql = format!(
            "{TASK_SELECT}
             WHERE enabled = 1
               AND status = 'active'
               AND next_occurrence_unix IS NOT NULL
               AND next_occurrence_unix <= ?1
             ORDER BY next_occurrence_unix ASC"
        );
        let mut stmt = self.conn.prepare(&sql)?;
        let rows = stmt
            .query_map(params![now.as_u64() as i64], row_to_task)?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    fn list_tasks(&self) -> Result<Vec<TaskRow>, StoreError> {
        let sql = format!("{TASK_SELECT} ORDER BY id ASC");
        let mut stmt = self.conn.prepare(&sql)?;
        let rows = stmt
            .query_map([], row_to_task)?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    fn delete_task(&mut self, id: i64) -> Result<(), StoreError> {
        let n = self
            .conn
            .execute("DELETE FROM tasks WHERE id = ?1", params![id])?;
        if n == 0 {
            return Err(StoreError::TaskNotFound(id));
        }
        Ok(())
    }

    fn set_task_status(&mut self, id: i64, status: TaskStatus) -> Result<(), StoreError> {
        let n = self.conn.execute(
            "UPDATE tasks SET status = ?1 WHERE id = ?2",
            params![status.as_str(), id],
        )?;
        if n == 0 {
            return Err(StoreError::TaskNotFound(id));
        }
        Ok(())
    }

    fn set_next_occurrence_unix(
        &mut self,
        id: i64,
        next_occurrence_unix: Option<u64>,
    ) -> Result<(), StoreError> {
        let n = self.conn.execute(
            "UPDATE tasks SET next_occurrence_unix = ?1 WHERE id = ?2",
            params![next_occurrence_unix.map(|v| v as i64), id],
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
    use crate::model::Timestamp;
    use crate::planning;
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
        assert!(n >= 3);
    }

    #[test]
    fn invalid_wall_clock_tz_on_add_returns_error() {
        let mut store = open_memory();
        let err = planning::add_task(&mut store, "x".into(), "minutely", Some("Not/AValid/ZoneId"))
            .unwrap_err();
        assert!(matches!(err, planning::PlanningError::InvalidTimezone(_)));
    }

    #[test]
    fn add_due_advance_round_trip() {
        let mut store = open_memory();
        let expr = "minutely";
        let cal: Calendar = expr.parse().unwrap();
        let now = Timestamp::now();
        let next = cal.next_occurrence(now).expect("next");
        let id = planning::add_task(&mut store, "brush teeth".into(), expr, None).expect("add");
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
        let next_after = planning::next_occurrence_after_fire(&due[0], next).unwrap();
        store
            .set_next_occurrence_unix(id, next_after.map(|t| t.as_u64()))
            .expect("advance");
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
        let id = planning::add_task(&mut store, "lunch".into(), expr, None).unwrap();
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
        planning::add_task(&mut store, "a".into(), "minutely", None).unwrap();
        planning::add_task(&mut store, "b".into(), "minutely", None).unwrap();
        let list = store.list_tasks().expect("list");
        assert_eq!(list.len(), 2);
        let descs: Vec<_> = list.iter().map(|r| r.description.as_str()).collect();
        assert!(descs.contains(&"a"));
        assert!(descs.contains(&"b"));
    }

    #[test]
    fn delete_task_removes_row() {
        let mut store = open_memory();
        let id = planning::add_task(&mut store, "gone".into(), "minutely", None).unwrap();
        store.delete_task(id).expect("delete");
        let list = store.list_tasks().unwrap();
        assert!(list.iter().all(|r| r.id != id));
        assert_eq!(
            store.delete_task(id).unwrap_err().to_string(),
            StoreError::TaskNotFound(id).to_string()
        );
    }

    #[test]
    fn tasks_due_before_skips_expired_and_finished() {
        let mut store = open_memory();
        let now = Timestamp::new(1_700_000_000);
        let id = planning::add_task(&mut store, "x".into(), "minutely", None).unwrap();
        store.set_next_occurrence_for_test(id, now.as_u64().saturating_sub(60));
        store
            .set_task_status(id, TaskStatus::Finished)
            .expect("finish");
        let due = store.tasks_due_before(now).expect("due");
        assert!(due.is_empty());
    }
}
