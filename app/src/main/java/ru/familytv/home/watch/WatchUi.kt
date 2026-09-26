package ru.familytv.home.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import ru.familytv.ui.ReferenceDrawing as StitchDrawing
import ru.familytv.ui.color

internal object WatchTokens {
    val surface = color("#080D1E")
    val panel = color("#111B38")
    val cyan = color("#38D9D2")
    val ink = color("#041D1C")
    val text = color("#F1F5F9")
    val muted = color("#94A3B8")
    const val CARD_WIDTH = 320f
    const val CARD_GAP = 36f
}

internal abstract class WatchDrawing(context: Context, w: Float, h: Float) : StitchDrawing(context, w, h) {
    protected fun art(c: Canvas, bitmap: Bitmap?, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        if (bitmap == null) return
        val scale = maxOf(w / bitmap.width, h / bitmap.height)
        val bw = bitmap.width * scale
        val bh = bitmap.height * scale
        c.save()
        val clip = Path().apply { addRoundRect(RectF(x, y, x + w, y + h), radius, radius, Path.Direction.CW) }
        c.clipPath(clip)
        paint.color = Color.WHITE
        paint.shader = null
        paint.isFilterBitmap = true
        c.drawBitmap(bitmap, null, RectF(x + (w - bw) / 2, y + (h - bh) / 2,
            x + (w + bw) / 2, y + (h + bh) / 2), paint)
        c.restore()
    }

    protected fun paragraph(c: Canvas, value: String, x: Float, y: Float, width: Int, size: Float, tint: Int, lines: Int = 2) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint; textSize = size; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(lines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false)
            .setLineSpacing(6f, 1f).build()
        c.save(); c.translate(x, y); layout.draw(c); c.restore()
    }

    protected fun glyph(c: Canvas, kind: String, x: Float, y: Float, size: Float, tint: Int = WatchTokens.text) {
        c.save(); c.translate(x, y); c.scale(size / 24, size / 24)
        paint.shader = null; paint.color = tint; paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND; paint.style = Paint.Style.STROKE
        when (kind) {
            "play", "watch" -> {
                if (kind == "watch") c.drawRoundRect(2f, 3f, 22f, 18f, 2f, 2f, paint)
                paint.style = Paint.Style.FILL
                c.drawPath(Path().apply { moveTo(9f, 7f); lineTo(17f, 12f); lineTo(9f, 17f); close() }, paint)
                if (kind == "watch") c.drawRect(8f, 21f, 16f, 23f, paint)
            }
            "pause" -> { paint.style = Paint.Style.FILL; c.drawRect(6f, 5f, 10f, 19f, paint); c.drawRect(14f, 5f, 18f, 19f, paint) }
            "back" -> { c.drawLine(18f, 12f, 5f, 12f, paint); c.drawLine(5f, 12f, 11f, 6f, paint); c.drawLine(5f, 12f, 11f, 18f, paint) }
            "space" -> { c.drawCircle(12f, 12f, 7f, paint); c.drawOval(2f, 9f, 22f, 15f, paint); c.drawLine(12f, 1f, 12f, 4f, paint); c.drawLine(12f, 20f, 12f, 23f, paint) }
            "nature" -> {
                c.drawCircle(12f, 12f, 10f, paint)
                paint.style = Paint.Style.FILL
                c.drawPath(Path().apply { moveTo(3f, 7f); lineTo(10f, 4f); lineTo(12f, 9f); lineTo(8f, 12f); lineTo(13f, 17f); lineTo(10f, 21f); lineTo(7f, 17f); lineTo(7f, 13f); lineTo(3f, 10f); close() }, paint)
                c.drawOval(14f, 4f, 20f, 11f, paint)
            }
            "how" -> {
                for (angle in 0 until 360 step 45) { c.save(); c.rotate(angle.toFloat(), 12f, 12f); c.drawLine(12f, 2f, 12f, 6f, paint); c.restore() }
                c.drawCircle(12f, 12f, 7f, paint); c.drawCircle(12f, 12f, 3f, paint)
            }
            "stories" -> { c.drawRoundRect(3f, 4f, 21f, 21f, 2f, 2f, paint); c.drawLine(12f, 5f, 12f, 20f, paint); c.drawLine(6f, 8f, 9f, 8f, paint); c.drawLine(15f, 8f, 18f, 8f, paint) }
            "creativity" -> {
                c.drawOval(2f, 3f, 22f, 21f, paint)
                paint.style = Paint.Style.FILL
                listOf(7f to 8f, 13f to 6f, 18f to 10f, 7f to 15f).forEach { c.drawCircle(it.first, it.second, 2f, paint) }
            }
            "parent" -> { c.drawRoundRect(6f, 10f, 18f, 22f, 2f, 2f, paint); c.drawArc(7f, 1f, 17f, 16f, 180f, 180f, false, paint) }
            "cc" -> { c.drawRoundRect(1f, 4f, 23f, 20f, 2f, 2f, paint); c.drawArc(4f, 8f, 10f, 16f, 50f, 260f, false, paint); c.drawArc(14f, 8f, 20f, 16f, 50f, 260f, false, paint) }
            "audio" -> { c.drawPath(Path().apply { moveTo(3f, 10f); lineTo(7f, 10f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 14f); lineTo(3f, 14f); close() }, paint); c.drawArc(12f, 4f, 23f, 20f, -65f, 130f, false, paint) }
            "lumi" -> { c.drawRoundRect(3f, 7f, 21f, 21f, 6f, 6f, paint); c.drawLine(12f, 2f, 12f, 7f, paint); paint.style = Paint.Style.FILL; c.drawCircle(12f, 2f, 2f, paint); c.drawCircle(8f, 13f, 1.5f, paint); c.drawCircle(16f, 13f, 1.5f, paint) }
        }
        paint.style = Paint.Style.FILL
        c.restore()
    }
}

internal class WatchBackdrop(context: Context) : WatchDrawing(context, 1920f, 1080f) {
    override fun drawScene(c: Canvas) {
        c.drawColor(WatchTokens.surface)
        glow(c, 340f, 185f, 800f, color("#0D147978"))
        glow(c, 1540f, 535f, 780f, color("#101B4C6E"))
    }
}

internal class WatchDecoration(context: Context, private val mode: String, private val title: String = "", private val count: Int = 0, private val categoryIcon: String = "space") : WatchDrawing(context, 1920f, 1080f) {
    var hint = "Выбирай стрелками на пульте"
        set(value) { field = value; invalidate() }
    override fun drawScene(c: Canvas) {
        if (mode == "home") {
            round(c, 88f, 76f, 56f, 56f, 16f, color("#1638BDF8"), color("#8060DEEF"), 2f)
            glyph(c, "watch", 102f, 90f, 28f, color("#67E8F9"))
            text(c, "FamilyTV", 164f, 104f, 32f, heavy = true)
            pill(c, "МИР «СМОТРЕТЬ»", 390f, 80f, 12f, color("#67E8F9"), color("#2038BDF8"))
            text(c, "Выбирай мир историй стрелками на пульте", 164f, 130f, 16f, WatchTokens.muted)
        } else if (mode == "browse") {
            round(c, 304f, 72f, 48f, 48f, 16f, color("#2038D9D2"), color("#A038D9D2"))
            glyph(c, categoryIcon, 316f, 84f, 24f, WatchTokens.cyan)
            text(c, title, 368f, 108f, 32f, heavy = true, maxWidth = 750f)
            text(c, "• $count видео", 1145f, 105f, 20f, WatchTokens.muted)
        } else {
            text(c, "• $title", 324f, 105f, 16f, WatchTokens.muted, maxWidth = 650f)
            text(c, "●  Семейный просмотр • Без рекламы", 88f, 1006f, 16f, WatchTokens.muted)
        }
        if (mode != "details") {
            text(c, "◀ ▶  Выбор     OK  Открыть     BACK  Назад", 1374f, 972f, 16f, WatchTokens.muted, maxWidth = 458f)
        }
    }
}

internal class WatchCard(context: Context, private val category: WatchCategory, private val bitmap: Bitmap?, private val title: String?, private val duration: Long?, private val episode: Int?, private val edge: Int = 0) : WatchDrawing(context, 352f, 420f) {
    var focused: (() -> Unit)? = null
    init {
        id = generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        contentDescription = title?.let { "$it, ${watchDuration(duration)}" } ?: category.title
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focused?.invoke()
            val s = width / 352f
            animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f)
                .translationX(if (hasFocus) edge * 14f * s else 0f)
                .translationZ(if (hasFocus) 8f else 0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
            invalidate()
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.className = "android.widget.Button"
    }
    override fun onDetachedFromWindow() { animate().cancel(); super.onDetachedFromWindow() }
    override fun drawScene(c: Canvas) {
        if (hasFocus()) {
            for (r in 12 downTo 2 step 2) round(c, 12f-r/2, 12f-r/2, 328f+r, 396f+r, 27f+r/2,
                Color.TRANSPARENT, color("#1238D9D2"), 4f)
            round(c, 12f, 12f, 328f, 396f, 26f, Color.TRANSPARENT, WatchTokens.cyan, 4f)
        }
        round(c, 16f, 16f, 320f, 388f, 24f, color("#0F172A"))
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(16f,16f,336f,404f),24f,24f,Path.Direction.CW) })
        val artH = if (title == null) 310f else 280f
        art(c, bitmap, 16f, 16f, 320f, artH)
        if (bitmap == null) glyph(c, category.id, 122f, 104f, 108f, category.accent)
        gradient(c, 16f, 120f, 320f, artH-104, 0f, Color.TRANSPARENT, color("#F0080D1E"))
        gradient(c, 16f, 16f+artH, 320f, 388f-artH, 0f, color("#0F172A"), color("#040711"))
        c.restore()
        round(c, 16f, 16f, 320f, 388f, 24f, Color.TRANSPARENT, if (hasFocus()) WatchTokens.cyan else (category.accent and 0xffffff or 0x55000000), 2f)
        if (title == null) {
            val gx = if (hasFocus()) 272f else 32f
            round(c, gx, 32f, 48f, 48f, 16f, if (hasFocus()) WatchTokens.cyan else category.accent)
            glyph(c, category.id, gx+10, 42f, 28f, if (hasFocus()) WatchTokens.ink else Color.WHITE)
            if (hasFocus()) {
                round(c, 32f, 32f, 145f, 30f, 15f, WatchTokens.cyan)
                text(c, "ВЫБРАНО · OK", 104f, 52f, 13f, WatchTokens.ink, true, true)
            }
            text(c, category.line1, 176f, 351f, 25f, if (hasFocus()) color("#67E8F9") else WatchTokens.text, true, true)
            text(c, category.line2, 176f, 378f, 16f, category.accent, true, true)
        } else {
            round(c, 242f, 32f, 76f, 32f, 16f, color("#B0000000"), color("#40FFFFFF"))
            text(c, watchDuration(duration), 280f, 54f, 16f, heavy = true, centered = true)
            text(c, title, 36f, 337f, 24f, if (hasFocus()) color("#A5F3FC") else WatchTokens.text, true, maxWidth = 280f)
            text(c, episode?.let { "Серия $it" } ?: "Видео", 36f, 378f, 16f, if (hasFocus()) WatchTokens.cyan else WatchTokens.muted)
            if (hasFocus()) {
                paint.color = WatchTokens.cyan; c.drawCircle(176f, 156f, 32f, paint)
                glyph(c, "play", 160f, 140f, 32f, WatchTokens.ink)
                text(c, "OK", 304f, 378f, 16f, WatchTokens.cyan, true, true)
            }
        }
    }
}

internal class WatchButton(context: Context, var label: String, private var kind: String = "", private val w: Float = 220f, private val h: Float = 64f, private val primary: Boolean = false) : WatchDrawing(context, w + 24f, h + 24f) {
    init {
        id = generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        contentDescription = label
        setOnFocusChangeListener { _, hasFocus ->
            animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(180).start()
            invalidate()
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.className = "android.widget.Button"
    }
    fun update(value: String, icon: String? = null) {
        label = value; contentDescription = value
        if (icon != null) kind = icon
        invalidate()
    }
    override fun drawScene(c: Canvas) {
        if (hasFocus()) round(c, 8f, 8f, w+8, h+8, 20f, Color.TRANSPARENT, WatchTokens.cyan, 3f)
        val fill = if (primary) WatchTokens.cyan else color("#C0111B28")
        val tint = if (primary) WatchTokens.ink else WatchTokens.text
        round(c, 12f, 12f, w, h, 16f, fill, if (hasFocus()) WatchTokens.cyan else color("#60475569"))
        if (kind.isNotBlank()) glyph(c, kind, 30f, 12+h/2-12, 24f, tint)
        text(c, label, if (kind.isBlank()) 12+w/2 else 68f, 12+h/2+7,
            if (h>=70) 24f else 18f, tint, true, kind.isBlank(), w-if (kind.isBlank()) 28 else 78)
    }
}

internal class WatchGuide(context: Context) : WatchDrawing(context, 600f, 110f) {
    var message = "Нажми OK, чтобы открыть мир историй."
        set(value) { field = value; contentDescription = value; invalidate() }
    init {
        id = generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        contentDescription = message
        setOnFocusChangeListener { _, _ -> invalidate() }
    }
    override fun drawScene(c: Canvas) {
        round(c, 4f, 4f, 592f, 94f, 18f, color("#D011192D"), if (hasFocus()) WatchTokens.cyan else color("#334155"), if (hasFocus()) 3f else 1f)
        round(c, 26f, 31f, 46f, 46f, 23f, color("#2538D9D2"), WatchTokens.cyan, 2f)
        glyph(c, "lumi", 36f, 40f, 26f, color("#67E8F9"))
        text(c, "ПОДСКАЗКА ЛУМИ", 90f, 33f, 13f, WatchTokens.cyan, true)
        paragraph(c, message, 90f, 44f, 480, 18f, WatchTokens.text, 2)
    }
}

internal class WatchHero(context: Context, private val bitmap: Bitmap?, private val duration: Long?, private val episode: Int?) : WatchDrawing(context, 1000f, 530f) {
    override fun drawScene(c: Canvas) {
        art(c, bitmap, 0f, 0f, 1000f, 530f, 24f)
        if (bitmap == null) glyph(c, "watch", 452f, 210f, 96f, WatchTokens.cyan)
        gradient(c, 0f, 330f, 1000f, 200f, 24f, Color.TRANSPARENT, color("#90000000"))
        round(c, 1f, 1f, 998f, 528f, 24f, Color.TRANSPARENT, color("#80475569"), 2f)
        if (episode != null) {
            round(c, 26f, 26f, 190f, 30f, 15f, color("#D038D9D2"))
            text(c, "СЕРИЯ $episode", 121f, 47f, 14f, WatchTokens.ink, true, true)
        }
        round(c, 26f, 462f, 112f, 42f, 18f, color("#C0000000"), color("#50FFFFFF"))
        text(c, watchDuration(duration), 82f, 491f, 20f, heavy = true, centered = true)
    }
}

internal class WatchDetailsText(context: Context, private val title: String, private val description: String) : WatchDrawing(context, 704f, 205f) {
    override fun drawScene(c: Canvas) {
        text(c, title, 0f, 52f, 44f, heavy = true, maxWidth = 700f)
        paragraph(c, description, 0f, 80f, 688, 24f, WatchTokens.text, 3)
    }
}
