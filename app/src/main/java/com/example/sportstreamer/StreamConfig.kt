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

    /**
     * 32 кГц, как в SportCam.
     *
     * 44.1 кГц для микрофона телефона ничего не добавляет: выше 16 кГц в зале почти
     * нет полезного сигнала, а лишняя полоса только грузит кодировщик.
     */
    const val AUDIO_SAMPLE_RATE = 32_000

    /**
     * Моно: микрофон у телефона один, второй канал лишь дублирует его или добавляет
     * перекос между встроенными микрофонами. Стерео нужно только с внешним микрофоном.
     */
    const val AUDIO_IS_STEREO = false

    /**
     * Эхоподавление выключено, как в SportCam.
     *
     * AcousticEchoCanceler тянет за собой адаптивный фильтр и автоматическую
     * регулировку усиления: уровень плавает «волнами». Без него запись ровнее.
     */
    const val AUDIO_ECHO_CANCELER = false

    const val AUDIO_NOISE_SUPPRESSOR = false
}

/**
 * Разрешение эфира, выбирается в настройках.
 *
 * Хранится как высота кадра: 720 или 1080. Ширина и потолок битрейта выводятся из неё,
 * чтобы профиль нельзя было «собрать» из несогласованных чисел.
 */
object StreamResolution {

    const val HD720 = 720
    const val FULL_HD = 1080

    fun sanitize(height: Int): Int = if (height == FULL_HD) FULL_HD else HD720

    fun width(height: Int): Int = if (sanitize(height) == FULL_HD) 1920 else 1280

    fun maxBitrate(height: Int): Int =
        if (sanitize(height) == FULL_HD) StreamConfig.MAX_BITRATE_1080P else StreamConfig.MAX_BITRATE_720P
}
