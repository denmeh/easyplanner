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
    pub(crate) fn set_next_occurrence_for_test(&self, id: i64, unix: u64) {
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
    use crate::scheduler::{SchedulerError, SqliteTaskScheduler, TaskState};

    #[test]
    fn migrations_apply_and_tasks_table_exists() {
        let scheduler = SqliteTaskScheduler::open(":memory:").expect("open");
        let n: i64 = scheduler
            .repo
            .lock()
            .expect("lock")
            .conn
            .query_row("SELECT COUNT(*) FROM refinery_schema_history", [], |r| {
                r.get(0)
            })
            .expect("refinery history");
        assert!(n >= 3);
    }

    #[test]
    fn invalid_wall_clock_tz_on_add_returns_error() {
        let scheduler = SqliteTaskScheduler::open(":memory:").expect("open");
        let err = scheduler
            .add_task("x".into(), "minutely", Some("Not/AValid/ZoneId"))
            .unwrap_err();
        assert!(matches!(err, SchedulerError::InvalidTimezone(_)));
    }

    #[test]
    fn add_due_advance_round_trip() {
        let scheduler = SqliteTaskScheduler::open(":memory:").expect("open");
        let expr = "minutely";
        let cal: Calendar = expr.parse().unwrap();
        let now = Timestamp::now();
        let next = cal.next_occurrence(now).expect("next");
        let id = scheduler
            .add_task("brush teeth".into(), expr, None)
            .expect("add");
        let due = scheduler.tick_at(next).expect("tick");
        assert!(
            due.iter().any(|e| matches!(e, crate::scheduler::TaskLifecycleEvent::Expired { .. })),
            "task should be due at or after its first next occurrence"
        );
        let tasks = scheduler.list_tasks().expect("list");
        let row = tasks.iter().find(|t| t.id == id).unwrap();
        assert_eq!(row.state, TaskState::Active);
        assert!(row.next_occurrence_unix.is_some());
    }

    #[test]
    fn next_occurrence_column_matches_calendar_for_specific_expr() {
        let scheduler = SqliteTaskScheduler::open(":memory:").expect("open");
        let expr = "*-*-* 12:00:00";
        let cal: Calendar = expr.parse().unwrap();
        let now = Timestamp::now();
        let expected_next = cal.next_occurrence(now).expect("next");
        let id = scheduler.add_task("lunch".into(), expr, None).unwrap();
        let tasks = scheduler.list_tasks().unwrap();
        let row = tasks.iter().find(|t| t.id == id).unwrap();
        assert_eq!(row.next_occurrence_unix, Some(expected_next.as_u64()));
    }

    #[test]
    fn list_tasks_returns_all_rows() {
        let scheduler = SqliteTaskScheduler::open(":memory:").expect("open");
        scheduler.add_task("a".into(), "minutely", None).unwrap();
        scheduler.add_task("b".into(), "minutely", None).unwrap();
        let list = scheduler.list_tasks().expect("list");
        assert_eq!(list.len(), 2);
        let descs: Vec<_> = list.iter().map(|r| r.description.as_str()).collect();
        assert!(descs.contains(&"a"));
        assert!(descs.contains(&"b"));
    }

    #[test]
    fn delete_task_removes_row() {
        let scheduler = SqliteTaskScheduler::open(":memory:").expect("open");
        let id = scheduler.add_task("gone".into(), "minutely", None).unwrap();
        scheduler.delete_task(id).expect("delete");
        let list = scheduler.list_tasks().unwrap();
        assert!(list.iter().all(|r| r.id != id));
        assert!(matches!(
            scheduler.delete_task(id).unwrap_err(),
            SchedulerError::Store(StoreError::TaskNotFound(_))
        ));
    }

    #[test]
    fn tasks_due_skips_finished() {
        let scheduler = SqliteTaskScheduler::open(":memory:").expect("open");
        let now = Timestamp::new(1_700_000_000);
        let id = scheduler.add_task("x".into(), "minutely", None).unwrap();
        scheduler.set_next_occurrence_for_test(id, now.as_u64().saturating_sub(60));
        {
            let mut repo = scheduler.repo.lock().expect("lock");
            repo.set_task_status(id, TaskStatus::Finished)
                .expect("finish");
        }
        let events = scheduler.tick_at(now).expect("tick");
        assert!(events.is_empty());
    }
}
