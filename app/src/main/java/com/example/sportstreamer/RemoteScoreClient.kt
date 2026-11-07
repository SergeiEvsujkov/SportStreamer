package com.example.sportstreamer

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Клиент удалённого пульта: подключается к телефону-источнику и отправляет команды счёта.
 *
 * Транспорт тот же, что у сервера: TCP и построчный JSON. Работает и в общей сети Wi-Fi,
 * и через точку доступа источника — различается только адрес.
 */
class RemoteScoreClient(
    private val host: String,
    private val port: Int,
    private val pin: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null

    @Volatile
    private var running = false

    /** Подключается в фоновом потоке; результат приходит в [Listener] на главном потоке. */
    fun connect() {
        if (running) return
        running = true
        thread(name = "remote-score-client", isDaemon = true) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                this.socket = socket

                val output = OutputStreamWriter(socket.outputStream)
                writer = output
                output.write(RemoteScoreProtocol.hello(pin) + "\n")
                output.flush()

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val answer = reader.readLine()
                val ok = answer != null &&
                    JSONObject(answer).optBoolean(RemoteScoreProtocol.FIELD_OK, false)
                if (!ok) {
                    val error = answer?.let {
                        JSONObject(it).optString(RemoteScoreProtocol.FIELD_ERROR)
                    }
                    disconnect(error?.ifBlank { "Неверный код" } ?: "Неверный код")
                    return@thread
                }

                main.post { listener.onConnected() }
                // Heartbeat: раз в 5 секунд, чтобы соединение не считалось простаивающим.
                val ping = object : Runnable {
                    override fun run() {
                        if (running) {
                            send(RemoteScoreProtocol.ping())
                            main.postDelayed(this, PING_INTERVAL_MS)
                        }
                    }
                }
                main.postDelayed(ping, PING_INTERVAL_MS)
                // Держим соединение: читаем ответы, пока сервер не закроет сокет.
                while (running) {
                    if (reader.readLine() == null) break
                }
                disconnect(null)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось подключиться к пульту", e)
                disconnect("Не удалось подключиться")
            }
        }
    }

    /** Отправляет команду начисления или снятия очка. */
    fun sendPoint(team: Int, add: Boolean) = send(RemoteScoreProtocol.point(team, add))

    private fun send(command: String) {
        try {
            writer?.apply {
                write(command + "\n")
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Команда не отправлена", e)
            disconnect(null)
        }
    }

    fun disconnect(reason: String?) {
        if (!running && socket == null) return
        running = false
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        writer = null
        socket = null
        main.post { listener.onDisconnected(reason) }
    }

    private companion object {
        const val TAG = "RemoteScoreClient"
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 0
        const val PING_INTERVAL_MS = 5000L
    }
}
