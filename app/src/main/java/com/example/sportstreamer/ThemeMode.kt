package com.example.sportstreamer

import androidx.appcompat.app.AppCompatDelegate

/**
 * Выбор темы приложения: как в системе, всегда светлая, всегда тёмная.
 *
 * Значения хранятся числами в настройках, а `nightMode` переводит их в константы
 * AppCompatDelegate — так логику выбора можно проверить тестами.
 */
object ThemeMode {

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    val OPTIONS = listOf(SYSTEM, LIGHT, DARK)

    fun nightMode(mode: Int): Int = when (mode) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
