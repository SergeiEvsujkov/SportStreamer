package com.example.sportstreamer

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Сервер удалённого пульта на телефоне, который снимает матч.
 *
 * Принимает одно подключение за раз: второй телефон отправляет команды счёта, сервер
 * проверяет код сопряжения и передаёт команды в UI-поток. Никакого интернета не нужно —
 * достаточно, чтобы телефоны были в одной сети.
 */
class RemoteScoreServer(
    private val context: android.content.Context,
    private val pin: String,
    private val listener: Listener
) {

    interface Listener {
        fun onPoint(team: Int, add: Boolean)
        fun onUndo()
        fun onSetDecision(newSet: Boolean)
        fun onSyncRequested()
        fun onClientConnected()
        fun onClientDisconnected()
    }

    private val main = Handler(Looper.getMainLooper())
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var serverSocket: ServerSocket? = null
    // @Volatile обязателен: поля пишет поток сервера, а читает главный поток
    // (рассылка состояния). Без этого он мог увидеть null и «потерять» первый пакет.
    @Volatile
    private var client: Socket? = null

    @Volatile
    private var clientWriter: OutputStreamWriter? = null
    @Volatile
    private var running = false

    /** Запускает приём подключений в фоновом потоке. */
    fun start() {
        if (running) return
        running = true
        // Держим Wi-Fi активным: иначе при погасшем экране радио уходит в сон и пульт отваливается.
        try {
            val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            wifiLock = wifi?.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "sportstreamer-remote"
            )?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось удержать Wi-Fi", e)
        }
        thread(name = "remote-score-server", isDaemon = true) {
            try {
                val socket = ServerSocket(RemoteScoreProtocol.PORT)
                serverSocket = socket
                Log.i(TAG, "Сервер пульта слушает порт ${RemoteScoreProtocol.PORT}")
                while (running) {
                    val accepted = socket.accept()
                    handleClient(accepted)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Сервер пульта остановлен с ошибкой", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = OutputStreamWriter(socket.outputStream)

            val helloLine = reader.readLine() ?: return
            val hello = JSONObject(helloLine)
            val receivedPin = hello.optString(RemoteScoreProtocol.FIELD_PIN)
            if (receivedPin != pin) {
                writer.write(RemoteScoreProtocol.response(false, "Неверный код") + "\n")
                writer.flush()
                socket.close()
                Log.w(TAG, "Подключение отклонено: неверный код")
                return
            }

            writer.write(RemoteScoreProtocol.response(true) + "\n")
            writer.flush()
            client = socket
            clientWriter = writer
            main.post { listener.onClientConnected() }
            Log.i(TAG, "Пульт подключён, код верный")

            while (running) {
                val line = reader.readLine() ?: break
                Log.i(TAG, "получено: $line")
                // Битая строка — не повод рвать соединение: пропускаем и читаем дальше.
                val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (message.optString(RemoteScoreProtocol.FIELD_CMD)) {
                    RemoteScoreProtocol.CMD_POINT -> {
                        val team = message.optInt(RemoteScoreProtocol.FIELD_TEAM, 0)
                        val add = message.optBoolean(RemoteScoreProtocol.FIELD_ADD, true)
                        if (team == 1 || team == 2) {
                            main.post { listener.onPoint(team, add) }
                        }
                    }

                    RemoteScoreProtocol.CMD_UNDO -> main.post { listener.onUndo() }

                    // Heartbeat: отвечаем сразу, чтобы пульт видел живое соединение
                    // и Wi-Fi не уходил в энергосбережение.
                    RemoteScoreProtocol.CMD_SET_DECISION -> {
                        val newSet = message.optBoolean(RemoteScoreProtocol.FIELD_NEW_SET, false)
                        main.post { listener.onSetDecision(newSet) }
                    }

                    RemoteScoreProtocol.CMD_SYNC -> main.post { listener.onSyncRequested() }

                    RemoteScoreProtocol.CMD_PING -> {
                        writer.write(RemoteScoreProtocol.response(true) + "\n")
                        writer.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Соединение с пультом прервано: ${e::class.java.simpleName} ${e.message}", e)
        } finally {
            try {
                client?.close()
            } catch (_: Exception) {
            }
            client = null
            clientWriter = null
            main.post { listener.onClientDisconnected() }
        }
    }

    /** Отправляет строку подключённому пульту; если пульта нет — молча игнорирует. */
    fun sendToClient(line: String) {
        try {
            clientWriter?.apply {
                write(line + "\n")
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось отправить данные пульту", e)
        }
    }

    /** Останавливает сервер и закрывает текущее подключение. */
    fun stop() {
        running = false
        try {
            wifiLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка освобождения Wi-Fi lock", e)
        }
        wifiLock = null
        try {
            client?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        client = null
    }

    private companion object {
        const val TAG = "RemoteScoreServer"
        const val SOCKET_TIMEOUT_MS = 0
    }
}
