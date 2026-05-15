//! Calendar-aware task operations built on top of [`TaskRepository`](crate::store::TaskRepository).

use chrono_tz::Tz;

use crate::calendar::Calendar;
use crate::model::Timestamp;
use crate::store::{StoreError, TaskRepository, TaskRow, TaskStatus};

fn normalize_wall_clock_tz(raw: Option<&str>) -> Option<String> {
    raw.map(str::trim)
        .filter(|s| !s.is_empty())
        .map(std::string::ToString::to_string)
}

fn wall_tz_from_stored(stored: Option<&str>) -> Result<Tz, PlanningError> {
    match stored.map(str::trim).filter(|s| !s.is_empty()) {
        None => Ok(Tz::UTC),
        Some(s) => s
            .parse()
            .map_err(|_| PlanningError::InvalidTimezone(s.to_string())),
    }
}

#[derive(Debug, thiserror::Error)]
pub enum PlanningError {
    #[error(transparent)]
    Store(#[from] StoreError),
    #[error(transparent)]
    Calendar(#[from] crate::calendar::CalendarError),
    #[error("invalid IANA time zone: {0}")]
    InvalidTimezone(String),
}

/// Persists a new task after validating `calendar_expr` and computing the first `next_occurrence_unix`.
pub fn add_task<R: TaskRepository>(
    repo: &mut R,
    description: String,
    calendar_expr: &str,
    wall_clock_tz_iana: Option<&str>,
) -> Result<i64, PlanningError> {
    let stored_tz = normalize_wall_clock_tz(wall_clock_tz_iana);
    let default_tz = wall_tz_from_stored(stored_tz.as_deref())?;
    let calendar: Calendar = calendar_expr.parse()?;
    let now = Timestamp::now();
    let next = calendar.next_occurrence_with_default_tz(now, default_tz);
    Ok(repo.insert_task(
        description,
        calendar_expr,
        stored_tz,
        next.map(|t| t.as_u64()),
        now.as_u64(),
        true,
        TaskStatus::Active,
    )?)
}

/// Recomputes the next fire time from a task's stored expression after a due event at `fired_at`.
pub fn next_occurrence_after_fire(
    task: &TaskRow,
    fired_at: Timestamp,
) -> Result<Option<Timestamp>, PlanningError> {
    let default_tz = wall_tz_from_stored(task.wall_clock_tz.as_deref())?;
    let calendar: Calendar = task.calendar_expr.parse()?;
    Ok(calendar.next_occurrence_with_default_tz(fired_at, default_tz))
}
