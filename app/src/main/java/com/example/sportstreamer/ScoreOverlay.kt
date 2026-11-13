package com.example.sportstreamer

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.library.rtmp.RtmpCamera2
import java.util.Locale

/**
 * Оверлеи счёта поверх трансляции: логотип, цветные полосы команд и текстовые фильтры.
 *
 * ВАЖНО про RootEncoder (проверено по байткоду 2.6.7, класс `com.pedro.encoder.input.gl.Sprite`):
 * ```
 * Sprite.scale(x, y):        position = position / (x / oldScale)   // position *= oldScale / x
 * Sprite.translate(x, y):    position = (x, y)                      // это и есть setPosition
 * ```
 * То есть `setScale` подкручивает позицию, и итог зависит от порядка вызовов:
 * ```
 * setPosition(P); setScale(S)  →  position = P * 100 / S   // так были подобраны координаты
 *                                                          // в первой версии приложения
 * setScale(S); setPosition(P)  →  position = P             // устойчиво к повторным вызовам
 * ```
 * Поэтому геометрия задаётся в порядке scale → position, а координаты заранее приведены к виду
 * `P * 100 / S`: так её можно применять повторно, ничего не сдвигая.
 *
 * ФИЛЬТРЫ СОЗДАЮТСЯ ОДИН РАЗ — при создании активити (вместе с этим объектом), и больше никогда
 * не пересоздаются. RootEncoder пересобирает GL при старте/остановке записи и эфира; если создавать
 * фильтры заново на каждый запуск, на видео мигают названия команд и цифры счёта. Поэтому [attach]
 * только добавляет уже готовые фильтры в GL-интерфейс (один раз), а дальше обновляет текст и цвета.
 */
class ScoreOverlay(
    private val camera: RtmpCamera2,
    private val resources: Resources
) {

    /** Что показывать на оверлее. */
    data class State(
        val team1Name: String,
        val team2Name: String,
        val team1Score: Int,
        val team2Score: Int,
        val team1Sets: Int,
        val team2Sets: Int,
        /** Счёт завершённых партий по порядку: 25:23, 22:25 … */
        val setScores: List<ScoreEngine.SetScore> = emptyList(),
        /** Партия засчитана и ждёт «Новой партии»: очки ещё не обнулены. */
        val awaitingNextSet: Boolean = false,
        /** Матч завершён: новой партии не будет, лишняя колонка не нужна. */
        val matchOver: Boolean = false
    )

    // --- Фильтры создаются один раз, при создании объекта (то есть при открытии активити). ---
    private val titleFirstTeam = TextObjectFilterRender()
    private val titleSecondTeam = TextObjectFilterRender()
    private val scoreFirstTeam = TextObjectFilterRender()
    private val scoreSecondTeam = TextObjectFilterRender()
    private val setFirstTeam = TextObjectFilterRender()
    private val setSecondTeam = TextObjectFilterRender()

    /**
     * Счёт партий: на каждую партию своя пара надписей с фиксированной позицией.
     *
     * Одна растущая строка не годится: позиция спрайта задаёт его центр, поэтому при
     * добавлении второй партии первая съезжала влево. Здесь у каждой партии своё место.
     */
    private val historyFirstTeam = List(MAX_SETS) { TextObjectFilterRender() }
    private val historySecondTeam = List(MAX_SETS) { TextObjectFilterRender() }

    /** Сколько партий сейчас показано в GL. */
    private var historyShown = 0

    private val logoFilter = ImageObjectFilterRender()
    private val stripTeam1Filter = ImageObjectFilterRender()
    private val stripTeam2Filter = ImageObjectFilterRender()

    // scoreFirstTeam/scoreSecondTeam больше не выводятся: счёт показывают колонки партий,
    // иначе на плашке было бы два одинаковых счётчика.
    private val textFilters = listOf(
        titleFirstTeam,
        titleSecondTeam,
        setFirstTeam,
        setSecondTeam
    )

    private val imageFilters = listOf(logoFilter, stripTeam1Filter, stripTeam2Filter)

    /** Добавлены ли фильтры в GL-интерфейс: это делается один раз за активити. */
    private var attached = false

    /**
     * Наложен ли фон плашки. Сбрасывается в [invalidate], как кэши цветов у полос.
     *
     * Сам битмап НЕ кэшируем: `TextureLoader.load(Bitmap[])` после загрузки текстуры вызывает
     * `bitmap.recycle()` (проверено по байткоду 2.6.7), поэтому сохранённый битмап ко второму
     * наложению уже переработан и даёт чёрный фон. Декодируем его заново — так же, как полосы
     * создаются заново в `applyStripImage(...)`.
     */
    private var logoApplied = false

    /** Какие цвета уже наложены на полосы, чтобы не перекрашивать их на каждый attach. */
    private var team1ColorApplied: Int? = null
    private var team2ColorApplied: Int? = null

    /**
     * Последнее применённое состояние. По нему видно, какое именно значение изменилось:
     * при изменении счёта перерисовывается ровно один счётчик через `setText`, остальные
     * текстовые фильтры не трогаются.
     */
    private var appliedState: State? = null

    /** Сколько партий было при последней установке картинки плашки. */
    private var plaqueSets = -1

    /** Картинка плашки под число сыгранных партий. */
    private fun plaqueDrawable(sets: Int): Int = when (sets) {
        0, 1 -> R.drawable.score_title
        2 -> R.drawable.score_title_2
        3 -> R.drawable.score_title_3
        4 -> R.drawable.score_title_4
        else -> R.drawable.score_title_5
    }

    /**
     * Декодирует фон плашки заново: битмап после загрузки текстуры библиотека перерабатывает.
     * [sets] выбирает картинку: у каждой сыгранной партии своя плитка.
     */
    private fun applyLogoImage(sets: Int) {
        val resId = plaqueDrawable(sets)
        val bitmap = BitmapFactory.decodeResource(resources, resId)
        if (bitmap == null) {
            Log.w(TAG, "Плашка $resId недоступна — оверлей без плашки")
            return
        }
        logoFilter.setImage(bitmap)
        Log.i(TAG, "плашка: sets=$sets, res=$resId, bmp=${bitmap.width}x${bitmap.height}")
    }

    /**
     * Помечает подключение потерянным: библиотека снимает фильтры, когда останавливает GL
     * (`MainRender.release()` делает `f.release()` и `filterRenders.clear()`).
     *
     * Вызывается при пересоздании поверхности (`surfaceCreated`) — в том числе при возврате
     * с экрана настроек. При старте и остановке записи/эфира surfaceCreated не приходит,
     * поэтому на них это не влияет.
     *
     * Сбрасывает кэши так же, как для надписей: `attached` — чтобы фильтры добавились заново,
     * `appliedState` — чтобы заново ушёл текст, цвета полос — чтобы они взялись из настроек.
     *
     * Сами объекты фильтров не пересоздаются: `MainRender.addFilter()` при добавлении заново
     * вызывает `filter.initGl(...)`, то есть те же экземпляры корректно инициализируются
     * в новом GL-контексте.
     */
    fun invalidate() {
        attached = false
        appliedState = null
        team1ColorApplied = null
        team2ColorApplied = null
        logoApplied = false
        plaqueSets = -1
        historyShown = 0
    }

    /**
     * Подключает оверлей. Первый вызов добавляет фильтры в GL, все последующие — только
     * обновляют надписи и цвета (фильтры не пересоздаются, поэтому ничего не мигает).
     *
     * Вызывать в onResume и после старта/остановки записи и эфира: так подхватываются изменённые
     * в настройках названия команд и цвета, а после пересборки GL надписи перерисовываются.
     */
    fun attach(state: State, team1Color: Int, team2Color: Int) {
        val reattached = !attached
        if (reattached) {
            // Порядок как в макете: логотип, полосы, затем надписи поверх них.
            imageFilters.forEach { camera.glInterface.addFilter(it) }
            textFilters.forEach { camera.glInterface.addFilter(it) }
            attached = true
        }

        // Так же, как текст: при повторном подключении отдаём картинки заново, а цвета полос
        // берём из настроек (после invalidate() кэш цветов сброшен).
        if (reattached || team1ColorApplied != team1Color) {
            applyStripImage(stripTeam1Filter, R.drawable.color_team1, team1Color)
            team1ColorApplied = team1Color
        }
        if (reattached || team2ColorApplied != team2Color) {
            applyStripImage(stripTeam2Filter, R.drawable.color_team2, team2Color)
            team2ColorApplied = team2Color
        }
        val setsNow = state.team1Sets + state.team2Sets
        if (reattached || !logoApplied || setsNow != plaqueSets) {
            applyLogoImage(setsNow)
            logoApplied = true
            plaqueSets = setsNow
        }

        applyGeometry()
        // В новом GL-контексте текстуры надписей тоже пустые: сбрасываем кэш и отдаём все
        // шесть надписей заново через setText. При обычной смене счёта трогается один фильтр.
        appliedState = null
        updateTexts(state)
    }

    /**
     * Обновляет надписи. Геометрию здесь не трогаем: она задана один раз в [attach].
     *
     * У RootEncoder нет счётчика, который умеет прибавлять/убавлять: текст меняется только
     * `TextObjectFilterRender.setText`. Поэтому сравниваем с прошлым состоянием и вызываем
     * `setText` лишь для тех надписей, которые реально изменились — изменение счёта трогает
     * ровно один фильтр, остальные не перерисовываются.
     */
    fun updateTexts(state: State) {
        val locale = Locale.getDefault()
        val previous = appliedState

        if (previous?.team1Name != state.team1Name) {
            titleFirstTeam.setText(
                state.team1Name.uppercase(locale).padStart(TITLE_CHARS, ' '),
                TITLE_TEXT_SIZE,
                TEXT_COLOR
            )
        }
        if (previous?.team2Name != state.team2Name) {
            titleSecondTeam.setText(
                state.team2Name.uppercase(locale).padStart(TITLE_CHARS, ' '),
                TITLE_TEXT_SIZE,
                TEXT_COLOR
            )
        }
        if (previous?.team1Sets != state.team1Sets) {
            setFirstTeam.setText(state.team1Sets.toString(), SET_TEXT_SIZE, TEXT_COLOR)
        }
        if (previous?.team2Sets != state.team2Sets) {
            setSecondTeam.setText(state.team2Sets.toString(), SET_TEXT_SIZE, TEXT_COLOR)
        }

        // Колонка текущей партии показывает живой счёт, поэтому обновляем на каждое изменение.
        updateSetColumns(state)

        val setsTotal = state.team1Sets + state.team2Sets
        if (logoApplied && setsTotal != plaqueSets) {
            applyLogoImage(setsTotal)
            plaqueSets = setsTotal
        }

        appliedState = state
    }

    /**
     * Забывает о подключении при завершении активити.
     *
     * Фильтры здесь НЕ снимаются (`clearFilters()`/`removeFilter` не вызываются): по правилу они
     * создаются один раз при открытии активити и не удаляются после остановок записи и эфира.
     * Снимать их перед уничтожением активити бессмысленно — GL-интерфейс уничтожается вместе с ней.
     */
    fun detach() {
        attached = false
        appliedState = null
        historyShown = 0
    }

    /**
     * Показывает счёт партий. Пока партий нет — надписей в GL нет: пустая строка ломает
     * `TextObjectFilterRender.setText` (битмап нулевой ширины).
     */
    /**
     * Колонки счёта по партиям.
     *
     * Колонок столько, сколько партий уже начато: завершённые берутся из истории, а последняя
     * показывает живой счёт идущей партии и обновляется на каждое очко — как табло на площадке.
     */
    private fun updateSetColumns(state: State) {
        val finished = state.setScores
        // После конца матча текущей партии нет: колонок ровно по числу сыгранных.
        val extra = if (state.matchOver) 0 else 1
        val target = (finished.size + extra).coerceAtMost(MAX_SETS)

        if (target == 0) {
            while (historyShown > 0) {
                historyShown--
                removeHistoryAt(historyShown)
            }
            return
        }

        while (historyShown > target) {
            historyShown--
            removeHistoryAt(historyShown)
        }
        while (historyShown < target) {
            camera.glInterface.addFilter(historyFirstTeam[historyShown])
            camera.glInterface.addFilter(historySecondTeam[historyShown])
            historyShown++
        }

        for (i in 0 until target) {
            val firstIsLive = !state.matchOver && i == finished.size
            // Пока оператор не нажал «Новая партия», очки на табло принадлежат только что
            // закрытой партии: новая колонка должна быть 00:00, а не повторять её счёт.
            val liveStarted = !state.awaitingNextSet
            val first = when {
                !firstIsLive -> finished[i].team1
                liveStarted -> state.team1Score
                else -> 0
            }
            val second = when {
                !firstIsLive -> finished[i].team2
                liveStarted -> state.team2Score
                else -> 0
            }

            // ВАЖНО: сначала setText (он сбрасывает геометрию), потом позиция.
            historyFirstTeam[i].setText("%02d".format(first), HISTORY_TEXT_SIZE, TEXT_COLOR)
            historySecondTeam[i].setText("%02d".format(second), HISTORY_TEXT_SIZE, TEXT_COLOR)

            val x = HISTORY_FIRST_X + i * HISTORY_STEP_X
            place(historyFirstTeam[i], x, ROW_TEAM1_Y, HISTORY_SCALE_X, ROW_SCALE_Y)
            place(historySecondTeam[i], x, ROW_TEAM2_Y, HISTORY_SCALE_X, ROW_SCALE_Y)
        }
    }

    private fun removeHistoryAt(index: Int) {
        camera.glInterface.removeFilter(historyFirstTeam[index])
        camera.glInterface.removeFilter(historySecondTeam[index])
    }

    /** Геометрия: порядок scale → position, поэтому повторные вызовы ничего не сдвигают. */
    private fun applyGeometry() {
        logoFilter.setScale(LOGO_SCALE_X, LOGO_SCALE_Y)
        logoFilter.setPosition(LOGO_X, LOGO_Y)

        stripTeam1Filter.setScale(STRIP_SCALE_X, STRIP_SCALE_Y)
        stripTeam1Filter.setPosition(STRIP_X, STRIP_TEAM1_Y)
        stripTeam2Filter.setScale(STRIP_SCALE_X, STRIP_SCALE_Y)
        stripTeam2Filter.setPosition(STRIP_X, STRIP_TEAM2_Y)

        place(titleFirstTeam, TITLE_X, ROW_TEAM1_Y, TITLE_SCALE_X, ROW_SCALE_Y)
        place(titleSecondTeam, TITLE_X, ROW_TEAM2_Y, TITLE_SCALE_X, ROW_SCALE_Y)
        place(scoreFirstTeam, SCORE_X, ROW_TEAM1_Y, SCORE_SCALE_X, ROW_SCALE_Y)
        place(scoreSecondTeam, SCORE_X, ROW_TEAM2_Y, SCORE_SCALE_X, ROW_SCALE_Y)
        place(setFirstTeam, SET_X, ROW_TEAM1_Y, SET_SCALE_X, ROW_SCALE_Y)
        place(setSecondTeam, SET_X, ROW_TEAM2_Y, SET_SCALE_X, ROW_SCALE_Y)
    }

    private fun place(
        filter: TextObjectFilterRender,
        x: Float,
        y: Float,
        scaleX: Float,
        scaleY: Float
    ) {
        filter.setScale(scaleX, scaleY)
        filter.setPosition(x, y)
    }

    /** Красит полосу команды: картинка меняется у уже существующего фильтра. */
    private fun applyStripImage(filter: ImageObjectFilterRender, drawableRes: Int, color: Int) {
        val bitmap: Bitmap? = ResourcesCompat.getDrawable(resources, drawableRes, null)
            ?.apply { setTint(color) }
            ?.toBitmap()
        if (bitmap == null) {
            Log.w(TAG, "Ресурс $drawableRes недоступен — полоса команды пропущена")
            return
        }
        filter.setImage(bitmap)
    }

    private companion object {
        const val TAG = "ScoreOverlay"

        /** 0xFFFFFFFF: const val не может быть результатом toInt(). */
        const val TEXT_COLOR = -0x1

        // --- Логотип и полосы: координаты используются напрямую (порядок scale → position) ---
        const val LOGO_X = 1f
        const val LOGO_Y = 86f
        const val LOGO_SCALE_X = 27f
        const val LOGO_SCALE_Y = 14f

        const val STRIP_X = 5f
        const val STRIP_SCALE_X = 1f
        const val STRIP_SCALE_Y = 5f
        const val STRIP_TEAM1_Y = 87f
        const val STRIP_TEAM2_Y = 92.5f

        // --- Тексты: масштаб ---
        const val ROW_SCALE_Y = 3f
        const val TITLE_SCALE_X = 13f
        const val SCORE_SCALE_X = 2f
        const val SET_SCALE_X = 1f

        /**
         * Координаты текстов приведены к виду `старое_значение * 100 / масштаб` — это ровно то
         * состояние спрайта, которое получалось в первой версии после `setPosition` + `setScale`
         * (0.95 / 2.65 при масштабе 13 / 3 давали 7.31 / 88.33). Так картинка остаётся прежней,
         * но геометрию можно применять повторно без сдвига.
         */
        val TITLE_X = 0.95f * 100f / TITLE_SCALE_X
        val SCORE_X = 0.49f * 100f / SCORE_SCALE_X
        val SET_X = 0.2235f * 100f / SET_SCALE_X
        val ROW_TEAM1_Y = 2.65f * 100f / ROW_SCALE_Y
        val ROW_TEAM2_Y = 2.80f * 100f / ROW_SCALE_Y

        // --- Тексты: размеры шрифта (плотность текстуры) ---
        const val TITLE_TEXT_SIZE = 1500f
        const val SCORE_TEXT_SIZE = 2000f
        const val SET_TEXT_SIZE = 2000f

        /** Строка со счётом партий: сдвиг по X, растяжение и размер шрифта. */
        /** Максимум партий в матче: столько пар надписей создаётся. */
        const val MAX_SETS = 5

        /** Первая партия стоит здесь и не двигается; следующие идут правее с этим шагом. */
        const val HISTORY_FIRST_X = 24.5f
        const val HISTORY_STEP_X = 2.7f
        const val HISTORY_SCALE_X = 2f
        const val HISTORY_TEXT_SIZE = 2000f

        const val TITLE_CHARS = 16
        const val SCORE_CHARS = 2
    }
}
