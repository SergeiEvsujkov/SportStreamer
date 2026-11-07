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
    const val CMD_STATE = "state"
    const val CMD_SET_DECISION = "setDecision"

    const val FIELD_CMD = "cmd"
    const val FIELD_PIN = "pin"
    const val FIELD_TEAM = "team"
    const val FIELD_ADD = "add"
    const val FIELD_OK = "ok"
    const val FIELD_ERROR = "error"
    const val FIELD_T1 = "t1"
    const val FIELD_T2 = "t2"
    const val FIELD_S1 = "s1"
    const val FIELD_S2 = "s2"
    const val FIELD_GAME = "game"
    const val FIELD_SET_PENDING = "setPending"
    const val FIELD_MATCH_OVER = "matchOver"
    const val FIELD_NEW_SET = "newSet"

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

    /** Состояние матча, которое источник рассылает пульту после каждого изменения. */
    fun state(
        team1Score: Int,
        team2Score: Int,
        team1Sets: Int,
        team2Sets: Int,
        game: Int,
        setPending: Boolean,
        matchOver: Boolean
    ): String = JSONObject()
        .put(FIELD_CMD, CMD_STATE)
        .put(FIELD_T1, team1Score)
        .put(FIELD_T2, team2Score)
        .put(FIELD_S1, team1Sets)
        .put(FIELD_S2, team2Sets)
        .put(FIELD_GAME, game)
        .put(FIELD_SET_PENDING, setPending)
        .put(FIELD_MATCH_OVER, matchOver)
        .toString()

    /** Решение по завершённой партии: начать новую или продолжить текущую. */
    fun setDecision(newSet: Boolean): String = JSONObject()
        .put(FIELD_CMD, CMD_SET_DECISION)
        .put(FIELD_NEW_SET, newSet)
        .toString()

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
