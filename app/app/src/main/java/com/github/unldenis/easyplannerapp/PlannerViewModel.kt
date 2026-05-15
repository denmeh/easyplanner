package com.github.unldenis.easyplannerapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.unldenis.easyplanner.PlannerStore
import com.github.unldenis.easyplanner.PlannerTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

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
 */
class PlannerViewModel(application: Application) : AndroidViewModel(application) {

    private val store =
        PlannerStore(File(application.filesDir, "easyplanner.db").absolutePath)

    private val _tasks = MutableStateFlow<List<PlannerTask>>(emptyList())
    val tasks: StateFlow<List<PlannerTask>> = _tasks.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun consumeError() {
        _error.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _tasks.value = store.listTasks()
            } catch (e: Throwable) {
                _error.value = e.message ?: e.toString()
            }
        }
    }

    fun addTask(description: String, calendarExpr: String) {
        viewModelScope.launch {
            try {
                store.addTask(description.trim(), calendarExpr.trim())
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
        super.onCleared()
        store.close()
    }
}
