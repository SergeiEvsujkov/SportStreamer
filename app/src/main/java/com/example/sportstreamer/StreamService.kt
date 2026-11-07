package com.example.sportstreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Фоновый сервис на время эфира или записи.
 *
 * Зачем он нужен: начиная с Android 9 камерой нельзя пользоваться, когда приложение
 * не на экране, если не запущен foreground-сервис с типом `camera`. Без него свёртывание
 * или переход в настройки обрывали съёмку. Сервис держит процесс в нужном состоянии,
 * а всю работу с камерой по-прежнему делает пульт.
 *
 * Поведение не меняет: пока сервис запущен, на экране висит обычное уведомление
 * «Идёт эфир / запись».
 */
class StreamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "stream_session"
        private const val NOTIFICATION_ID = 42

        /** Запускает сервис, если сессия (эфир или запись) началась. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamService::class.java)
            )
        }

        /** Останавливает сервис, когда сессия закончилась. */
        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
