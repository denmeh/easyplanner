package com.github.unldenis.easyplannerapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.unldenis.easyplanner.PlannerTask
import com.github.unldenis.easyplannerapp.PlannerViewModel
import com.github.unldenis.easyplannerapp.R
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val nextTimeFormat = SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault())

/** Delete dialog keeps primitives so the row object is not held across JNI + list refresh. */
private data class PendingDelete(val id: Long, val description: String)

private enum class SchedulePreset {
    Minutely,
    Hourly,
    Daily,
    Weekly,
    Monthly,
    Other,
    ;

    fun toCalendarExpr(): String? =
        when (this) {
            Minutely -> "minutely"
            Hourly -> "hourly"
            Daily -> "daily"
            Weekly -> "weekly"
            Monthly -> "monthly"
            Other -> null
        }
}

@Composable
private fun SchedulePreset.displayLabel(): String =
    when (this) {
        SchedulePreset.Minutely -> stringResource(R.string.schedule_minutely)
        SchedulePreset.Hourly -> stringResource(R.string.schedule_hourly)
        SchedulePreset.Daily -> stringResource(R.string.schedule_daily)
        SchedulePreset.Weekly -> stringResource(R.string.schedule_weekly)
        SchedulePreset.Monthly -> stringResource(R.string.schedule_monthly)
        SchedulePreset.Other -> stringResource(R.string.schedule_other)
    }

/**
 * Task tab: DB work stays in the ViewModel; this layer only renders [tasks] and routes user
 * actions. Uses [Box] + [TopAppBar] instead of a nested [androidx.compose.material3.Scaffold]
 * under [MainActivity] to avoid inset/FAB bugs when dialogs dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PlannerViewModel,
    tasks: List<PlannerTask>,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_home)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            if (tasks.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.home_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onDelete = {
                                pendingDelete = PendingDelete(task.id, task.description)
                            },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_task))
        }
    }

    if (showAdd) {
        AddTaskDialog(
            onDismiss = { showAdd = false },
            onConfirm = { title, schedule, wallClockTz ->
                viewModel.addTask(title, schedule, wallClockTz)
                showAdd = false
            },
        )
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_task_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_task_message,
                        pending.description,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = pending.id
                        pendingDelete = null
                        viewModel.deleteTask(id)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: PlannerTask,
    onDelete: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_task),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = task.calendarExpr,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            val zoneLabel = task.wallClockTz?.takeIf { it.isNotBlank() } ?: "UTC"
            Text(
                text = stringResource(R.string.task_wall_clock_tz, zoneLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = stringResource(
                    R.string.task_next_occurrence,
                    formatNext(task.nextOccurrenceUnix),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun formatNext(epoch: ULong?): String =
    if (epoch == null) {
        "—"
    } else {
        nextTimeFormat.format(Date(epoch.toLong() * 1000L))
    }

@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(SchedulePreset.Minutely) }
    var customSchedule by remember { mutableStateOf("") }
    var scheduleMenuExpanded by remember { mutableStateOf(false) }
    var wallClockTz by remember { mutableStateOf(ZoneId.systemDefault().id) }

    val resolvedSchedule =
        preset.toCalendarExpr()?.trim().orEmpty().ifEmpty { customSchedule.trim() }
    val canSave = title.isNotBlank() && resolvedSchedule.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = preset.displayLabel(),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.field_schedule)) },
                        supportingText = {
                            Text(stringResource(R.string.field_schedule_dropdown_hint))
                        },
                        trailingIcon = {
                            IconButton(onClick = { scheduleMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.field_schedule),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = scheduleMenuExpanded,
                        onDismissRequest = { scheduleMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SchedulePreset.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayLabel()) },
                                onClick = {
                                    preset = option
                                    scheduleMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                if (preset == SchedulePreset.Other) {
                    OutlinedTextField(
                        value = customSchedule,
                        onValueChange = { customSchedule = it },
                        label = { Text(stringResource(R.string.field_custom_schedule)) },
                        supportingText = { Text(stringResource(R.string.field_schedule_hint)) },
                        singleLine = false,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = wallClockTz,
                    onValueChange = { wallClockTz = it },
                    label = { Text(stringResource(R.string.field_wall_clock_tz)) },
                    supportingText = { Text(stringResource(R.string.field_wall_clock_tz_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, resolvedSchedule, wallClockTz) },
                enabled = canSave,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
