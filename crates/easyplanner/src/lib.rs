//! Core planner library: recurrence parsing, timestamps, and optional SQLite persistence.
//! UI and FFI live in other crates so this stays usable from tests and non-Android targets.

pub mod calendar;
pub mod model;

#[cfg(feature = "storage-sqlite")]
pub mod scheduler;

#[cfg(feature = "storage-sqlite")]
pub use scheduler::{
    SchedulerError, SqliteTaskScheduler, Task, TaskLifecycleEvent, TaskScheduler, TaskState,
    schedule_summary,
};

#[cfg(feature = "storage-sqlite")]
pub(crate) mod store;
