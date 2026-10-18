package com.example.sportstreamer

import kotlin.math.abs

/**
 * Состояние и правила матча (волейбол).
 *
 * Обычная партия играется до [REGULAR_SET_POINTS] очков, решающая — до [DECIDING_SET_POINTS];
 * в обоих случаях нужен перевес минимум в 2 очка.
 * Матч идёт до [setsToWin] выигранных партий: «3 партии» — это 2 победы, «5 партий» — 3 победы.
 *
 * Когда партия закрыта, движок возвращает [PointResult.SetFinished] или [PointResult.MatchFinished]
 * и запоминает победителя в [pendingWinner] — до решения пользователя партия ещё не засчитана
 * в [team1Sets]/[team2Sets], но уже учитывается в [isMatchOver]. Подтверждение — [startNewSet],
 * отказ («продолжить эту партию») — [declineSetCompletion].
 *
 * Незавершённое подтверждение не попадает в [Snapshot]: после пересоздания активити диалог
 * исчезает, и вопрос будет задан снова при следующем очке.
 *
 * Класс не зависит от Android, правила покрыты unit-тестами (ScoreEngineTest).
 */
class ScoreEngine(val setsToWin: Int = SETS_TO_WIN_BEST_OF_THREE) {

    enum class Team { FIRST, SECOND }

    /** Что произошло после изменения счёта: нужно ли спрашивать про новую партию или матч. */
    sealed interface PointResult {
        /** Счёт не изменился (матч решён или ждём ответа по закрытой партии). */
        data object Ignored : PointResult

        data object ScoreChanged : PointResult

        /** Партия выиграна, матч продолжается — нужно подтверждение новой партии. */
        data class SetFinished(val team: Team) : PointResult

        /** Партия выиграна и матч завершён. */
        data class MatchFinished(val team: Team) : PointResult
    }

    data class Snapshot(
        val team1Score: Int,
        val team2Score: Int,
        val team1Sets: Int,
        val team2Sets: Int,
        val setCompletionDeclined: Boolean
    )

    var team1Score: Int = 0
        private set

    var team2Score: Int = 0
        private set

    var team1Sets: Int = 0
        private set

    var team2Sets: Int = 0
        private set

    /** Пользователь отказался начинать новую партию: не переспрашиваем, пока условие не сброшено. */
    private var setCompletionDeclined = false

    /** Победитель закрытой партии, ожидающий решения пользователя. */
    private var pendingSetWinner: Team? = null

    /** Победитель закрытой партии или null, если решения не ждём. */
    val pendingWinner: Team?
        get() = pendingSetWinner

    val isMatchOver: Boolean
        get() = setsWithPending(Team.FIRST) >= setsToWin || setsWithPending(Team.SECOND) >= setsToWin

    val currentSetNumber: Int
        get() = team1Sets + team2Sets + 1

    val isDecidingSet: Boolean
        get() = team1Sets == team2Sets && team1Sets == setsToWin - 1

    val pointsToWinSet: Int
        get() = if (isDecidingSet) DECIDING_SET_POINTS else REGULAR_SET_POINTS

    fun scoreOf(team: Team): Int = if (team == Team.FIRST) team1Score else team2Score

    fun setsOf(team: Team): Int = if (team == Team.FIRST) team1Sets else team2Sets

    fun addPoint(team: Team): PointResult {
        if (isMatchOver || pendingSetWinner != null) return PointResult.Ignored

        if (team == Team.FIRST) team1Score++ else team2Score++

        if (!isAnySetFinished()) {
            // Ни одна команда не «закрыла» партию (например, соперник отыграл разницу) —
            // разрешаем спросить про новую партию заново.
            setCompletionDeclined = false
        }
        if (!isSetFinished(team)) return PointResult.ScoreChanged
        if (setCompletionDeclined) return PointResult.ScoreChanged

        // Победная партия засчитывается сразу: отменить её уже нечем — в диалоге только
        // «Новая игра» или «Закрыть». Иначе и в диалоге, и на оверлее показывался бы счёт
        // без победной партии: 2:2 вместо 3:2 или 2:3.
        if (setsOf(team) + 1 >= setsToWin) {
            if (team == Team.FIRST) team1Sets++ else team2Sets++
            pendingSetWinner = null
            setCompletionDeclined = false
            return PointResult.MatchFinished(team)
        }

        // Обычная партия пока «висит»: пользователь может выбрать «продолжить эту».
        pendingSetWinner = team
        return PointResult.SetFinished(team)
    }

    fun removePoint(team: Team) {
        // Пока висит вопрос о закрытой партии, счёт не правим — иначе состояние разъедется.
        if (pendingSetWinner != null) return

        if (team == Team.FIRST) {
            if (team1Score > 0) team1Score--
        } else {
            if (team2Score > 0) team2Score--
        }
        if (!isAnySetFinished()) setCompletionDeclined = false
    }

    /** Пользователь выбрал «продолжить эту партию»: партия не засчитывается. */
    fun declineSetCompletion() {
        setCompletionDeclined = true
        pendingSetWinner = null
    }

    /** Новая партия: очки обнуляются, победителю засчитывается партия. */
    fun startNewSet(winner: Team) {
        team1Score = 0
        team2Score = 0
        if (winner == Team.FIRST) team1Sets++ else team2Sets++
        pendingSetWinner = null
        setCompletionDeclined = false
    }

    fun resetMatch() {
        team1Score = 0
        team2Score = 0
        team1Sets = 0
        team2Sets = 0
        pendingSetWinner = null
        setCompletionDeclined = false
    }

    fun snapshot(): Snapshot =
        Snapshot(team1Score, team2Score, team1Sets, team2Sets, setCompletionDeclined)

    fun restore(snapshot: Snapshot) {
        team1Score = snapshot.team1Score
        team2Score = snapshot.team2Score
        team1Sets = snapshot.team1Sets
        team2Sets = snapshot.team2Sets
        setCompletionDeclined = snapshot.setCompletionDeclined
        pendingSetWinner = null
    }

    /** Партии команды с учётом ещё не подтверждённой выигранной партии. */
    private fun setsWithPending(team: Team): Int =
        setsOf(team) + if (pendingSetWinner == team) 1 else 0

    /** Закрыта ли партия хотя бы одной из команд (порог очков и перевес в 2 очка). */
    private fun isAnySetFinished(): Boolean =
        isSetFinished(Team.FIRST) || isSetFinished(Team.SECOND)

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
