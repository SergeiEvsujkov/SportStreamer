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
        fun onState(team1Score: Int, team2Score: Int, team1Sets: Int, team2Sets: Int, game: Int, setPending: Boolean, matchOver: Boolean, setsToWin: Int, team1Name: String, team2Name: String)
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

                Log.i(TAG, "подключено к $host:$port")
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
                // Держим соединение и принимаем состояние матча.
                while (running) {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(TAG, "сервер закрыл соединение (EOF)")
                        break
                    }
                    Log.i(TAG, "получено: $line")
                    val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (message.optString(RemoteScoreProtocol.FIELD_CMD) == RemoteScoreProtocol.CMD_STATE) {
                        val t1 = message.optInt(RemoteScoreProtocol.FIELD_T1)
                        val t2 = message.optInt(RemoteScoreProtocol.FIELD_T2)
                        val s1 = message.optInt(RemoteScoreProtocol.FIELD_S1)
                        val s2 = message.optInt(RemoteScoreProtocol.FIELD_S2)
                        val game = message.optInt(RemoteScoreProtocol.FIELD_GAME)
                        val pending = message.optBoolean(RemoteScoreProtocol.FIELD_SET_PENDING)
                        val over = message.optBoolean(RemoteScoreProtocol.FIELD_MATCH_OVER)
                        val setsToWin = message.optInt(RemoteScoreProtocol.FIELD_SETS_TO_WIN, 2)
                        val team1 = message.optString(RemoteScoreProtocol.FIELD_TEAM1_NAME)
                        val team2 = message.optString(RemoteScoreProtocol.FIELD_TEAM2_NAME)
                        main.post { listener.onState(t1, t2, s1, s2, game, pending, over, setsToWin, team1, team2) }
                    }
                }
                disconnect(null)
            } catch (e: Exception) {
                Log.w(TAG, "обрыв: ${e::class.java.simpleName} ${e.message}", e)
                disconnect("Не удалось подключиться")
            }
        }
    }

    /** Отправляет команду начисления или снятия очка. */
    fun sendPoint(team: Int, add: Boolean) = send(RemoteScoreProtocol.point(team, add))

    /** Решение по завершённой партии: начать новую или продолжить текущую. */
    fun sendSetDecision(newSet: Boolean) = send(RemoteScoreProtocol.setDecision(newSet))

    private fun send(command: String) {
        try {
            writer?.apply {
                write(command + "\n")
                flush()
            }
        } catch (e: Exception) {
            // Одна неудачная запись не означает, что соединение мертво: не рвём его,
            // иначе пульт отваливается от любого таймаута записи.
            Log.w(TAG, "Команда не отправлена", e)
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
