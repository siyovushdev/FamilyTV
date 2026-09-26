package ru.familytv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import ru.familytv.R
import ru.familytv.adventure.AdventurePhase
import ru.familytv.adventure.AdventureViewState

/**
 * Mid-layer world art for flagship adventures — reacts when the world changes on success.
 */
class AdventureWorldStage(context: Context) : View(context) {
    enum class Theme { SPACE, FOREST, GENERIC }

    var theme: Theme = Theme.GENERIC
        set(value) { field = value; invalidate() }

    /** 0..1 progress of mission reactions (engine, path, planet, map). */
    var worldProgress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var celebrating: Boolean = false
        set(value) { field = value; invalidate() }

    /** Space launch slice: broken → powered → flying. */
    var spaceShipPowered: Boolean = false
        set(value) { field = value; invalidate() }

    var spaceShipFlying: Boolean = false
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val shipDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.space_ship)
    private val engineDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.space_engine_glow)

    fun syncFrom(adventureId: String, state: AdventureViewState, objectivesDone: Int, objectivesTotal: Int) {
        theme = when {
            adventureId.contains("space") -> Theme.SPACE
            adventureId.contains("forest") -> Theme.FOREST
            else -> Theme.GENERIC
        }
        worldProgress = if (objectivesTotal <= 0) 0f else objectivesDone.toFloat() / objectivesTotal
        celebrating = state.feedbackCorrect == true || state.phase == AdventurePhase.COMPLETED
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (theme) {
            Theme.SPACE -> drawSpace(canvas)
            Theme.FOREST -> drawForest(canvas)
            Theme.GENERIC -> drawGeneric(canvas)
        }
    }

    private fun drawSpace(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Stars
        paint.color = Color.argb(180, 200, 220, 255)
        for (i in 0 until 24) {
            val x = ((i * 97) % 100) / 100f * w
            val y = ((i * 53) % 100) / 100f * h * 0.55f
            canvas.drawCircle(x, y, if (i % 3 == 0) 2.5f else 1.5f, paint)
        }
        // Planet appears with progress
        if (worldProgress > 0.55f) {
            val pr = 28f + worldProgress * 20f
            paint.color = Color.rgb(90, 160, 200)
            canvas.drawCircle(w * 0.82f, h * 0.28f, pr, paint)
            paint.color = Color.rgb(60, 120, 150)
            canvas.drawCircle(w * 0.82f - 8f, h * 0.28f - 6f, pr * 0.35f, paint)
        }
        val flyLift = if (spaceShipFlying) h * 0.18f else if (celebrating && spaceShipPowered) h * 0.08f else 0f
        val shipY = h * 0.58f - worldProgress * h * 0.08f - flyLift
        val shipLeft = (w * 0.22f).toInt()
        val shipTop = (shipY - h * 0.28f).toInt()
        val shipRight = (w * 0.78f).toInt()
        val shipBottom = (shipY + h * 0.28f).toInt()
        shipDrawable?.setBounds(shipLeft, shipTop, shipRight, shipBottom)
        shipDrawable?.draw(canvas)
        if (spaceShipPowered || celebrating || worldProgress > 0.12f) {
            val glowAlpha = when {
                spaceShipPowered || celebrating -> 255
                else -> (100 + worldProgress * 120).toInt().coerceAtMost(220)
            }
            engineDrawable?.alpha = glowAlpha
            val eg = (h * 0.22f).toInt()
            engineDrawable?.setBounds(
                shipLeft - eg / 3,
                (shipY - eg / 2f).toInt(),
                shipLeft + eg / 2,
                (shipY + eg / 2f).toInt(),
            )
            engineDrawable?.draw(canvas)
        } else {
            // Broken engine cue — dim orange blink region
            paint.color = Color.argb(100, 255, 140, 60)
            canvas.drawCircle(w * 0.24f, shipY, 12f, paint)
        }
        // Course path
        if (worldProgress > 0.3f || spaceShipFlying) {
            paint.color = Color.argb(200, 120, 220, 180)
            paint.strokeWidth = 4f
            paint.style = Paint.Style.STROKE
            val path = Path()
            path.moveTo(w * 0.55f, shipY)
            path.quadTo(w * 0.65f, shipY - 40f - worldProgress * 30f, w * 0.78f, h * 0.32f)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawForest(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Canopy layers
        paint.color = Color.rgb(30, 70, 40)
        canvas.drawOval(RectF(-40f, -20f, w * 0.55f, h * 0.45f), paint)
        paint.color = Color.rgb(40, 90, 50)
        canvas.drawOval(RectF(w * 0.4f, -30f, w + 40f, h * 0.5f), paint)
        // Ground
        paint.color = Color.rgb(55, 100, 55)
        canvas.drawRect(0f, h * 0.7f, w, h, paint)
        // Tracks appear with progress
        if (worldProgress > 0.2f) {
            paint.color = Color.argb(200, 90, 60, 40)
            for (i in 0 until (3 + (worldProgress * 4).toInt())) {
                val x = w * 0.2f + i * 48f
                oval.set(x, h * 0.78f, x + 22f, h * 0.86f)
                canvas.drawOval(oval, paint)
            }
        }
        // Map fragment
        if (worldProgress > 0.5f) {
            paint.color = Color.rgb(230, 210, 160)
            canvas.drawRoundRect(RectF(w * 0.68f, h * 0.35f, w * 0.92f, h * 0.62f), 12f, 12f, paint)
            paint.color = Color.rgb(120, 80, 40)
            paint.strokeWidth = 3f
            paint.style = Paint.Style.STROKE
            canvas.drawLine(w * 0.72f, h * 0.48f, w * 0.88f, h * 0.52f, paint)
            paint.style = Paint.Style.FILL
        }
        // Soft creature silhouettes
        if (worldProgress > 0.35f) {
            paint.color = Color.rgb(180, 120, 70)
            canvas.drawCircle(w * 0.25f, h * 0.62f, 16f, paint)
            paint.color = Color.rgb(90, 140, 200)
            canvas.drawCircle(w * 0.45f, h * 0.55f, 10f, paint)
        }
    }

    private fun drawGeneric(canvas: Canvas) {
        paint.color = Color.argb(40, 255, 255, 255)
        canvas.drawRoundRect(RectF(16f, 16f, width - 16f, height - 16f), 24f, 24f, paint)
    }
}
