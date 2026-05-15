//! Background watcher that processes due tasks and emits lifecycle events on a channel.
//!
//! Install a `log` backend in the host app (e.g. `env_logger` in tests, `android_logger` on Android)
//! to surface watcher diagnostics.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use log::{debug, error, warn};

use crate::model::Timestamp;
use crate::planning::{self, PlanningError};
use crate::store::{StoreError, TaskRepository, TaskRow, TaskStatus};

const DEFAULT_INTERVAL: Duration = Duration::from_secs(30);

/// Outcome of processing one due task.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DueProcessResult {
    Expired(TaskRow),
    Finished(i64),
}

/// Events emitted when the watcher processes due tasks.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TaskLifecycleEvent {
    Expired { task: TaskRow },
    Finished { id: i64 },
}

/// Marks due tasks expired, advances or finishes them, and returns notification payloads.
pub fn process_all_due<R: TaskRepository>(
    repo: &mut R,
    now: Timestamp,
) -> Result<Vec<DueProcessResult>, SchedulerError> {
    let due = repo.tasks_due_before(now)?;
    let mut results = Vec::with_capacity(due.len());
    for task in due {
        results.push(process_due_task(repo, &task, now)?);
    }
    Ok(results)
}

fn process_due_task<R: TaskRepository>(
    repo: &mut R,
    task: &TaskRow,
    now: Timestamp,
) -> Result<DueProcessResult, SchedulerError> {
    repo.set_task_status(task.id, TaskStatus::Expired)?;

    let next = planning::next_occurrence_after_fire(task, now)?;
    repo.set_next_occurrence_unix(task.id, next.map(|t| t.as_u64()))?;

    if next.is_some() {
        repo.set_task_status(task.id, TaskStatus::Active)?;
        let mut expired_row = task.clone();
        expired_row.status = TaskStatus::Expired;
        Ok(DueProcessResult::Expired(expired_row))
    } else {
        repo.set_task_status(task.id, TaskStatus::Finished)?;
        Ok(DueProcessResult::Finished(task.id))
    }
}

#[derive(Debug, thiserror::Error)]
pub enum SchedulerError {
    #[error(transparent)]
    Store(#[from] StoreError),
    #[error(transparent)]
    Planning(#[from] PlanningError),
}

/// Polls a [`TaskRepository`] on a background thread and sends [`TaskLifecycleEvent`] values.
pub struct TaskExpiryWatcher<R> {
    repo: Arc<Mutex<R>>,
    interval: Duration,
    stopped: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl<R> TaskExpiryWatcher<R>
where
    R: TaskRepository + Send + 'static,
{
    pub fn new(repo: Arc<Mutex<R>>) -> Self {
        Self {
            repo,
            interval: DEFAULT_INTERVAL,
            stopped: Arc::new(AtomicBool::new(false)),
            handle: None,
        }
    }

    /// Overrides the default 30-second poll interval (intended for tests).
    pub fn with_interval(repo: Arc<Mutex<R>>, interval: Duration) -> Self {
        Self {
            repo,
            interval,
            stopped: Arc::new(AtomicBool::new(false)),
            handle: None,
        }
    }

    /// Starts the background loop and returns the event receiver.
    pub fn start(&mut self) -> mpsc::Receiver<TaskLifecycleEvent> {
        self.stopped.store(false, Ordering::SeqCst);
        let (tx, rx) = mpsc::channel();
        let repo = Arc::clone(&self.repo);
        let stopped = Arc::clone(&self.stopped);
        let interval = self.interval;

        let handle = thread::spawn(move || {
            debug!("task expiry watcher: background loop started");
            while !stopped.load(Ordering::SeqCst) {
                match repo.lock() {
                    Ok(mut guard) => {
                        let now = Timestamp::now();
                        match process_all_due(&mut *guard, now) {
                            Ok(results) => {
                                if !results.is_empty() {
                                    debug!(
                                        "task expiry watcher: processed {} due task(s) at {}",
                                        results.len(),
                                        now.as_u64()
                                    );
                                }
                                for result in results {
                                    let event = match result {
                                        DueProcessResult::Expired(task) => {
                                            TaskLifecycleEvent::Expired { task }
                                        }
                                        DueProcessResult::Finished(id) => {
                                            TaskLifecycleEvent::Finished { id }
                                        }
                                    };
                                    if tx.send(event).is_err() {
                                        debug!(
                                            "task expiry watcher: event receiver dropped, stopping loop"
                                        );
                                        return;
                                    }
                                }
                            }
                            Err(e) => {
                                warn!("task expiry watcher: tick failed, will retry: {e}");
                            }
                        }
                    }
                    Err(poison) => {
                        error!(
                            "task expiry watcher: repository mutex poisoned, skipping tick: {poison}"
                        );
                    }
                }
                thread::sleep(interval);
            }
            debug!("task expiry watcher: background loop stopped");
        });

        self.handle = Some(handle);
        rx
    }

    pub fn stop(&mut self) {
        self.stopped.store(true, Ordering::SeqCst);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

impl<R> Drop for TaskExpiryWatcher<R> {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::SeqCst);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    use crate::planning;
    use crate::store::{SqliteTaskStore, TaskRepository, TaskStatus};

    #[test]
    fn process_all_due_advances_recurring_task_to_active() {
        let mut store = SqliteTaskStore::open(":memory:").expect("open");
        let now = Timestamp::new(1_700_000_000);
        let id = planning::add_task(&mut store, "repeat".into(), "minutely", None).unwrap();
        store.set_next_occurrence_for_test(id, now.as_u64().saturating_sub(120));

        let results = process_all_due(&mut store, now).expect("process");
        assert_eq!(results.len(), 1);
        assert!(matches!(results[0], DueProcessResult::Expired(_)));

        let row = store.list_tasks().unwrap().into_iter().find(|r| r.id == id).unwrap();
        assert_eq!(row.status, TaskStatus::Active);
        assert!(row.next_occurrence_unix.is_some());
        assert!(row.next_occurrence_unix.unwrap() > now.as_u64());
    }

    #[test]
    fn process_all_due_finishes_task_with_no_next_occurrence() {
        let mut store = SqliteTaskStore::open(":memory:").expect("open");
        let expr = "2020-01-01 08:00:00";
        let fired_at = Timestamp::new(1_577_880_000);
        let id = planning::add_task(&mut store, "once".into(), expr, None).unwrap();
        store.set_next_occurrence_for_test(id, fired_at.as_u64());

        let results = process_all_due(
            &mut store,
            Timestamp::new(fired_at.as_u64() + 3600),
        )
        .expect("process");
        assert_eq!(results.len(), 1);
        assert!(matches!(results[0], DueProcessResult::Finished(_)));

        let row = store.list_tasks().unwrap().into_iter().find(|r| r.id == id).unwrap();
        assert_eq!(row.status, TaskStatus::Finished);
        assert!(row.next_occurrence_unix.is_none());
    }

    #[test]
    fn watcher_emits_expired_event_for_due_task() {
        let mut store = SqliteTaskStore::open(":memory:").expect("open");
        let now = Timestamp::new(1_700_000_000);
        let id = planning::add_task(&mut store, "due".into(), "minutely", None).unwrap();
        store.set_next_occurrence_for_test(id, now.as_u64().saturating_sub(120));

        let repo = Arc::new(Mutex::new(store));
        let mut watcher =
            TaskExpiryWatcher::with_interval(Arc::clone(&repo), Duration::from_millis(50));
        let rx = watcher.start();

        let event = rx
            .recv_timeout(Duration::from_secs(2))
            .expect("recv timeout");
        watcher.stop();

        match event {
            TaskLifecycleEvent::Expired { task } => {
                assert_eq!(task.id, id);
                assert_eq!(task.status, TaskStatus::Expired);
            }
            other => panic!("expected Expired, got {other:?}"),
        }

        let store = repo.lock().unwrap();
        let row = store
            .list_tasks()
            .unwrap()
            .into_iter()
            .find(|r| r.id == id)
            .unwrap();
        assert_eq!(row.status, TaskStatus::Active);
    }
}
