package com.example.sportstreamer

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `системная тема следует за настройками устройства`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.nightMode(ThemeMode.SYSTEM))
    }

    @Test
    fun `светлая тема всегда светлая`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.nightMode(ThemeMode.LIGHT))
    }

    @Test
    fun `тёмная тема всегда тёмная`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.nightMode(ThemeMode.DARK))
    }

    @Test
    fun `неизвестное значение трактуется как системная тема`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.nightMode(-1))
        assertEquals(listOf(0, 1, 2), ThemeMode.OPTIONS)
    }
}
