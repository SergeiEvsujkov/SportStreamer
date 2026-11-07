package com.example.sportstreamer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Локальная точка доступа на телефоне-источнике.
 *
 * Обычный хот-спот приложению недоступен (нужно системное разрешение), но `startLocalOnlyHotspot`
 * разрешён: сеть создаётся без интернета — ровно для связи двух телефонов на площадке.
 * Живёт, пока живёт приложение: закрыли — точка закрывается.
 */
class RemoteHotspot(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onReady(ssid: String, password: String)
        fun onFailed(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var starting = false

    /** Запускает точку доступа; результат придёт в [Listener] на главном потоке. */
    fun start() {
        if (reservation != null || starting) return
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            listener.onFailed("Wi-Fi недоступен")
            return
        }
        starting = true
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        starting = false
                        this@RemoteHotspot.reservation = reservation
                        val ssid: String
                        val password: String
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val config = reservation.softApConfiguration
                            ssid = config?.ssid.orEmpty()
                            password = config?.passphrase.orEmpty()
                        } else {
                            @Suppress("DEPRECATION")
                            val config = reservation.wifiConfiguration
                            @Suppress("DEPRECATION")
                            ssid = config?.SSID.orEmpty()
                            @Suppress("DEPRECATION")
                            password = config?.preSharedKey.orEmpty()
                        }
                        Log.i(TAG, "Точка доступа поднята: $ssid")
                        main.post { listener.onReady(ssid, password) }
                    }

                    override fun onFailed(reason: Int) {
                        starting = false
                        Log.w(TAG, "Точка доступа не поднялась, код $reason")
                        main.post { listener.onFailed("Не удалось включить точку доступа (код $reason)") }
                    }

                    override fun onStopped() {
                        starting = false
                        reservation = null
                        Log.i(TAG, "Точка доступа остановлена")
                    }
                },
                main
            )
        } catch (e: Exception) {
            starting = false
            Log.e(TAG, "Ошибка запуска точки доступа", e)
            listener.onFailed("Не удалось включить точку доступа")
        }
    }

    /** Закрывает точку доступа. */
    fun stop() {
        try {
            reservation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки точки доступа", e)
        }
        reservation = null
    }

    private companion object {
        const val TAG = "RemoteHotspot"
    }
}
