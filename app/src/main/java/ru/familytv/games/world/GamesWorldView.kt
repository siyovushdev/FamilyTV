package ru.familytv.games.world

import android.content.Context
import android.graphics.*
import android.view.View
import ru.familytv.games.GameColor
import ru.familytv.ui.color
import kotlin.math.sin

internal class GamesWorldView(context: Context, private val state: GamesState, private val art: Bitmap?) : GamesDrawing(
    context, if (state.world == GameWorld.MATH) 1000f else 1380f, if (state.world == GameWorld.MATH) 520f else 550f,
) {
    var combineFraction = if (state.combined) 1f else 0f
    var counted = 0
    var countAlpha = 1f
    var reaction = LumiReactionFrame()
    var paintFraction = 1f
    var previousPaint: GameColor? = state.painted
    var launchFraction = if (state.launched) 1f else 0f
    var selectedPaint = state.paintChoice
    init {
        isFocusable = state.world == GameWorld.MATH && !state.combined
        isClickable = isFocusable
        contentDescription = if (state.world == GameWorld.MATH) "Яблоки: ${state.apples.left} и ${state.apples.right}. OK: соединить в одну корзину" else "Ракета. Покрась правое крыло в синий цвет"
        onFocusChangeListener = View.OnFocusChangeListener { _, _ -> invalidate() }
    }
    override fun drawScene(c: Canvas) {
        val math = state.world == GameWorld.MATH
        val w = if (math) 1000f else 1380f; val h = if (math) 520f else 550f
        c.save(); c.clipPath(Path().apply { addRoundRect(RectF(3f, 3f, w - 3, h - 3), 24f, 24f, Path.Direction.CW) })
        bitmap(c, art, RectF(0f, 0f, w, h))
        if (math) orchard(c) else workshop(c)
        lumiReaction(c, math)
        c.restore()
        val outline = when {
            state.feedback == GameFeedback.CORRECT -> GamesInk.amber
            isFocused -> GamesInk.focus
            else -> GamesInk.outline
        }
        round(c, 3f, 3f, w - 6, h - 6, 24f, Color.TRANSPARENT, outline, if (isFocused || state.feedback == GameFeedback.CORRECT) 4f else 2f)
    }
    private fun orchard(c: Canvas) {
        val n = state.apples.left + state.apples.right
        val t = combineFraction
        val leftX = 345f + 105f * t; val rightX = 555f - 105f * t
        val width = 162f + 188f * t
        if (t < .96f) { basket(c, leftX, 292f, width, false); basket(c, rightX, 292f, width, false) }
        else basket(c, 450f, 292f, 350f, false)
        for (i in 0 until n) {
            val left = i < state.apples.left
            val size = if (left) state.apples.left else state.apples.right
            val j = if (left) i else i - state.apples.left
            val initialX = (if (left) 345f else 555f) + if (size == 3 && j == 2) 0f else (j - (minOf(size, 2) - 1) / 2f) * 65
            val initialY = if (size == 3 && j == 2) 198f else 257f
            val finalX = 450f - (n - 1) * 31f + i * 62
            val x = initialX + (finalX - initialX) * t
            val y = initialY + (256f - initialY) * t - sin(t * Math.PI).toFloat() * 55
            if (counted == i + 1) glow(c, x, y, 47f, ((countAlpha * 176).toInt() shl 24) or 0xfbbf24)
            apple(c, x, y, 29f, state.feedback == GameFeedback.CORRECT)
            if (counted > i) {
                circle(c, x, y - 65, 19f, ((countAlpha * 255).toInt() shl 24) or (GamesInk.amber and 0xffffff))
                text(c, "${i + 1}", x, y - 58, 22f, ((countAlpha * 255).toInt() shl 24) or 0x493520, true, true)
            }
        }
        if (t < .96f) { basket(c, leftX, 292f, width, true); basket(c, rightX, 292f, width, true) }
        else basket(c, 450f, 292f, 350f, true)
        if (!state.combined && !state.combining) {
            pill(c, "→     ←", 453f, 371f, 37f, GamesInk.focus, color("#c0080d1e"))
            pill(c, "OK   Соединить яблоки", 500f, 461f, 23f, if (isFocused) color("#041d1c") else GamesInk.text, if (isFocused) GamesInk.focus else color("#d0080d1e"), 400f)
        } else if (state.feedback == GameFeedback.CORRECT) {
            pill(c, "Все вместе!", 450f, 387f, 25f, GamesInk.amber, color("#d0080d1e"))
        }
    }
    private fun workshop(c: Canvas) {
        val blue = GamesInk.paint(GameColor.BLUE)
        if (!state.launched) {
            glow(c, 931f, 232f, if (state.feedback == GameFeedback.HELP) 126f else 89f, color("#4238d9d2"))
            circle(c, 1014f, 116f, 29f, color("#d4080d1e")); circle(c, 1014f, 116f, 20f, blue)
            line(c, 990f, 142f, 937f, 205f, GamesInk.focus, 3f)
        }
        if (launchFraction < 1f) {
            val from = GamesInk.paint(previousPaint); val to = GamesInk.paint(state.painted)
            val mix = android.animation.ArgbEvaluator().evaluate(paintFraction, from, to) as Int
            rocket(c, 800f, 384f - launchFraction * 800f, 1f, mix, launchFraction > 0)
        }
        for (i in state.paints.indices) jar(c, 150f + i * 110, 470f, 79f, GamesInk.paint(state.paints[i]), state.paints[i] == selectedPaint && !state.launched)
        if (paintFraction < 1f) {
            val x = (150f + state.paints.indexOf(state.painted) * 110) * (1 - paintFraction) + 925f * paintFraction
            val y = 363f * (1 - paintFraction) + 246f * paintFraction - sin(paintFraction * Math.PI).toFloat() * 100f
            glow(c, x, y, 32f, color("#80ffffff")); circle(c, x, y, 13f, GamesInk.paint(state.painted))
            line(c, x + 8, y - 8, x + 37, y - 38, color("#e2b582"), 9f)
        }
        if (state.launched && launchFraction == 1f) {
            pill(c, "Счастливого пути!", 801f, 90f, 29f, GamesInk.amber, color("#d0080d1e"), 380f)
        }
    }
    private fun lumiReaction(c: Canvas, math: Boolean) {
        val strength = reaction.strength
        if (strength <= 0f) return
        // Lumi is baked into the approved background. Native halos frame that same character;
        // the header companion supplies the small tilt/bounce without editing the illustration.
        val x = if (math) 705f else 1155f
        val y = if (math) 209f else 288f
        val tint = if (reaction.warm) GamesInk.amber else GamesInk.focus
        fun alpha(amount: Int) = ((amount * strength).toInt() shl 24) or (tint and 0xffffff)
        glow(c, x, y + 14f, 137f, alpha(if (reaction.warm) 88 else 48))
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = if (reaction.warm) 5f else 3f
        paint.color = alpha(210)
        val expand = 8f * strength
        c.drawOval(x - 88 - expand, y - 94 - expand, x + 88 + expand, y + 112 + expand, paint)
        paint.style = Paint.Style.FILL
        if (reaction.warm) {
            // Short warm rays around the character, never reward stars or confetti.
            for (i in 0..4) {
                val angle = (-150 + i * 30) * Math.PI / 180
                val dx = kotlin.math.cos(angle).toFloat(); val dy = kotlin.math.sin(angle).toFloat()
                line(c, x + dx * 115, y + dy * 125, x + dx * (126 + 13 * strength), y + dy * (136 + 13 * strength), alpha(225), 4f)
            }
        } else if (reaction.kind == LumiReaction.HELP && math && counted > 0) {
            val targetX = 450f - (state.apples.left + state.apples.right - 1) * 31f + (counted - 1) * 62
            for (i in 1..3) {
                val t = i / 4f
                circle(c, (x - 70) * (1 - t) + targetX * t, (y + 45) * (1 - t) + 200f * t, 4f, alpha(180))
            }
        }
    }
}
