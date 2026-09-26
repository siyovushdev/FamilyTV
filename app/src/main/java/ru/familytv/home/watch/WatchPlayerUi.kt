package ru.familytv.home.watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import ru.familytv.ui.color

/** Root keeps receiving remote input when the entire HUD is hidden. */
internal class WatchPlayerRoot(context: Context) : FrameLayout(context) {
    var routeKey: ((KeyEvent) -> Boolean)? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        routeKey?.invoke(event) == true || super.dispatchKeyEvent(event)
}

internal class WatchPlayerShade(context: Context, private val title: String) : WatchDrawing(context, 1920f, 1080f) {
    var status = ""
        set(value) { field = value; invalidate() }
    var paused = false
        set(value) { field = value; invalidate() }
    override fun drawScene(c: Canvas) {
        gradient(c, 0f, 0f, 1920f, 230f, 0f, color("#D0080D1E"), Color.TRANSPARENT)
        gradient(c, 0f, 700f, 1920f, 380f, 0f, Color.TRANSPARENT, color("#F0080D1E"))
        text(c, title, 320f, 112f, 32f, heavy = true, maxWidth = 1410f)
        if (status.isNotBlank()) text(c, status, 960f, 650f, 24f, centered = true, maxWidth = 1400f)
        if (paused) {
            round(c, 902f, 482f, 116f, 116f, 58f, color("#B0111B38"), color("#6038D9D2"), 2f)
            glyph(c, "pause", 936f, 516f, 48f)
        }
    }
}

internal class WatchTimeline(context: Context) : WatchDrawing(context, 1744f, 80f) {
    var position = 0L
    var duration = 0L
    init {
        id = generateViewId(); isFocusable = true; isFocusableInTouchMode = true
        setOnFocusChangeListener { _, _ -> invalidate() }
    }
    fun update(position: Long, duration: Long) {
        this.position = position; this.duration = duration
        contentDescription = "${watchTime(position)} из ${watchTime(duration)}. Влево и вправо — 10 секунд."
        invalidate()
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            0f, duration.coerceAtLeast(0).toFloat(), position.coerceIn(0, duration.coerceAtLeast(0)).toFloat())
    }
    override fun drawScene(c: Canvas) {
        text(c, watchTime(position), 0f, 50f, 20f, heavy = true)
        text(c, watchTime(duration), 1744f, 50f, 20f, centered = true)
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        round(c, 84f, 33f, 1570f, 10f, 5f, color("#705C667A"))
        round(c, 84f, 33f, 1570f * fraction, 10f, 5f, WatchTokens.cyan)
        paint.color = WatchTokens.cyan
        c.drawCircle(84f + 1570f * fraction, 38f, if (hasFocus()) 12f else 8f, paint)
        if (hasFocus()) round(c, 74f, 19f, 1590f, 38f, 18f, Color.TRANSPARENT, WatchTokens.cyan, 2f)
    }
}
