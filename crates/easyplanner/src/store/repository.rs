//! Persistence API for tasks (implementations may use SQLite, etc.).

use crate::calendar::CalendarError;
use crate::model::Timestamp;

/// Row returned for a stored task.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskRow {
    pub id: i64,
    pub description: String,
    pub calendar_expr: String,
    pub next_occurrence_unix: Option<u64>,
    pub created_at_unix: u64,
    pub enabled: bool,
}

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error(transparent)]
    Sqlite(#[from] rusqlite::Error),
    #[error(transparent)]
    Migrate(#[from] refinery::Error),
    #[error(transparent)]
    Calendar(#[from] CalendarError),
    #[error("task not found: {0}")]
    TaskNotFound(i64),
}

/// Task persistence: add tasks, query due work, advance schedules after a fire.
pub trait TaskRepository {
    /// Insert a task and return its row id. `calendar_expr` is stored verbatim (e.g. `"minutely"`).
    /// It must parse as a [`Calendar`](crate::calendar::Calendar); `next_occurrence_unix` is set from that parse.
    fn add_task(&mut self, description: String, calendar_expr: &str) -> Result<i64, StoreError>;

    /// Tasks that are enabled, have a next time, and that time is at or before `now`.
    fn tasks_due_before(&self, now: Timestamp) -> Result<Vec<TaskRow>, StoreError>;

    /// Recompute `next_occurrence` after a firing at `fired_at`.
    fn advance_task_after_fire(&mut self, id: i64, fired_at: Timestamp) -> Result<(), StoreError>;
}
