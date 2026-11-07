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
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
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
        }

        try {
            // Принудительно 2.4 ГГц: по умолчанию система может поднять точку на 5 ГГц,
            // и устройства без этого диапазона (часто ПК) её просто не видят.
            if (!startOn24GHz(wifi, callback)) {
                wifi.startLocalOnlyHotspot(callback, main)
            }
        } catch (e: Exception) {
            starting = false
            Log.e(TAG, "Ошибка запуска точки доступа", e)
            listener.onFailed("Не удалось включить точку доступа")
        }
    }

    /**
     * Запускает точку доступа на 2.4 ГГц.
     *
     * Вызов идёт через отражение: метод с конфигурацией появился в API 30, а компилятор
     * в этом проекте видит только старую пару аргументов. Отражение позволяет не терять
     * диапазон, а при любой ошибке мы откатываемся на обычный запуск.
     *
     * @return true, если удалось запустить с конфигурацией.
     */
    private fun startOn24GHz(
        wifi: WifiManager,
        callback: WifiManager.LocalOnlyHotspotCallback
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setBand", Int::class.javaPrimitiveType)
                .invoke(builder, BAND_2GHZ)
            val config = builderClass.getMethod("build").invoke(builder)!!
            val method = WifiManager::class.java.getMethod(
                "startLocalOnlyHotspot",
                config.javaClass,
                java.util.concurrent.Executor::class.java,
                WifiManager.LocalOnlyHotspotCallback::class.java
            )
            method.invoke(wifi, config, java.util.concurrent.Executor { it.run() }, callback)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Диапазон 2.4 ГГц задать не удалось, запускаю по умолчанию", e)
            false
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

        /** BAND_2GHZ из SoftApConfiguration: 1. */
        const val BAND_2GHZ = 1
    }
}
