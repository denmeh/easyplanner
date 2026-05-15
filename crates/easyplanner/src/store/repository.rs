//! Persistence API for tasks (implementations may use SQLite, etc.).

use crate::model::Timestamp;

/// Lifecycle state persisted on each task row.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskStatus {
    Active,
    Expired,
    Finished,
}

impl TaskStatus {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            TaskStatus::Active => "active",
            TaskStatus::Expired => "expired",
            TaskStatus::Finished => "finished",
        }
    }

    pub(crate) fn from_str(s: &str) -> Result<Self, StoreError> {
        match s {
            "active" => Ok(TaskStatus::Active),
            "expired" => Ok(TaskStatus::Expired),
            "finished" => Ok(TaskStatus::Finished),
            _ => Err(StoreError::InvalidTaskStatus(s.to_string())),
        }
    }
}

/// One persisted task row. `calendar_expr` is the source of truth for scheduling; the DB also
/// stores `next_occurrence_unix` so due queries stay cheap without re-parsing on every read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskRow {
    pub id: i64,
    pub description: String,
    pub calendar_expr: String,
    /// IANA id when the expression has no embedded zone; `None` means UTC.
    pub wall_clock_tz: Option<String>,
    pub next_occurrence_unix: Option<u64>,
    pub created_at_unix: u64,
    pub enabled: bool,
    pub status: TaskStatus,
}

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error(transparent)]
    Sqlite(#[from] rusqlite::Error),
    #[error(transparent)]
    Migrate(#[from] refinery::Error),
    #[error("task not found: {0}")]
    TaskNotFound(i64),
    #[error("invalid task status: {0}")]
    InvalidTaskStatus(String),
}

/// Abstraction over storage so callers (FFI, jobs, tests) do not depend on SQL details.
pub trait TaskRepository {
    fn insert_task(
        &mut self,
        description: String,
        calendar_expr: &str,
        wall_clock_tz: Option<String>,
        next_occurrence_unix: Option<u64>,
        created_at_unix: u64,
        enabled: bool,
        status: TaskStatus,
    ) -> Result<i64, StoreError>;

    /// Scheduler-facing view: only active, enabled rows with a concrete next time at or before `now`,
    /// ordered soonest-first so a worker can process the next due items without sorting in app code.
    fn tasks_due_before(&self, now: Timestamp) -> Result<Vec<TaskRow>, StoreError>;

    /// UI / admin listing: full set, `id` order so pagination and stable keys stay predictable.
    fn list_tasks(&self) -> Result<Vec<TaskRow>, StoreError>;

    fn delete_task(&mut self, id: i64) -> Result<(), StoreError>;

    fn set_task_status(&mut self, id: i64, status: TaskStatus) -> Result<(), StoreError>;

    fn set_next_occurrence_unix(
        &mut self,
        id: i64,
        next_occurrence_unix: Option<u64>,
    ) -> Result<(), StoreError>;
}
