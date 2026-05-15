package com.github.unldenis.easyplannerapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.github.unldenis.easyplannerapp.data.setThemeMode
import com.github.unldenis.easyplannerapp.data.themeModeFlow
import com.github.unldenis.easyplannerapp.ui.home.HomeScreen
import com.github.unldenis.easyplannerapp.ui.settings.SettingsScreen
import com.github.unldenis.easyplannerapp.ui.theme.EasyPlannerTheme
import com.github.unldenis.easyplannerapp.ui.theme.ThemeMode
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

/**
 * Single [androidx.compose.material3.Scaffold] owns the snackbar and bottom navigation so window
 * insets and the snackbar host stay coherent when a tab shows a dialog; stacking another scaffold
 * in tabs caused fragile inset/FAB behavior on some OEM builds.
 *
 * One [PlannerViewModel] is shared across the [androidx.navigation.compose.NavHost] so Home and
 * Settings use the same [com.github.unldenis.easyplanner.PlannerStore]; the native handle is
 * released from the ViewModel's `onCleared`, not from composable `DisposableEffect`, because the
 * store must outlive individual screens.
 *
 * Tab [androidx.compose.material3.NavigationBarItem] navigation uses `launchSingleTop` and
 * `restoreState` to avoid duplicate destinations and to keep tab state across configuration
 * changes where NavController restores its back stack.
 *
 * FFI failures are shown via a snackbar tied to `LaunchedEffect(error)` so the ViewModel only
 * exposes state; clearing after `showSnackbar` keeps a repeat of the same message observable.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val themeMode by context.themeModeFlow()
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val scope = rememberCoroutineScope()

            EasyPlannerTheme(themeMode = themeMode) {
                val snackbarHostState = remember { SnackbarHostState() }
                val plannerViewModel: PlannerViewModel = viewModel()
                val tasks by plannerViewModel.tasks.collectAsStateWithLifecycle()
                val error by plannerViewModel.error.collectAsStateWithLifecycle()

                LaunchedEffect(error) {
                    val message = error ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    plannerViewModel.consumeError()
                }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar {
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
                            )
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                viewModel = plannerViewModel,
                                tasks = tasks,
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
                    }
                }
            }
        }
    }
}
