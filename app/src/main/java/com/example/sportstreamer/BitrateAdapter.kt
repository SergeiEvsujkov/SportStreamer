package com.example.sportstreamer

import kotlin.math.abs

/**
 * Адаптация битрейта по фактически отправленным байтам.
 *
 * Чистая логика без Android: вызывается по таймеру, возвращает статистику и, при необходимости,
 * новый целевой битрейт. Покрыта unit-тестами (BitrateAdapterTest).
 *
 * Важная физика: энкодер не может отправить больше целевого битрейта, поэтому «сеть успевает»
 * выглядит как фактический битрейт около 100% от цели, а не выше. Соответственно рост начинается
 * уже при [KEEP_RATIO] и выше, а снижение — только при заметной просадке.
 */
class BitrateAdapter(
    initialBitrate: Int = StreamConfig.INITIAL_BITRATE,
    private val minBitrate: Int = StreamConfig.MIN_BITRATE,
    maxBitrate: Int = StreamConfig.MAX_BITRATE
) {

    /** Результат одного замера. */
    data class Stats(
        val actualBitrate: Int,
        /** Новый целевой битрейт или null, если менять не нужно. */
        val newTargetBitrate: Int?
    )

    /**
     * Верхняя граница битрейта. Зависит от разрешения эфира: 720p не нуждается
     * в 1080p-битрейте, а лишние мегабиты — это лишний нагрев.
     */
    var maxBitrate: Int = maxBitrate.coerceAtLeast(minBitrate)
        set(value) {
            field = value.coerceAtLeast(minBitrate)
            if (targetBitrate > effectiveMax) targetBitrate = effectiveMax
        }

    /**
     * Множитель от термоконтроля: 1.0 — норма, 0.7 — сильный нагрев, 0.5 — критично.
     * Эфир при этом не останавливается — снижается только качество.
     */
    var thermalFactor: Float = 1f
        set(value) {
            field = value.coerceIn(0.3f, 1f)
            if (targetBitrate > effectiveMax) targetBitrate = effectiveMax
        }

    /** Фактическая верхняя граница с учётом нагрева. */
    val effectiveMax: Int
        get() = (maxBitrate * thermalFactor).toInt().coerceAtLeast(minBitrate)

    var targetBitrate: Int = initialBitrate.coerceIn(minBitrate, effectiveMax)
        private set

    private val history = ArrayDeque<Int>()
    private var lastUpdateTimeMs = 0L
    private var lastSentBytes = 0L
    private var hasBaseline = false

    /** Начать новую сессию: сбросить историю и точку отсчёта статистики. */
    fun reset(nowMs: Long, sentBytes: Long) {
        history.clear()
        lastUpdateTimeMs = nowMs
        lastSentBytes = sentBytes
        hasBaseline = true
    }

    /**
     * @return статистика замера или null, если прошло меньше [UPDATE_INTERVAL_MS]
     *         (первый вызов только инициализирует точку отсчёта).
     */
    fun onStats(nowMs: Long, sentBytes: Long): Stats? {
        if (!hasBaseline) {
            reset(nowMs, sentBytes)
            return null
        }

        val elapsedMs = nowMs - lastUpdateTimeMs
        if (elapsedMs < UPDATE_INTERVAL_MS) return null

        val bytesSent = (sentBytes - lastSentBytes).coerceAtLeast(0L)
        lastUpdateTimeMs = nowMs
        lastSentBytes = sentBytes

        val actualBitrate = (bytesSent * 8 * 1000 / elapsedMs).toInt()
        val smoothed = smooth(nextBitrate(actualBitrate))

        val newTarget = if (abs(smoothed - targetBitrate) < HYSTERESIS_BITS) {
            null
        } else {
            targetBitrate = smoothed
            targetBitrate
        }

        return Stats(actualBitrate = actualBitrate, newTargetBitrate = newTarget)
    }

    private fun nextBitrate(actualBitrate: Int): Int {
        val ratio = if (targetBitrate > 0) actualBitrate.toDouble() / targetBitrate else 1.0
        val candidate = when {
            ratio < POOR_RATIO -> (targetBitrate * 0.6).toInt()
            ratio < FAIR_RATIO -> (targetBitrate * 0.85).toInt()
            ratio < KEEP_RATIO -> targetBitrate
            // Сеть успевает за целевым битрейтом — пробуем поднять его.
            else -> (targetBitrate * 1.1).toInt()
        }
        return candidate.coerceIn(minBitrate, effectiveMax)
    }

    /** Медиана последних значений сглаживает одиночные выбросы. */
    private fun smooth(candidate: Int): Int {
        history.addLast(candidate)
        while (history.size > MAX_HISTORY) history.removeFirst()
        if (history.size < MIN_HISTORY_FOR_SMOOTHING) return candidate
        val sorted = history.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        /** Как часто пересчитываем битрейт. */
        const val UPDATE_INTERVAL_MS = 3000L

        /** Меньше 128 кбит/с разницы — энкодер не трогаем, чтобы не дёргать картинку. */
        const val HYSTERESIS_BITS = 128 * 1024

        /** Меньше 30% от цели — жёсткая просадка канала. */
        const val POOR_RATIO = 0.3

        /** Меньше 60% от цели — заметная просадка. */
        const val FAIR_RATIO = 0.6

        /** Меньше 85% от цели — терпимо, битрейт не меняем. */
        const val KEEP_RATIO = 0.85

        private const val MAX_HISTORY = 5
        private const val MIN_HISTORY_FOR_SMOOTHING = 3
    }
}
