package com.example.sportstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingBitrateTest {

    @Test
    fun `варианты битрейта записи — 1, 3 и 5 Мбит с`() {
        assertEquals(listOf(1, 3, 5), RecordingBitrate.OPTIONS)
        assertEquals(5, RecordingBitrate.DEFAULT_MBPS)
    }

    @Test
    fun `битрейт переводится в биты в секунду`() {
        assertEquals(1 * 1024 * 1024, RecordingBitrate.bitsPerSecond(1))
        assertEquals(3 * 1024 * 1024, RecordingBitrate.bitsPerSecond(3))
        assertEquals(5 * 1024 * 1024, RecordingBitrate.bitsPerSecond(5))
    }

    @Test
    fun `неизвестное значение заменяется значением по умолчанию`() {
        assertEquals(RecordingBitrate.bitsPerSecond(5), RecordingBitrate.bitsPerSecond(0))
        assertEquals(RecordingBitrate.bitsPerSecond(5), RecordingBitrate.bitsPerSecond(7))
    }

    @Test
    fun `оценка размера файла в мегабайтах в минуту`() {
        assertEquals(7, RecordingBitrate.megabytesPerMinute(1))
        assertEquals(22, RecordingBitrate.megabytesPerMinute(3))
        assertEquals(37, RecordingBitrate.megabytesPerMinute(5))
    }
}
