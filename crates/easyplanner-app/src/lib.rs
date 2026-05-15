//! JNI surface for the Android app. Keeps BoltFFI types in this crate so the domain library stays
//! free of `boltffi` and so codegen sees `#[data]` on types defined here.
//!
use std::sync::Mutex;

use boltffi::*;
use easyplanner::{SqliteTaskScheduler, Task};

/// Wire shape for Kotlin; field layout matches the `tasks` table (see migrations).
#[data]
#[derive(Debug, Clone)]
pub struct PlannerTask {
    pub id: i64,
    pub description: String,
    pub calendar_expr: String,
    pub wall_clock_tz: Option<String>,
    pub next_occurrence_unix: Option<u64>,
    pub created_at_unix: u64,
    pub enabled: bool,
}

fn map_task(t: Task) -> PlannerTask {
    PlannerTask {
        id: t.id,
        description: t.description,
        calendar_expr: t.calendar_expr,
        wall_clock_tz: t.wall_clock_tz,
        next_occurrence_unix: t.next_occurrence_unix,
        created_at_unix: t.created_at_unix,
        enabled: t.enabled,
    }
}

/// Owns the DB file; Kotlin should call [`PlannerStore::close`](PlannerStore::close) when the
/// handle is dropped on the JVM side so the connection is released deterministically.
pub struct PlannerStore {
    inner: Mutex<SqliteTaskScheduler>,
}

#[export]
impl PlannerStore {
    pub fn open(path: &str) -> Result<Self, String> {
        SqliteTaskScheduler::open(path)
            .map_err(|e| e.to_string())
            .map(|scheduler| Self {
                inner: Mutex::new(scheduler),
            })
    }

    pub fn list_tasks(&self) -> Result<Vec<PlannerTask>, String> {
        let guard = self.inner.lock().map_err(|e| e.to_string())?;
        let rows = guard.list_tasks().map_err(|e| e.to_string())?;
        Ok(rows.into_iter().map(map_task).collect())
    }

    /// `wall_clock_tz`: IANA id when `calendar_expr` has no embedded timezone. Empty string uses UTC.
    pub fn add_task(
        &self,
        description: String,
        calendar_expr: String,
        wall_clock_tz: String,
    ) -> Result<i64, String> {
        let guard = self.inner.lock().map_err(|e| e.to_string())?;
        let tz = wall_clock_tz.trim();
        let tz_opt = if tz.is_empty() { None } else { Some(tz) };
        guard
            .add_task(description, &calendar_expr, tz_opt)
            .map_err(|e| e.to_string())
    }

    /// Returns `1` on success. `Result<(), String>` crashes BoltFFI Android JNI on `Ok(())`; see https://github.com/boltffi/boltffi/issues/308
    pub fn delete_task(&self, id: i64) -> Result<i64, String> {
        let guard = self.inner.lock().map_err(|e| e.to_string())?;
        guard.delete_task(id).map_err(|e| e.to_string())?;
        Ok(1)
    }
}
