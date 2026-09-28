package ru.familytv.move

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import ru.familytv.ui.ReferenceDrawing
import ru.familytv.ui.color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val CYAN = color("#38d9d2")
private val NAVY = color("#080d1e")
private val CREAM = color("#f1f5f9")
private val AMBER = color("#fbbf24")
private val PURPLE = color("#c084fc")
private val SKY = color("#7bd0ff")
private val TEAL = color("#78d2e6")
private val FACE = color("#1e3246")
private fun tint(c: Int, a: Int) = (c and 0xffffff) or (a.coerceIn(0, 255) shl 24)
private fun ease(f: Float) = f.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

internal object MoveGeometry {
    val place = mapOf(
        MovePlace.CLOUD to RectF(720f, 70f, 1200f, 300f),
        MovePlace.MUSIC to RectF(40f, 360f, 430f, 760f),
        MovePlace.TRAIL to RectF(1490f, 280f, 1880f, 780f),
        MovePlace.STARS to RectF(560f, 340f, 1360f, 900f),
    )
    val start = RectF(620f, 900f, 1300f, 1004f)
    val readyStart = RectF(620f, 820f, 1300f, 964f)
    val pause = RectF(88f, 72f, 360f, 148f)
    val continueBtn = RectF(560f, 500f, 940f, 600f)
    val finishBtn = RectF(980f, 500f, 1360f, 600f)
    val replay = RectF(300f, 860f, 720f, 960f)
    val world = RectF(740f, 860f, 1180f, 960f)
    val home = RectF(1200f, 860f, 1620f, 960f)
    val lumi = RectF(760f, 430f, 1160f, 900f)

    fun target(key: String): RectF? = when {
        key.startsWith("place:") -> place[MovePlace.valueOf(key.substringAfter(':'))]
        key == "start" -> start
        key == "pause" -> pause
        key == "continue" -> continueBtn
        key == "finish" -> finishBtn
        key == "replay" -> replay
        key == "world" -> world
        key == "home" -> home
        key == "scene" -> RectF(200f, 180f, 1720f, 840f)
        else -> null
    }
}

@SuppressLint("ViewConstructor")
internal class MoveTarget(context: Context, label: String) : View(context) {
    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = label; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

internal enum class MoveGlyph { NONE, BACK, PAUSE, PLAY, HOME }

@SuppressLint("ViewConstructor")
internal class MoveButton(context: Context, val label: String, private val dw: Float, private val dh: Float, private val primary: Boolean, private val glyph: MoveGlyph) : ReferenceDrawing(context, dw, dh) {
    private val path = Path()
    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = label }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) { super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); invalidate() }
    override fun drawScene(c: Canvas) {
        if (isFocused) glow(c, dw / 2, dh / 2, dw / 1.5f, tint(CYAN, 88))
        round(c, 4f, 4f, dw - 8, dh - 8, (dh - 8) / 2, if (isFocused) CYAN else tint(NAVY, 230), if (isFocused) Color.WHITE else tint(if (primary) CYAN else Color.WHITE, 110), if (isFocused) 4f else 1.5f)
        val ink = if (isFocused) NAVY else CREAM
        val shift = if (glyph != MoveGlyph.NONE) 20f else 0f
        if (glyph != MoveGlyph.NONE) drawGlyph(c, dw / 2 - paintMeasure(label) / 2 - 18, dh / 2, ink)
        text(c, label, dw / 2 + shift, dh / 2 + 10, if (dh < 80) 26f else 30f, ink, true, true, dw - 48)
    }
    private fun paintMeasure(label: String): Float { paint.textSize = 28f; paint.typeface = Typeface.create("sans-serif", Typeface.BOLD); return paint.measureText(label) }
    private fun drawGlyph(c: Canvas, x: Float, y: Float, ink: Int) {
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (isFocused) NAVY else CYAN
        path.reset()
        when (glyph) {
            MoveGlyph.BACK -> { path.moveTo(x + 6, y - 11); path.lineTo(x - 6, y); path.lineTo(x + 6, y + 11); c.drawPath(path, paint) }
            MoveGlyph.PAUSE -> { paint.style = Paint.Style.FILL; c.drawRoundRect(x - 8, y - 12, x - 2, y + 12, 2f, 2f, paint); c.drawRoundRect(x + 2, y - 12, x + 8, y + 12, 2f, 2f, paint) }
            MoveGlyph.PLAY -> { paint.style = Paint.Style.FILL; paint.color = ink; path.moveTo(x - 8, y - 12); path.lineTo(x + 12, y); path.lineTo(x - 8, y + 12); path.close(); c.drawPath(path, paint) }
            MoveGlyph.HOME -> { paint.style = Paint.Style.STROKE; path.moveTo(x - 10, y); path.lineTo(x, y - 12); path.lineTo(x + 10, y); c.drawPath(path, paint); c.drawRect(x - 7, y, x + 7, y + 12, paint) }
            MoveGlyph.NONE -> Unit
        }
        paint.style = Paint.Style.FILL
    }
}

@SuppressLint("ViewConstructor")
internal class MoveWorldView(context: Context, private val state: MovePreviewState, private val art: Map<String, Bitmap>) : ReferenceDrawing(context, 1920f, 1080f) {
    var ambientMs = 0L
    private val path = Path()
    private val bounds = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val oval = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val wave get() = .5f + .5f * sin(ambientMs / 420.0).toFloat()
    private val frozen get() = state.value.paused || state.value.worldStill > 0.92f

    override fun drawScene(c: Canvas) {
        val s = state.value
        background(c, artName(s))
        scrim(c, s)
        when (s.screen) {
            MoveScreen.MOVE_HOME -> home(c, s)
            MoveScreen.READY -> ready(c)
            MoveScreen.REACH -> reach(c, s)
            MoveScreen.COMETS -> comets(c, s)
            MoveScreen.FREEZE -> freeze(c, s)
            MoveScreen.BALANCE -> balance(c, s)
            MoveScreen.ENDING -> ending(c, s)
        }
        if (s.helping) lumi(c, s)
        banner(c, s)
        if (s.paused) pauseOverlay(c)
    }

    private fun artName(s: MoveSnapshot) = when (s.screen) {
        MoveScreen.MOVE_HOME -> "move_home"
        MoveScreen.READY -> "ready"
        MoveScreen.REACH -> "reach"
        MoveScreen.COMETS -> "comets"
        MoveScreen.FREEZE -> "freeze"
        MoveScreen.BALANCE -> "balance"
        MoveScreen.ENDING -> "ending"
    }

    private fun background(c: Canvas, name: String) {
        val bitmap = art[name] ?: return
        val scale = max(1920f / bitmap.width, 1080f / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        bounds.set((1920 - w) / 2, (1080 - h) / 2, (1920 + w) / 2, (1080 + h) / 2)
        c.drawBitmap(bitmap, null, bounds, bitmapPaint)
    }

    private fun scrim(c: Canvas, s: MoveSnapshot) {
        val bottom = if (s.screen == MoveScreen.ENDING && s.actionsReady) 210 else 150
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, 0f, 0f, 1080f, intArrayOf(tint(NAVY, 170), tint(NAVY, 0), tint(NAVY, 0), tint(NAVY, bottom)), floatArrayOf(0f, .22f, .72f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, paint); paint.shader = null
    }

    private fun home(c: Canvas, s: MoveSnapshot) {
        for (place in MovePlace.entries) {
            val r = MoveGeometry.place.getValue(place)
            val on = s.focus == "place:${place.name}"
            if (on) glow(c, r.centerX(), r.centerY(), r.width() / 2.1f, tint(if (place.available) CYAN else PURPLE, 70))
            val label = if (place.available) "${place.label} · Активно" else "${place.label} · Скоро"
            val fill = if (on) tint(CYAN, 230) else tint(NAVY, 200)
            val ink = if (on) NAVY else CREAM
            round(c, r.centerX() - 210, r.top + 8, 420f, 56f, 28f, fill, if (on) Color.WHITE else tint(Color.WHITE, 70), if (on) 3f else 1f)
            text(c, label, r.centerX(), r.top + 46, 24f, ink, true, true, 400f)
        }
    }

    private fun ready(c: Canvas) {
        round(c, 420f, 620f, 1080f, 280f, 36f, tint(NAVY, 210), tint(CYAN, 90), 2f)
        chip(c, 620f, 648f, "Игрушки в стороне")
        chip(c, 1020f, 648f, "Свободное место")
        text(c, "Встаём перед экраном!", 960f, 760f, 40f, CREAM, true, true)
        text(c, "Пульт можно положить рядом — Луми будет двигаться вместе с нами.", 960f, 808f, 24f, tint(CREAM, 200), false, true, 980f)
    }

    private fun reach(c: Canvas, s: MoveSnapshot) {
        val glow = ease(s.starsGlow)
        val twinkle = if (frozen) 0f else wave
        for (i in 0 until 8) {
            val x = 240f + i * 200f + sin((i + 1) * 1.7).toFloat() * 30f
            val y = 160f + (i % 3) * 70f
            val r = 10f + glow * 16f + if (frozen) 0f else twinkle * 4f
            glow(c, x.toFloat(), y, r + 18, tint(AMBER, (40 + glow * 140).toInt()))
            star(c, x.toFloat(), y, r, tint(AMBER, (120 + glow * 130).toInt()))
        }
        if (s.pose == LumiPose.STRETCH || s.helping) {
            arrowsUp(c, 260f); arrowsUp(c, 1660f)
        }
        meter(c, 0.18f + glow * 0.55f, "Звёздочки просыпаются")
    }

    private fun comets(c: Canvas, s: MoveSnapshot) {
        val t = if (frozen) 0.35f else (ambientMs % 4000) / 4000f
        for (i in 0 until 4) {
            val slide = ((t + i * 0.22f) % 1f)
            val x = -80f + slide * 2100f
            val y = 220f + i * 90f
            glow(c, x, y, 48f, tint(AMBER, 80))
            paint.color = tint(color("#ffd9a0"), 230)
            c.drawCircle(x, y, 22f, paint)
            paint.color = tint(color("#ffb86b"), 160)
            c.drawOval(x - 70, y - 10, x, y + 10, paint)
        }
        val left = s.pose == LumiPose.STEP_LEFT || (s.helping && s.helpMs < MoveRules.HELP_MS / 2)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 10f; paint.color = CYAN
        val y = 620f
        if (left) { path.reset(); path.moveTo(280f, y); path.lineTo(160f, y); path.lineTo(200f, y - 30); c.drawPath(path, paint) }
        else { path.reset(); path.moveTo(1640f, y); path.lineTo(1760f, y); path.lineTo(1720f, y - 30); c.drawPath(path, paint) }
        paint.style = Paint.Style.FILL
        meter(c, s.pathOpen, "Звёздный путь открывается")
    }

    private fun freeze(c: Canvas, s: MoveSnapshot) {
        val still = s.worldStill
        val drift = if (still > 0.85f || frozen) 0f else (ambientMs / 18f) % 80f
        for (i in 0 until 14) {
            val x = 140f + (i * 127f) % 1640f + if (still > 0.85f) 0f else sin(i + ambientMs / 500.0).toFloat() * 18f
            val y = 200f + (i * 67f) % 620f + drift
            glow(c, x, y, 10f + (1 - still) * 8, tint(if (still > 0.6f) SKY else AMBER, 140))
            paint.color = tint(Color.WHITE, 210); c.drawCircle(x, y, 4f, paint)
        }
        if (s.phase == MovePhase.COUNTDOWN || (s.phase == MovePhase.FREEZE && s.elapsedMs > 400)) {
            val n = if (s.phase == MovePhase.COUNTDOWN) s.countdown else 0
            glow(c, 1680f, 420f, 90f, tint(CYAN, 70))
            round(c, 1600f, 330f, 160f, 160f, 80f, tint(NAVY, 200), CYAN, 3f)
            text(c, if (n > 0) n.toString() else "❄", 1680f, 430f, 52f, CYAN, true, true)
        }
        meter(c, still, "Тсс… застыли вместе")
    }

    private fun balance(c: Canvas, s: MoveSnapshot) {
        val built = ease(s.bridge)
        paint.shader = LinearGradient(360f, 820f, 1560f, 700f, intArrayOf(tint(SKY, 40), tint(CYAN, (80 + built * 140).toInt()), tint(AMBER, (60 + built * 120).toInt())), null, Shader.TileMode.CLAMP)
        path.reset(); path.moveTo(280f, 880f); path.quadTo(960f, 620f - built * 40f, 1640f, 860f); path.lineTo(1600f, 900f); path.quadTo(960f, 700f, 320f, 920f); path.close()
        c.drawPath(path, paint); paint.shader = null
        meter(c, built, "Звёздный мостик")
    }

    private fun ending(c: Canvas, s: MoveSnapshot) {
        for (i in 0 until 10) {
            val x = 180f + i * 170f
            glow(c, x, 160f + (i % 2) * 40f, 16f + wave * 4, tint(AMBER, 90))
        }
        if (s.actionsReady) {
            text(c, "Глубокий вдох… и мягкий выдох", 960f, 820f, 28f, CREAM, true, true)
        }
    }

    private fun lumi(c: Canvas, s: MoveSnapshot) {
        if (s.screen == MoveScreen.MOVE_HOME || s.screen == MoveScreen.READY || (s.screen == MoveScreen.ENDING && !s.helping)) return
        val pose = s.pose
        val cx = 960f + when (pose) {
            LumiPose.STEP_LEFT -> -70f
            LumiPose.STEP_RIGHT -> 70f
            else -> 0f
        }
        val cy = 780f
        val bounce = if (pose == LumiPose.DANCE && !frozen) sin(ambientMs / 180.0).toFloat() * 10f else if (pose == LumiPose.STRETCH) -18f else 0f
        MoveLumi.draw(c, paint, oval, path, cx, cy + bounce, 92f, pose, if (frozen) 0f else wave)
    }

    private fun banner(c: Canvas, s: MoveSnapshot) {
        val line = state.prompt()
        if (line.isBlank() || s.paused) return
        val w = 920f
        round(c, 500f, 72f, w, 78f, 39f, tint(NAVY, 215), tint(CYAN, 80), 2f)
        text(c, line, 960f, 122f, 28f, CYAN, true, true, w - 40)
    }

    private fun pauseOverlay(c: Canvas) {
        paint.color = tint(NAVY, 150); c.drawRect(0f, 0f, 1920f, 1080f, paint)
        round(c, 520f, 340f, 880f, 320f, 36f, tint(NAVY, 240), CYAN, 3f)
        text(c, "Пауза", 960f, 420f, 40f, CREAM, true, true)
        text(c, "Приключение ждёт на том же шаге", 960f, 470f, 24f, tint(CREAM, 190), false, true)
    }

    private fun chip(c: Canvas, x: Float, y: Float, label: String) {
        round(c, x, y, 280f, 48f, 24f, tint(NAVY, 180), tint(CYAN, 60), 1f)
        text(c, label, x + 140, y + 34, 20f, CREAM, true, true, 260f)
    }

    private fun meter(c: Canvas, fill: Float, label: String) {
        round(c, 620f, 980f, 680f, 56f, 28f, tint(NAVY, 200), tint(Color.WHITE, 50), 1f)
        text(c, label, 960f, 1016f, 22f, CREAM, true, true, 640f)
        round(c, 700f, 1028f, 520f, 8f, 4f, tint(Color.WHITE, 40))
        paint.color = CYAN
        c.drawRoundRect(700f, 1028f, 700f + 520f * fill.coerceIn(0f, 1f), 1036f, 4f, 4f, paint)
    }

    private fun arrowsUp(c: Canvas, x: Float) {
        paint.color = tint(CYAN, 200); paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f
        val y = 360f - if (frozen) 0f else wave * 16f
        path.reset(); path.moveTo(x, y + 40); path.lineTo(x, y - 10); path.moveTo(x - 18, y + 8); path.lineTo(x, y - 10); path.lineTo(x + 18, y + 8)
        c.drawPath(path, paint); paint.style = Paint.Style.FILL
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float, tint: Int) {
        path.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val a = -PI / 2 + i * PI / 5
            val px = x + cos(a).toFloat() * rad; val py = y + sin(a).toFloat() * rad
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); paint.shader = null; paint.color = tint; c.drawPath(path, paint)
    }
}

/** One canonical FamilyTV Lumi: teal orb, one antenna, dark face. Pose changes only. */
internal object MoveLumi {
    fun draw(c: Canvas, paint: Paint, oval: RectF, path: Path, cx: Float, cy: Float, r: Float, pose: LumiPose, wave: Float) {
        paint.shader = RadialGradient(cx, cy, r * 1.8f, tint(TEAL, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.7f, paint); paint.shader = null
        val arm = when (pose) {
            LumiPose.STRETCH, LumiPose.BREATHE_IN -> -110f
            LumiPose.BALANCE -> 8f
            LumiPose.FREEZE -> -20f
            LumiPose.DANCE -> -40f - wave * 20f
            LumiPose.STEP_LEFT -> -15f
            LumiPose.STEP_RIGHT -> -15f
            LumiPose.BREATHE_OUT, LumiPose.CALM -> 55f
            else -> 25f
        }
        val spread = when (pose) {
            LumiPose.BALANCE -> 1.35f
            LumiPose.STRETCH -> 0.55f
            LumiPose.STEP_LEFT -> 0.85f
            LumiPose.STEP_RIGHT -> 0.85f
            else -> 1f
        }
        paint.color = TEAL; paint.strokeWidth = r * 0.22f; paint.strokeCap = Paint.Cap.ROUND; paint.style = Paint.Style.STROKE
        val leftX = cx - r * 0.95f * spread
        val rightX = cx + r * 0.95f * spread
        val handY = cy + sin(Math.toRadians(arm.toDouble())).toFloat() * r * 1.1f
        val leftHand = if (pose == LumiPose.STEP_LEFT) leftX - 20 else leftX
        val rightHand = if (pose == LumiPose.STEP_RIGHT) rightX + 20 else rightX
        c.drawLine(cx - r * 0.45f, cy, leftHand, handY, paint)
        c.drawLine(cx + r * 0.45f, cy, rightHand, handY, paint)
        paint.style = Paint.Style.FILL
        paint.color = TEAL
        oval.set(cx - r, cy - r * 1.05f, cx + r, cy + r * 0.95f)
        c.drawOval(oval, paint)
        paint.color = color("#bef0fa")
        oval.set(cx - r * 0.42f, cy - r * 0.05f, cx + r * 0.42f, cy + r * 0.62f)
        c.drawOval(oval, paint)
        paint.color = color("#5aa8c8"); paint.strokeWidth = r * 0.08f; paint.style = Paint.Style.STROKE
        c.drawLine(cx, cy - r * 0.95f, cx, cy - r * 1.38f, paint)
        paint.style = Paint.Style.FILL; c.drawCircle(cx, cy - r * 1.42f, r * 0.13f, paint)
        paint.color = FACE
        val eyeY = cy - r * 0.18f
        if (pose == LumiPose.CALM || pose == LumiPose.BREATHE_OUT) {
            paint.strokeWidth = r * 0.07f; paint.style = Paint.Style.STROKE
            c.drawLine(cx - r * 0.28f, eyeY, cx - r * 0.08f, eyeY, paint)
            c.drawLine(cx + r * 0.08f, eyeY, cx + r * 0.28f, eyeY, paint)
            paint.style = Paint.Style.FILL
        } else {
            c.drawCircle(cx - r * 0.26f, eyeY, r * 0.11f, paint)
            c.drawCircle(cx + r * 0.26f, eyeY, r * 0.11f, paint)
            paint.color = Color.WHITE
            c.drawCircle(cx - r * 0.30f, eyeY - r * 0.04f, r * 0.04f, paint)
            c.drawCircle(cx + r * 0.22f, eyeY - r * 0.04f, r * 0.04f, paint)
        }
        paint.color = FACE; paint.style = Paint.Style.STROKE; paint.strokeWidth = r * 0.07f
        path.reset()
        val my = cy + r * 0.32f
        path.moveTo(cx - r * 0.22f, my)
        path.quadTo(cx, my + r * (if (pose == LumiPose.FREEZE) 0.06f else 0.22f), cx + r * 0.22f, my)
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
