package com.github.unldenis.easyplannerapp.ui.theme

/**
 * Fixed light or dark appearance (no "follow system"). Legacy `system` preference values are
 * treated as [LIGHT] so upgrades stay predictable.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    ;

    fun toPreferenceString(): String = name.lowercase()

    companion object {
        fun fromPreferenceString(raw: String?): ThemeMode =
            when (raw?.lowercase()) {
                "dark" -> DARK
                "light", "system", null, "" -> LIGHT
                else -> LIGHT
            }
    }
}
