package com.example.sportstreamer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Приложение применяет выбранную тему до создания первого экрана — иначе после старта
 * успевал мелькнуть экран с темой по умолчанию.
 */
class SportStreamerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val mode = SettingsRepository.load(this).themeMode
        AppCompatDelegate.setDefaultNightMode(ThemeMode.nightMode(mode))
    }
}
