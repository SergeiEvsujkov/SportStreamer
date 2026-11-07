package com.example.sportstreamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.sportstreamer.databinding.ActivitySettingsBinding

/**
 * Экран настроек для первого запуска, когда пульт ещё не настроен.
 *
 * Сам экран живёт в [SettingsPanel]; здесь только окно, insets и запрос разрешений.
 * При обычной работе настройки показываются панелью поверх пульта — так запись и эфир
 * не прерываются при переходе в настройки.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var panel: SettingsPanel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.RECORD_AUDIO] == true
        if (!granted) {
            Toast.makeText(this, R.string.permissions_camera_audio_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        panel = SettingsPanel(
            activity = this,
            binding = binding,
            onClose = { finish() },
            onThemeSelected = { mode ->
                // Здесь пересоздание экрана безопасно: камера и запись ещё не запущены.
                AppCompatDelegate.setDefaultNightMode(ThemeMode.nightMode(mode))
            },
            styleWindow = true
        )
        panel.attach()

        applyWindowInsets()
        ViewCompat.requestApplyInsets(binding.root)
        ensurePermissions()
    }

    override fun onPause() {
        panel.commit()
        super.onPause()
    }

    private fun ensurePermissions() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }
}
