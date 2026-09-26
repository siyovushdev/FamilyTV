package ru.familytv.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import ru.familytv.adventure.GuideEmotion

/**
 * Original Lumi character — readable at 2–3 m.
 * Lightweight canvas drawing + short emotion animations (no skeletal engine).
 * See docs/LUMI_CHARACTER.md.
 */
class LumiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Motion {
        IDLE, APPEAR, LISTEN, THINK, REACT_CORRECT, REACT_WRONG, POINT, CELEBRATE, TRANSITION, HELP
    }

    var emotion: GuideEmotion = GuideEmotion.CURIOUS
        set(value) {
            field = value
            refreshArt()
            invalidate()
        }

    private var bounce = 0f
    private var glow = 0f
    private var animator: ValueAnimator? = null
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var artDrawable: android.graphics.drawable.Drawable? = null
    var sizeVariant: LumiAssets.SizeVariant = LumiAssets.SizeVariant.UI

    private fun refreshArt() {
        artDrawable = LumiAssets.resolve(context, emotion, sizeVariant)
    }

    init {
        refreshArt()
    }

    fun play(motion: Motion) {
        animator?.cancel()
        when (motion) {
            Motion.IDLE -> {
                bounce = 0f
                glow = 0.15f
                invalidate()
            }
            Motion.APPEAR -> animateFloat(0f, 1f, 380) { t ->
                alpha = t
                bounce = (1f - t) * 12f
                glow = t * 0.4f
            }
            Motion.LISTEN -> pulse(0.25f, 0.55f, 900)
            Motion.THINK -> pulse(0.1f, 0.35f, 1100)
            Motion.REACT_CORRECT, Motion.CELEBRATE -> animateFloat(0f, 1f, 420) { t ->
                bounce = -kotlin.math.sin(t * Math.PI.toFloat()) * 18f
                glow = 0.2f + t * 0.5f
            }
            Motion.REACT_WRONG -> animateFloat(0f, 1f, 360) { t ->
                bounce = kotlin.math.sin(t * Math.PI.toFloat() * 2) * 4f
                glow = 0.2f
            }
            Motion.POINT, Motion.HELP -> animateFloat(0f, 1f, 300) { t ->
                bounce = -t * 6f
                glow = 0.35f
            }
            Motion.TRANSITION -> animateFloat(1f, 0.7f, 220) { t ->
                alpha = 0.7f + 0.3f * t
            }
        }
    }

    fun setEmotionWithMotion(emotion: GuideEmotion) {
        this.emotion = emotion
        play(
            when (emotion) {
                GuideEmotion.CALM, GuideEmotion.REST -> Motion.IDLE
                GuideEmotion.CURIOUS, GuideEmotion.LISTENING -> Motion.LISTEN
                GuideEmotion.THINKING -> Motion.THINK
                GuideEmotion.HAPPY, GuideEmotion.EXCITED, GuideEmotion.CELEBRATING -> Motion.CELEBRATE
                GuideEmotion.ENCOURAGING, GuideEmotion.HELPING -> Motion.HELP
                GuideEmotion.SURPRISED -> Motion.REACT_CORRECT
            },
        )
    }

    private fun pulse(from: Float, to: Float, duration: Long) {
        animator = ObjectAnimator.ofFloat(from, to).apply {
            this.duration = duration
            repeatMode = ValueAnimator.REVERSE
            repeatCount = 1
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                glow = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateFloat(from: Float, to: Float, duration: Long, block: (Float) -> Unit) {
        animator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                block(it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val art = artDrawable
        if (art != null) {
            val pad = (width * 0.06f).toInt()
            val dy = bounce.toInt()
            art.setBounds(pad, pad + dy, width - pad, height - pad + dy)
            art.alpha = (255 * (0.85f + glow * 0.15f)).toInt().coerceIn(120, 255)
            art.draw(canvas)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f + bounce
        val r = minOf(w, h) * 0.38f

        // Soft aura
        accent.color = Color.argb((60 + glow * 120).toInt().coerceIn(0, 180), 120, 210, 230)
        canvas.drawCircle(cx, cy, r * (1.25f + glow * 0.15f), accent)

        // Body — friendly rounded teardrop / orb hybrid
        body.color = Color.rgb(120, 210, 230)
        oval.set(cx - r, cy - r * 1.05f, cx + r, cy + r * 0.95f)
        canvas.drawOval(oval, body)

        // Belly highlight
        body.color = Color.rgb(190, 240, 250)
        oval.set(cx - r * 0.45f, cy - r * 0.1f, cx + r * 0.45f, cy + r * 0.7f)
        canvas.drawOval(oval, body)

        // Antenna
        accent.color = Color.rgb(90, 170, 200)
        accent.strokeWidth = r * 0.08f
        accent.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy - r * 0.95f, cx, cy - r * 1.35f, accent)
        accent.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy - r * 1.4f, r * 0.14f, accent)

        drawFace(canvas, cx, cy, r)
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        face.color = Color.rgb(30, 50, 70)
        val eyeY = cy - r * 0.15f
        val eyeR = when (emotion) {
            GuideEmotion.SURPRISED, GuideEmotion.CELEBRATING -> r * 0.14f
            GuideEmotion.THINKING -> r * 0.1f
            GuideEmotion.REST -> r * 0.06f
            else -> r * 0.11f
        }
        val eyeDx = r * 0.28f
        when (emotion) {
            GuideEmotion.REST -> {
                face.strokeWidth = r * 0.06f
                face.style = Paint.Style.STROKE
                canvas.drawLine(cx - eyeDx - eyeR, eyeY, cx - eyeDx + eyeR, eyeY, face)
                canvas.drawLine(cx + eyeDx - eyeR, eyeY, cx + eyeDx + eyeR, eyeY, face)
                face.style = Paint.Style.FILL
            }
            else -> {
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR, face)
                canvas.drawCircle(cx + eyeDx, eyeY, eyeR, face)
                // Highlights
                face.color = Color.WHITE
                canvas.drawCircle(cx - eyeDx - eyeR * 0.25f, eyeY - eyeR * 0.25f, eyeR * 0.28f, face)
                canvas.drawCircle(cx + eyeDx - eyeR * 0.25f, eyeY - eyeR * 0.25f, eyeR * 0.28f, face)
                face.color = Color.rgb(30, 50, 70)
            }
        }

        // Mouth
        face.style = Paint.Style.STROKE
        face.strokeWidth = r * 0.07f
        face.strokeCap = Paint.Cap.ROUND
        val mouth = Path()
        val my = cy + r * 0.35f
        when (emotion) {
            GuideEmotion.HAPPY, GuideEmotion.EXCITED, GuideEmotion.CELEBRATING, GuideEmotion.ENCOURAGING -> {
                mouth.moveTo(cx - r * 0.28f, my - r * 0.05f)
                mouth.quadTo(cx, my + r * 0.28f, cx + r * 0.28f, my - r * 0.05f)
            }
            GuideEmotion.THINKING, GuideEmotion.CURIOUS, GuideEmotion.LISTENING -> {
                mouth.moveTo(cx - r * 0.18f, my)
                mouth.quadTo(cx, my + r * 0.08f, cx + r * 0.18f, my)
            }
            GuideEmotion.SURPRISED -> {
                face.style = Paint.Style.FILL
                canvas.drawCircle(cx, my, r * 0.12f, face)
                face.style = Paint.Style.STROKE
                return
            }
            GuideEmotion.HELPING -> {
                mouth.moveTo(cx - r * 0.22f, my)
                mouth.quadTo(cx, my + r * 0.18f, cx + r * 0.22f, my)
            }
            else -> {
                mouth.moveTo(cx - r * 0.2f, my)
                mouth.lineTo(cx + r * 0.2f, my)
            }
        }
        canvas.drawPath(mouth, face)
        face.style = Paint.Style.FILL
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
