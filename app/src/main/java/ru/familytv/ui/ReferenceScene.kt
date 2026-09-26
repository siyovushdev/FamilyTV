package ru.familytv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

internal fun color(hex: String): Int = Color.parseColor(hex)
private fun alpha(c: Int, a: Int) = (c and 0x00ffffff) or (a.coerceIn(0, 255) shl 24)

/** Reference pixels, not dp: H728 reports density 2 at 1920x1080. Letterbox other aspect ratios. */
internal class ReferenceStage(context: Context) : ViewGroup(context) {
    var routeKey: ((KeyEvent) -> Boolean)? = null
    private val placements = linkedMapOf<View, RectF>()
    var sceneScale = 1f
        private set

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(color("#090D21"))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        routeKey?.invoke(event) == true || super.dispatchKeyEvent(event)

    fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        placements[view] = RectF(x, y, x + w, y + h)
        addView(view)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        sceneScale = minOf(w / 1920f, h / 1080f)
        setMeasuredDimension(w, h)
        placements.forEach { (view, r) ->
            view.measure(
                MeasureSpec.makeMeasureSpec((r.width() * sceneScale).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((r.height() * sceneScale).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val ox = (width - 1920 * sceneScale) / 2
        val oy = (height - 1080 * sceneScale) / 2
        placements.forEach { (view, rect) ->
            val left = (ox + rect.left * sceneScale).roundToInt()
            val top = (oy + rect.top * sceneScale).roundToInt()
            view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
        }
    }
}

/** Small native drawing primitives, cached as Android display lists. No bitmap backgrounds/WebView. */
internal abstract class ReferenceDrawing(context: Context, private val dw: Float, private val dh: Float) : View(context) {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val icons = mutableMapOf<Int, android.graphics.drawable.Drawable>()

    final override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / dw, height / dh)
        drawScene(canvas)
        canvas.restore()
    }

    abstract fun drawScene(c: Canvas)

    protected fun round(c: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float, fill: Int, stroke: Int? = null, sw: Float = 1f) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawRoundRect(x, y, x + w, y + h, radius, radius, paint)
        if (stroke != null) {
            paint.color = stroke
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            c.drawRoundRect(x, y, x + w, y + h, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    protected fun gradient(c: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float, top: Int, bottom: Int, horizontal: Boolean = false) {
        paint.color = Color.WHITE
        paint.shader = LinearGradient(x, y, if (horizontal) x + w else x, if (horizontal) y else y + h, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(x, y, x + w, y + h, radius, radius, paint)
        paint.shader = null
    }

    protected fun text(c: Canvas, value: String, x: Float, baseline: Float, size: Float, tint: Int = color("#F8FAFC"), heavy: Boolean = false, centered: Boolean = false, maxWidth: Float = 2000f) {
        textPaint.color = tint
        textPaint.textSize = size
        textPaint.typeface = if (heavy) bold else regular
        textPaint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        val fitting = TextUtils.ellipsize(value, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        c.drawText(fitting, x, baseline, textPaint)
    }

    protected fun pill(c: Canvas, value: String, x: Float, y: Float, size: Float, tint: Int, fill: Int, maxWidth: Float = 300f) {
        textPaint.textSize = size
        textPaint.typeface = regular
        val w = minOf(textPaint.measureText(value) + 24f, maxWidth)
        round(c, x - w / 2, y, w, size + 10, (size + 10) / 2, fill, alpha(tint, 90))
        text(c, value, x, y + size + 2, size, tint, centered = true, maxWidth = w - 16)
    }

    protected fun icon(c: Canvas, res: Int, x: Float, y: Float, w: Float, h: Float = w) {
        val drawable = icons.getOrPut(res) { requireNotNull(ContextCompat.getDrawable(context, res)) }
        drawable.setBounds(x.roundToInt(), y.roundToInt(), (x + w).roundToInt(), (y + h).roundToInt())
        drawable.draw(c)
    }

    protected fun glow(c: Canvas, x: Float, y: Float, radius: Float, tint: Int) {
        paint.color = Color.WHITE
        paint.shader = RadialGradient(x, y, radius, tint, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, radius, paint)
        paint.shader = null
    }
}

