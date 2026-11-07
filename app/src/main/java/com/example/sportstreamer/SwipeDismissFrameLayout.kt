package com.example.sportstreamer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Контейнер панели настроек, который закрывает её горизонтальным смахиванием.
 *
 * Стоит снаружи повёрнутой панели, поэтому его оси совпадают с тем, что видит пользователь:
 * горизонтальное движение — жест закрытия, вертикальное — прокрутка списка настроек.
 * Вертикальные драги перехватывает прокрутка внутри, горизонтальные — этот контейнер.
 */
class SwipeDismissFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Срабатывает, когда смахивание признано закрывающим. */
    var onDismiss: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                // Горизонталь уверенно перевешивает вертикаль — это жест закрытия,
                // а не прокрутка содержимого.
                if (dx > touchSlop && dx > dy * 1.5f) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > width * DISMISS_THRESHOLD && dx > dy * 1.5f) {
                    onDismiss?.invoke()
                }
            }
        }
        // Событие поглощается целиком: иначе отпускание уйдёт в дочерние view
        // и смахивание сработает дважды или не сработает вовсе.
        return true
    }

    private companion object {
        /** Доля ширины панели, после которой смахивание считается закрывающим. */
        const val DISMISS_THRESHOLD = 0.2f
    }
}
