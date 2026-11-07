package com.example.sportstreamer

import androidx.appcompat.app.AppCompatActivity

/**
 * Заглушка для сборки без RuStore: приложение обновляется вручную.
 * Интерфейс совпадает с версией для RuStore, чтобы вызывающий код был общим.
 */
object AppUpdater {

    fun checkForUpdate(activity: AppCompatActivity) {
        // Магазина в этой сборке нет — проверять нечего.
    }
}
