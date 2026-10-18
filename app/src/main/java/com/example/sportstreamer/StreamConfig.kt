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

    /** 7 Мбит/с — верхняя граница адаптации. */
    const val MAX_BITRATE = 7_000 * 1024

    const val AUDIO_BITRATE = 128_000
    const val AUDIO_SAMPLE_RATE = 44_100
    const val AUDIO_IS_STEREO = true
    const val AUDIO_ECHO_CANCELER = true
    const val AUDIO_NOISE_SUPPRESSOR = false
}
