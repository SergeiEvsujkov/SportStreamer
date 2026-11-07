package com.example.sportstreamer

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.sportstreamer.databinding.ActivitySettingsBinding
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/**
 * Содержимое экрана настроек без привязки к активити.
 *
 * Панель можно показать двумя способами: отдельным экраном ([SettingsActivity], первый запуск)
 * или наложением поверх пульта ([MainActivity]). Во втором случае активити пульта остаётся
 * на экране, её поверхность не уничтожается, и запись/эфир продолжаются без единого колбэка
 * жизненного цикла.
 *
 * Логика, ключи и хранение — в [SettingsRepository]; здесь только состояние экрана.
 */
class SettingsPanel(
    private val activity: AppCompatActivity,
    private val binding: ActivitySettingsBinding,
    private val onClose: () -> Unit,
    private val onThemeSelected: (Int) -> Unit,
    /** true — панель занимает всё окно активити (стилизуем окно), false — живёт поверх пульта. */
    private val styleWindow: Boolean = false
) {

    private val context: Context = activity

    private data class Swatch(val color: Int, val labelRes: Int)

    /** Пока идёт загрузка сохранённых значений, слушатели изменений не должны срабатывать. */
    private var loadingSettings = false

    /**
     * Текущая выбранная тема. Хранится отдельным полем, иначе `persistSettings()` собирает
     * `SettingsLoc` без явного `themeMode` и записывает значение по умолчанию (SYSTEM) —
     * выбор темы терялся при первом же сохранении любых других полей.
     */
    private var selectedThemeMode = ThemeMode.SYSTEM

    private var selectedColor1 = Color.RED
    private var selectedColor2 = Color.BLUE

    /** 20 цветов: базовые, спортивные и нейтральные — есть из чего выбрать обеим командам. */
    private val palette = listOf(
        Swatch("#E53935".toColorInt(), R.string.settings_color_red),
        Swatch("#C2185B".toColorInt(), R.string.settings_color_crimson),
        Swatch("#EC407A".toColorInt(), R.string.settings_color_pink),
        Swatch("#FB8C00".toColorInt(), R.string.settings_color_orange),
        Swatch("#FFB300".toColorInt(), R.string.settings_color_amber),
        Swatch("#FDD835".toColorInt(), R.string.settings_color_yellow),
        Swatch("#C0CA33".toColorInt(), R.string.settings_color_lime),
        Swatch("#43A047".toColorInt(), R.string.settings_color_green),
        Swatch("#00897B".toColorInt(), R.string.settings_color_teal),
        Swatch("#00ACC1".toColorInt(), R.string.settings_color_turquoise),
        Swatch("#039BE5".toColorInt(), R.string.settings_color_sky),
        Swatch("#1E88E5".toColorInt(), R.string.settings_color_blue),
        Swatch("#3949AB".toColorInt(), R.string.settings_color_indigo),
        Swatch("#8E24AA".toColorInt(), R.string.settings_color_purple),
        Swatch("#5E35B1".toColorInt(), R.string.settings_color_violet),
        Swatch("#6D4C41".toColorInt(), R.string.settings_color_brown),
        Swatch("#757575".toColorInt(), R.string.settings_color_gray),
        Swatch("#607D8B".toColorInt(), R.string.settings_color_gray_blue),
        Swatch("#212121".toColorInt(), R.string.settings_color_black),
        Swatch("#FFFFFF".toColorInt(), R.string.settings_color_white)
    )

    private val swatchesPerRow = 5

    /** Кружки команд: держим их списками, чтобы обновлять выделение одним проходом. */
    private val swatchesTeam1 = mutableListOf<MaterialCardView>()
    private val swatchesTeam2 = mutableListOf<MaterialCardView>()

    /**
     * Собирает экран: слушатели и загрузка сохранённых значений.
     * Вызывается один раз после того, как [binding] добавлен в окно.
     */
    fun attach() {
        if (styleWindow) applyWindowSurfaces()
        binding.toolbar.setNavigationOnClickListener { onClose() }
        binding.btnSupport.setOnClickListener {
            // Ссылка на поддержку задаётся одной константой ниже.
            if (SUPPORT_URL.isBlank()) {
                android.widget.Toast.makeText(context, R.string.settings_support_no_link, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(SUPPORT_URL)
                        )
                    )
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, R.string.settings_support_no_link, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Инструкция: содержимое добавляется отдельно, пока кнопка только на месте.
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_guide -> {
                    showGuide()
                    true
                }
                else -> false
            }
        }

        setupTeamNameFields()
        setupColorPickers()
        setupCameraControls()
        setupBroadcastAndRecording()
        setupThemeControls()
        setupAutoSave()

        // Пока грузим сохранённые значения, слушатели не должны применяться:
        // иначе экран пересобирался бы сразу после открытия.
        loadingSettings = true
        loadSavedSettings()
        loadingSettings = false
        updateRtmpHint()
    }

    /**
     * Фиксирует состояние экрана перед закрытием: пустое название заменяем на
     * «Команда 1»/«Команда 2», иначе пульт снова считал бы настройки незаполненными.
     */
    fun commit() {
        if (binding.etTeam1.text.isNullOrBlank()) {
            binding.etTeam1.setText(context.getString(R.string.settings_default_team1))
        }
        if (binding.etTeam2.text.isNullOrBlank()) {
            binding.etTeam2.setText(context.getString(R.string.settings_default_team2))
        }
        persistSettings()
    }

    // ------------------------------------------------------------- настройки

    /** Инструкция для пользователя: как работает, где взять ключи, пульт и предупреждения. */
    private fun showGuide() {
        val sections = listOf(
            R.string.guide_how_title to R.string.guide_how_body,
            R.string.guide_vk_title to R.string.guide_vk_body,
            R.string.guide_yt_title to R.string.guide_yt_body,
            R.string.guide_remote_title to R.string.guide_remote_body,
            R.string.guide_warn_title to R.string.guide_warn_body
        )
        val density = context.resources.displayMetrics.density
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        sections.forEach { (titleRes, bodyRes) ->
            content.addView(android.widget.TextView(context).apply {
                setText(titleRes)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, (14 * density).toInt(), 0, (4 * density).toInt())
            })
            content.addView(android.widget.TextView(context).apply {
                setText(bodyRes)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            })
        }
        val scroll = android.widget.ScrollView(context).apply { addView(content) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.guide_title)
            .setView(scroll)
            .setPositiveButton(R.string.guide_close, null)
            .show()
    }

    private fun setupTeamNameFields() {
        val hint = context.getString(
            R.string.settings_team_name_hint,
            SettingsRepository.TEAM_NAME_MAX_LENGTH
        )
        binding.tilTeam1.hint = hint
        binding.tilTeam2.hint = hint
    }

    /** Раскладывает палитру сеткой по пять кружков в строке — все цвета видно сразу. */
    private fun setupColorPickers() {
        fillColorGrid(binding.colorsContainer1, swatchesTeam1, isTeam1 = true)
        fillColorGrid(binding.colorsContainer2, swatchesTeam2, isTeam1 = false)
    }

    private fun fillColorGrid(
        container: LinearLayout,
        target: MutableList<MaterialCardView>,
        isTeam1: Boolean
    ) {
        container.removeAllViews()
        target.clear()

        palette.chunked(swatchesPerRow).forEach { rowColors ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            rowColors.forEach { swatch ->
                val card = createColorView(swatch, target, isTeam1)
                target += card
                row.addView(card)
            }
            container.addView(row)
        }
    }

    private fun createColorView(
        swatch: Swatch,
        target: MutableList<MaterialCardView>,
        isTeam1: Boolean
    ): MaterialCardView {
        val size = context.resources.getDimensionPixelSize(R.dimen.color_swatch_size)
        val margin = context.resources.getDimensionPixelSize(R.dimen.color_swatch_margin)
        val density = context.resources.displayMetrics.density
        val label = context.getString(swatch.labelRes)

        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            }
            setCardBackgroundColor(swatch.color)
            radius = size / 2f
            cardElevation = density * 2f
            strokeWidth = 0
            // Галочка внутри кружка: видно, какой цвет выбран, даже на светлых оттенках.
            isCheckable = true
            checkedIcon = ContextCompat.getDrawable(context, R.drawable.ic_check)
            checkedIconTint = ColorStateList.valueOf(contrastStrokeColor(swatch.color))
            isClickable = true
            isFocusable = true
            contentDescription = label
            tooltipText = label
            setOnClickListener {
                if (isTeam1) {
                    selectedColor1 = swatch.color
                } else {
                    selectedColor2 = swatch.color
                }
                updateSelection(target, this)
                updateTeamAccent()
                persistSettings()
            }
        }
    }

    private fun updateSelection(swatches: List<MaterialCardView>, selected: MaterialCardView) {
        val strokeWidth = context.resources.getDimensionPixelSize(R.dimen.color_swatch_stroke)
        val selectedElevation = context.resources.displayMetrics.density * 6f
        val normalElevation = context.resources.displayMetrics.density * 2f

        swatches.forEach { card ->
            val isSelected = card === selected
            card.isChecked = isSelected
            card.strokeWidth = if (isSelected) strokeWidth else 0
            card.cardElevation = if (isSelected) selectedElevation else normalElevation
            if (isSelected) {
                // Рамка контрастна самому цвету, а не всегда белая: на белом кружке её видно.
                card.strokeColor = contrastStrokeColor(card.cardBackgroundColor.defaultColor)
            }
        }
    }

    /** Заголовки и рамки карточек команд окрашиваются в выбранный цвет — сразу видно результат. */
    private fun updateTeamAccent() {
        val stroke = context.resources.getDimensionPixelSize(R.dimen.team_card_stroke)
        binding.team1Header.setTextColor(selectedColor1)
        binding.team1Card.strokeWidth = stroke
        binding.team1Card.strokeColor = selectedColor1
        binding.team2Header.setTextColor(selectedColor2)
        binding.team2Card.strokeWidth = stroke
        binding.team2Card.strokeColor = selectedColor2
    }

    private fun contrastStrokeColor(color: Int): Int =
        if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE

    /** Автоэкспозиция и поправка яркости: ползунок активен, только пока экспозиция автоматическая. */
    private fun setupCameraControls() {
        binding.brightnessSlider.addOnChangeListener { _, value, _ ->
            updateBrightnessLabel(value.roundToInt())
            persistSettings()
        }
        binding.switchAutoExposure.setOnCheckedChangeListener { _, checked ->
            binding.brightnessSlider.isEnabled = checked
            updateBrightnessLabel(binding.brightnessSlider.value.roundToInt())
            persistSettings()
        }
        updateBrightnessLabel(binding.brightnessSlider.value.roundToInt())
    }

    private fun updateBrightnessLabel(percent: Int) {
        binding.brightnessLabel.text = context.getString(R.string.settings_brightness_label, percent)
    }

    /** Выбранный режим запуска: эфир, запись или оба. */
    private var selectedMode = StartMode.BOTH

    /**
     * Трансляция и запись. Без трансляции поля RTMP не нужны; поля записи видны, только
     * когда запись входит в выбранный режим.
     */
    private fun setupBroadcastAndRecording() {
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (loadingSettings) return@setOnCheckedChangeListener
            selectedMode = when (checkedId) {
                R.id.rbModeStream -> StartMode.STREAM
                R.id.rbModeRecord -> StartMode.RECORD
                else -> StartMode.BOTH
            }
            updateModeDependentViews(selectedMode)
            updateRtmpHint()
            persistSettings()
        }
        binding.switchStabilization.setOnCheckedChangeListener { _, _ -> persistSettings() }
    }

    /** Видимость карточек и опций зависит от режима: RTMP нужен только для эфира. */
    private fun updateModeDependentViews(mode: Int) {
        // Содержимое карточки «Что запускать» меняется сразу при выборе режима.
        binding.rtmpCard.isVisible = StartMode.includesStream(mode)
        binding.recordOptions.isVisible = StartMode.includesRecording(mode)
    }

    /**
     * Тема сохраняется сразу, а применяется через [onThemeSelected]: решение о том, можно ли
     * пересоздавать экран, принимает хост. Во время эфира пересоздание пульта недопустимо —
     * оно уничтожило бы поверхность превью и прервало запись.
     */
    private fun setupThemeControls() {
        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            if (loadingSettings) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbThemeLight -> ThemeMode.LIGHT
                R.id.rbThemeDark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            // RadioGroup восстанавливает галочку после пересоздания экрана и слушатель
            // срабатывает повторно. Если тема уже такая, второй раз её не применяем.
            if (mode == selectedThemeMode) return@setOnCheckedChangeListener

            selectedThemeMode = mode
            SettingsRepository.saveThemeMode(context, mode)
            onThemeSelected(mode)
            persistSettings()
        }
    }

    private fun loadSavedSettings() {
        val settings = SettingsRepository.load(context)

        binding.etTeam1.setText(settings.team1Name)
        binding.etTeam2.setText(settings.team2Name)
        binding.etRtmpUrl.setText(settings.rtmpUrl)
        binding.etRtmpKey.setText(settings.rtmpKey)

        selectedColor1 = settings.team1Color
        selectedColor2 = settings.team2Color
        restoreColorSelection()

        selectedMode = settings.startMode
        when (settings.startMode) {
            StartMode.STREAM -> binding.rbModeStream.isChecked = true
            StartMode.RECORD -> binding.rbModeRecord.isChecked = true
            else -> binding.rbModeBoth.isChecked = true
        }
        updateModeDependentViews(settings.startMode)

        when (settings.splitMinutes) {
            10 -> binding.rbSplit10.isChecked = true
            20 -> binding.rbSplit20.isChecked = true
            30 -> binding.rbSplit30.isChecked = true
            else -> binding.rbSplitNone.isChecked = true
        }

        when (settings.recordBitrateMbps) {
            1 -> binding.rbBitrate1.isChecked = true
            3 -> binding.rbBitrate3.isChecked = true
            else -> binding.rbBitrate5.isChecked = true
        }

        when (settings.themeMode) {
            ThemeMode.LIGHT -> binding.rbThemeLight.isChecked = true
            ThemeMode.DARK -> binding.rbThemeDark.isChecked = true
            else -> binding.rbThemeSystem.isChecked = true
        }
        selectedThemeMode = settings.themeMode

        binding.switchStabilization.isChecked = settings.videoStabilization
        binding.switchAutoExposure.isChecked = settings.autoExposure
        binding.brightnessSlider.value = settings.exposureCompensation.toFloat()
        binding.brightnessSlider.isEnabled = settings.autoExposure
        updateBrightnessLabel(settings.exposureCompensation)

        if (settings.setsToWin == ScoreEngine.SETS_TO_WIN_BEST_OF_FIVE) {
            binding.rbBestOfFive.isChecked = true
        } else {
            binding.rbBestOfThree.isChecked = true
        }
    }

    private fun restoreColorSelection() {
        selectByColor(swatchesTeam1, selectedColor1)
        selectByColor(swatchesTeam2, selectedColor2)
        updateTeamAccent()
    }

    private fun selectByColor(swatches: List<MaterialCardView>, color: Int) {
        val card = swatches.firstOrNull { it.cardBackgroundColor.defaultColor == color } ?: return
        updateSelection(swatches, card)
    }

    /**
     * Всё сохраняется сразу при изменении, поэтому отдельной кнопки «Сохранить» нет:
     * ни одно изменение не теряется.
     */
    private fun setupAutoSave() {
        binding.etTeam1.doAfterTextChanged { persistSettings() }
        binding.etTeam2.doAfterTextChanged { persistSettings() }
        binding.etRtmpUrl.doAfterTextChanged {
            persistSettings()
            updateRtmpHint()
        }
        binding.etRtmpKey.doAfterTextChanged { persistSettings() }
        binding.rgGames.setOnCheckedChangeListener { _, _ -> persistSettings() }
        binding.rgSplit.setOnCheckedChangeListener { _, _ -> persistSettings() }
        binding.rgRecordBitrate.setOnCheckedChangeListener { _, _ -> persistSettings() }
    }

    /** Сохраняет текущее состояние экрана. Ошибки не блокируют сохранение — только подсказывают. */
    private fun persistSettings() {
        if (loadingSettings) return

        val team1 = binding.etTeam1.text?.toString()?.trim().orEmpty()
        val team2 = binding.etTeam2.text?.toString()?.trim().orEmpty()

        SettingsRepository.save(
            context,
            SettingsLoc(
                team1Name = team1,
                team2Name = team2,
                team1Color = selectedColor1,
                team2Color = selectedColor2,
                setsToWin = if (binding.rbBestOfFive.isChecked) {
                    ScoreEngine.SETS_TO_WIN_BEST_OF_FIVE
                } else {
                    ScoreEngine.SETS_TO_WIN_BEST_OF_THREE
                },
                rtmpUrl = binding.etRtmpUrl.text?.toString()?.trim().orEmpty(),
                rtmpKey = binding.etRtmpKey.text?.toString()?.trim().orEmpty(),
                saveVideoLocally = StartMode.includesRecording(selectedMode),
                broadcastEnabled = StartMode.includesStream(selectedMode),
                startMode = selectedMode,
                splitMinutes = when {
                    binding.rbSplit10.isChecked -> 10
                    binding.rbSplit20.isChecked -> 20
                    binding.rbSplit30.isChecked -> 30
                    else -> RecordingParts.NO_SPLIT
                },
                recordBitrateMbps = when {
                    binding.rbBitrate1.isChecked -> 1
                    binding.rbBitrate3.isChecked -> 3
                    else -> RecordingBitrate.DEFAULT_MBPS
                },
                videoStabilization = binding.switchStabilization.isChecked,
                autoExposure = binding.switchAutoExposure.isChecked,
                exposureCompensation = binding.brightnessSlider.value.roundToInt(),
                themeMode = selectedThemeMode,
                isConfigured = team1.isNotBlank() && team2.isNotBlank()
            )
        )
    }

    /** Подсказка в поле RTMP: пустой или неверный адрес видно сразу, но сохранять он не мешает. */
    private fun updateRtmpHint() {
        if (loadingSettings) return
        val url = binding.etRtmpUrl.text?.toString()?.trim().orEmpty()
        binding.tilRtmpUrl.error = when {
            !StartMode.includesStream(selectedMode) -> null
            url.isEmpty() -> context.getString(R.string.settings_error_rtmp_required)
            !SettingsRepository.isValidRtmpUrl(url) -> context.getString(R.string.settings_error_rtmp_invalid)
            else -> null
        }
    }

    /**
     * Фон окна и цвет системных значков — только когда панель занимает всё окно активити.
     * При показе поверх пульта окно принадлежит ему, и трогать его нельзя.
     */
    private fun applyWindowSurfaces() {
        val surface = com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorSurface
        )
        activity.window.setBackgroundDrawable(ColorDrawable(surface))
        binding.root.setBackgroundColor(surface)

        val darkTheme = (context.resources.configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    private companion object {
        /** Ссылка кнопки «Поддержать проект». Пустая строка — кнопка показывает подсказку. */
        const val SUPPORT_URL = ""
    }
}
