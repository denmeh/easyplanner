//! Application entry point for persisted tasks: CRUD, due processing, and the background poller.
//!
//! [`TaskScheduler`] is generic over [`TaskRepository`](crate::store::TaskRepository). Use
//! [`SqliteTaskScheduler`] (alias) for the default SQLite-backed app path.
//!
//! Install a `log` backend in the host app (e.g. `env_logger` in tests, `android_logger` on Android)
//! to surface poller diagnostics.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use chrono_tz::Tz;
use log::{debug, error, warn};

use crate::calendar::Calendar;
use crate::model::Timestamp;
use crate::store::{SqliteTaskStore, StoreError, TaskRepository, TaskRow, TaskStatus};

const DEFAULT_POLL_INTERVAL: Duration = Duration::from_secs(30);

/// Default scheduler backed by on-device SQLite.
pub type SqliteTaskScheduler = TaskScheduler<SqliteTaskStore>;

#[derive(Debug, thiserror::Error)]
pub enum SchedulerError {
    #[error(transparent)]
    Store(#[from] StoreError),
    #[error(transparent)]
    Calendar(#[from] crate::calendar::CalendarError),
    #[error("invalid IANA time zone: {0}")]
    InvalidTimezone(String),
    #[error("background poller is already running")]
    WatcherAlreadyRunning,
    #[error("repository lock poisoned")]
    MutexPoisoned,
    #[error("cannot modify finished task: {0}")]
    TaskFinished(i64),
}

/// Lifecycle state exposed to hosts (maps the persisted `status` column).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskState {
    Active,
    Expired,
    Finished,
}

/// A scheduled task visible outside this crate.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Task {
    pub id: i64,
    pub title: String,
    /// Optional notes (persisted in the `description` column).
    pub description: String,
    pub calendar_expr: String,
    pub wall_clock_tz: Option<String>,
    pub next_occurrence_unix: Option<u64>,
    pub created_at_unix: u64,
    pub enabled: bool,
    pub state: TaskState,
}

/// Events emitted when the background poller processes due tasks.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TaskLifecycleEvent {
    Expired { task: Task },
    Finished { id: i64, title: String },
}

/// Owns a [`TaskRepository`] and optionally runs a background loop to process due tasks.
pub struct TaskScheduler<R> {
    pub(crate) repo: Arc<Mutex<R>>,
    poll_interval: Duration,
    stopped: Arc<AtomicBool>,
    watcher_handle: Mutex<Option<JoinHandle<()>>>,
}

impl<R> TaskScheduler<R>
where
    R: TaskRepository + Send + 'static,
{
    /// Wraps an existing repository handle (shared with other callers via `Arc<Mutex<_>>` if needed).
    pub fn new(repo: R, poll_interval: Duration) -> Self {
        Self::from_repository(Arc::new(Mutex::new(repo)), poll_interval)
    }

    pub fn from_repository(repo: Arc<Mutex<R>>, poll_interval: Duration) -> Self {
        Self {
            repo,
            poll_interval,
            stopped: Arc::new(AtomicBool::new(true)),
            watcher_handle: Mutex::new(None),
        }
    }

    /// Persists a task after validating `calendar_expr` and computing the first run time.
    pub fn add_task(
        &self,
        title: String,
        notes: String,
        calendar_expr: &str,
        wall_clock_tz_iana: Option<&str>,
    ) -> Result<i64, SchedulerError> {
        let mut repo = self.lock_repo()?;
        let stored_tz = normalize_wall_clock_tz(wall_clock_tz_iana);
        let default_tz = wall_tz_from_stored(stored_tz.as_deref())?;
        let calendar: Calendar = calendar_expr.parse()?;
        let now = Timestamp::now();
        let next = calendar.next_occurrence_with_default_tz(now, default_tz);
        Ok(repo.insert_task(
            title,
            notes,
            calendar_expr,
            stored_tz,
            next.map(|t| t.as_u64()),
            now.as_u64(),
            true,
            TaskStatus::Active,
        )?)
    }

    /// Disables or re-enables a task. Re-enabling recomputes the next run from `now` and sets status active.
    pub fn set_task_enabled(&self, id: i64, enabled: bool) -> Result<(), SchedulerError> {
        let mut repo = self.lock_repo()?;
        let Some(row) = repo.get_task(id)? else {
            return Err(StoreError::TaskNotFound(id).into());
        };
        if row.status == TaskStatus::Finished {
            return Err(SchedulerError::TaskFinished(id));
        }
        repo.set_task_enabled(id, enabled)?;
        if enabled {
            let default_tz = wall_tz_from_stored(row.wall_clock_tz.as_deref())?;
            let calendar: Calendar = row.calendar_expr.parse()?;
            let now = Timestamp::now();
            let next = calendar.next_occurrence_with_default_tz(now, default_tz);
            repo.set_next_occurrence_unix(id, next.map(|t| t.as_u64()))?;
            repo.set_task_status(id, TaskStatus::Active)?;
        }
        Ok(())
    }

    /// Updates content and schedule for a non-finished task; recomputes next occurrence from `now`.
    pub fn update_task(
        &self,
        id: i64,
        title: String,
        notes: String,
        calendar_expr: &str,
        wall_clock_tz_iana: Option<&str>,
    ) -> Result<(), SchedulerError> {
        let mut repo = self.lock_repo()?;
        let Some(row) = repo.get_task(id)? else {
            return Err(StoreError::TaskNotFound(id).into());
        };
        if row.status == TaskStatus::Finished {
            return Err(SchedulerError::TaskFinished(id));
        }
        let stored_tz = normalize_wall_clock_tz(wall_clock_tz_iana);
        let default_tz = wall_tz_from_stored(stored_tz.as_deref())?;
        let calendar: Calendar = calendar_expr.parse()?;
        let now = Timestamp::now();
        let next = calendar.next_occurrence_with_default_tz(now, default_tz);
        repo.update_task_content(
            id,
            title,
            notes,
            calendar_expr,
            stored_tz,
            next.map(|t| t.as_u64()),
            TaskStatus::Active,
        )?;
        Ok(())
    }

    pub fn list_tasks(&self) -> Result<Vec<Task>, SchedulerError> {
        let repo = self.lock_repo()?;
        Ok(repo.list_tasks()?.into_iter().map(Task::from).collect())
    }

    pub fn delete_task(&self, id: i64) -> Result<(), SchedulerError> {
        let mut repo = self.lock_repo()?;
        repo.delete_task(id)?;
        Ok(())
    }

    /// Runs one due-processing pass at the current time.
    pub fn tick(&self) -> Result<Vec<TaskLifecycleEvent>, SchedulerError> {
        self.tick_at(Timestamp::now())
    }

    /// Runs one due-processing pass at `now` (useful for tests or host-driven alarms).
    pub fn tick_at(&self, now: Timestamp) -> Result<Vec<TaskLifecycleEvent>, SchedulerError> {
        let mut repo = self.lock_repo()?;
        let results = Self::process_all_due(&mut *repo, now)?;
        Ok(results
            .into_iter()
            .filter_map(TaskLifecycleEvent::try_from_due_result)
            .collect())
    }

    /// Starts the background poller and returns a channel of lifecycle events.
    pub fn start_watcher(&self) -> Result<mpsc::Receiver<TaskLifecycleEvent>, SchedulerError> {
        let mut slot = self
            .watcher_handle
            .lock()
            .map_err(|_| SchedulerError::MutexPoisoned)?;
        if slot.is_some() {
            return Err(SchedulerError::WatcherAlreadyRunning);
        }

        self.stopped.store(false, Ordering::SeqCst);
        let (tx, rx) = mpsc::channel();
        let repo = Arc::clone(&self.repo);
        let stopped = Arc::clone(&self.stopped);
        let interval = self.poll_interval;

        let handle = thread::spawn(move || {
            debug!("task scheduler: background poller started");
            while !stopped.load(Ordering::SeqCst) {
                match repo.lock() {
                    Ok(mut guard) => {
                        let now = Timestamp::now();
                        match Self::process_all_due(&mut *guard, now) {
                            Ok(results) => {
                                if !results.is_empty() {
                                    debug!(
                                        "task scheduler: processed {} due task(s) at {}",
                                        results.len(),
                                        now.as_u64()
                                    );
                                }
                                for result in results {
                                    let Some(event) =
                                        TaskLifecycleEvent::try_from_due_result(result)
                                    else {
                                        continue;
                                    };
                                    if tx.send(event).is_err() {
                                        debug!(
                                            "task scheduler: event receiver dropped, stopping poller"
                                        );
                                        return;
                                    }
                                }
                            }
                            Err(e) => {
                                warn!("task scheduler: tick failed, will retry: {e}");
                            }
                        }
                    }
                    Err(poison) => {
                        error!(
                            "task scheduler: repository mutex poisoned, skipping tick: {poison}"
                        );
                    }
                }
                thread::sleep(interval);
            }
            debug!("task scheduler: background poller stopped");
        });

        *slot = Some(handle);
        Ok(rx)
    }

    fn lock_repo(&self) -> Result<MutexGuard<'_, R>, SchedulerError> {
        self.repo
            .lock()
            .map_err(|_| SchedulerError::MutexPoisoned)
    }

    fn next_occurrence_after_fire(
        task: &TaskRow,
        fired_at: Timestamp,
    ) -> Result<Option<Timestamp>, SchedulerError> {
        let default_tz = wall_tz_from_stored(task.wall_clock_tz.as_deref())?;
        let calendar: Calendar = task.calendar_expr.parse()?;
        Ok(calendar.next_occurrence_with_default_tz(fired_at, default_tz))
    }

    fn process_all_due(
        repo: &mut R,
        now: Timestamp,
    ) -> Result<Vec<DueProcessResult>, SchedulerError> {
        let due = repo.tasks_due_before(now)?;
        due.iter()
            .map(|task| Self::process_due_task(repo, task, now))
            .collect()
    }

    fn process_due_task(
        repo: &mut R,
        task: &TaskRow,
        now: Timestamp,
    ) -> Result<DueProcessResult, SchedulerError> {
        repo.set_task_status(task.id, TaskStatus::Expired)?;

        let next = Self::next_occurrence_after_fire(task, now)?;
        repo.set_next_occurrence_unix(task.id, next.map(|t| t.as_u64()))?;

        if next.is_some() {
            repo.set_task_status(task.id, TaskStatus::Active)?;
            let mut expired_row = task.clone();
            expired_row.status = TaskStatus::Expired;
            Ok(DueProcessResult::Expired(expired_row))
        } else {
            repo.set_task_status(task.id, TaskStatus::Finished)?;
            Ok(DueProcessResult::Finished(task.id, task.title.clone()))
        }
    }
}

impl<R> TaskScheduler<R> {
    pub fn stop_watcher(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        if let Ok(mut slot) = self.watcher_handle.lock() {
            if let Some(handle) = slot.take() {
                let _ = handle.join();
            }
        }
    }
}

impl TaskScheduler<SqliteTaskStore> {
    /// Opens (or creates) the database at `path` and returns a scheduler handle.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, SchedulerError> {
        let store = SqliteTaskStore::open(path)?;
        Ok(Self::new(store, DEFAULT_POLL_INTERVAL))
    }

    /// Overrides the poll interval (intended for tests).
    #[cfg(test)]
    pub fn open_with_poll_interval(
        path: impl AsRef<Path>,
        poll_interval: Duration,
    ) -> Result<Self, SchedulerError> {
        let store = SqliteTaskStore::open(path)?;
        Ok(Self::new(store, poll_interval))
    }

    #[cfg(test)]
    pub(crate) fn set_next_occurrence_for_test(&self, id: i64, unix: u64) {
        let repo = self.repo.lock().expect("repo lock");
        repo.set_next_occurrence_for_test(id, unix);
    }
}

impl<R> Drop for TaskScheduler<R> {
    fn drop(&mut self) {
        self.stop_watcher();
    }
}

type MutexGuard<'a, T> = std::sync::MutexGuard<'a, T>;

enum DueProcessResult {
    Expired(TaskRow),
    Finished(i64, String),
}

impl TaskLifecycleEvent {
    fn try_from_due_result(result: DueProcessResult) -> Option<Self> {
        match result {
            DueProcessResult::Expired(row) => Some(Self::Expired {
                task: Task::from(row),
            }),
            DueProcessResult::Finished(id, title) => Some(Self::Finished { id, title }),
        }
    }
}

impl Task {
    fn from(row: TaskRow) -> Self {
        Self {
            id: row.id,
            title: row.title,
            description: row.description,
            calendar_expr: row.calendar_expr,
            wall_clock_tz: row.wall_clock_tz,
            next_occurrence_unix: row.next_occurrence_unix,
            created_at_unix: row.created_at_unix,
            enabled: row.enabled,
            state: TaskState::from(row.status),
        }
    }
}

impl TaskState {
    fn from(status: TaskStatus) -> Self {
        match status {
            TaskStatus::Active => TaskState::Active,
            TaskStatus::Expired => TaskState::Expired,
            TaskStatus::Finished => TaskState::Finished,
        }
    }
}

fn normalize_wall_clock_tz(raw: Option<&str>) -> Option<String> {
    raw.map(str::trim)
        .filter(|s| !s.is_empty())
        .map(std::string::ToString::to_string)
}

fn wall_tz_from_stored(stored: Option<&str>) -> Result<Tz, SchedulerError> {
    match stored.map(str::trim).filter(|s| !s.is_empty()) {
        None => Ok(Tz::UTC),
        Some(s) => s
            .parse()
            .map_err(|_| SchedulerError::InvalidTimezone(s.to_string())),
    }
}

/// Human-readable schedule line for UI; falls back to the raw expression if parsing fails.
pub fn schedule_summary(calendar_expr: &str) -> String {
    match calendar_expr.parse::<Calendar>() {
        Ok(cal) => cal.to_human_readable(true),
        Err(_) => calendar_expr.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn tick_advances_recurring_task_to_active() {
        let scheduler =
            SqliteTaskScheduler::open_with_poll_interval(":memory:", Duration::from_secs(30))
                .unwrap();
        let now = Timestamp::new(1_700_000_000);
        let id = scheduler
            .add_task("repeat".into(), String::new(), "minutely", None)
            .unwrap();
        scheduler.set_next_occurrence_for_test(id, now.as_u64().saturating_sub(120));

        let events = scheduler.tick().expect("tick");
        assert_eq!(events.len(), 1);
        assert!(matches!(
            events[0],
            TaskLifecycleEvent::Expired { .. }
        ));

        let row = scheduler
            .list_tasks()
            .unwrap()
            .into_iter()
            .find(|t| t.id == id)
            .unwrap();
        assert_eq!(row.state, TaskState::Active);
        assert!(row.next_occurrence_unix.is_some());
        assert!(row.next_occurrence_unix.unwrap() > now.as_u64());
    }

    #[test]
    fn tick_finishes_task_with_no_next_occurrence() {
        let scheduler = SqliteTaskScheduler::open(":memory:").unwrap();
        let expr = "2020-01-01 08:00:00";
        let fired_at = Timestamp::new(1_577_880_000);
        let id = scheduler
            .add_task("once".into(), String::new(), expr, None)
            .unwrap();
        scheduler.set_next_occurrence_for_test(id, fired_at.as_u64());

        let events = scheduler
            .tick_at(Timestamp::new(fired_at.as_u64() + 3600))
            .expect("tick");
        assert_eq!(events.len(), 1);
        assert!(matches!(events[0], TaskLifecycleEvent::Finished { .. }));

        let row = scheduler
            .list_tasks()
            .unwrap()
            .into_iter()
            .find(|t| t.id == id)
            .unwrap();
        assert_eq!(row.state, TaskState::Finished);
        assert!(row.next_occurrence_unix.is_none());
    }

    #[test]
    fn watcher_emits_expired_event_for_due_task() {
        let scheduler =
            SqliteTaskScheduler::open_with_poll_interval(":memory:", Duration::from_millis(50))
                .unwrap();
        let now = Timestamp::new(1_700_000_000);
        let id = scheduler
            .add_task("due".into(), String::new(), "minutely", None)
            .unwrap();
        scheduler.set_next_occurrence_for_test(id, now.as_u64().saturating_sub(120));

        let rx = scheduler.start_watcher().expect("start");
        let event = rx
            .recv_timeout(Duration::from_secs(2))
            .expect("recv timeout");
        scheduler.stop_watcher();

        match event {
            TaskLifecycleEvent::Expired { task } => {
                assert_eq!(task.id, id);
                assert_eq!(task.state, TaskState::Expired);
            }
            other => panic!("expected Expired, got {other:?}"),
        }

        let row = scheduler
            .list_tasks()
            .unwrap()
            .into_iter()
            .find(|t| t.id == id)
            .unwrap();
        assert_eq!(row.state, TaskState::Active);
    }
}
