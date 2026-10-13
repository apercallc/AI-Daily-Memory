package com.apercallc.aidailymemory.recording.appearance

/** Light, dark, or follow the phone. Following the phone is the default. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** [systemIsDark] is what the phone currently asks for. */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val Default = SYSTEM

        /** Unknown or missing stored values fall back to following the phone. */
        fun parse(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: Default
    }
}
