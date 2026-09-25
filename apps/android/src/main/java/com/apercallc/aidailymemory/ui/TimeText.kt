package com.apercallc.aidailymemory.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Clock times in the user's locale *and* their 12/24-hour setting, which java.time ignores. */
object TimeText {
    fun formatter(is24Hour: Boolean, locale: java.util.Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)

    @Composable
    fun rememberFormatter(): (ZonedDateTime) -> String {
        val formatter = rememberTimeFormatter()
        return remember(formatter) { { time: ZonedDateTime -> time.format(formatter) } }
    }

    @Composable
    fun rememberLocalFormatter(): (LocalTime) -> String {
        val formatter = rememberTimeFormatter()
        return remember(formatter) { { time: LocalTime -> time.format(formatter) } }
    }

    @Composable
    private fun rememberTimeFormatter(): DateTimeFormatter {
        val context = LocalContext.current
        val locale = LocalConfiguration.current.locales[0]
        val is24Hour = DateFormat.is24HourFormat(context)
        return remember(is24Hour, locale) { formatter(is24Hour, locale) }
    }
}
