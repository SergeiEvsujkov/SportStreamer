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
    private val pin: String,
    private val listener: Listener
) {

    interface Listener {
        fun onPoint(team: Int, add: Boolean)
        fun onUndo()
        fun onClientConnected()
        fun onClientDisconnected()
    }

    private val main = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var client: Socket? = null
    @Volatile
    private var running = false

    /** Запускает приём подключений в фоновом потоке. */
    fun start() {
        if (running) return
        running = true
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
            main.post { listener.onClientConnected() }
            Log.i(TAG, "Пульт подключён")

            while (running) {
                val line = reader.readLine() ?: break
                val message = JSONObject(line)
                when (message.optString(RemoteScoreProtocol.FIELD_CMD)) {
                    RemoteScoreProtocol.CMD_POINT -> {
                        val team = message.optInt(RemoteScoreProtocol.FIELD_TEAM, 0)
                        val add = message.optBoolean(RemoteScoreProtocol.FIELD_ADD, true)
                        if (team == 1 || team == 2) {
                            main.post { listener.onPoint(team, add) }
                        }
                    }

                    RemoteScoreProtocol.CMD_UNDO -> main.post { listener.onUndo() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Соединение с пультом прервано", e)
        } finally {
            try {
                client?.close()
            } catch (_: Exception) {
            }
            client = null
            main.post { listener.onClientDisconnected() }
        }
    }

    /** Останавливает сервер и закрывает текущее подключение. */
    fun stop() {
        running = false
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
