//! Core planner library: recurrence parsing, timestamps, and optional SQLite persistence.
//! UI and FFI live in other crates so this stays usable from tests and non-Android targets.

pub mod calendar;
pub mod model;

#[cfg(feature = "storage-sqlite")]
pub mod store;

#[cfg(feature = "storage-sqlite")]
pub mod planning;

#[cfg(feature = "storage-sqlite")]
pub mod scheduler;
