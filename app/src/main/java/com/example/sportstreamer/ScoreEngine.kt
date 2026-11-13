package com.example.sportstreamer

import kotlin.math.abs

/**
 * Состояние и правила матча (волейбол).
 *
 * Обычная партия играется до [REGULAR_SET_POINTS] очков, решающая — до [DECIDING_SET_POINTS];
 * в обоих случаях нужен перевес минимум в 2 очка.
 * Матч идёт до [setsToWin] выигранных партий: «3 партии» — это 2 победы, «5 партий» — 3 победы.
 *
 * Партия засчитывается СРАЗУ в момент победного очка: счёт по партиям и [setHistory] меняются
 * синхронно с табло. Очки при этом остаются на экране (25:23), чтобы зритель успел прочитать
 * итог партии — обнуляются они только по команде [startNextSet].
 *
 * Пока оператор не начал следующую партию ([isAwaitingNextSet]) или пока матч не завершён
 * ([isMatchOver]), очки не растут. Ошибку можно исправить одним шагом: [rollbackLastSet]
 * снимает последнюю партию со счёта и возвращает очки к состоянию до победного очка.
 *
 * Класс не зависит от Android.
 */
class ScoreEngine(val setsToWin: Int = SETS_TO_WIN_BEST_OF_THREE) {

    enum class Team { FIRST, SECOND }

    /** Итоговый счёт одной завершённой партии. */
    data class SetScore(val team1: Int, val team2: Int)

    /** Что произошло после изменения счёта: нужно ли спрашивать про новую партию или матч. */
    sealed interface PointResult {
        /** Счёт не изменился (матч решён или ждём начала следующей партии). */
        data object Ignored : PointResult

        data object ScoreChanged : PointResult

        /** Партия засчитана, матч продолжается — нужно начать следующую партию. */
        data class SetFinished(val team: Team) : PointResult

        /** Партия засчитана и матч завершён. */
        data class MatchFinished(val team: Team) : PointResult
    }

    data class Snapshot(
        val team1Score: Int,
        val team2Score: Int,
        val team1Sets: Int,
        val team2Sets: Int,
        val history: List<SetScore>,
        val awaitingNextSet: Boolean
    )

    /** Состояние перед победным очком: по нему откатывается случайно засчитанная партия. */
    private data class RollbackPoint(
        val team1Score: Int,
        val team2Score: Int,
        val team1Sets: Int,
        val team2Sets: Int
    )

    var team1Score: Int = 0
        private set

    var team2Score: Int = 0
        private set

    var team1Sets: Int = 0
        private set

    var team2Sets: Int = 0
        private set

    /** Счёт всех завершённых партий по порядку: 25:23, 22:25, 15:10 … */
    private val history = mutableListOf<SetScore>()

    val setHistory: List<SetScore>
        get() = history.toList()

    /** Победитель последней закрытой партии, пока оператор не начал следующую. */
    var lastSetWinner: Team? = null
        private set

    /** Партия засчитана и ждёт команды «начать следующую»: очки заморожены. */
    var isAwaitingNextSet: Boolean = false
        private set

    private var rollbackPoint: RollbackPoint? = null

    val isMatchOver: Boolean
        get() = team1Sets >= setsToWin || team2Sets >= setsToWin

    val currentSetNumber: Int
        get() = team1Sets + team2Sets + 1

    val isDecidingSet: Boolean
        get() = team1Sets == team2Sets && team1Sets == setsToWin - 1

    val pointsToWinSet: Int
        get() = if (isDecidingSet) DECIDING_SET_POINTS else REGULAR_SET_POINTS

    fun scoreOf(team: Team): Int = if (team == Team.FIRST) team1Score else team2Score

    fun setsOf(team: Team): Int = if (team == Team.FIRST) team1Sets else team2Sets

    fun addPoint(team: Team): PointResult {
        if (isMatchOver || isAwaitingNextSet) return PointResult.Ignored

        // Запоминаем состояние ДО очка: из него откатывается засчитанная партия.
        val before = RollbackPoint(team1Score, team2Score, team1Sets, team2Sets)

        if (team == Team.FIRST) team1Score++ else team2Score++

        if (!isSetFinished(team)) return PointResult.ScoreChanged

        // Партия выиграна: сразу в счёт партий и в историю — табло всегда согласовано.
        rollbackPoint = before
        if (team == Team.FIRST) team1Sets++ else team2Sets++
        history.add(SetScore(team1Score, team2Score))
        lastSetWinner = team

        if (isMatchOver) {
            // Очки остаются на экране: зритель видит итог последней партии.
            return PointResult.MatchFinished(team)
        }
        isAwaitingNextSet = true
        return PointResult.SetFinished(team)
    }

    /**
     * Снимает очко.
     *
     * Пока ждём начала следующей партии, снимать по одному очку нельзя — состояние
     * разъедется. Вместо этого снятие очка отменяет партию целиком.
     *
     * @return true, если состояние изменилось.
     */
    fun removePoint(team: Team): Boolean {
        if (isAwaitingNextSet) return rollbackLastSet()
        if (isMatchOver) return false
        if (team == Team.FIRST) {
            if (team1Score <= 0) return false
            team1Score--
        } else {
            if (team2Score <= 0) return false
            team2Score--
        }
        return true
    }

    /** Начинает следующую партию: очки обнуляются, засчитанная партия остаётся. */
    fun startNextSet() {
        if (!isAwaitingNextSet) return
        team1Score = 0
        team2Score = 0
        isAwaitingNextSet = false
        rollbackPoint = null
        lastSetWinner = null
    }

    /**
     * Отменяет последнюю засчитанную партию.
     *
     * Возвращает очки к состоянию до победного очка (24:23) и убирает партию из счёта
     * и истории. Используется, когда очко начислили не той команде.
     */
    fun rollbackLastSet(): Boolean {
        val point = rollbackPoint ?: return false
        if (history.isEmpty()) return false

        history.removeAt(history.lastIndex)
        team1Sets = point.team1Sets
        team2Sets = point.team2Sets
        team1Score = point.team1Score
        team2Score = point.team2Score
        isAwaitingNextSet = false
        rollbackPoint = null
        lastSetWinner = null
        return true
    }

    fun resetMatch() {
        team1Score = 0
        team2Score = 0
        team1Sets = 0
        team2Sets = 0
        history.clear()
        isAwaitingNextSet = false
        rollbackPoint = null
        lastSetWinner = null
    }

    fun snapshot(): Snapshot =
        Snapshot(team1Score, team2Score, team1Sets, team2Sets, setHistory, isAwaitingNextSet)

    fun restore(snapshot: Snapshot) {
        team1Score = snapshot.team1Score
        team2Score = snapshot.team2Score
        team1Sets = snapshot.team1Sets
        team2Sets = snapshot.team2Sets
        history.clear()
        history.addAll(snapshot.history)
        isAwaitingNextSet = snapshot.awaitingNextSet
        rollbackPoint = null
        lastSetWinner = null
    }

    private fun isSetFinished(team: Team): Boolean {
        val own = scoreOf(team)
        val other = scoreOf(if (team == Team.FIRST) Team.SECOND else Team.FIRST)
        return own >= pointsToWinSet && abs(own - other) > 1
    }

    companion object {
        const val REGULAR_SET_POINTS = 25
        const val DECIDING_SET_POINTS = 15

        /** Формат «3 партии» (best of 3): для победы нужно выиграть 2 партии. */
        const val SETS_TO_WIN_BEST_OF_THREE = 2

        /** Формат «5 партий» (best of 5): для победы нужно выиграть 3 партии. */
        const val SETS_TO_WIN_BEST_OF_FIVE = 3
    }
}
