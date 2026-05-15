package com.github.unldenis.easyplannerapp.ui.theme

/**
 * Three-way appearance: [SYSTEM] tracks night mode; fixed light/dark ignore the device toggle so
 * the user can override (e.g. daylight readability or OLED preference) independent of system UI.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun toPreferenceString(): String = name.lowercase()

    companion object {
        fun fromPreferenceString(raw: String?): ThemeMode =
            when (raw?.lowercase()) {
                "light" -> LIGHT
                "dark" -> DARK
                else -> SYSTEM
            }
    }
}
