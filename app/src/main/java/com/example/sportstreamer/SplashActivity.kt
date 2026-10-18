package com.example.sportstreamer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Загрузочный экран в вертикальной ориентации.
 *
 * Пульт работает только в ландшафте, поэтому отдельная активити с портретом — единственный
 * способ показать системный splash вертикально. Экран держится недолго и открывает пульт.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val openApp = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handler.postDelayed(openApp, SPLASH_DURATION_MS)
        } else {
            // Пересоздание (смена конфигурации, восстановление процесса, многооконный режим):
            // ждать нечего, иначе этот экран остался бы навсегда.
            openApp.run()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(openApp)
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_DURATION_MS = 700L
    }
}
