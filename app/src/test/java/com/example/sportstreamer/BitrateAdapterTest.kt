package com.example.sportstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateAdapterTest {

    private val threeMegabits = 3_000_000

    @Test
    fun `первый замер только задаёт точку отсчёта`() {
        val adapter = BitrateAdapter(threeMegabits, 1_000_000, 7_000_000)

        assertNull(adapter.onStats(1_000L, 0L))
        assertNull(adapter.onStats(2_000L, 100_000L))
    }

    @Test
    fun `слабая сеть понижает целевой битрейт`() {
        val adapter = BitrateAdapter(threeMegabits, 1_000_000, 7_000_000)
        adapter.reset(1_000L, 0L)

        // за 3 секунды ушло 150 000 байт ≈ 400 кбит/с — это около 13% от цели
        val stats = adapter.onStats(4_000L, 150_000L)

        assertNotNull(stats)
        assertTrue(stats!!.actualBitrate in 300_000..500_000)
        assertNotNull(stats.newTargetBitrate)
        assertTrue(stats.newTargetBitrate!! < threeMegabits)
    }

    @Test
    fun `битрейт не опускается ниже минимума`() {
        val adapter = BitrateAdapter(5_000_000, 1_000_000, 7_000_000)
        adapter.reset(0L, 0L)

        var now = 0L
        repeat(10) {
            now += 3_000L
            adapter.onStats(now, 0L)
        }

        assertTrue(adapter.targetBitrate >= 1_000_000)
        assertTrue(adapter.targetBitrate <= 1_200_000)
    }

    @Test
    fun `когда сеть успевает, битрейт растёт и упирается в максимум`() {
        val adapter = BitrateAdapter(1_500_000, 1_000_000, 2_000_000)
        adapter.reset(0L, 0L)

        var now = 0L
        var sent = 0L
        repeat(12) {
            now += 3_000L
            sent += 1_000_000L
            adapter.onStats(now, sent)
        }

        // практически у максимума: гистерезис не даёт доползти ровно до границы
        assertTrue(adapter.targetBitrate in 1_900_000..2_000_000)
    }

    @Test
    fun `сеть на уровне цели поднимает битрейт на 10 процентов`() {
        val adapter = BitrateAdapter(threeMegabits, 1_000_000, 7_000_000)
        adapter.reset(0L, 0L)

        // 1 125 000 байт за 3 с = ровно 3 Мбит/с
        val stats = adapter.onStats(3_000L, 1_125_000L)

        assertNotNull(stats)
        assertEquals(threeMegabits, stats!!.actualBitrate)
        assertEquals(3_300_000, stats.newTargetBitrate)
    }

    @Test
    fun `небольшая просадка не меняет битрейт`() {
        val adapter = BitrateAdapter(threeMegabits, 1_000_000, 7_000_000)
        adapter.reset(0L, 0L)

        // 900 000 байт за 3 с = 2.4 Мбит/с, это 80% от цели — в пределах KEEP_RATIO
        val stats = adapter.onStats(3_000L, 900_000L)

        assertNotNull(stats)
        assertNull(stats!!.newTargetBitrate)
        assertEquals(threeMegabits, adapter.targetBitrate)
    }
}
