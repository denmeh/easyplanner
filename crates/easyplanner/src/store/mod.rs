//! SQLite persistence. Gated behind `storage-sqlite` so the rest of `easyplanner` stays usable
//! without linking SQLite (tests or future backends).

mod repository;
mod sqlite;

pub use repository::{StoreError, TaskRepository, TaskRow, TaskStatus};
pub use sqlite::SqliteTaskStore;
