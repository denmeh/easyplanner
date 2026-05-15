package com.github.unldenis.easyplannerapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.unldenis.easyplannerapp.data.setThemeMode
import com.github.unldenis.easyplannerapp.data.themeModeFlow
import com.github.unldenis.easyplannerapp.ui.home.AddTaskScreen
import com.github.unldenis.easyplannerapp.ui.home.HomeScreen
import com.github.unldenis.easyplannerapp.ui.settings.SettingsScreen
import com.github.unldenis.easyplannerapp.ui.theme.EasyPlannerTheme
import com.github.unldenis.easyplannerapp.ui.theme.ThemeMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ADD_TASK = "add_task"
    const val EDIT_TASK = "edit_task/{taskId}"

    fun editTask(taskId: Long): String = "edit_task/$taskId"
}

private fun isTaskEditorRoute(route: String?): Boolean =
    route == Routes.ADD_TASK || route?.startsWith("edit_task/") == true

private const val NavAnimMs = 300

/** Matches the native default poll interval so the list stays in sync when due tasks fire. */
private const val SchedulerUiRefreshMs = 30_000L

private const val SchedulerEventPollMs = 1_500L

/**
 * Single [androidx.compose.material3.Scaffold] owns the snackbar and bottom navigation so window
 * insets and the snackbar host stay coherent when a tab shows a dialog; stacking another scaffold
 * in tabs caused fragile inset/FAB behavior on some OEM builds.
 *
 * One [PlannerViewModel] is shared across the [androidx.navigation.compose.NavHost] so Home and
 * Settings use the same [com.github.unldenis.easyplanner.PlannerStore]; the native handle is
 * released from the ViewModel's `onCleared`, not from composable `DisposableEffect`, because the
 * store must outlive individual screens. While the activity is at least [androidx.lifecycle.Lifecycle.State.STARTED],
 * [MainActivity] runs [com.github.unldenis.easyplanner.PlannerStore.startSchedulerLoop] and stops it
 * when leaving that state; the poller updates SQLite; the UI reloads on a 30s ticker while started.
 * A faster poll drains [com.github.unldenis.easyplanner.PlannerStore.pollSchedulerEvents] (~1.5s) to
 * post system notifications when a run fires (needs runtime POST_NOTIFICATIONS on API 33+).
 *
 * Tab [androidx.compose.material3.NavigationBarItem] navigation uses `launchSingleTop` and
 * `restoreState` to avoid duplicate destinations and to keep tab state across configuration
 * changes where NavController restores its back stack.
 *
 * FFI failures are shown via a snackbar tied to `LaunchedEffect(error)` so the ViewModel only
 * exposes state; clearing after `showSnackbar` keeps a repeat of the same message observable.
 *
 * The Android 12+ splash stays visible until [PlannerViewModel.initialLoadComplete] so JNI/SQLite
 * work does not surface as an empty frame after the splash animation.
 */
class MainActivity : ComponentActivity() {
    private val plannerViewModel: PlannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !plannerViewModel.initialLoadComplete.value }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val themeMode by context.themeModeFlow()
                .collectAsStateWithLifecycle(initialValue = ThemeMode.LIGHT)
            val scope = rememberCoroutineScope()

            EasyPlannerTheme(themeMode = themeMode) {
                val snackbarHostState = remember { SnackbarHostState() }
                val tasks by plannerViewModel.tasks.collectAsStateWithLifecycle()
                val error by plannerViewModel.error.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                val postNotificationsLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { }

                LaunchedEffect(Unit) {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        plannerViewModel.syncStartSchedulerLoop()
                        plannerViewModel.refresh()
                        try {
                            coroutineScope {
                                launch {
                                    while (true) {
                                        delay(SchedulerUiRefreshMs)
                                        plannerViewModel.refresh()
                                    }
                                }
                                launch {
                                    while (true) {
                                        delay(SchedulerEventPollMs)
                                        plannerViewModel.pollSchedulerEventsAndNotify()
                                    }
                                }
                            }
                        } finally {
                            plannerViewModel.syncStopSchedulerLoop()
                        }
                    }
                }

                LaunchedEffect(error) {
                    val message = error ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    plannerViewModel.consumeError()
                }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                val navItemColors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!isTaskEditorRoute(currentDestination?.route)) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                            ) {
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Outlined.Home,
                                            contentDescription = stringResource(R.string.content_desc_home),
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_home)) },
                                    selected = currentDestination?.hierarchy?.any { it.route == Routes.HOME } == true,
                                    onClick = {
                                        navController.navigate(Routes.HOME) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors = navItemColors,
                                )
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Outlined.Settings,
                                            contentDescription = stringResource(R.string.content_desc_settings),
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_settings)) },
                                    selected = currentDestination?.hierarchy?.any { it.route == Routes.SETTINGS } == true,
                                    onClick = {
                                        navController.navigate(Routes.SETTINGS) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors = navItemColors,
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable(
                            Routes.HOME,
                            exitTransition = {
                                if (isTaskEditorRoute(targetState.destination.route)) {
                                    fadeOut(animationSpec = tween(NavAnimMs)) +
                                        slideOutHorizontally(
                                            animationSpec = tween(NavAnimMs),
                                            targetOffsetX = { -it / 5 },
                                        )
                                } else {
                                    null
                                }
                            },
                            popEnterTransition = {
                                if (isTaskEditorRoute(initialState.destination.route)) {
                                    fadeIn(animationSpec = tween(NavAnimMs)) +
                                        slideInHorizontally(
                                            animationSpec = tween(NavAnimMs),
                                            initialOffsetX = { -it / 5 },
                                        )
                                } else {
                                    null
                                }
                            },
                        ) {
                            HomeScreen(
                                viewModel = plannerViewModel,
                                tasks = tasks,
                                onNavigateToAddTask = {
                                    navController.navigate(Routes.ADD_TASK) {
                                        launchSingleTop = true
                                    }
                                },
                                onNavigateToEditTask = { taskId ->
                                    navController.navigate(Routes.editTask(taskId)) {
                                        launchSingleTop = true
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                themeMode = themeMode,
                                onThemeModeChange = { mode ->
                                    scope.launch { context.setThemeMode(mode) }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        composable(
                            Routes.ADD_TASK,
                            enterTransition = {
                                fadeIn(animationSpec = tween(NavAnimMs)) +
                                    slideInHorizontally(
                                        animationSpec = tween(NavAnimMs),
                                        initialOffsetX = { it },
                                    )
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(NavAnimMs)) +
                                    slideOutHorizontally(
                                        animationSpec = tween(NavAnimMs),
                                        targetOffsetX = { it },
                                    )
                            },
                        ) {
                            AddTaskScreen(
                                navController = navController,
                                viewModel = plannerViewModel,
                                tasks = tasks,
                                editingTaskId = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        composable(
                            Routes.EDIT_TASK,
                            arguments = listOf(navArgument("taskId") { type = NavType.LongType }),
                            enterTransition = {
                                fadeIn(animationSpec = tween(NavAnimMs)) +
                                    slideInHorizontally(
                                        animationSpec = tween(NavAnimMs),
                                        initialOffsetX = { it },
                                    )
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(NavAnimMs)) +
                                    slideOutHorizontally(
                                        animationSpec = tween(NavAnimMs),
                                        targetOffsetX = { it },
                                    )
                            },
                        ) { entry ->
                            val taskId =
                                entry.arguments?.getLong("taskId")
                                    ?: error("edit_task requires taskId")
                            AddTaskScreen(
                                navController = navController,
                                viewModel = plannerViewModel,
                                tasks = tasks,
                                editingTaskId = taskId,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
