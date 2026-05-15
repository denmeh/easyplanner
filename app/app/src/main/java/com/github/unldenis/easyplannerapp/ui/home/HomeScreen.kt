package com.github.unldenis.easyplannerapp.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.unldenis.easyplanner.PlannerTask
import com.github.unldenis.easyplannerapp.PlannerViewModel
import com.github.unldenis.easyplannerapp.R
import java.text.SimpleDateFormat
import java.util.Comparator
import java.util.Date
import java.util.Locale

private val nextTimeFormat = SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault())

private enum class TaskLifecycleUi {
    ACTIVE,
    EXPIRED,
    FINISHED,
}

private enum class TaskListFilter {
    ALL,
    OPEN,
    FINISHED,
}

private fun PlannerTask.lifecycleUi(): TaskLifecycleUi =
    when (state.lowercase(Locale.US)) {
        "expired" -> TaskLifecycleUi.EXPIRED
        "finished" -> TaskLifecycleUi.FINISHED
        else -> TaskLifecycleUi.ACTIVE
    }

private fun PlannerTask.matchesFilter(filter: TaskListFilter): Boolean =
    when (filter) {
        TaskListFilter.ALL -> true
        TaskListFilter.OPEN -> lifecycleUi() != TaskLifecycleUi.FINISHED
        TaskListFilter.FINISHED -> lifecycleUi() == TaskLifecycleUi.FINISHED
    }

private fun PlannerTask.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.lowercase(Locale.getDefault())
    return title.lowercase(Locale.getDefault()).contains(q) ||
        description.lowercase(Locale.getDefault()).contains(q) ||
        calendarExpr.lowercase(Locale.getDefault()).contains(q)
}

private val taskDisplayComparator =
    Comparator<PlannerTask> { a, b ->
        val af = a.lifecycleUi() == TaskLifecycleUi.FINISHED
        val bf = b.lifecycleUi() == TaskLifecycleUi.FINISHED
        if (af != bf) return@Comparator if (af) 1 else -1
        if (!af) {
            val an = a.nextOccurrenceUnix
            val bn = b.nextOccurrenceUnix
            if (an == null && bn == null) {
                return@Comparator a.title.compareTo(b.title, ignoreCase = true)
            }
            if (an == null) return@Comparator 1
            if (bn == null) return@Comparator -1
            val c = an.compareTo(bn)
            if (c != 0) return@Comparator c
        }
        a.title.compareTo(b.title, ignoreCase = true)
    }

/** Delete dialog keeps primitives so the row object is not held across JNI + list refresh. */
private data class PendingDelete(val id: Long, val title: String)

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
    onNavigateToEditTask: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var listFilter by remember { mutableStateOf(TaskListFilter.ALL) }

    val displayedTasks =
        remember(tasks, searchQuery, listFilter) {
            tasks
                .asSequence()
                .filter { it.matchesQuery(searchQuery) }
                .filter { it.matchesFilter(listFilter) }
                .sortedWith(taskDisplayComparator)
                .toList()
        }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_home)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_tasks)) },
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = listFilter == TaskListFilter.ALL,
                    onClick = { listFilter = TaskListFilter.ALL },
                    label = { Text(stringResource(R.string.filter_all)) },
                )
                FilterChip(
                    selected = listFilter == TaskListFilter.OPEN,
                    onClick = { listFilter = TaskListFilter.OPEN },
                    label = { Text(stringResource(R.string.filter_open)) },
                )
                FilterChip(
                    selected = listFilter == TaskListFilter.FINISHED,
                    onClick = { listFilter = TaskListFilter.FINISHED },
                    label = { Text(stringResource(R.string.filter_finished)) },
                )
            }
            when {
                tasks.isEmpty() -> {
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
                }

                displayedTasks.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.home_no_matches),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.home_no_matches_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(displayedTasks, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                onOpen = { onNavigateToEditTask(task.id) },
                                onDelete = {
                                    pendingDelete = PendingDelete(task.id, task.title)
                                },
                                onEnabledChange = { enabled ->
                                    viewModel.setTaskEnabled(task.id, enabled)
                                },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onNavigateToAddTask,
            modifier =
                Modifier
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
                        pending.title,
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
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val activeToggleLabel = stringResource(R.string.task_active_toggle)
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(onClick = onOpen),
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (life == TaskLifecycleUi.FINISHED) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                    if (task.description.isNotBlank()) {
                        Text(
                            text = task.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (life != TaskLifecycleUi.FINISHED) {
                    val scheme = MaterialTheme.colorScheme
                    Switch(
                        checked = task.enabled,
                        onCheckedChange = onEnabledChange,
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = scheme.onPrimary,
                                checkedTrackColor = scheme.primary,
                                uncheckedThumbColor = scheme.onSurface,
                                uncheckedTrackColor = scheme.surfaceContainerHigh,
                                uncheckedBorderColor = scheme.outline,
                            ),
                        modifier =
                            Modifier.semantics {
                                contentDescription = activeToggleLabel
                            },
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_task),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!task.enabled && life != TaskLifecycleUi.FINISHED) {
                Spacer(Modifier.height(8.dp))
                AssistChip(
                    onClick = { },
                    enabled = false,
                    label = { Text(stringResource(R.string.task_paused)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                        )
                    },
                    colors =
                        AssistChipDefaults.assistChipColors(
                            disabledContainerColor =
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
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
                text = task.scheduleSummary,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .clickable(onClick = onOpen),
            )
            val zoneLabel = task.wallClockTz?.takeIf { it.isNotBlank() } ?: "UTC"
            Text(
                text = stringResource(R.string.task_wall_clock_tz, zoneLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text =
                    stringResource(
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
