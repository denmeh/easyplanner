//! Persistence API for tasks (implementations may use SQLite, etc.).

use crate::calendar::CalendarError;
use crate::model::Timestamp;

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
    #[error("invalid IANA time zone: {0}")]
    InvalidTimezone(String),
}

/// Abstraction over storage so callers (FFI, jobs, tests) do not depend on SQL details.
pub trait TaskRepository {
    /// Persists `calendar_expr` verbatim; validity is enforced by parsing as [`Calendar`](crate::calendar::Calendar)
    /// before insert so bad expressions never hit the table.
    ///
    /// `wall_clock_tz_iana`: when the expression omits a timezone, this IANA id is used for
    /// `next_occurrence_unix` (and later advances). Empty / missing means UTC.
    fn add_task(
        &mut self,
        description: String,
        calendar_expr: &str,
        wall_clock_tz_iana: Option<&str>,
    ) -> Result<i64, StoreError>;

    /// Scheduler-facing view: only enabled rows with a concrete next time at or before `now`,
    /// ordered soonest-first so a worker can process the next due items without sorting in app code.
    fn tasks_due_before(&self, now: Timestamp) -> Result<Vec<TaskRow>, StoreError>;

    /// UI / admin listing: full set, `id` order so pagination and stable keys stay predictable.
    fn list_tasks(&self) -> Result<Vec<TaskRow>, StoreError>;

    /// Hard delete: UI chose removal; no soft-delete layer yet, so `DELETE` keeps the model honest.
    fn delete_task(&mut self, id: i64) -> Result<(), StoreError>;

    /// After a notification (or manual tick), recompute the next slot from the stored expression so
    /// `next_occurrence_unix` stays aligned with calendar rules without re-reading all history.
    fn advance_task_after_fire(&mut self, id: i64, fired_at: Timestamp) -> Result<(), StoreError>;
}
