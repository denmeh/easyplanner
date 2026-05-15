package com.github.unldenis.easyplannerapp.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import java.util.Date
import java.util.Locale

private val nextTimeFormat = SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault())

private enum class TaskLifecycleUi {
    ACTIVE,
    EXPIRED,
    FINISHED,
}

private fun PlannerTask.lifecycleUi(): TaskLifecycleUi =
    when (state.lowercase(Locale.US)) {
        "expired" -> TaskLifecycleUi.EXPIRED
        "finished" -> TaskLifecycleUi.FINISHED
        else -> TaskLifecycleUi.ACTIVE
    }

/** Delete dialog keeps primitives so the row object is not held across JNI + list refresh. */
private data class PendingDelete(val id: Long, val description: String)

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
    onNavigateToAddTask: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            onClick = onNavigateToAddTask,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_task))
        }
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
    val life = task.lifecycleUi()
    val outlineColor =
        when (life) {
            TaskLifecycleUi.EXPIRED ->
                MaterialTheme.colorScheme.error.copy(alpha = 0.88f)
            TaskLifecycleUi.FINISHED ->
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            TaskLifecycleUi.ACTIVE ->
                MaterialTheme.colorScheme.outlineVariant
        }
    val outlineWidth =
        when (life) {
            TaskLifecycleUi.EXPIRED -> 2.dp
            TaskLifecycleUi.FINISHED, TaskLifecycleUi.ACTIVE -> 1.dp
        }
    val cardColors =
        when (life) {
            TaskLifecycleUi.EXPIRED ->
                CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                )
            TaskLifecycleUi.FINISHED ->
                CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            TaskLifecycleUi.ACTIVE ->
                CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
        }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(outlineWidth, outlineColor),
        colors = cardColors,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (life == TaskLifecycleUi.FINISHED) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
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
            when (life) {
                TaskLifecycleUi.EXPIRED -> {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = { },
                        enabled = false,
                        label = { Text(stringResource(R.string.task_status_expired)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = null,
                            )
                        },
                        colors =
                            AssistChipDefaults.assistChipColors(
                                disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                                disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                                disabledLeadingIconContentColor =
                                    MaterialTheme.colorScheme.error,
                            ),
                    )
                    Text(
                        text = stringResource(R.string.task_status_expired_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.92f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                TaskLifecycleUi.FINISHED -> {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = { },
                        enabled = false,
                        label = { Text(stringResource(R.string.task_status_finished)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Flag,
                                contentDescription = null,
                            )
                        },
                        colors =
                            AssistChipDefaults.assistChipColors(
                                disabledContainerColor =
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                disabledLeadingIconContentColor =
                                    MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                    )
                    Text(
                        text = stringResource(R.string.task_status_finished_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                TaskLifecycleUi.ACTIVE -> Unit
            }
            Text(
                text = task.calendarExpr,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
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
