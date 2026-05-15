# Android JNI crash when a class method returns `Result<(), String>`

**BoltFFI 0.25.0** — Android app, Kotlin generated from a `#[export] impl` on a handle type.

A method was exported as:

```rust
pub fn delete_task(&self, id: i64) -> Result<(), String>
```

The SQLite delete completed, then the app crashed in native code before control returned to Kotlin. There was no `FfiException`; the process aborted during the JNI return.

The native stack trace showed `FfiBuf::from_vec` and `FfiBuf::wire_encode` under `boltffi_planner_store_delete_task`, then the JNI stub `Java_..._boltffi_planner_store_delete_task`. That points to the wire encoder handling the success branch (`Ok(())`), not application logic after the call.

Changing the signature to `Result<i64, String>` and returning `Ok(1)` on success fixed it. After `boltffi pack android`, Kotlin exposes `deleteTask(id): Long` with `@Throws(FfiException::class)`, and the crash no longer occurs.