package com.example.sportstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingPartsTest {

    @Test
    fun `без деления интервала нет`() {
        assertNull(RecordingParts.intervalMs(RecordingParts.NO_SPLIT))
    }

    @Test
    fun `варианты деления ровно те, что в настройках`() {
        assertEquals(listOf(0, 10, 20, 30), RecordingParts.OPTIONS)
    }

    @Test
    fun `интервал переводится из минут в миллисекунды`() {
        assertEquals(10 * 60_000L, RecordingParts.intervalMs(10))
        assertEquals(30 * 60_000L, RecordingParts.intervalMs(30))
    }

    @Test
    fun `первая часть без суффикса, следующие с номером`() {
        assertEquals("матч.mp4", RecordingParts.fileName("матч", 1))
        assertEquals("матч_part2.mp4", RecordingParts.fileName("матч", 2))
        assertEquals("матч_part3.mp4", RecordingParts.fileName("матч", 3))
    }
}
