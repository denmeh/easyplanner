package com.github.unldenis.easyplannerapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.unldenis.easyplanner.PlannerTask
import com.github.unldenis.easyplannerapp.PlannerViewModel
import com.github.unldenis.easyplannerapp.R
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    navController: NavController,
    viewModel: PlannerViewModel,
    tasks: List<PlannerTask>,
    editingTaskId: Long? = null,
    modifier: Modifier = Modifier,
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(SchedulePreset.Minutely) }
    var customSchedule by remember { mutableStateOf("") }
    var scheduleMenuExpanded by remember { mutableStateOf(false) }
    var wallClockTz by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var showCalendarHelp by remember { mutableStateOf(false) }
    var lastPrefilledEditId by remember { mutableLongStateOf(0L) }

    LaunchedEffect(editingTaskId, tasks) {
        val id = editingTaskId ?: run {
            lastPrefilledEditId = 0L
            return@LaunchedEffect
        }
        if (lastPrefilledEditId == id) return@LaunchedEffect
        val t = tasks.find { it.id == id } ?: return@LaunchedEffect
        lastPrefilledEditId = id
        title = t.title
        notes = t.description
        val (p, expr) = SchedulePreset.fromCalendarExpr(t.calendarExpr)
        preset = p
        customSchedule = if (p == SchedulePreset.Other) expr else ""
        wallClockTz = t.wallClockTz?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id
    }

    val resolvedSchedule =
        preset.toCalendarExpr()?.trim().orEmpty().ifEmpty { customSchedule.trim() }
    val canSave = title.isNotBlank() && resolvedSchedule.isNotBlank()
    val isEditing = editingTaskId != null

    if (showCalendarHelp) {
        AlertDialog(
            onDismissRequest = { showCalendarHelp = false },
            title = { Text(stringResource(R.string.calendar_help)) },
            text = {
                Text(
                    stringResource(R.string.calendar_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showCalendarHelp = false }) {
                    Text(stringResource(R.string.calendar_help_close))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEditing) {
                            stringResource(R.string.edit_task)
                        } else {
                            stringResource(R.string.add_task)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_navigate_up),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val editId = editingTaskId
                            if (editId != null) {
                                viewModel.updateTask(
                                    editId,
                                    title,
                                    notes,
                                    resolvedSchedule,
                                    wallClockTz,
                                )
                            } else {
                                viewModel.addTask(
                                    title,
                                    notes,
                                    resolvedSchedule,
                                    wallClockTz,
                                )
                            }
                            navController.popBackStack()
                        },
                        enabled = canSave,
                    ) { Text(stringResource(R.string.save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.field_notes)) },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { showCalendarHelp = true },
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(stringResource(R.string.calendar_help))
            }
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
    }
}
