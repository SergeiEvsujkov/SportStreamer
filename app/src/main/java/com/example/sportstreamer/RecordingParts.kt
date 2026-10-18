package com.example.sportstreamer

/**
 * Разбиение локальной записи на части.
 *
 * Логика вынесена из Activity, чтобы её можно было проверить тестами: варианты длины части
 * и правила образования имён файлов. Первая часть идёт без суффикса, следующие — с номером,
 * чтобы части одного матча было легко отличить в галерее.
 */
object RecordingParts {

    /** Не делить запись. */
    const val NO_SPLIT = 0

    /** Варианты в меню настроек: не делить, 10, 20, 30 минут. */
    val OPTIONS = listOf(NO_SPLIT, 10, 20, 30)

    /** Интервал в миллисекундах или null, если делить не нужно. */
    fun intervalMs(minutes: Int): Long? =
        if (minutes > NO_SPLIT) minutes * 60_000L else null

    /** Имя файла для части: `матч.mp4`, `матч_part2.mp4`, `матч_part3.mp4`. */
    fun fileName(base: String, part: Int): String =
        if (part <= 1) "$base.mp4" else "${base}_part$part.mp4"
}
