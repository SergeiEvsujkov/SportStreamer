package com.example.sportstreamer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.example.sportstreamer.databinding.ActivitySettingsBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * Экран настроек: команды, цвета, формат матча, RTMP и локальная запись.
 *
 * Названия ключей и работа с SharedPreferences полностью вынесены в [SettingsRepository].
 */
class SettingsActivity : AppCompatActivity() {

    private data class Swatch(val color: Int, val labelRes: Int)

    private lateinit var binding: ActivitySettingsBinding

    /** Пока идёт загрузка сохранённых значений, слушатели изменений не должны срабатывать. */
    private var loadingSettings = false

    /**
     * Текущая выбранная тема. Хранится отдельным полем, иначе `persistSettings()` собирает
     * `SettingsLoc` без явного `themeMode` и записывает в настройки значение по умолчанию
     * (`SYSTEM`, 0) — выбор темы терялся при первом же сохранении любых других полей.
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.RECORD_AUDIO] == true
        if (!granted) {
            Toast.makeText(this, R.string.permissions_camera_audio_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ensurePermissions()
    }

    /**
     * Собирает экран: инфляция, слушатели и загрузка сохранённых значений.
     * Вызывается из [onCreate]; смена темы пересоздаёт активити, поэтому пересобирать
     * содержимое вручную не нужно (Material-компоненты берут палитру при создании экрана).
     */
    private fun buildUi() {
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        applyThemeSurfaces()
        binding.toolbar.setNavigationOnClickListener { finish() }

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
        // Новое дерево представлений подключается к окну уже после первой раздачи insets —
        // просим их заново, иначе шапка уезжает под статус-бар, а карточки под навигацию.
        ViewCompat.requestApplyInsets(binding.root)
    }

    // ------------------------------------------------------------- настройки

    private fun setupTeamNameFields() {
        val hint = getString(R.string.settings_team_name_hint, SettingsRepository.TEAM_NAME_MAX_LENGTH)
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
            val row = LinearLayout(this).apply {
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
        val size = resources.getDimensionPixelSize(R.dimen.color_swatch_size)
        val margin = resources.getDimensionPixelSize(R.dimen.color_swatch_margin)
        val density = resources.displayMetrics.density
        val label = getString(swatch.labelRes)

        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            }
            setCardBackgroundColor(swatch.color)
            radius = size / 2f
            cardElevation = density * 2f
            strokeWidth = 0
            // Галочка внутри кружка: видно, какой цвет выбран, даже на светлых оттенках.
            isCheckable = true
            checkedIcon = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.ic_check)
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
        val strokeWidth = resources.getDimensionPixelSize(R.dimen.color_swatch_stroke)
        val selectedElevation = resources.displayMetrics.density * 6f
        val normalElevation = resources.displayMetrics.density * 2f

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
        val stroke = resources.getDimensionPixelSize(R.dimen.team_card_stroke)
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
        binding.brightnessLabel.text = getString(R.string.settings_brightness_label, percent)
    }

    /**
     * Трансляция и запись. Без трансляции поля RTMP не нужны, а запись в память становится
     * единственным смыслом работы — поэтому она включается принудительно.
     */
    private fun setupBroadcastAndRecording() {
        binding.switchBroadcast.setOnCheckedChangeListener { _, enabled ->
            updateBroadcastDependentViews(enabled)
            updateRtmpHint()
            persistSettings()
        }
        binding.switchSaveVideo.setOnCheckedChangeListener { _, enabled ->
            updateRecordingOptionsVisibility(enabled)
            persistSettings()
        }
        binding.switchStabilization.setOnCheckedChangeListener { _, _ -> persistSettings() }
    }

    /** Деление записи и битрейт нужны только тогда, когда запись в память вообще включена. */
    private fun updateRecordingOptionsVisibility(saveVideoEnabled: Boolean) {
        binding.splitLabel.isVisible = saveVideoEnabled
        binding.rgSplit.isVisible = saveVideoEnabled
        binding.recordBitrateLabel.isVisible = saveVideoEnabled
        binding.rgRecordBitrate.isVisible = saveVideoEnabled
        binding.recordBitrateHint.isVisible = saveVideoEnabled
    }

    private fun updateBroadcastDependentViews(broadcastEnabled: Boolean) {
        binding.rtmpCard.isVisible = broadcastEnabled
        updateRecordingOptionsVisibility(binding.switchSaveVideo.isChecked)
    }

    /**
     * Тема применяется и сохраняется сразу: выбор должен быть виден без перезапуска,
     * а экран пересоздаётся системой — введённые поля при этом восстанавливаются.
     */
    private fun setupThemeControls() {
        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            if (loadingSettings) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbThemeLight -> ThemeMode.LIGHT
                R.id.rbThemeDark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            // После перестройки экрана (смена uiMode) RadioGroup восстанавливает галочку сам,
            // уже после onCreate — и слушатель срабатывает повторно. Если тема уже такая,
            // второй раз её не применяем: иначе setDefaultNightMode() запускает новое
            // пересоздание, и экран мигает бесконечно.
            if (mode == selectedThemeMode) return@setOnCheckedChangeListener

            selectedThemeMode = mode
            SettingsRepository.saveThemeMode(this, mode)
            AppCompatDelegate.setDefaultNightMode(ThemeMode.nightMode(mode))
            persistSettings()
        }
    }

    private fun loadSavedSettings() {
        val settings = SettingsRepository.load(this)

        binding.etTeam1.setText(settings.team1Name)
        binding.etTeam2.setText(settings.team2Name)
        binding.etRtmpUrl.setText(settings.rtmpUrl)
        binding.etRtmpKey.setText(settings.rtmpKey)

        selectedColor1 = settings.team1Color
        selectedColor2 = settings.team2Color
        restoreColorSelection()

        binding.switchBroadcast.isChecked = settings.broadcastEnabled
        binding.switchSaveVideo.isChecked = settings.saveVideoLocally
        updateBroadcastDependentViews(settings.broadcastEnabled)

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
     * Всё сохраняется сразу при изменении, поэтому отдельной кнопки «Сохранить» больше нет:
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
            this,
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
                saveVideoLocally = binding.switchSaveVideo.isChecked,
                broadcastEnabled = binding.switchBroadcast.isChecked,
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
            !binding.switchBroadcast.isChecked -> null
            url.isEmpty() -> getString(R.string.settings_error_rtmp_required)
            !SettingsRepository.isValidRtmpUrl(url) -> getString(R.string.settings_error_rtmp_invalid)
            else -> null
        }
    }

    /**
     * Уход с экрана: пустое название заменяем на «Команда 1»/«Команда 2», иначе пульт
     * снова открывал бы настройки вместо работы.
     */
    override fun onPause() {
        if (binding.etTeam1.text.isNullOrBlank()) {
            binding.etTeam1.setText(getString(R.string.settings_default_team1))
        }
        if (binding.etTeam2.text.isNullOrBlank()) {
            binding.etTeam2.setText(getString(R.string.settings_default_team2))
        }
        persistSettings()
        super.onPause()
    }

    // ------------------------------------------------------------ служебное

    private fun ensurePermissions() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    /**
     * Фон окна и цвет системных значков. При смене темы «на месте» windowBackground остаётся
     * от прежней темы и светлой полосой проступает под статус-баром и навигацией, поэтому
     * перекрашиваем окно вручную по текущей теме.
     */
    private fun applyThemeSurfaces() {
        val surface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        window.setBackgroundDrawable(ColorDrawable(surface))
        binding.root.setBackgroundColor(surface)

        val darkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    private companion object {
        const val TAG = "SettingsActivity"
    }
}


