//! SQLite persistence (crate-private). Use [`TaskScheduler`](crate::scheduler::TaskScheduler) from other modules.

mod repository;
mod sqlite;

pub(crate) use repository::{StoreError, TaskRepository, TaskRow, TaskStatus};
pub(crate) use sqlite::SqliteTaskStore;
