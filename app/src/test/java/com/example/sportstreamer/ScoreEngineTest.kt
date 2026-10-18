package com.example.sportstreamer

import com.example.sportstreamer.ScoreEngine.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreEngineTest {

    private fun ScoreEngine.addPoints(team: Team, count: Int) {
        repeat(count) { addPoint(team) }
    }

    /** Выигрывает партию 25 очками. Победную партию матча движок засчитывает сам. */
    private fun ScoreEngine.winSet(team: Team) {
        var last: ScoreEngine.PointResult = ScoreEngine.PointResult.ScoreChanged
        repeat(ScoreEngine.REGULAR_SET_POINTS) { last = addPoint(team) }
        if (last !is ScoreEngine.PointResult.MatchFinished) {
            startNewSet(team)
        }
    }

    @Test
    fun `очко увеличивает счёт своей команды`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)

        assertEquals(ScoreEngine.PointResult.ScoreChanged, engine.addPoint(Team.FIRST))
        assertEquals(1, engine.team1Score)
        assertEquals(0, engine.team2Score)

        assertEquals(ScoreEngine.PointResult.ScoreChanged, engine.addPoint(Team.SECOND))
        assertEquals(1, engine.team2Score)
    }

    @Test
    fun `минус не уводит счёт ниже нуля`() {
        val engine = ScoreEngine()

        engine.removePoint(Team.FIRST)

        assertEquals(0, engine.team1Score)
    }

    @Test
    fun `обычная партия завершается на 25 очках с перевесом 2`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)
        engine.addPoints(Team.FIRST, 24)
        engine.addPoints(Team.SECOND, 24)

        assertEquals(ScoreEngine.PointResult.ScoreChanged, engine.addPoint(Team.FIRST)) // 25:24
        assertEquals(ScoreEngine.PointResult.ScoreChanged, engine.addPoint(Team.SECOND)) // 25:25
        assertEquals(ScoreEngine.PointResult.ScoreChanged, engine.addPoint(Team.FIRST)) // 26:25
        assertEquals(ScoreEngine.PointResult.SetFinished(Team.FIRST), engine.addPoint(Team.FIRST)) // 27:25
        assertEquals(Team.FIRST, engine.pendingWinner)
    }

    @Test
    fun `решающая партия играется до 15 и сразу завершает матч`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)
        engine.winSet(Team.FIRST)
        engine.winSet(Team.SECOND)

        assertTrue(engine.isDecidingSet)
        assertEquals(ScoreEngine.DECIDING_SET_POINTS, engine.pointsToWinSet)
        assertEquals(3, engine.currentSetNumber)

        engine.addPoints(Team.FIRST, ScoreEngine.DECIDING_SET_POINTS - 1)
        assertEquals(ScoreEngine.PointResult.MatchFinished(Team.FIRST), engine.addPoint(Team.FIRST))
        // победная партия засчитана сразу — в диалоге и на оверлее виден итоговый счёт
        assertEquals(2, engine.team1Sets)
        assertNull(engine.pendingWinner)
        assertTrue(engine.isMatchOver)
        assertFalse(engine.isDecidingSet)
    }

    @Test
    fun `формат из 5 партий требует 3 побед, решающая партия при 2-2`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_FIVE)
        engine.winSet(Team.FIRST)
        engine.winSet(Team.SECOND)
        engine.winSet(Team.FIRST)
        engine.winSet(Team.SECOND)

        assertTrue(engine.isDecidingSet)
        assertEquals(ScoreEngine.DECIDING_SET_POINTS, engine.pointsToWinSet)
        assertFalse(engine.isMatchOver)

        engine.addPoints(Team.SECOND, ScoreEngine.DECIDING_SET_POINTS)
        assertTrue(engine.isMatchOver)
        // итоговый счёт по партиям — 2:3, а не 2:2
        assertEquals(2, engine.team1Sets)
        assertEquals(3, engine.team2Sets)
    }

    @Test
    fun `в формате из трёх партий итоговый счёт 2-1, а не 1-1`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)
        engine.winSet(Team.FIRST)
        engine.winSet(Team.SECOND)
        assertTrue(engine.isDecidingSet)

        engine.addPoints(Team.FIRST, ScoreEngine.DECIDING_SET_POINTS)

        assertEquals(2, engine.team1Sets)
        assertEquals(1, engine.team2Sets)
        assertTrue(engine.isMatchOver)
    }

    @Test
    fun `обычная партия ждёт подтверждения и не портит итоговый счёт`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_FIVE)
        engine.addPoints(Team.FIRST, ScoreEngine.REGULAR_SET_POINTS)

        // партия выиграна, но пока не засчитана — пользователь может её продолжить
        assertEquals(Team.FIRST, engine.pendingWinner)
        assertEquals(0, engine.team1Sets)

        engine.declineSetCompletion()
        assertEquals(0, engine.team1Sets)
        assertNull(engine.pendingWinner)
    }

    @Test
    fun `после завершения матча очки не начисляются`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)
        engine.winSet(Team.FIRST)
        engine.winSet(Team.FIRST)

        assertTrue(engine.isMatchOver)
        assertEquals(ScoreEngine.PointResult.Ignored, engine.addPoint(Team.SECOND))
        assertEquals(0, engine.team2Score)
    }

    @Test
    fun `отказ от новой партии не переспрашивает повторно`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)
        engine.addPoints(Team.FIRST, ScoreEngine.REGULAR_SET_POINTS)
        engine.declineSetCompletion()

        assertEquals(ScoreEngine.PointResult.ScoreChanged, engine.addPoint(Team.FIRST)) // 26:0
        assertEquals(ScoreEngine.PointResult.ScoreChanged, engine.addPoint(Team.SECOND)) // 26:1
    }

    @Test
    fun `если перевес сократился, вопрос о новой партии задаётся снова`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)
        engine.addPoints(Team.FIRST, ScoreEngine.REGULAR_SET_POINTS)
        engine.declineSetCompletion()

        engine.addPoints(Team.SECOND, ScoreEngine.REGULAR_SET_POINTS - 1) // 25:24, перевес 1

        assertEquals(ScoreEngine.PointResult.SetFinished(Team.FIRST), engine.addPoint(Team.FIRST)) // 26:24
    }

    @Test
    fun `снимок состояния восстанавливает счёт и партии`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_FIVE)
        engine.addPoints(Team.SECOND, 7)
        engine.winSet(Team.FIRST)

        val restored = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_FIVE)
        restored.restore(engine.snapshot())

        assertEquals(engine.team1Score, restored.team1Score)
        assertEquals(engine.team2Score, restored.team2Score)
        assertEquals(engine.team1Sets, restored.team1Sets)
        assertEquals(engine.team2Sets, restored.team2Sets)
    }

    @Test
    fun `новая игра обнуляет матч`() {
        val engine = ScoreEngine(ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)
        engine.addPoints(Team.FIRST, 10)
        engine.winSet(Team.SECOND)

        engine.resetMatch()

        assertEquals(0, engine.team1Score)
        assertEquals(0, engine.team2Score)
        assertEquals(0, engine.team1Sets)
        assertEquals(0, engine.team2Sets)
        assertFalse(engine.isMatchOver)
    }
}
