package com.example.sportstreamer

/** Параметры трансляции в одном месте. */
object StreamConfig {

    /**
     * Эфир — 720p. Разрешение эфира обязано отличаться от разрешения записи (при том же
     * соотношении сторон), иначе RootEncoder не включает отдельный энкодер записи и файл
     * пишется с битрейтом потока.
     */
    const val STREAM_WIDTH = 1280
    const val STREAM_HEIGHT = 720

    /** Запись в память — полное 1080p, со своим битрейтом и своим энкодером. */
    const val RECORD_WIDTH = 1920
    const val RECORD_HEIGHT = 1080

    /** Частота кадров и интервал ключевых кадров (секунды) для обоих энкодеров. */
    const val FPS = 30
    const val IFRAME_INTERVAL_SECONDS = 2

    /** 3 Мбит/с — стартовый битрейт потока. */
    const val INITIAL_BITRATE = 3_000 * 1024

    /** 1.5 Мбит/с — нижняя граница адаптации. */
    const val MIN_BITRATE = 1_500 * 1024

    /**
     * Верхняя граница адаптации.
     *
     * 720p несёт заметно меньше деталей, чем 1080p: держать на нём прежние 7 Мбит/с —
     * это греть энкодер и радиомодуль без выигрыша в картинке. Проверено логом эфира:
     * адаптер уходил в максимум за минуту и дальше только грел телефон.
     */
    const val MAX_BITRATE_720P = 4_000 * 1024

    /** 1080p-эфир (режим без записи) — верхняя граница 6 Мбит/с. */
    const val MAX_BITRATE_1080P = 6_000 * 1024

    /** Верхняя граница по умолчанию (1080p-эфир). */
    const val MAX_BITRATE = MAX_BITRATE_1080P

    const val AUDIO_BITRATE = 128_000
    const val AUDIO_SAMPLE_RATE = 44_100
    const val AUDIO_IS_STEREO = true
    const val AUDIO_ECHO_CANCELER = true
    const val AUDIO_NOISE_SUPPRESSOR = false
}
