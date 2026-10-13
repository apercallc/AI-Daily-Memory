package com.apercallc.aidailymemory.recording.appearance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeModeTest {
    @Test
    fun `following the phone is the default`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.Default)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("PURPLE"))
    }

    @Test
    fun `stored names round-trip`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.parse(it.name)) }
    }

    @Test
    fun `light and dark ignore the phone, system follows it`() {
        assertFalse(ThemeMode.LIGHT.isDark(systemIsDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemIsDark = false))
        assertTrue(ThemeMode.SYSTEM.isDark(systemIsDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemIsDark = false))
    }
}
