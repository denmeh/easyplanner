//! Task persistence behind [`TaskRepository`].
//!
//! Import the trait to call `add_task` / `tasks_due_before` / `advance_task_after_fire` on a concrete store:
//! `use easyplanner::store::{SqliteTaskStore, TaskRepository};`

mod repository;
mod sqlite;

pub use repository::{StoreError, TaskRepository, TaskRow};
pub use sqlite::SqliteTaskStore;
