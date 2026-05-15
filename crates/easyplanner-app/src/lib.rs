//! JNI surface for the Android app. Keeps BoltFFI types in this crate so the domain library stays
//! free of `boltffi` and so codegen sees `#[data]` on types defined here.
//!
use std::sync::{mpsc, Mutex};

use boltffi::*;
use easyplanner::{
    SchedulerError, SqliteTaskScheduler, Task, TaskLifecycleEvent, TaskState,
};

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
    /// Mirrors persisted scheduler status; stable lowercase for Kotlin (`active` | `expired` | `finished`).
    pub state: String,
}

/// One fired lifecycle event from the background poller (non-blocking poll from Kotlin).
#[data]
#[derive(Debug, Clone)]
pub struct PlannerSchedulerEvent {
    /// `due` = occurrence reached (recurring or one-shot); `completed` = no further runs.
    pub event_kind: String,
    pub task_id: i64,
    pub description: String,
}

fn map_task_state(state: TaskState) -> &'static str {
    match state {
        TaskState::Active => "active",
        TaskState::Expired => "expired",
        TaskState::Finished => "finished",
    }
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
        state: map_task_state(t.state).to_string(),
    }
}

fn map_scheduler_event(ev: TaskLifecycleEvent) -> PlannerSchedulerEvent {
    match ev {
        TaskLifecycleEvent::Expired { task } => PlannerSchedulerEvent {
            event_kind: "due".to_string(),
            task_id: task.id,
            description: task.description,
        },
        TaskLifecycleEvent::Finished { id, description } => PlannerSchedulerEvent {
            event_kind: "completed".to_string(),
            task_id: id,
            description,
        },
    }
}

/// Owns the DB file; Kotlin should call [`PlannerStore::close`](PlannerStore::close) when the
/// handle is dropped on the JVM side so the connection is released deterministically.
pub struct PlannerStore {
    inner: Mutex<SqliteTaskScheduler>,
    /// Receiver from [`SqliteTaskScheduler::start_watcher`]; drained from JNI via
    /// [`PlannerStore::poll_scheduler_events`].
    event_rx: Mutex<Option<mpsc::Receiver<TaskLifecycleEvent>>>,
}

#[export]
impl PlannerStore {
    pub fn open(path: &str) -> Result<Self, String> {
        SqliteTaskScheduler::open(path)
            .map_err(|e| e.to_string())
            .map(|scheduler| Self {
                inner: Mutex::new(scheduler),
                event_rx: Mutex::new(None),
            })
    }

    /// Starts the native SQLite poller. Events are queued until [`poll_scheduler_events`](PlannerStore::poll_scheduler_events).
    pub fn start_scheduler_loop(&self) -> Result<i64, String> {
        let rx = match {
            let guard = self.inner.lock().map_err(|e| e.to_string())?;
            guard.start_watcher()
        } {
            Ok(rx) => rx,
            Err(SchedulerError::WatcherAlreadyRunning) => return Ok(1),
            Err(e) => return Err(e.to_string()),
        };

        let mut slot = self.event_rx.lock().map_err(|e| e.to_string())?;
        if slot.is_some() {
            drop(rx);
            let guard = self.inner.lock().map_err(|e| e.to_string())?;
            guard.stop_watcher();
            return Err("scheduler event queue in inconsistent state".to_string());
        }
        *slot = Some(rx);
        Ok(1)
    }

    pub fn stop_scheduler_loop(&self) -> Result<i64, String> {
        {
            let guard = self.inner.lock().map_err(|e| e.to_string())?;
            guard.stop_watcher();
        }
        let _ = self.event_rx.lock().map_err(|e| e.to_string())?.take();
        Ok(1)
    }

    /// Non-blocking: returns all events currently queued (may be empty).
    pub fn poll_scheduler_events(&self) -> Result<Vec<PlannerSchedulerEvent>, String> {
        let mut slot = self.event_rx.lock().map_err(|e| e.to_string())?;
        let Some(rx) = slot.as_mut() else {
            return Ok(Vec::new());
        };
        let mut out = Vec::new();
        loop {
            match rx.try_recv() {
                Ok(ev) => out.push(map_scheduler_event(ev)),
                Err(mpsc::TryRecvError::Empty) => break,
                Err(mpsc::TryRecvError::Disconnected) => break,
            }
        }
        Ok(out)
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
