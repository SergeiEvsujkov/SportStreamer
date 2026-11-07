package com.example.sportstreamer

import org.json.JSONObject

/**
 * Протокол удалённого пульта.
 *
 * Оба конца — наше приложение, поэтому вместо WebSocket достаточно обычного TCP:
 * сообщения идут построчно, каждое — отдельный JSON. Так нет ни зависимостей,
 * ни требований к интернету: телефоны связываются по Wi-Fi или точке доступа.
 */
object RemoteScoreProtocol {

    /** Порт по умолчанию. Не служебный, чтобы не пересекаться с системными. */
    const val PORT = 8642

    const val CMD_HELLO = "hello"
    const val CMD_POINT = "point"
    const val CMD_UNDO = "undo"
    const val CMD_PING = "ping"

    const val FIELD_CMD = "cmd"
    const val FIELD_PIN = "pin"
    const val FIELD_TEAM = "team"
    const val FIELD_ADD = "add"
    const val FIELD_OK = "ok"
    const val FIELD_ERROR = "error"

    /** Команда «дай очко»: team = 1 или 2, add = true — прибавить, false — убрать. */
    fun point(team: Int, add: Boolean): String =
        JSONObject()
            .put(FIELD_CMD, CMD_POINT)
            .put(FIELD_TEAM, team)
            .put(FIELD_ADD, add)
            .toString()

    fun undo(): String = JSONObject().put(FIELD_CMD, CMD_UNDO).toString()

    /** Проверка живости соединения: без трафика Wi-Fi усыпляет сокет. */
    fun ping(): String = JSONObject().put(FIELD_CMD, CMD_PING).toString()

    fun hello(pin: String): String =
        JSONObject()
            .put(FIELD_CMD, CMD_HELLO)
            .put(FIELD_PIN, pin)
            .toString()

    fun response(ok: Boolean, error: String? = null): String {
        val json = JSONObject().put(FIELD_OK, ok)
        if (error != null) json.put(FIELD_ERROR, error)
        return json.toString()
    }

    /** Случайный четырёхзначный код сопряжения. */
    fun generatePin(): String = (1000..9999).random().toString()
}
