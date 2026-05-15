package com.github.unldenis.easyplannerapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.unldenis.easyplanner.PlannerStore
import com.github.unldenis.easyplanner.PlannerTask
import com.github.unldenis.easyplannerapp.notifications.TaskReminderNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the Bolt [PlannerStore] for the activity scope and mirrors task rows into [StateFlow] so
 * Compose reads one observable list instead of calling JNI from arbitrary composables.
 *
 * The DB path is under [Application.getFilesDir]: no storage permission, and the file survives
 * process death until the user clears app data.
 *
 * [PlannerStore.close] runs from [onCleared] because the store must outlive individual screens;
 * tying native teardown to the shared ViewModel matches how [MainActivity] scopes this instance.
 *
 * After each mutation we reload the full list so the UI cannot briefly show a row that was
 * already removed if a refresh raced the database.
 *
 * Catching [Throwable] (not only [com.github.unldenis.easyplanner.FfiException]) covers JNI
 * failures that sometimes surface as generic runtime types on ART.
 *
 * The Rust background poller is started from the activity while it is at least [androidx.lifecycle.Lifecycle.State.STARTED]
 * and stopped in `finally` when leaving that state ([MainActivity]); [onCleared] still stops before [PlannerStore.close]
 * so the native thread joins if the VM is torn down abruptly.
 */
class PlannerViewModel(application: Application) : AndroidViewModel(application) {

    private val store =
        PlannerStore(File(application.filesDir, "easyplanner.db").absolutePath)

    private val _tasks = MutableStateFlow<List<PlannerTask>>(emptyList())
    val tasks: StateFlow<List<PlannerTask>> = _tasks.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()

    private val initialRefreshCommitted = AtomicBoolean(false)

    init {
        refresh()
    }

    fun consumeError() {
        _error.value = null
    }

    /** Runs JNI on [Dispatchers.Default]; safe to call from [androidx.lifecycle.repeatOnLifecycle]. */
    suspend fun syncStartSchedulerLoop() =
        withContext(Dispatchers.Default) {
            try {
                store.startSchedulerLoop()
            } catch (e: Throwable) {
                _error.value = e.message ?: e.toString()
            }
        }

    suspend fun syncStopSchedulerLoop() =
        withContext(Dispatchers.Default) {
            try {
                store.stopSchedulerLoop()
            } catch (_: Throwable) {
                // Best-effort on background / teardown.
            }
        }

    /**
     * Drains queued native watcher events ([PlannerStore.pollSchedulerEvents]) and shows
     * notifications when [TaskReminderNotifications.canPost] allows.
     */
    suspend fun pollSchedulerEventsAndNotify() {
        val events =
            try {
                withContext(Dispatchers.Default) {
                    store.pollSchedulerEvents()
                }
            } catch (_: Throwable) {
                emptyList()
            }
        if (events.isEmpty()) return

        val app = getApplication<Application>()
        withContext(Dispatchers.Main.immediate) {
            TaskReminderNotifications.notifyForEvents(app, events)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _tasks.value =
                    withContext(Dispatchers.Default) {
                        store.listTasks()
                    }
            } catch (e: Throwable) {
                _error.value = e.message ?: e.toString()
            } finally {
                if (initialRefreshCommitted.compareAndSet(false, true)) {
                    _initialLoadComplete.value = true
                }
            }
        }
    }

    fun addTask(
        title: String,
        notes: String,
        calendarExpr: String,
        wallClockTzIana: String,
    ) {
        viewModelScope.launch {
            try {
                store.addTask(
                    title.trim(),
                    notes.trim(),
                    calendarExpr.trim(),
                    wallClockTzIana.trim(),
                )
                _tasks.value = store.listTasks()
            } catch (e: Throwable) {
                _error.value = e.message ?: e.toString()
            }
        }
    }

    fun updateTask(
        id: Long,
        title: String,
        notes: String,
        calendarExpr: String,
        wallClockTzIana: String,
    ) {
        viewModelScope.launch {
            try {
                store.updateTask(
                    id,
                    title.trim(),
                    notes.trim(),
                    calendarExpr.trim(),
                    wallClockTzIana.trim(),
                )
                _tasks.value = store.listTasks()
            } catch (e: Throwable) {
                _error.value = e.message ?: e.toString()
            }
        }
    }

    fun setTaskEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            try {
                store.setTaskEnabled(id, enabled)
                _tasks.value = store.listTasks()
            } catch (e: Throwable) {
                _error.value = e.message ?: e.toString()
            }
        }
    }

    fun deleteTask(id: Long) {
        viewModelScope.launch {
            try {
                store.deleteTask(id)
                _tasks.value = store.listTasks()
            } catch (e: Throwable) {
                _error.value = e.message ?: e.toString()
            }
        }
    }

    override fun onCleared() {
        runBlocking(Dispatchers.Default) {
            try {
                store.stopSchedulerLoop()
            } catch (_: Throwable) {
            }
        }
        store.close()
        super.onCleared()
    }
}
