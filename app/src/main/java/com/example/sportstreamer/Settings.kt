package com.example.sportstreamer

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** Настройки приложения. */
data class SettingsLoc(
    val team1Name: String = "",
    val team2Name: String = "",
    val team1Color: Int = Color.RED,
    val team2Color: Int = Color.BLUE,
    /** Сколько партий нужно выиграть для победы: 2 — «3 партии», 3 — «5 партий». */
    val setsToWin: Int = ScoreEngine.SETS_TO_WIN_BEST_OF_THREE,
    val rtmpUrl: String = "",
    val rtmpKey: String = "",
    val saveVideoLocally: Boolean = false,
    /** Трансляция в интернет. Выключено — пишем только в память телефона. */
    val broadcastEnabled: Boolean = true,
    /** Что запускает кнопка пульта: [StartMode.STREAM], [StartMode.RECORD] или [StartMode.BOTH]. */
    val startMode: Int = StartMode.BOTH,
    /** Делить запись на части: 0 — не делить, иначе длина части в минутах. */
    val splitMinutes: Int = RecordingParts.NO_SPLIT,
    /** Битрейт записи в память, Мбит/с: 1, 3 или 5. Действует, когда трансляция выключена. */
    val recordBitrateMbps: Int = RecordingBitrate.DEFAULT_MBPS,
    /** Тема приложения: системная, светлая или тёмная. */
    val themeMode: Int = ThemeMode.SYSTEM,
    /** Стабилизация изображения (электронная, EIS) — если устройство её поддерживает. */
    val videoStabilization: Boolean = false,
    /** Автоматическая экспозиция: камера сама подстраивается под освещение. */
    val autoExposure: Boolean = true,
    /** Поправка яркости к автоэкспозиции в процентах: -100 (темнее) … 100 (светлее). */
    val exposureCompensation: Int = 0,
    val isConfigured: Boolean = false
) {
    val team1DisplayName: String
        get() = team1Name.ifBlank { "" }

    /** Полный адрес для энкодера: URL + ключ с нормализованным разделителем. */
    val fullRtmpUrl: String
        get() = SettingsRepository.buildRtmpUrl(rtmpUrl, rtmpKey)
}

/**
 * Единственное место работы с SharedPreferences: имена ключей, чтение, запись и разбор RTMP-адреса.
 * Раньше ключи читались строками из двух разных файлов — теперь это исключено.
 */
object SettingsRepository {

    /** Максимальная длина названия команды (продублирована в @integer/team_name_max_length). */
    const val TEAM_NAME_MAX_LENGTH = 16

    private const val PREFS_NAME = "AppPrefs"

    private const val KEY_TEAM1_NAME = "team1_name"
    private const val KEY_TEAM2_NAME = "team2_name"
    private const val KEY_TEAM1_COLOR = "team1_color"
    private const val KEY_TEAM2_COLOR = "team2_color"
    private const val KEY_SETS_TO_WIN = "sets_to_win"

    /** Старое имя ключа: значение означало то же самое (число побед), читаем для совместимости. */
    private const val KEY_SETS_TO_WIN_LEGACY = "number_of_games"

    private const val KEY_RTMP_URL = "rtmp_url"
    private const val KEY_RTMP_KEY = "rtmp_key"
    private const val KEY_SAVE_VIDEO = "save_video_locally"
    private const val KEY_BROADCAST_ENABLED = "broadcast_enabled"
    private const val KEY_SPLIT_MINUTES = "split_minutes"
    private const val KEY_RECORD_BITRATE = "record_bitrate_mbps"
    private const val KEY_START_MODE = "start_mode"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_VIDEO_STABILIZATION = "video_stabilization"
    private const val KEY_AUTO_EXPOSURE = "auto_exposure"
    private const val KEY_EXPOSURE_COMPENSATION = "exposure_compensation"
    private const val KEY_IS_CONFIGURED = "is_configured"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): SettingsLoc {
        val prefs = prefs(context)
        val storedSetsToWin = prefs.getInt(KEY_SETS_TO_WIN, 0)
        val setsToWin = if (storedSetsToWin > 0) {
            storedSetsToWin
        } else {
            prefs.getInt(KEY_SETS_TO_WIN_LEGACY, ScoreEngine.SETS_TO_WIN_BEST_OF_THREE)
        }

        // Режим хранится отдельно. Для старых установок выводим его из двух прежних флагов.
        val storedMode = prefs.getInt(KEY_START_MODE, -1)
        val legacyBroadcast = prefs.getBoolean(KEY_BROADCAST_ENABLED, true)
        val legacySave = prefs.getBoolean(KEY_SAVE_VIDEO, false)
        val startMode = when {
            storedMode in StartMode.OPTIONS -> storedMode
            legacyBroadcast && legacySave -> StartMode.BOTH
            legacyBroadcast -> StartMode.STREAM
            legacySave -> StartMode.RECORD
            else -> StartMode.BOTH
        }

        return SettingsLoc(
            team1Name = prefs.getString(KEY_TEAM1_NAME, "").orEmpty(),
            team2Name = prefs.getString(KEY_TEAM2_NAME, "").orEmpty(),
            team1Color = prefs.getInt(KEY_TEAM1_COLOR, Color.RED),
            team2Color = prefs.getInt(KEY_TEAM2_COLOR, Color.BLUE),
            setsToWin = setsToWin,
            rtmpUrl = prefs.getString(KEY_RTMP_URL, "").orEmpty(),
            rtmpKey = prefs.getString(KEY_RTMP_KEY, "").orEmpty(),
            saveVideoLocally = StartMode.includesRecording(startMode),
            broadcastEnabled = StartMode.includesStream(startMode),
            startMode = startMode,
            splitMinutes = prefs.getInt(KEY_SPLIT_MINUTES, RecordingParts.NO_SPLIT),
            recordBitrateMbps = prefs.getInt(KEY_RECORD_BITRATE, RecordingBitrate.DEFAULT_MBPS),
            themeMode = prefs.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM),
            videoStabilization = prefs.getBoolean(KEY_VIDEO_STABILIZATION, false),
            autoExposure = prefs.getBoolean(KEY_AUTO_EXPOSURE, true),
            exposureCompensation = prefs.getInt(KEY_EXPOSURE_COMPENSATION, 0),
            isConfigured = prefs.getBoolean(KEY_IS_CONFIGURED, false)
        )
    }

    fun save(context: Context, settings: SettingsLoc) {
        prefs(context).edit()
            .putString(KEY_TEAM1_NAME, settings.team1Name)
            .putString(KEY_TEAM2_NAME, settings.team2Name)
            .putInt(KEY_TEAM1_COLOR, settings.team1Color)
            .putInt(KEY_TEAM2_COLOR, settings.team2Color)
            .putInt(KEY_SETS_TO_WIN, settings.setsToWin)
            .putString(KEY_RTMP_URL, settings.rtmpUrl)
            .putString(KEY_RTMP_KEY, settings.rtmpKey)
            .putBoolean(KEY_SAVE_VIDEO, StartMode.includesRecording(settings.startMode))
            .putBoolean(KEY_BROADCAST_ENABLED, StartMode.includesStream(settings.startMode))
            .putInt(KEY_START_MODE, settings.startMode)
            .putInt(KEY_SPLIT_MINUTES, settings.splitMinutes)
            .putInt(KEY_RECORD_BITRATE, settings.recordBitrateMbps)
            .putInt(KEY_THEME_MODE, settings.themeMode)
            .putBoolean(KEY_VIDEO_STABILIZATION, settings.videoStabilization)
            .putBoolean(KEY_AUTO_EXPOSURE, settings.autoExposure)
            .putInt(KEY_EXPOSURE_COMPENSATION, settings.exposureCompensation)
            .putBoolean(KEY_IS_CONFIGURED, settings.isConfigured)
            .apply()
    }

    /**
     * Тему сохраняем сразу при выборе: экран пересоздаётся с новой темой, и настройка
     * не должна теряться, если пользователь не нажал «Сохранить».
     */
    fun saveThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    /** Склеивает адрес сервера и ключ трансляции, не допуская двойных и пропущенных слэшей. */    fun buildRtmpUrl(url: String, key: String): String {
        val base = url.trim()
        val streamKey = key.trim()
        return when {
            base.isEmpty() -> streamKey
            streamKey.isEmpty() -> base
            base.endsWith("/") -> base + streamKey
            else -> "$base/$streamKey"
        }
    }

    fun isValidRtmpUrl(url: String): Boolean {
        val normalized = url.trim().lowercase()
        return normalized.startsWith("rtmp://") || normalized.startsWith("rtmps://")
    }
}
