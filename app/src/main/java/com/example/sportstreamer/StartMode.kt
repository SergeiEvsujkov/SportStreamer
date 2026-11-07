package com.example.sportstreamer

/**
 * Что запускает единственная кнопка пульта.
 *
 * Режим выбирается заранее, до старта: как в SportCam, состав кодировщиков фиксируется
 * на всю сессию и не меняется на ходу — именно пересборки GL давали чёрный кадр.
 */
object StartMode {

    /** Только эфир: один кодек 1080p, файл не пишется. */
    const val STREAM = 0

    /** Только запись: один кодек 1080p с выбранным битрейтом записи. */
    const val RECORD = 1

    /** Эфир и запись одновременно: один кодек, файл получает качество эфира. */
    const val BOTH = 2

    val OPTIONS = listOf(STREAM, RECORD, BOTH)

    fun includesStream(mode: Int): Boolean = mode != RECORD

    fun includesRecording(mode: Int): Boolean = mode != STREAM
}
