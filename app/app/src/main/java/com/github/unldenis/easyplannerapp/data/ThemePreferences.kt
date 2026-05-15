package com.github.unldenis.easyplannerapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.unldenis.easyplannerapp.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Theme uses DataStore so it survives process death; a dedicated store name avoids key clashes later. */
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "easy_planner_theme")

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

fun Context.themeModeFlow(): Flow<ThemeMode> =
    themeDataStore.data.map { prefs ->
        ThemeMode.fromPreferenceString(prefs[THEME_MODE_KEY])
    }

suspend fun Context.setThemeMode(mode: ThemeMode) {
    themeDataStore.edit { prefs ->
        prefs[THEME_MODE_KEY] = mode.toPreferenceString()
    }
}
