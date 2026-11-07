package com.example.sportstreamer

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.sportstreamer.databinding.ActivityMainBinding
import com.example.sportstreamer.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Пульт трансляции: счёт, оверлеи, RTMP и локальная запись.
 *
 * Логика матча вынесена в [ScoreEngine], адаптация битрейта — в [BitrateAdapter],
 * запись — в [VideoRecorder], оверлеи — в [ScoreOverlay], доступ к настройкам — в [SettingsRepository].
 */
class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsLoc
    private lateinit var engine: ScoreEngine
    private lateinit var bitrateAdapter: BitrateAdapter

    private var rtmpCamera2: RtmpCamera2? = null
    private var overlay: ScoreOverlay? = null
    private var recorder: VideoRecorder? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Единственный цикл адаптации битрейта: сам себя останавливает, когда трансляция кончилась. */
    private val bitrateTicker = object : Runnable {
        override fun run() {
            val camera = rtmpCamera2 ?: return
            if (!camera.isStreaming) return
            updateBitrateStats(camera)
            // Держим плашки в актуальном состоянии: соединение может пропасть между кликами.
            updateUi()
            handler.postDelayed(this, BitrateAdapter.UPDATE_INTERVAL_MS)
        }
    }

    private var surfaceHolderCallback: SurfaceHolder.Callback? = null
    private var streamPrepared = false

    /** Работает ли превью: `stopStream()` закрывает камеру, и её нужно открыть заново. */
    private var previewRunning = false

    /**
     * Открытая панель настроек. Пока она на экране, пульт не останавливается, поверхность
     * превью не уничтожается, запись и эфир продолжаются.
     */
    private var settingsPanel: SettingsPanel? = null

    /** Контейнер панели настроек: он же приёмник смахивания и объект поворота. */
    private var settingsPanelContainer: SwipeDismissFrameLayout? = null

    /** Запрошен ли второй энкодер на следующую подготовку (переключение во время эфира). */
    private var forceDualEncoders = false

    /** Подняты ли сейчас два энкодера: эфирный 720p и записывающий 1080p. */
    private var dualEncodersActive = false

    /** Слушатель переразметки панели настроек: снимаем его при закрытии, иначе он копит ссылки. */
    private var panelLayoutListener: View.OnLayoutChangeListener? = null

    /** GL переведён в фоновый режим (работает без SurfaceView), пока сессия продолжается. */
    private var backgroundGl = false

    /** Номер сессии открытия настроек: отсекает устаревшие запросы ориентации. */
    private var settingsSession = 0

    /** Настройки уже показаны при первом запуске — второй раз не открываем. */
    private var awaitingSettings = false

    /** Превью развёрнуто на весь экран. */
    private var previewFullscreen = false

    /** Параметры превью до разворота — чтобы вернуть их обратно. */
    private var collapsedPreviewParams: ConstraintLayout.LayoutParams? = null

    /** Текущий зум камеры (значение из диапазона getZoomRange()). */
    private var currentZoom = 1f

    /** Удержание кнопок зума: пока палец на кнопке, значение меняется маленькими шагами. */
    private var zoomHoldRunnable: Runnable? = null
    private var zoomHoldDirection = 0
    private var zoomHoldStartedAt = 0L
    private var zoomHoldTicks = 0

    /** Предупреждение о неподдерживаемой стабилизации показываем один раз за запуск. */
    private var stabilizationWarningShown = false

    /** Номер текущей части записи и задача на её закрытие (деление записи по минутам). */
    private var recordingPart = 0
    private var splitRunnable: Runnable? = null

    /** Время начала текущего файла: из него считается «00:42» в нижней строке пульта. */
    private var recordingStartedAtMs = 0L

    /**
     * Идёт ли запись в память прямо сейчас.
     *
     * Единственный источник правды — наш дескриптор файла. `camera.isRecording` для этого
     * не годится: библиотека отдаёт `false` сразу после `startRecord()` и на время смены части,
     * из-за чего плашка «Запись» пропадала, тикер таймера останавливался, а повторное нажатие
     * кнопки запускало вторую запись вместо остановки.
     */
    private val isRecordingNow: Boolean
        get() = recorder?.isActive == true

    /** Секундный тикер времени записи; сам останавливается, когда запись кончилась. */
    private val recordTicker = object : Runnable {
        override fun run() {
            // Ориентируемся на наш собственный дескриптор, а не на camera.isRecording:
            // библиотека отдаёт false сразу после startRecord и на время смены части записи,
            // из-за чего таймер и плашка «Запись» пропадали.
            if (recorder?.isActive != true) return
            // Таймер и плашка записи обновляются раз в секунду — состояние не «залипает».
            updateUi()
            handler.postDelayed(this, RECORD_TICK_MS)
        }
    }

    /**
     * Поток остановлен самим пользователем — тогда обрыв не перезапускаем.
     *
     * Снимается только при следующем явном запуске. Сбрасывать флаг сразу после `stopStream()`
     * нельзя: библиотека гасит соединение в корутине на Dispatchers.IO и вызывает
     * `ConnectChecker.onDisconnect()` уже постом в главный поток, то есть после возврата из
     * остановки. Со сброшенным флагом `onDisconnect()` принимал нашу же остановку за обрыв
     * связи и через `scheduleHardStreamRestart()` поднимал трансляцию обратно.
     */
    private var userStoppedStream = false

    /**
     * Реально ли установлено соединение с сервером.
     *
     * `camera.isStreaming` для этого не годится: библиотека выставляет его ещё до подключения
     * и не сбрасывает после неудачи, поэтому после «Connection refused» пульт показывал «В эфире»,
     * а проверки по `isStreaming` блокировали переподключение.
     */
    @Volatile
    /** Последние записанные в лог отступы безопасной зоны — чтобы не писать их повторно. */
    private var lastSafeArea: String? = null

    /** Последние показания телеметрии: дропы считаем как разницу между опросами. */
    private var lastDroppedFrames = 0L
    private var lastTelemetryLogMs = 0L

    /** Текущий термостатус устройства (Android 10+): по нему видно, греется ли телефон. */
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var powerManager: PowerManager? = null

    private var streamConnected = false

    /** Сколько раз поднимали поток «с нуля» после исчерпания автопереподключений библиотеки. */
    private var streamHardRestartCount = 0

    /** Отложенный перезапуск потока: библиотека исчерпала свои попытки переподключения. */
    private val hardRestartRunnable = Runnable { restartStreamFromScratch() }

    private var retryCount = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.RECORD_AUDIO] == true
        if (granted) {
            toast(getString(R.string.main_permissions_granted))
            prepareStream()
        } else {
            toast(getString(R.string.permissions_camera_audio_required))
            binding.bitrate.text = getString(R.string.main_bitrate_disabled)
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // В светлой теме системные значки должны быть тёмными — иначе они белые на белом.
            val darkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPanel != null) {
                    hideSettingsPanel()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        settings = SettingsRepository.load(this)
        engine = ScoreEngine(settings.setsToWin)
        savedInstanceState?.let(::restoreMatchState)
        bitrateAdapter = BitrateAdapter()

        val camera = RtmpCamera2(binding.openGlView, this)
        rtmpCamera2 = camera
        overlay = ScoreOverlay(camera, resources)
        recorder = VideoRecorder(this)

        // Запоминаем исходные параметры превью до любых изменений — они нужны для возврата
        // из полноэкранного режима.
        collapsedPreviewParams = ConstraintLayout.LayoutParams(
            binding.openGlView.layoutParams as ConstraintLayout.LayoutParams
        )

        applyWindowInsets()
        setupClickListeners()
        registerThermalMonitor()
        updateUi()

        if (!settings.isConfigured) {
            // Настройки откроет onResume — только когда пульт действительно на экране.
            // Из onCreate открывать нельзя: при смене темы активити пересоздаётся в фоне,
            // и onCreate ставил второй экран настроек поверх уже открытого.
            return
        }

        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return

        // Возвращаемся из фона во время сессии: подключаем GL обратно к SurfaceView.
        if (backgroundGl) {
            backgroundGl = false
            val camera = rtmpCamera2
            if (camera != null) {
                try {
                    camera.replaceView(binding.openGlView)
                    overlay?.invalidate()
                    attachOverlayWhenGlReady()
                    applyZoom()
                    applyVideoStabilization()
                    applyExposure()
                    Log.i(TAG, "Возврат из фона: GL снова на экране")
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось вернуть GL на экран — останавливаемся", e)
                    stopEverything(showToasts = true)
                }
            }
        }

        settings = SettingsRepository.load(this)
        if (!settings.isConfigured) {
            if (!awaitingSettings) {
                awaitingSettings = true
                showSettingsPanel()
            }
            return
        }
        if (awaitingSettings) {
            // Настройки заполнили — досоздаём пульт. recreate() здесь использовать нельзя:
            // активити перезапускается со старым savedInstanceState, где awaitingSettings = true,
            // флаг восстанавливается снова, и экран пересоздаётся бесконечно (проверено на
            // устройстве: чёрный экран и relaunch каждые ~300 мс). В onCreate до этого места
            // камера, оверлей и рекордер уже созданы — не хватало только разрешений и превью.
            awaitingSettings = false
            updateUi()
            ensurePermissions()
            return
        }

        if (settings.setsToWin != engine.setsToWin) {
            // Формат матча изменили в настройках — начинаем матч заново.
            engine = ScoreEngine(settings.setsToWin)
        }

        // Свёртывание и остановка потока гасят GL-интерфейс: сначала возвращаем превью,
        // затем графику счёта.
        rtmpCamera2?.let { ensurePreview(it) }
        if (previewRunning) {
            attachOverlayWhenGlReady()
            // Настройку могли изменить на экране настроек — применяем заново.
            applyVideoStabilization()
            applyExposure()
        }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        // Если идёт эфир или запись — не останавливаемся: библиотека умеет работать без
        // SurfaceView, поэтому переводим GL в фоновый режим и продолжаем сессию.
        val camera = rtmpCamera2
        if (camera != null && (camera.isStreaming || isRecordingNow)) {
            try {
                camera.replaceView(applicationContext)
                backgroundGl = true
                Log.i(TAG, "Свёрнуто во время сессии: GL переведён в фоновый режим")
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось уйти в фоновый GL — останавливаемся", e)
                stopEverything(showToasts = true)
            }
            return
        }
        stopEverything(showToasts = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val snapshot = engine.snapshot()
        outState.putInt(STATE_TEAM1_SCORE, snapshot.team1Score)
        outState.putInt(STATE_TEAM2_SCORE, snapshot.team2Score)
        outState.putInt(STATE_TEAM1_SETS, snapshot.team1Sets)
        outState.putInt(STATE_TEAM2_SETS, snapshot.team2Sets)
        outState.putBoolean(STATE_DECLINED, snapshot.setCompletionDeclined)
        outState.putBoolean(STATE_AWAITING_SETTINGS, awaitingSettings)
    }

    override fun onDestroy() {
        super.onDestroy()
        StreamService.stop(this)
        remoteServer?.stop()
        remoteServer = null
        panelLayoutListener?.let { binding.settingsHost.removeOnLayoutChangeListener(it) }
        panelLayoutListener = null
        stopZoomHold()
        powerManager?.removeThermalStatusListener(thermalListener)
        handler.removeCallbacksAndMessages(null)

        val camera = rtmpCamera2
        if (camera != null) {
            // Каждый шаг отдельно: если один упадёт, остальные всё равно должны выполниться.
            try {
                if (isRecordingNow) camera.stopRecord()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки записи при закрытии", e)
            }
            try {
                if (camera.isStreaming) camera.stopStream()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки RTMP при закрытии", e)
            }
            try {
                camera.stopPreview()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки превью при закрытии", e)
            }
        }

        // Запись ещё активна (приложение закрыли, не доведя до onPause): доводим файл
        // до конца и публикуем. discard() — только если финализация не удалась,
        // иначе вместе с мусором удалялся бы целый матч.
        if (recorder?.isActive == true) {
            if (recorder?.stop() != true) recorder?.discard()
        }

        surfaceHolderCallback?.let { binding.openGlView.holder.removeCallback(it) }
        surfaceHolderCallback = null

        binding.streamBtn.setOnClickListener(null)
        binding.recordBtn.setOnClickListener(null)
        binding.remoteBtn.setOnClickListener(null)
        binding.settingsBtn.setOnClickListener(null)
        binding.fullscreenBtn.setOnClickListener(null)
        binding.collapseBtn.setOnClickListener(null)
        binding.zoomInBtn.setOnClickListener(null)
        binding.zoomOutBtn.setOnClickListener(null)
        binding.firstPlusButton.setOnClickListener(null)
        binding.firstMinusButton.setOnClickListener(null)
        binding.secondPlusButton.setOnClickListener(null)
        binding.secondMinusButton.setOnClickListener(null)

        overlay?.detach()
        rtmpCamera2 = null
        // Повторная очистка: stopStream() запускает disconnect() в корутине, и колбэки могли
        // успеть поставить задачи в handler уже после первой очистки.
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- счёт

    private fun setupClickListeners() {
        binding.firstPlusButton.setOnClickListener { onPoint(ScoreEngine.Team.FIRST, add = true) }
        binding.firstMinusButton.setOnClickListener { onPoint(ScoreEngine.Team.FIRST, add = false) }
        binding.secondPlusButton.setOnClickListener { onPoint(ScoreEngine.Team.SECOND, add = true) }
        binding.secondMinusButton.setOnClickListener { onPoint(ScoreEngine.Team.SECOND, add = false) }

        binding.streamBtn.setOnClickListener { toggleSession() }
        // Вторая кнопка больше не нужна: режим выбирается в настройках, запуск — одной кнопкой.
        binding.recordBtn.isVisible = false
        binding.fullscreenBtn.setOnClickListener { setPreviewFullscreen(true) }
        binding.collapseBtn.setOnClickListener { setPreviewFullscreen(false) }
        // Короткое нажатие — один шаг, удержание — плавное изменение (startZoomHold).
        // Счётчик тиков обнуляем, чтобы следующее нажатие снова сработало (в том числе через TalkBack).
        binding.zoomInBtn.setOnClickListener {
            if (zoomHoldTicks == 0) zoomStep(1)
            zoomHoldTicks = 0
        }
        binding.zoomOutBtn.setOnClickListener {
            if (zoomHoldTicks == 0) zoomStep(-1)
            zoomHoldTicks = 0
        }
        binding.zoomInBtn.setOnTouchListener { _, event -> handleZoomTouch(event, 1) }
        binding.zoomOutBtn.setOnTouchListener { _, event -> handleZoomTouch(event, -1) }
        binding.remoteBtn.setOnClickListener { showRemoteDialog() }
        binding.settingsBtn.setOnClickListener { showSettingsPanel() }
    }

    /**
     * Раскладывает панель как портретный экран внутри ландшафтного окна.
     *
     * Локальные размеры: ширина = высоте окна, высота = ширине окна. После поворота на 90°
     * ограничивающий прямоугольник совпадает с окном, а содержимое читается так, будто
     * телефон держат вертикально. Ориентация самого окна при этом не меняется — поверхность
     * превью не пересоздаётся, и запись продолжается.
     */
    private fun applyPortraitLayout(host: FrameLayout, panel: View) {
        val hostWidth = host.width
        val hostHeight = host.height
        if (hostWidth == 0 || hostHeight == 0) return

        // -90° — читаемый текст при обычном хвате (верхний край телефона справа).
        // Если понадобится обратный хват, знак меняется на 90f.
        panel.rotation = -90f
        val params = panel.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(hostHeight, hostWidth, Gravity.CENTER)
        params.width = hostHeight
        params.height = hostWidth
        params.gravity = Gravity.CENTER
        panel.layoutParams = params
    }

    /**
     * Раскладка панели под текущую ориентацию окна.
     *
     * Если ничего не пишется и не транслируется, окно переводится в портрет — тогда и
     * системная клавиатура открывается вертикально, а панель занимает экран как есть.
     * Во время эфира ориентацию менять нельзя: это уничтожило бы поверхность превью и
     * прервало запись, поэтому панель остаётся повёрнутой внутри ландшафтного окна.
     */
    private fun applyPanelLayout() {
        val panel = settingsPanelContainer ?: return
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (portrait) {
            panel.rotation = 0f
            panel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            return
        }
        applyPortraitLayout(binding.settingsHost, panel)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(
            TAG,
            "ориентация: " +
                if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) "портрет" else "ландшафт" +
                ", панель " + (if (settingsPanel != null) "открыта" else "закрыта")
        )
        if (settingsPanel != null) applyPanelLayout()
    }

    /**
     * Показывает настройки панелью поверх пульта.
     *
     * Отдельная активити для этого не годится: её запуск переводит пульт в `onPause`
     * (там всё останавливается) и уничтожает поверхность `OpenGlView`, из-за чего
     * камера и GL гасятся, а запись обрывается. Панель живёт в том же окне — ни одного
     * колбэка жизненного цикла не приходит, кадры продолжают идти в энкодер.
     */
    private fun showSettingsPanel() {
        if (settingsPanel != null) return

        val live = isRecordingNow || rtmpCamera2?.isStreaming == true

        val panelBinding = ActivitySettingsBinding.inflate(layoutInflater, binding.settingsHost, false)
        binding.settingsHost.removeAllViews()
        // Ребёнок будет больше окна по высоте и повёрнут — обрезать его нельзя.
        binding.settingsHost.clipChildren = false
        binding.settingsHost.clipToPadding = false

        // Смахивание закрывает настройки. Контейнер стоит снаружи повёрнутой панели,
        // поэтому его оси совпадают с тем, что видит пользователь: горизонтальный жест —
        // закрытие, вертикальный — прокрутка списка настроек.
        val swipeContainer = SwipeDismissFrameLayout(this).apply {
            onDismiss = { hideSettingsPanel() }
        }
        swipeContainer.addView(
            panelBinding.root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        binding.settingsHost.addView(
            swipeContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        settingsPanelContainer = swipeContainer
        // Пока идёт эфир или запись, ориентацию окна не трогаем: смена ориентации
        // уничтожает поверхность превью и обрывает запись. В остальное время переводим
        // окно в портрет, чтобы клавиатура ввода тоже была вертикальной.
        //
        // Запрос откладываем на кадр: если панель только что закрывали, возврат в ландшафт
        // ещё в пути, и два запроса подряд система сводит в один — тогда клавиатура остаётся
        // горизонтальной. Номер сессии отсекает устаревшие запросы.
        val session = ++settingsSession
        binding.settingsHost.post {
            if (settingsSession != session || settingsPanel == null) return@post
            requestedOrientation = if (live) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        applyPanelLayout()
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPanelLayout()
        }
        panelLayoutListener = layoutListener
        binding.settingsHost.addOnLayoutChangeListener(layoutListener)
        // Панель занимает весь экран, поэтому отступы системных панелей добавляем сами.
        ViewCompat.setOnApplyWindowInsetsListener(panelBinding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(panelBinding.root)

        val panel = SettingsPanel(
            activity = this,
            binding = panelBinding,
            onClose = { hideSettingsPanel() },
            onThemeSelected = { mode -> applyThemeFromPanel(mode) }
        )
        settingsPanel = panel
        panel.attach()
        binding.settingsHost.isVisible = true
        Log.i(TAG, "Настройки открыты панелью поверх пульта; запись и эфир не прерываются")
    }

    /** Закрывает панель и применяет изменившиеся настройки к пульту. */
    /** Сервер удалённого пульта: работает, пока открыт диалог сопряжения. */
    private var remoteServer: RemoteScoreServer? = null

    /**
     * Показывает адрес и код для второго телефона и принимает с него команды счёта.
     *
     * Пока диалог открыт, сервер слушает порт; при закрытии — останавливается.
     */
    private fun showRemoteDialog() {
        if (remoteServer != null) return
        val pin = RemoteScoreProtocol.generatePin()
        val address = localIpAddress()
        val server = RemoteScoreServer(pin, object : RemoteScoreServer.Listener {
            override fun onPoint(team: Int, add: Boolean) {
                onPoint(if (team == 1) ScoreEngine.Team.FIRST else ScoreEngine.Team.SECOND, add)
            }

            override fun onUndo() {
                Log.i(TAG, "Пульт: отмена не поддерживается и игнорируется")
            }

            override fun onClientConnected() {
                toast(getString(R.string.remote_connected))
            }

            override fun onClientDisconnected() {
                toast(getString(R.string.remote_disconnected))
            }
        })
        remoteServer = server
        server.start()

        val message = if (address == null) {
            getString(R.string.remote_no_address, pin)
        } else {
            getString(R.string.remote_dialog_message, "$address:${RemoteScoreProtocol.PORT}", pin)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                remoteServer?.stop()
                remoteServer = null
            }
            .show()
    }

    /** Адрес телефона в локальной сети: первый IPv4, не относящийся к loopback. */
    private fun localIpAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось определить локальный адрес", e)
        null
    }

    private fun hideSettingsPanel() {
        val panel = settingsPanel ?: return
        panel.commit()
        settingsPanel = null
        settingsPanelContainer = null
        // Снимаем слушатель переразметки: иначе он держит уничтоженное дерево панели.
        panelLayoutListener?.let { binding.settingsHost.removeOnLayoutChangeListener(it) }
        panelLayoutListener = null
        settingsSession++
        binding.settingsHost.removeAllViews()
        binding.settingsHost.isVisible = false
        // Пульт всегда ландшафтный: после закрытия настроек возвращаем окно в него.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        settings = SettingsRepository.load(this)
        if (settings.setsToWin != engine.setsToWin) {
            // Формат матча изменили в настройках — начинаем матч заново.
            engine = ScoreEngine(settings.setsToWin)
        }
        val camera = rtmpCamera2
        if (camera != null && !previewRunning) {
            ensurePreview(camera)
        }
        if (previewRunning) {
            attachOverlayWhenGlReady()
            applyVideoStabilization()
            applyExposure()
        }
        // Первый запуск: настройки заполнили только что — разрешения и превью ещё не запрошены.
        ensurePermissions()
        updateUi()
    }

    /**
     * Смена темы из панели.
     *
     * `setDefaultNightMode` пересоздаёт активити, а вместе с ней уничтожается поверхность
     * превью и обрывается запись. Пока идёт эфир или запись, выбор сохраняется и применится
     * после остановки — об этом честно сообщаем.
     */
    private fun applyThemeFromPanel(mode: Int) {
        val live = isRecordingNow || rtmpCamera2?.isStreaming == true
        if (live) {
            toast(getString(R.string.settings_theme_applied_later))
            return
        }
        AppCompatDelegate.setDefaultNightMode(ThemeMode.nightMode(mode))
    }

    private fun onPoint(team: ScoreEngine.Team, add: Boolean) {
        if (add && engine.isMatchOver) {
            toast(getString(R.string.main_match_over_toast))
            return
        }

        val result = if (add) {
            engine.addPoint(team)
        } else {
            engine.removePoint(team)
            ScoreEngine.PointResult.ScoreChanged
        }

        when (result) {
            is ScoreEngine.PointResult.SetFinished -> showSetFinishedDialog(result.team)
            is ScoreEngine.PointResult.MatchFinished -> showMatchFinishedDialog(result.team)
            ScoreEngine.PointResult.Ignored, ScoreEngine.PointResult.ScoreChanged -> Unit
        }

        onMatchChanged()
    }

    private fun showSetFinishedDialog(winner: ScoreEngine.Team) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_set_finished_title)
            .setMessage(
                getString(
                    R.string.main_set_finished_message,
                    engine.team1Score,
                    engine.team2Score
                )
            )
            .setCancelable(false)
            .setPositiveButton(R.string.main_set_finished_positive) { _, _ ->
                engine.startNewSet(winner)
                onMatchChanged()
            }
            .setNegativeButton(R.string.main_set_finished_negative) { dialog, _ ->
                engine.declineSetCompletion()
                dialog.dismiss()
            }
            .show()
    }

    private fun showMatchFinishedDialog(winner: ScoreEngine.Team) {
        val winnerName = if (winner == ScoreEngine.Team.FIRST) settings.team1Name else settings.team2Name
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_match_finished_title)
            .setMessage(
                getString(
                    R.string.main_match_finished_message,
                    winnerName,
                    engine.team1Sets,
                    engine.team2Sets
                )
            )
            .setCancelable(false)
            .setPositiveButton(R.string.main_match_finished_positive) { _, _ ->
                engine.resetMatch()
                onMatchChanged()
            }
            .setNegativeButton(R.string.main_match_finished_negative) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun onMatchChanged() {
        // Меняем только надписи: геометрия оверлея задаётся один раз в attach()
        // (RootEncoder сдвигает спрайт при повторном setPosition/setScale).
        overlay?.updateTexts(overlayState())
        updateUi()
    }

    private fun overlayState(): ScoreOverlay.State = ScoreOverlay.State(
        team1Name = settings.team1Name,
        team2Name = settings.team2Name,
        team1Score = engine.team1Score,
        team2Score = engine.team2Score,
        team1Sets = engine.team1Sets,
        team2Sets = engine.team2Sets
    )

    private fun updateUi() {
        binding.titleSet.text = if (engine.isMatchOver) {
            getString(R.string.main_match_finished)
        } else {
            getString(R.string.main_set_label, engine.currentSetNumber)
        }
        binding.scoreCardFirst.text = engine.team1Score.toString()
        binding.scoreCardSecond.text = engine.team2Score.toString()
        binding.nameFirstTeam.text = settings.team1Name
        binding.nameSecondTeam.text = settings.team2Name

        // Полоски над табло — в тех же цветах, что и на оверлее трансляции.
        binding.team1Accent.setBackgroundColor(settings.team1Color)
        binding.team2Accent.setBackgroundColor(settings.team2Color)

        // Верхняя строка — эфир: плашка только при реально установленном соединении
        // (camera.isStreaming остаётся true и во время безуспешных переподключений).
        val streaming = streamConnected
        // Состояние записи берём из нашего дескриптора: camera.isRecording кратковременно false
        // сразу после старта записи и при смене части, из-за чего плашка «Запись» пропадала.
        val recording = isRecordingNow
        binding.liveChip.visibility = if (streaming) View.VISIBLE else View.GONE

        // Нижняя строка — запись в память: во время записи плашка и время файла,
        // без записи — такая же плашка, как «Трансляция выключена», только про запись.
        // Если запись выключена в настройках, все три плашки скрыты, а кнопки остаются.
        val recordingEnabled = settings.saveVideoLocally
        binding.recordChip.visibility =
            if (recording && recordingEnabled) View.VISIBLE else View.GONE
        binding.recordTime.visibility =
            if (recording && recordingEnabled) View.VISIBLE else View.GONE
        binding.recordStatus.visibility =
            if (!recording && recordingEnabled) View.VISIBLE else View.GONE
        if (!recording) binding.recordTime.text = RECORD_TIME_ZERO
        else updateRecordTime()

        binding.streamBtn.setImageResource(
            if (rtmpCamera2?.isStreaming == true) R.drawable.ic_pause else R.drawable.ic_link
        )
        binding.recordBtn.setImageResource(if (recording) R.drawable.ic_pause else R.drawable.ic_record)
        binding.streamBtn.isEnabled = hasCameraAndAudioPermissions()
        binding.recordBtn.isEnabled = hasCameraAndAudioPermissions()

        // Строка эфира скрывается целиком, если трансляция выключена в настройках.
        // Строку записи не скрываем: в ней же кнопки — при выключенной записи скрыты только плашки.
        // Кнопки выключенных режимов гасим, но оставляем кликабельными, чтобы объяснить причину.
        val broadcastEnabled = settings.broadcastEnabled
        binding.broadcastRow.visibility = if (broadcastEnabled) View.VISIBLE else View.GONE
        binding.streamBtn.alpha = if (broadcastEnabled) 1f else DISABLED_ALPHA
        binding.recordBtn.alpha = if (recordingEnabled) 1f else DISABLED_ALPHA

        // Пилюля битрейта не должна оставаться пустой: заполняем её, только если там ещё ничего нет,
        // чтобы не затирать сообщение об ошибке авторизации.
        if (binding.bitrate.text.isNullOrEmpty()) {
            binding.bitrate.text = getString(R.string.main_bitrate_disabled)
        }
    }

    private fun restoreMatchState(state: Bundle) {
        if (!state.containsKey(STATE_TEAM1_SCORE)) return
        engine.restore(
            ScoreEngine.Snapshot(
                team1Score = state.getInt(STATE_TEAM1_SCORE),
                team2Score = state.getInt(STATE_TEAM2_SCORE),
                team1Sets = state.getInt(STATE_TEAM1_SETS),
                team2Sets = state.getInt(STATE_TEAM2_SETS),
                setCompletionDeclined = state.getBoolean(STATE_DECLINED)
            )
        )
    }

    // ------------------------------------------------------- разрешения и поток

    private fun hasCameraAndAudioPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (hasCameraAndAudioPermissions()) {
            prepareStream()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun prepareStream() {
        val camera = rtmpCamera2 ?: return
        if (streamPrepared) return
        if (!hasCameraAndAudioPermissions()) {
            Log.w(TAG, "Нет разрешений камеры/микрофона — превью не запускаем")
            updateUi()
            return
        }

        if (!prepareEncoders(forStream = settings.broadcastEnabled)) {
            toast(getString(R.string.main_stream_prepare_failed))
            return
        }

        surfaceHolderCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Поверхность создаётся заново — при первом запуске и при возврате с экрана
                // настроек. При потере поверхности библиотека снимает фильтры оверлея
                // (stopPreview() → stopCamera() → MainRender.release()), поэтому помечаем
                // подключение потерянным: ensurePreview() ниже добавит фильтры заново.
                // При старте/остановке записи и эфира surfaceCreated не приходит, так что
                // на них это никак не влияет.
                val wasRunning = previewRunning
                overlay?.invalidate()
                ensurePreview(camera)
                // Если превью уже работало, ensurePreview() выходит сразу, а фильтры из
                // прошлого GL-контекста потеряны — возвращаем их после старта GL.
                if (wasRunning) attachOverlayWhenGlReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                try {
                    camera.stopPreview()
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось остановить превью", e)
                }
                previewRunning = false
                stopEverything(showToasts = false)
            }
        }

        binding.openGlView.holder.addCallback(surfaceHolderCallback)
        streamPrepared = true

        // Поверхность могла появиться раньше, чем мы подписались на её колбэки (например, пульт
        // открыли уже после заполнения настроек): тогда surfaceCreated не придёт, и превью нужно
        // поднимать самим.
        if (binding.openGlView.holder.surface?.isValid == true) ensurePreview(camera)
    }

    /**
     * Готовит энкодеры. Вызывать перед КАЖДЫМ запуском потока: `Camera2Base.stopStream()` вызывает
     * `BaseEncoder.stop()`, который сбрасывает признак `prepared`, а `BaseEncoder.start()` без него
     * бросает IllegalStateException («not prepared yet. You must call prepare method before start it»).
     * Из-за этого повторное нажатие кнопки после остановки ничего не запускало.
     *
     * @param forStream готовим запуск эфира (а не запись без трансляции).
     */
    private fun prepareEncoders(forStream: Boolean): Boolean {
        val camera = rtmpCamera2 ?: return false
        val streamBitrate = bitrateAdapter.targetBitrate
        val recordBitrate = RecordingBitrate.bitsPerSecond(settings.recordBitrateMbps)
        // Ориентация нужна, если передаём параметры энкодера записи вручную (11-аргументный
        // prepareVideo): в коротких перегрузках библиотека подставляет её сама.
        val rotation = CameraHelper.getCameraOrientation(this)

        // Режимы:
        //  • «только эфир» — один энкодер 1080p с битрейтом потока. Второй кодек здесь
        //    кодировал бы 1080p вхолостую и грел телефон (по логу: критический нагрев
        //    наступал за 12 минут);
        //  • «эфир + запись» — два энкодера: эфир 720p и запись 1080p со своим битрейтом.
        //    RootEncoder включает второй энкодер, только когда разрешения различаются
        //    (соотношение сторон обязано совпадать). Добавить его на ходу нельзя —
        //    `prepareVideo()` останавливает текущий, поэтому первое включение записи
        //    во время эфира перезапускает поток на пару секунд;
        //  • «только запись» — один энкодер 1080p с битрейтом записи.
        // Как в SportCam: один видеокодер на всё. Запись пакует тот же поток кадров,
        // поэтому второй энкодер не нужен, а значит нет и пересборок GL на ходу —
        // именно они давали чёрный кадр.
        val dual = false
        dualEncodersActive = false
        // Потолок зависит от разрешения эфира: 720p не нужны 1080p-мегабиты,
        // а каждый лишний мегабит — это лишний нагрев телефона.
        bitrateAdapter.maxBitrate = if (dual) {
            StreamConfig.MAX_BITRATE_720P
        } else {
            StreamConfig.MAX_BITRATE_1080P
        }
        val videoPrepared = when {
            dual -> camera.prepareVideo(
                StreamConfig.STREAM_WIDTH,
                StreamConfig.STREAM_HEIGHT,
                StreamConfig.FPS,
                streamBitrate,
                StreamConfig.IFRAME_INTERVAL_SECONDS,
                rotation,
                -1,
                -1,
                StreamConfig.RECORD_WIDTH,
                StreamConfig.RECORD_HEIGHT,
                recordBitrate
            )

            forStream -> camera.prepareVideo(
                StreamConfig.RECORD_WIDTH,
                StreamConfig.RECORD_HEIGHT,
                StreamConfig.FPS,
                streamBitrate,
                StreamConfig.IFRAME_INTERVAL_SECONDS,
                rotation,
                -1,
                -1
            )

            else -> camera.prepareVideo(
                StreamConfig.RECORD_WIDTH,
                StreamConfig.RECORD_HEIGHT,
                StreamConfig.FPS,
                recordBitrate,
                StreamConfig.IFRAME_INTERVAL_SECONDS,
                rotation,
                -1,
                -1
            )
        }
        // Полезно при разборе жалоб на качество/размер файла: видно, что и с каким битрейтом
        // готовится и какой режим включён.
        Log.i(
            TAG,
            if (dual) {
                "prepareEncoders (${if (forStream) "эфир" else "запись"}): " +
                    "эфир ${StreamConfig.STREAM_WIDTH}x${StreamConfig.STREAM_HEIGHT} " +
                    "${streamBitrate / 1000} кбит/с + запись " +
                    "${StreamConfig.RECORD_WIDTH}x${StreamConfig.RECORD_HEIGHT} " +
                    "${recordBitrate / 1000} кбит/с (отдельные энкодеры)"
            } else {
                "prepareEncoders (${if (forStream) "эфир" else "запись"}): " +
                    "${StreamConfig.RECORD_WIDTH}x${StreamConfig.RECORD_HEIGHT} " +
                    "${streamBitrate / 1000} кбит/с, запись в настройках выключена"
            }
        )
        val audioPrepared = camera.prepareAudio(
            StreamConfig.AUDIO_BITRATE,
            StreamConfig.AUDIO_SAMPLE_RATE,
            StreamConfig.AUDIO_IS_STEREO,
            StreamConfig.AUDIO_ECHO_CANCELER,
            StreamConfig.AUDIO_NOISE_SUPPRESSOR
        )
        return videoPrepared && audioPrepared
    }

    /**
     * Поднимает превью: `stopStream()`/`stopPreview()` закрывают камеру и останавливают GL-интерфейс.
     * Сразу после запуска превью подключаем оверлей заново: фильтры, добавленные в остановленный
     * GL-интерфейс, не отрисовываются — именно поэтому после свёртывания пропадала графика счёта.
     */
    private fun ensurePreview(camera: RtmpCamera2) {
        if (previewRunning) return
        if (!hasCameraAndAudioPermissions()) return
        // Поверхность превью после запуска активити появляется не сразу. Если её ещё нет,
        // startPreview падает с «Make sure the SurfaceView ... has a valid Surface» — ждём
        // surfaceCreated: он вызовет этот же метод, когда поверхность будет готова.
        if (binding.openGlView.holder.surface?.isValid != true) return
        try {
            // startPreview() поднимает новый GL-контекст: фильтры оверлея в нём отсутствуют,
            // а признак «подключён» остался с прошлого раза. Без сброса attach() ничего не добавит.
            overlay?.invalidate()
            camera.startPreview()
            previewRunning = true
            applyZoom()   // камера открылась заново — возвращаем прежний зум
            applyVideoStabilization()   // и настройку стабилизации
            applyExposure()             // и автоэкспозицию с поправкой яркости
            attachOverlayWhenGlReady()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить превью", e)
        }
    }

    /** (Пере)подключает оверлей счёта к текущему GL-интерфейсу. */
    private fun attachOverlay() {
        val currentOverlay = overlay ?: return
        currentOverlay.attach(overlayState(), settings.team1Color, settings.team2Color)
    }

    /**
     * Подключает оверлей, дождавшись реального запуска GL.
     *
     * `startPreview()` поднимает GL-поток асинхронно, а фильтры, добавленные до его старта,
     * теряются: в первом запуске из-за этого не появлялась графика счёта, а после повторного
     * открытия настроек возникала. Ждём `glInterface.isRunning` и только затем добавляем.
     */
    private fun attachOverlayWhenGlReady(attempt: Int = 0) {
        val camera = rtmpCamera2 ?: return
        if (overlay == null) return
        if (previewRunning && camera.glInterface.isRunning) {
            attachOverlay()
            return
        }
        if (attempt >= GL_READY_ATTEMPTS) {
            Log.w(TAG, "GL не запустился за ${GL_READY_ATTEMPTS * GL_READY_STEP_MS} мс — оверлей не подключён")
            return
        }
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) attachOverlayWhenGlReady(attempt + 1)
        }, GL_READY_STEP_MS)
    }

    /**
     * Обновляет оверлей после старта/остановки записи и эфира.
     *
     * `prepareVideo()` → `startEncoders()` → `prepareGlView()` пересобирает GL: надписи нужно
     * перерисовать в новом контексте, иначе пропадают названия команд и цифры счёта, хотя фон
     * плашки остаётся. Фильтры при этом НЕ пересоздаются (правило: создаются один раз при
     * открытии активити) — [ScoreOverlay.attach] только обновляет текст и цвета, поэтому мигания нет.
     */
    private fun refreshOverlay() {
        val currentOverlay = overlay ?: return
        currentOverlay.attach(overlayState(), settings.team1Color, settings.team2Color)
    }

    /**
     * Возвращает превью и оверлей после `prepareVideo()`.
     *
     * `Camera2Base.prepareVideo()` при работающем превью и несовпадении размера кадра с размером
     * превью вызывает `stopPreview()` → `stopCamera()`: GL-интерфейс останавливается, камера
     * закрывается, `onPreview` становится false. Наш флаг [previewRunning] при этом остаётся true,
     * поэтому [ensurePreview] молча выходил — синхронизируем флаг с библиотекой.
     */
    private fun refreshPreviewAfterPrepare(camera: RtmpCamera2) {
        previewRunning = camera.isOnPreview
        if (previewRunning) {
            // prepareVideo() пересобирает GL: фильтры потеряны, признак подключения — нет.
            overlay?.invalidate()
            refreshOverlay()
        } else {
            ensurePreview(camera)
        }
    }

    /**
     * Возвращает превью и графику после остановки потока: библиотека закрывает камеру и глушит GL.
     * Небольшая задержка нужна, чтобы камера успела закрыться после `stopStream()`.
     */
    private fun restorePreviewAfterStop() {
        if (binding.openGlView.holder.surface?.isValid != true) return
        rtmpCamera2?.let { ensurePreview(it) }
    }

    /**
     * Поднимает превью после teardown, который библиотека делает при остановке записи или эфира.
     *
     * Делаем несколько быстрых попыток подряд: первая успешная выставляет [previewRunning], и все
     * следующие сразу выходят. Так картинка возвращается настолько быстро, насколько успевает
     * открыться камера, и мигание не растягивается на фиксированные 300 мс.
     */
    private fun restorePreviewSoon() {
        previewRunning = false
        PREVIEW_RESTORE_DELAYS_MS.forEach { delayMs ->
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) restorePreviewAfterStop()
            }, delayMs)
        }
    }

    /**
     * Разворачивает превью почти на весь экран — до нижней панели управления, чтобы кнопки
     * «стоп» и «настройки» не перекрывались картинкой. Карточки команд и подпись партии скрываются.
     */
    private fun setPreviewFullscreen(fullscreen: Boolean) {
        previewFullscreen = fullscreen
        val preview = binding.openGlView

        // Всегда работаем с копиями: если отдать представлению сохранённый объект,
        // следующий разворот изменит именно его — и свернуть превью будет уже нечем.
        val collapsed = collapsedPreviewParams
        if (collapsed == null) {
            Log.w(TAG, "Исходные параметры превью не сохранены — беру текущие")
        }
        val params = ConstraintLayout.LayoutParams(
            collapsed ?: (preview.layoutParams as ConstraintLayout.LayoutParams)
        )

        if (fullscreen) {
            params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.dimensionRatio = null
            params.matchConstraintPercentWidth = 1f
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            // Панель управления в полном экране скрыта, поэтому превью занимает весь экран.
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            params.setMargins(0, 0, 0, 0)
        }
        preview.layoutParams = params

        val mode = if (fullscreen) "развёрнуто до панели" else "обычный размер (${params.matchConstraintPercentWidth})"
        Log.d(TAG, "Превью: $mode")

        // В полном экране не остаётся элементов управления: только полупрозрачная кнопка
        // сворачивания поверх видео.
        val controlsVisibility = if (fullscreen) View.GONE else View.VISIBLE
        binding.titleSet.visibility = controlsVisibility
        binding.cardFirstTeam.visibility = controlsVisibility
        binding.cardSecondTeam.visibility = controlsVisibility
        binding.card1.visibility = controlsVisibility
        binding.card2.visibility = controlsVisibility
        binding.bottomLayout.visibility = controlsVisibility
        binding.collapseBtn.visibility = if (fullscreen) View.VISIBLE else View.GONE

        // Зум доступен только в полном экране и только если камера его поддерживает.
        val zoomVisibility = if (fullscreen && zoomRangeOrNull() != null) View.VISIBLE else View.GONE
        binding.zoomInBtn.visibility = zoomVisibility
        binding.zoomOutBtn.visibility = zoomVisibility
        if (!fullscreen) stopZoomHold()   // вышли из полного экрана — удержание не продолжаем
    }

    /** Диапазон зума камеры или null, если он недоступен (камера закрыта или зум не поддерживается). */
    private fun zoomRangeOrNull(): Range<Float>? = try {
        rtmpCamera2?.getZoomRange()
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось получить диапазон зума", e)
        null
    }

    /**
     * Плавный зум при удержании: пока палец на кнопке, значение меняется каждые [ZOOM_TICK_MS]
     * маленьким шагом, а после [ZOOM_ACCELERATE_AFTER_MS] удержания шаг ускоряется.
     */
    private fun startZoomHold(direction: Int) {
        stopZoomHold()
        if (zoomRangeOrNull() == null) return

        zoomHoldDirection = direction
        zoomHoldStartedAt = SystemClock.elapsedRealtime()
        zoomHoldTicks = 0

        val tick = object : Runnable {
            override fun run() {
                if (!applyZoomHoldTick()) return
                handler.postDelayed(this, ZOOM_TICK_MS)
            }
        }
        zoomHoldRunnable = tick
        handler.post(tick)
    }

    private fun stopZoomHold() {
        zoomHoldRunnable?.let { handler.removeCallbacks(it) }
        zoomHoldRunnable = null
    }

    /** Один шаг удержания; false — упёрлись в границу диапазона, дальше крутить нечего. */
    private fun applyZoomHoldTick(): Boolean {
        val range = zoomRangeOrNull() ?: return false
        if (range.upper <= range.lower) return false

        val held = SystemClock.elapsedRealtime() - zoomHoldStartedAt
        val speed = if (held >= ZOOM_ACCELERATE_AFTER_MS) ZOOM_ACCELERATED_SPEED else 1f
        val step = (range.upper - range.lower) * ZOOM_STEP_PER_TICK * speed

        val next = (currentZoom + zoomHoldDirection * step).coerceIn(range.lower, range.upper)
        if (next == currentZoom) return false

        currentZoom = next
        zoomHoldTicks++
        applyZoom()
        return true
    }

    /** Касания кнопок зума: интересны только начало и конец удержания. */
    private fun handleZoomTouch(event: MotionEvent, direction: Int): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startZoomHold(direction)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopZoomHold()
        }
        // false — чтобы кнопка сама показала нажатие и отправила обычный click.
        return false
    }

    /** Один шаг зума кнопками «+» / «−»: 1 — приблизить, -1 — отдалить. */
    private fun zoomStep(direction: Int) {
        val range = zoomRangeOrNull() ?: return
        if (range.upper <= range.lower) {
            Log.w(TAG, "Камера не поддерживает зум")
            return
        }

        val step = ((range.upper - range.lower) / ZOOM_STEPS).coerceAtLeast(MIN_ZOOM_STEP)
        val next = (currentZoom + direction * step).coerceIn(range.lower, range.upper)
        if (next != currentZoom) {
            currentZoom = next
            applyZoom()
        }
    }

    /**
     * Автоэкспозиция и поправка яркости. В настройках поправка хранится в процентах (-100…100),
     * а камера принимает её в своих шагах (`getMinExposure()`…`getMaxExposure()`), поэтому
     * проценты раскладываются по диапазону конкретного устройства.
     */
    private fun applyExposure() {
        val camera = rtmpCamera2 ?: return
        try {
            if (!settings.autoExposure) {
                if (camera.isAutoExposureEnabled) {
                    camera.disableAutoExposure()
                    Log.i(TAG, "Автоэкспозиция выключена")
                }
                return
            }

            if (!camera.isAutoExposureEnabled && !camera.enableAutoExposure()) {
                Log.w(TAG, "Устройство не поддерживает автоэкспозицию")
                return
            }

            val min = camera.minExposure
            val max = camera.maxExposure
            val percent = settings.exposureCompensation
            val value = (if (percent >= 0) max * percent / 100 else min * -percent / 100)
                .coerceIn(min, max)

            if (camera.exposure != value) {
                camera.setExposure(value)
                Log.i(TAG, "Экспозиция: $value (поправка $percent%, диапазон $min..$max)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось применить экспозицию", e)
        }
    }

    /**
     * Включает или выключает стабилизацию изображения по настройке. Вызывается, когда камера уже
     * открыта (после `startPreview()`), и при возврате на пульт — настройку могли изменить.
     */
    private fun applyVideoStabilization() {
        val camera = rtmpCamera2 ?: return
        try {
            if (settings.videoStabilization) {
                if (camera.enableVideoStabilization()) {
                    Log.i(TAG, "Стабилизация изображения включена")
                } else {
                    Log.w(TAG, "Устройство не поддерживает стабилизацию изображения")
                    if (!stabilizationWarningShown) {
                        stabilizationWarningShown = true
                        toast(getString(R.string.stabilization_not_supported))
                    }
                }
            } else if (camera.isVideoStabilizationEnabled) {
                camera.disableVideoStabilization()
                Log.i(TAG, "Стабилизация изображения выключена")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось применить стабилизацию", e)
        }
    }

    /** Применяет текущий зум к камере, если устройство его поддерживает. */
    private fun applyZoom() {
        val camera = rtmpCamera2 ?: return
        try {
            val range = camera.getZoomRange()
            if (range == null) {
                Log.w(TAG, "Камера не поддерживает зум")
                return
            }
            val clamped = currentZoom.coerceIn(range.lower, range.upper)
            currentZoom = clamped
            camera.setZoom(clamped)
            Log.d(TAG, "Зум камеры: $clamped (диапазон ${range.lower}..${range.upper})")
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось применить зум", e)
        }
    }

    /** Верхняя строка пульта: включить или выключить эфир. Запись не трогаем. */
    /**
     * Единственная кнопка пульта: запускает выбранный в настройках режим или останавливает всё.
     *
     * Состав кодировщиков фиксируется на старте и не меняется до конца сессии — именно
     * пересборки GL во время работы давали чёрный кадр.
     */
    private fun toggleSession() {
        val camera = rtmpCamera2 ?: return

        if (camera.isStreaming || isRecordingNow) {
            confirmStopSession()
            return
        }

        when (settings.startMode) {
            StartMode.RECORD -> startRecording()
            StartMode.STREAM -> startBroadcast()
            else -> {
                startBroadcast()
                // Запись начинается сразу после успешного подключения, тем же кодеком.
                if (camera.isStreaming && !isRecordingNow) startLocalRecording()
            }
        }
        // Пока идёт сессия, держим процесс в foreground: иначе Android запретит камеру в фоне.
        if (camera.isStreaming || isRecordingNow) StreamService.start(this)
        updateUi()
    }

    /**
     * Подтверждение остановки: кнопка одна и на паузе пульта, и во время эфира, поэтому
     * случайное касание не должно обрывать трансляцию или закрывать файл.
     */
    private fun confirmStopSession() {
        val streaming = rtmpCamera2?.isStreaming == true
        val recording = isRecordingNow
        val message = when {
            streaming && recording -> getString(R.string.stop_confirm_both)
            recording -> getString(R.string.stop_confirm_record)
            else -> getString(R.string.stop_confirm_stream)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.stop_confirm_yes) { _, _ -> stopSession() }
            .setNegativeButton(R.string.stop_confirm_no, null)
            .show()
    }

    /** Останавливает то, что было запущено, в правильном порядке: сначала поток, потом файл. */
    private fun stopSession() {
        if (rtmpCamera2?.isStreaming == true) toggleBroadcast()
        if (isRecordingNow) toggleRecording()
        StreamService.stop(this)
        updateUi()
    }

    private fun toggleBroadcast() {
        val camera = rtmpCamera2 ?: return
        if (camera.isStreaming) {
            // Библиотека считает поток запущенным и во время попыток подключения — гасим его,
            // запись при этом продолжается.
            stopStreamOnly()
            updateUi()
            return
        }
        if (!settings.broadcastEnabled) {
            toast(getString(R.string.main_broadcast_disabled))
            return
        }
        startBroadcast()
    }

    /** Нижняя строка пульта: включить или выключить запись в память. Эфир не трогаем. */
    private fun toggleRecording() {
        val camera = rtmpCamera2 ?: return
        if (isRecordingNow) {
            stopRecording()
            return
        }
        if (!settings.saveVideoLocally) {
            toast(getString(R.string.main_recording_disabled))
            return
        }
        startRecording()
    }

    /**
     * Запись без эфира: готовим энкодеры и превью сами. Во время трансляции энкодеры уже
     * готовы (если запись включена в настройках — с отдельным энкодером записи).
     */
    private fun startRecording() {
        val camera = rtmpCamera2 ?: return
        if (camera.isStreaming) {
            // Эфир уже идёт — кодировщик общий, поэтому достаточно открыть муксер.
            // Никакой пересборки GL, никакого второго кодека: файл получит качество эфира.
            Log.i(TAG, "Запись во время эфира: пишу текущим энкодером (качество эфира)")
        }
        if (!camera.isStreaming) {
            if (!prepareEncoders(forStream = false)) {
                toast(getString(R.string.main_stream_prepare_failed))
                return
            }
            refreshPreviewAfterPrepare(camera)
        }
        recordingPart = 0
        if (!startLocalRecording()) return
        // startRecord() при отсутствии эфира пересобирает GL: возвращаем надписи без
        // clearFilters(), чтобы плашка не мигала.
        refreshOverlay()
        updateUi()
    }

    /** Останавливает только запись: трансляция продолжается. */
    private fun stopRecording() {
        if (rtmpCamera2 == null) return
        if (!isRecordingNow) return
        handler.removeCallbacks(recordTicker)
        splitRunnable?.let { handler.removeCallbacks(it) }
        splitRunnable = null
        try {
            rtmpCamera2?.stopRecord()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки записи", e)
        }
        val hadActiveRecording = recorder?.isActive == true
        if (recorder?.stop() == true) {
            toast(getString(R.string.main_recording_saved))
        } else if (hadActiveRecording) {
            Log.w(TAG, "Файл записи не опубликован в MediaStore")
        }
        recordingPart = 0
        // Без эфира stopRecord() внутри библиотеки вызывает stopStream() — полный teardown:
        // GL остановлен, камера закрыта. Поднимаем превью детерминированно, а не серией
        // отложенных попыток: они выставляли previewRunning, хотя GL ещё не отрисовал кадр.
        if (rtmpCamera2?.isStreaming != true) restartPreviewAfterTeardown()
        updateUi()
    }

    /**
     * Поднимает превью после teardown, который библиотека делает при остановке записи
     * без эфира.
     *
     * Прошлый вариант оставлял чёрный экран: первый отложенный вызов успевал выставить
     * `previewRunning = true`, хотя GL ещё не запустился, и все следующие попытки выходили
     * сразу. Здесь состояние сбрасывается заранее, запуск один, и результат проверяется.
     */
    private fun restartPreviewAfterTeardown() {
        previewRunning = false
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val camera = rtmpCamera2 ?: return@postDelayed
            try {
                camera.stopPreview()
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось сбросить превью перед перезапуском", e)
            }
            ensurePreview(camera)
            attachOverlayWhenGlReady()
            // Страховка: если GL не поднялся за секунду, повторяем ровно один раз.
            handler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                val cam = rtmpCamera2 ?: return@postDelayed
                if (!cam.glInterface.isRunning) {
                    Log.w(TAG, "GL не поднялся после остановки записи — повторяю запуск превью")
                    previewRunning = false
                    ensurePreview(cam)
                    attachOverlayWhenGlReady()
                }
            }, 1200L)
        }, 400L)
    }

    private fun startBroadcast() {
        val camera = rtmpCamera2 ?: return
        val url = settings.fullRtmpUrl
        if (url.isBlank()) {
            toast(getString(R.string.main_stream_url_missing))
            showSettingsPanel()
            return
        }

        if (!streamPrepared) prepareStream()
        if (!streamPrepared) return

        // Запуск эфира требует переподготовки энкодеров, а это порвало бы текущий файл записи.
        // Поэтому закрываем его штатно и после старта потока начинаем новую часть.
        val wasRecording = isRecordingNow
        if (wasRecording) {
            Log.i(TAG, "Старт эфира во время записи: закрываю текущую часть и начну новую")
            stopRecording()
            recordingPart = 0
        }

        // Перед подготовкой энкодеров гасим превью намеренно.
        //
        // prepareVideo() перезапускает камеру только если размер кадра отличается от размера
        // превью. В ленивом режиме «только эфир» они совпадают (оба 1920x1080), камера
        // остаётся открытой, а GL после пересборки энкодеров оказывается в полусобранном
        // состоянии — на экране чёрный кадр с одним табло. Поэтому закрываем превью сами,
        // а ниже refreshPreviewAfterPrepare() поднимет его заново уже под новый энкодер.
        try {
            camera.stopPreview()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось остановить превью перед подготовкой энкодеров", e)
        }
        previewRunning = false

        // Перед каждым запуском: stopStream() сбрасывает prepared у энкодеров и закрывает камеру.
        // Если запись шла до старта эфира, нужен второй энкодер: иначе файл писался бы
        // с разрешением и битрейтом потока.
        val previousForceDual = forceDualEncoders
        forceDualEncoders = previousForceDual || wasRecording
        val encodersPrepared = prepareEncoders(forStream = true)
        forceDualEncoders = previousForceDual
        if (!encodersPrepared) {
            toast(getString(R.string.main_stream_prepare_failed))
            return
        }
        // Превью здесь намеренно НЕ поднимаем: startStream() сам откроет камеру и GL
        // (startEncoders → prepareGlView → openLastCamera). Если поднять его заранее,
        // камера готовится дважды подряд, и кадр становится чёрным — проверено на устройстве.

        retryCount = 0
        // Новая сессия: с этого момента обрыв связи снова повод поднимать поток заново.
        userStoppedStream = false
        streamConnected = false
        try {
            camera.startStream(url)
        } catch (e: IllegalStateException) {
            // Библиотека успевает выставить streaming = true до падения: возвращаем чистое состояние.
            Log.e(TAG, "startStream: энкодеры не подготовлены", e)
            toast(getString(R.string.main_stream_prepare_failed))
            stopEverything(showToasts = false)
            return
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось начать трансляцию", e)
            toast(getString(R.string.main_stream_prepare_failed))
            stopEverything(showToasts = false)
            return
        }

        // startStream() пересобирает GL под энкодеры: превью он поднял сам, синхронизируем флаг
        // и возвращаем надписи — но сначала сбрасываем кэш, старые фильтры остались в прежнем контексте.
        previewRunning = camera.isOnPreview
        if (!previewRunning) ensurePreview(camera)
        overlay?.invalidate()
        attachOverlayWhenGlReady()

        // Если запись шла до старта эфира — продолжаем её новой частью (старая закрыта выше).
        // В режиме «эфир и запись» файл начинается сразу после подключения, тем же кодеком.
        if (wasRecording || StartMode.includesRecording(settings.startMode)) startLocalRecording()

        bitrateAdapter.reset(SystemClock.elapsedRealtime(), safeBytesSent(camera))
        handler.removeCallbacks(bitrateTicker)
        handler.postDelayed(bitrateTicker, BitrateAdapter.UPDATE_INTERVAL_MS)

        updateUi()
        toast(getString(R.string.main_stream_started))
    }

    /** Запускает локальную запись — первую или следующую часть. @return true, если пишем. */
    private fun startLocalRecording(): Boolean {
        val camera = rtmpCamera2 ?: return false
        if (recorder?.isActive == true) return true

        if (recordingPart < 1) recordingPart = 1
        val descriptor = recorder?.start(RecordingParts.fileName(buildRecordingBaseName(), recordingPart))
        if (descriptor == null) {
            toast(getString(R.string.main_recording_failed))
            return false
        }
        return try {
            camera.startRecord(descriptor)
            toast(getString(R.string.main_recording_started))
            startRecordTimer()
            scheduleRecordingSplit()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось начать запись", e)
            recorder?.discard()
            toast(getString(R.string.main_recording_failed))
            false
        }
    }

    /** Запускает секундный счётчик времени записи для нижней строки пульта. */
    private fun startRecordTimer() {
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(recordTicker)
        handler.post(recordTicker)
        updateRecordTime()
    }

    /** Обновляет «00:42» в нижней строке: время текущего файла. */
    private fun updateRecordTime() {
        if (recorder?.isActive != true) return
        val elapsedMs = SystemClock.elapsedRealtime() - recordingStartedAtMs
        val minutes = elapsedMs / 60_000
        val seconds = (elapsedMs / 1_000) % 60
        binding.recordTime.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun buildRecordingBaseName(): String {
        val stamp = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
        val names = listOf(settings.team1Name, settings.team2Name)
            .filter { it.isNotBlank() }
            .joinToString("_")
        return if (names.isBlank()) stamp else "${names}_$stamp"
    }

    /**
     * Планирует закрытие текущей части записи. Части режутся остановкой и повторным запуском
     * записи: поток при этом не трогаем, каждый файл корректно закрывается.
     */
    private fun scheduleRecordingSplit() {
        splitRunnable?.let { handler.removeCallbacks(it) }
        splitRunnable = null

        val interval = RecordingParts.intervalMs(settings.splitMinutes) ?: return
        val task = Runnable {
            splitRunnable = null
            splitRecordingNow()
        }
        splitRunnable = task
        handler.postDelayed(task, interval)
        Log.i(TAG, "Следующая часть записи через ${settings.splitMinutes} мин")
    }

    /** Закрывает текущую часть записи и сразу начинает следующую. */
    private fun splitRecordingNow() {
        val camera = rtmpCamera2 ?: return
        if (!isRecordingNow) return

        Log.i(TAG, "Закрываю часть $recordingPart записи и начинаю следующую")
        try {
            camera.stopRecord()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось закрыть часть записи", e)
        }
        recorder?.stop()
        recordingPart++
        startLocalRecording()
    }

    /**
     * Останавливает трансляцию и запись.
     *
     * Порядок как в исходной версии приложения: сначала стоп потока, затем записи. Так
     * `Camera2Base.stopStream()` видит активную запись и пропускает ветку полного teardown —
     * в ней библиотека глушит GL, закрывает камеру и сбрасывает у энкодеров признак `prepared`.
     * Благодаря этому превью и графика счёта остаются на экране, а повторный запуск не требует
     * «починки» состояния.
     */
    private fun stopEverything(showToasts: Boolean) {
        val camera = rtmpCamera2
        handler.removeCallbacks(bitrateTicker)
        handler.removeCallbacks(recordTicker)
        handler.removeCallbacks(hardRestartRunnable)
        splitRunnable?.let { handler.removeCallbacks(it) }
        splitRunnable = null

        // Останавливаем по своей воле (кнопка, onPause, гибель поверхности): запоздавшие
        // onDisconnect/onConnectionFailed не должны поднимать поток заново. Флаг снимется
        // при следующем явном запуске.
        userStoppedStream = true
        streamConnected = false

        // Фиксируем до остановки: именно от этого зависит ветка внутри stopStream().
        val streamStoppedWhileRecording = isRecordingNow

        if (camera?.isStreaming == true) {
            if (showToasts) toast(getString(R.string.main_stream_stopping))
            try {
                camera.stopStream()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки трансляции", e)
            }
            // Запись была активна → teardown пропущен → превью и GL продолжают работать.
            previewRunning = streamStoppedWhileRecording
        }

        if (isRecordingNow) {
            if (showToasts) toast(getString(R.string.main_recording_stopping))
            try {
                camera?.stopRecord()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки записи", e)
            }
        }

        val hadActiveRecording = recorder?.isActive == true
        if (recorder?.stop() == true) {
            toast(getString(R.string.main_recording_saved))
        } else if (hadActiveRecording) {
            Log.w(TAG, "Файл записи не опубликован в MediaStore")
        }
        recordingPart = 0
        // Флаг не сбрасываем: onDisconnect() придёт уже после возврата из stopStream() и должен
        // знать, что поток остановлен нами. Снимется при следующем запуске.
        streamHardRestartCount = 0

        updateUi()
    }

    /** Немедленно применяет текущий целевой битрейт к энкодеру (после смены потолка). */
    private fun applyBitrateNow() {
        val camera = rtmpCamera2 ?: return
        if (!camera.isStreaming) return
        try {
            camera.setVideoBitrateOnFly(bitrateAdapter.targetBitrate)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось применить битрейт", e)
        }
    }

    private fun updateBitrateStats(camera: RtmpCamera2) {
        val stats = bitrateAdapter.onStats(SystemClock.elapsedRealtime(), safeBytesSent(camera)) ?: return

        binding.bitrate.text = getString(
            R.string.main_bitrate_stats,
            bitrateAdapter.targetBitrate / MEGABIT,
            stats.actualBitrate / MEGABIT
        )

        logTelemetry(camera)

        stats.newTargetBitrate?.let { newBitrate ->
            try {
                camera.setVideoBitrateOnFly(newBitrate)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось изменить битрейт на лету", e)
            }
        }
    }

    /**
     * Телеметрия канала раз в [TELEMETRY_INTERVAL_MS].
     *
     * Библиотека отдаёт больше, чем битрейт: сколько кадров отправлено, сколько потеряно,
     * сколько лежит в очереди и есть ли затор. На площадке это единственный способ отличить
     * слабый канал от перегрева телефона — без этих строк в логе любые жалобы на качество
     * приходится разбирать догадками.
     */
    private fun logTelemetry(camera: RtmpCamera2) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTelemetryLogMs < TELEMETRY_INTERVAL_MS) return
        lastTelemetryLogMs = now

        val client = try {
            camera.streamClient
        } catch (e: Exception) {
            Log.w(TAG, "Телеметрия канала недоступна", e)
            return
        }

        val sent = runCatching { client.getSentVideoFrames() }.getOrDefault(0L)
        val dropped = runCatching { client.getDroppedVideoFrames() }.getOrDefault(0L)
        val queue = runCatching { client.getItemsInCache() }.getOrDefault(0)
        val capacity = runCatching { client.getCacheSize() }.getOrDefault(0)
        val congest = runCatching { client.hasCongestion(CONGESTION_PERCENT) }.getOrDefault(false)
        val bytes = safeBytesSent(camera)

        val droppedDelta = (dropped - lastDroppedFrames).coerceAtLeast(0L)
        lastDroppedFrames = dropped
        val total = sent + dropped
        val dropPercent = if (total > 0) dropped * 100.0 / total else 0.0

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "телеметрия: цель %.2f Мбит (потолок %.2f), кадров %d, дроп %d (+%d, %.1f%%), " +
                    "очередь %d/%d, затор %s, отправлено %.1f МБ, термо %s",
                bitrateAdapter.targetBitrate / MEGABIT,
                bitrateAdapter.effectiveMax / MEGABIT,
                sent,
                dropped,
                droppedDelta,
                dropPercent,
                queue,
                capacity,
                if (congest) "да" else "нет",
                bytes / MEGABIT,
                thermalLabel(thermalStatus)
            )
        )
    }

    private fun thermalLabel(status: Int): String = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> "критично"
        status >= PowerManager.THERMAL_STATUS_SEVERE -> "сильный нагрев"
        status >= PowerManager.THERMAL_STATUS_MODERATE -> "умеренный нагрев"
        status >= PowerManager.THERMAL_STATUS_LIGHT -> "лёгкий нагрев"
        else -> "норма"
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val previous = thermalStatus
        thermalStatus = status
        Log.i(TAG, "термостатус: ${thermalLabel(status)} ($status), было ${thermalLabel(previous)}")
        // Эфир не останавливаем: при нагреве снижаем потолок битрейта, качество деградирует.
        bitrateAdapter.thermalFactor = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> 0.5f
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 0.7f
            else -> 1f
        }
        Log.i(
            TAG,
            "битрейт с учётом нагрева: цель ${bitrateAdapter.targetBitrate / 1024} кбит/с, " +
                "потолок ${bitrateAdapter.effectiveMax / 1024} кбит/с"
        )
        applyBitrateNow()
        // Предупреждаем только когда что-то пишется или транслируется: на пустом пульте
        // «телефон перегрет» ничего не значит и только пугает.
        val sessionActive = isRecordingNow || rtmpCamera2?.isStreaming == true
        if (sessionActive) {
            when {
                status >= PowerManager.THERMAL_STATUS_CRITICAL &&
                    previous < PowerManager.THERMAL_STATUS_CRITICAL ->
                    toast(getString(R.string.main_thermal_critical))

                status >= PowerManager.THERMAL_STATUS_SEVERE &&
                    previous < PowerManager.THERMAL_STATUS_SEVERE ->
                    toast(getString(R.string.main_thermal_warning))
            }
        }
    }

    /** Подписывается на термостатус: система отдаёт его без опроса датчиков. */
    private fun registerThermalMonitor() {
        val manager = getSystemService(PowerManager::class.java) ?: return
        powerManager = manager
        thermalStatus = manager.currentThermalStatus
        manager.addThermalStatusListener(mainExecutor, thermalListener)
        Log.i(TAG, "термоконтроль подключён, стартовый статус: ${thermalLabel(thermalStatus)}")
    }

    private fun safeBytesSent(camera: RtmpCamera2): Long = try {
        camera.streamClient.getBytesSend()
    } catch (e: Exception) {
        Log.w(TAG, "Статистика отправки недоступна", e)
        0L
    }

    /**
     * Отступы безопасной зоны. В ландшафте вырез фронтальной камеры оказывается слева или справа
     * (на этом устройстве 72 px = 24 dp), а скруглённые углы экрана срезают по 30 px = 10 dp
     * в каждом углу. Поэтому контент отодвигаем со всех сторон, а не только снизу: иначе вырез
     * съедает часть превью, а углы подрезают табло.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val corner = resources.getDimensionPixelSize(R.dimen.safe_area_corner)
            val left = safe.left + corner
            val top = safe.top + corner
            val right = safe.right + corner
            val bottom = safe.bottom + corner

            view.updatePadding(left = left, top = top, right = right, bottom = bottom)
            // Insets приходят на каждую перекладку (в том числе от панели настроек) —
            // пишем только реальное изменение, иначе лог превращается в мусор.
            val stamp = "$left/$top/$right/$bottom"
            if (stamp != lastSafeArea) {
                lastSafeArea = stamp
                Log.i(TAG, "Безопасная зона: слева $left, сверху $top, справа $right, снизу $bottom px")
            }
            insets
        }
    }

    // ------------------------------------------------------------- колбэки RTMP

    override fun onConnectionStarted(url: String) {
        streamConnected = false
        postToUi { updateUi() }
    }

    override fun onConnectionSuccess() {
        // Связь восстановилась — счётчики перезапусков больше не нужны.
        streamConnected = true
        streamHardRestartCount = 0
        retryCount = 0
        Log.i(TAG, "onConnectionSuccess")
        // Плашка «В эфире» должна появиться сразу, не дожидаясь следующего изменения счёта.
        postToUi { updateUi() }
    }

    override fun onConnectionFailed(reason: String) {
        val camera = rtmpCamera2 ?: return
        // Соединение гасили мы сами — переподключаться не нужно. Проверять здесь camera.isStreaming
        // нельзя: библиотека выставляет streaming = true ещё до установки соединения, и такая
        // проверка отключала бы любые попытки переподключения.
        if (userStoppedStream) return

        streamConnected = false
        Log.i(TAG, "onConnectionFailed: $reason (попытка $retryCount)")
        postToUi {
            toast(getString(R.string.main_stream_connection_failed, reason))
            binding.bitrate.text = getString(R.string.main_bitrate_disabled)
            // Соединения нет — сразу убираем плашку «В эфире», а не по следующему клику по счёту.
            updateUi()
        }

        if (retryCount < MAX_RETRIES) {
            retryCount++
            tryReconnect(camera, reason)
        } else {
            // Свои попытки исчерпаны — пробуем поднять поток заново (или только переподключиться,
            // если идёт запись).
            scheduleHardStreamRestart()
        }
    }

    /**
     * Одна попытка переподключения клиента без остановки энкодеров.
     *
     * `reTry()` работает только когда у клиента есть запас попыток: `shouldRetry()` проверяет
     * `doingRetry` и `reTries > 0`, а `reTries` выставляется лишь вызовом `setReTries()`. Без него
     * `reTry()` молча ничего не делает — поэтому перед каждой попыткой ставим одну.
     */
    private fun tryReconnect(camera: RtmpCamera2, reason: String): Boolean = try {
        camera.streamClient.setReTries(1)
        camera.streamClient.reTry(RETRY_DELAY_MS, reason)
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось запланировать переподключение", e)
        false
    }

    override fun onNewBitrate(bitrate: Long) = Unit

    override fun onDisconnect() {
        val camera = rtmpCamera2
        // Соединения больше нет — фиксируем это до постановки в очередь, чтобы запоздавшие
        // проверки не приняли живой поток за оборванный (и наоборот).
        streamConnected = false
        // Полезно при разборе жалоб «эфир сам вернулся»: видно, чья это была остановка.
        Log.i(TAG, "onDisconnect: своя остановка=$userStoppedStream, поток идёт=${camera?.isStreaming}")
        postToUi {
            binding.bitrate.text = getString(R.string.main_bitrate_disabled)
            // Плашка «В эфире» гаснет сразу при обрыве, а не по следующему изменению счёта.
            updateUi()
            // Остановка по нашей воле или уже поднятая заново сессия — перезапускать нечего.
            if (userStoppedStream || streamConnected) return@postToUi

            // Обрыв не по нашей воле: запись продолжаем, поток поднимаем заново.
            val recording = isRecordingNow
            toast(
                getString(
                    if (recording) R.string.main_stream_lost_keep_recording else R.string.main_stream_lost
                )
            )
            scheduleHardStreamRestart()
        }
    }

    override fun onAuthError() {
        postToUi {
            toast(getString(R.string.main_stream_auth_error))
            binding.bitrate.text = getString(R.string.main_bitrate_auth_error)
            // Ключ или доступ неверны: переподключаться бессмысленно, но запись не бросаем.
            stopStreamOnly()
        }
    }

    override fun onAuthSuccess() {
        retryCount = 0
        postToUi { toast(getString(R.string.main_stream_auth_ok)) }
    }

    /** Гасит только трансляцию, не трогая локальную запись. */
    private fun stopStreamOnly() {
        val camera = rtmpCamera2 ?: return
        if (!camera.isStreaming) return
        // Остановка по нашей воле: запоздавший onDisconnect() не должен поднимать поток заново.
        userStoppedStream = true
        streamConnected = false
        // Снимаем всё, что могло остаться в очереди: иначе отложенный перезапуск
        // сработает уже после «Стоп» и тихо вернёт эфир.
        handler.removeCallbacks(hardRestartRunnable)
        handler.removeCallbacks(bitrateTicker)
        streamHardRestartCount = 0
        retryCount = 0
        try {
            camera.stopStream()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки трансляции", e)
        }
        // Без активной записи stopStream() делает полный teardown: глушит GL и закрывает камеру,
        // поэтому флаг превью больше не соответствует действительности. Поднимаем картинку
        // детерминированно: серия отложенных попыток оставляла чёрный экран (см. restartPreviewAfterTeardown).
        if (!isRecordingNow) restartPreviewAfterTeardown()
        updateUi()
    }

    /** Планирует перезапуск потока «с нуля», если попытки ещё остались. */
    private fun scheduleHardStreamRestart() {
        if (!settings.broadcastEnabled) return
        // Во время записи пересоздавать энкодеры нельзя — файл пишется теми же кодеками.
        // Поэтому в этом режиме делаем больше попыток переподключения и не трогаем кодеки.
        val recording = isRecordingNow
        val limit = if (recording) MAX_RESTARTS_WHILE_RECORDING else MAX_HARD_RESTARTS
        if (streamHardRestartCount >= limit) {
            postToUi {
                toast(
                    getString(
                        if (recording) R.string.main_stream_retry_exhausted_recording
                        else R.string.main_stream_retry_exhausted
                    )
                )
            }
            return
        }
        streamHardRestartCount++
        handler.removeCallbacks(hardRestartRunnable)
        handler.postDelayed(hardRestartRunnable, HARD_RESTART_DELAY_MS)
        Log.i(
            TAG,
            "Перезапуск потока №$streamHardRestartCount из $limit через $HARD_RESTART_DELAY_MS мс" +
                if (recording) " (запись идёт — только переподключение)" else ""
        )
    }

    /**
     * Поднимает поток заново.
     *
     * Если идёт запись, энкодеры пересоздавать нельзя: эфир и файл кодируются одними и теми же
     * кодеками, и повторный `startStream()` их перезапустил бы — файл бы порвался. В этом режиме
     * ограничиваемся переподключением клиента (`reTry`), которое кодеки не трогает.
     */
    private fun restartStreamFromScratch() {
        val camera = rtmpCamera2 ?: return
        if (!settings.broadcastEnabled) return
        // Пользователь нажал «Стоп» — никакие отложенные перезапуски не нужны.
        if (userStoppedStream) return
        // Соединение действительно живо (например, библиотека успела переподключиться сама).
        // Проверять camera.isStreaming нельзя: он остаётся true и после неудачного подключения.
        if (streamConnected) return
        val url = settings.fullRtmpUrl
        if (url.isBlank()) return

        if (isRecordingNow) {
            // Энкодеры заняты записью: пересоздавать их нельзя. Только переподключение клиента.
            val scheduled = tryReconnect(camera, "переподключение без остановки записи")
            Log.i(TAG, "Переподключение без остановки записи, попытка $streamHardRestartCount: $scheduled")
            return
        }

        // Соединение гасили снаружи, а записи нет — значит stopStream() прошёл полный teardown,
        // закрыл камеру и GL. Без сброса флага ensurePreview() ничего бы не сделал.
        previewRunning = false
        ensurePreview(camera)
        if (!prepareEncoders(forStream = true)) return
        // Поднимаем новую сессию: обрыв уже обработан, флаг «остановлено пользователем» снимаем.
        userStoppedStream = false
        retryCount = 0
        try {
            camera.startStream(url)
        } catch (e: Exception) {
            Log.e(TAG, "Перезапуск трансляции не удался", e)
        }
    }

    /** Колбэки ConnectChecker приходят из потока RTMP-клиента: View трогаем только в UI-потоке. */
    private fun postToUi(action: () -> Unit) {
        // Колбэк может прийти уже после onDestroy(): библиотека гасит соединение в корутине.
        if (isFinishing || isDestroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!isFinishing && !isDestroyed) action()
        } else {
            handler.post {
                if (!isFinishing && !isDestroyed) action()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MainActivity"

        /** Шаг и число попыток дождаться запуска GL, прежде чем подключать оверлей. */
        const val GL_READY_STEP_MS = 100L
        const val GL_READY_ATTEMPTS = 20

        /** Как часто писать телеметрию канала в лог (мс). */
        const val TELEMETRY_INTERVAL_MS = 5_000L

        /** Порог заполнения очереди, выше которого библиотека считает, что канал «в заторе». */
        const val CONGESTION_PERCENT = 20f

        const val MEGABIT = 1024.0 * 1024.0

        /** Сколько раз пробуем поднять поток «с нуля» после исчерпания автопереподключений. */
        const val MAX_HARD_RESTARTS = 3

        /**
         * Пока идёт запись, энкодеры не пересоздаются — делаем только переподключение клиента.
         * Это дешевле, поэтому попыток больше: файл всё равно пишется, ждать нечего терять.
         */
        const val MAX_RESTARTS_WHILE_RECORDING = 12

        /** Пауза перед перезапуском потока. */
        const val HARD_RESTART_DELAY_MS = 5_000L

        /** Экран настроек уже открыт — переживает пересоздание активити. */
        const val STATE_AWAITING_SETTINGS = "state_awaiting_settings"

        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 5000L

        /** Шаг проверки завершения остановки записи и эфира перед переходом в настройки. */

        /** Предохранитель: 30 × 100 мс = 3 с, после чего настройки открываются в любом случае. */

        /** Прозрачность кнопки, режим которой выключен в настройках (кнопка остаётся кликабельной). */
        const val DISABLED_ALPHA = 0.4f

        /** Пауза перед повторным подключением оверлея после старта записи/эфира. */
        const val OVERLAY_REATTACH_DELAY_MS = 500L

        /** Попытки вернуть превью после teardown библиотеки: первая успешная отменяет остальные. */
        val PREVIEW_RESTORE_DELAYS_MS = longArrayOf(0L, 120L, 300L, 600L)

        /** Раз в секунду обновляем время записи в нижней строке пульта. */
        const val RECORD_TICK_MS = 1_000L

        /** Значение таймера записи, когда файл ещё не пишется. */
        const val RECORD_TIME_ZERO = "00:00"

        /** На сколько шагов делится диапазон зума при нажатии «+» / «−». */
        const val ZOOM_STEPS = 10f

        /** Минимальный шаг зума на случай очень узкого диапазона. */
        const val MIN_ZOOM_STEP = 0.1f

        /** Период обновления зума при удержании: 20 раз в секунду — плавно и без нагрузки на камеру. */
        const val ZOOM_TICK_MS = 50L

        /** Доля диапазона за один тик удержания. */
        const val ZOOM_STEP_PER_TICK = 0.02f

        /** После этого времени удержания зум начинает ускоряться. */
        const val ZOOM_ACCELERATE_AFTER_MS = 600L

        /** Во сколько раз ускоряется зум при долгом удержании. */
        const val ZOOM_ACCELERATED_SPEED = 2.5f

        const val STATE_TEAM1_SCORE = "state_team1_score"
        const val STATE_TEAM2_SCORE = "state_team2_score"
        const val STATE_TEAM1_SETS = "state_team1_sets"
        const val STATE_TEAM2_SETS = "state_team2_sets"
        const val STATE_DECLINED = "state_set_declined"
    }
}



