package ru.familytv.explore

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.graphics.withTranslation
import android.graphics.*
import android.view.View
import ru.familytv.ui.ReferenceDrawing
import ru.familytv.ui.color
import kotlin.math.*

internal val WEATHER_CYAN = color("#38d9d2")
private val NAVY = color("#080d1e")
private val INK = color("#204960")
private val CREAM = color("#fff6dc")
private fun tint(c: Int, a: Int) = (c and 0xffffff) or (a.coerceIn(0, 255) shl 24)

/** Geometry is in the HTML's full 1920 × 1080 coordinate space, never the cropped PNG viewport. */
internal object WeatherGeometry {
    val home = listOf(
        RectF(100f, 210f, 410f, 590f), RectF(360f, 560f, 700f, 930f),
        RectF(770f, 510f, 1180f, 930f), RectF(1210f, 560f, 1630f, 965f),
        RectF(1615f, 260f, 1830f, 820f),
    )
    val sun = RectF(465f, 430f, 830f, 740f)
    val shade = RectF(1080f, 430f, 1445f, 740f)
    val canopy = RectF(340f, 320f, 780f, 770f)
    val leaves = RectF(1290f, 495f, 1755f, 990f)
    /** The compact Ask Lumi chain card, as drawn in [WeatherWorldView.explanation]. */
    val explanation = RectF(603f, 170f, 1348f, 295f)
}

/** Transparent accessible focus target. The world itself paints the illuminated physical object. */
@SuppressLint("ViewConstructor") // Programmatic scene; never inflated from XML.
internal class WeatherWorldTarget(context: Context, label: String) : View(context) {
    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = label; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

@SuppressLint("ViewConstructor") // Programmatic scene; never inflated from XML.
internal class WeatherButton(context: Context, val label: String, private val dw: Float, private val primary: Boolean = false, private val dh: Float = 84f) : ReferenceDrawing(context, dw, dh) {
    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = label }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) { super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); invalidate() }
    override fun drawScene(c: Canvas) {
        if (isFocused) glow(c, dw / 2, dh / 2, dw / 1.5f, tint(WEATHER_CYAN, 75))
        round(c, 4f, 4f, dw - 8, dh - 8, 42f, if (primary) WEATHER_CYAN else tint(NAVY, 235), if (isFocused) Color.WHITE else if (primary) WEATHER_CYAN else color("#566878"), if (isFocused) 4f else 1.5f)
        val foreground = if (primary) NAVY else CREAM
        if (primary) {
            round(c, 22f, dh / 2 - 20, 55f, 40f, 15f, tint(NAVY, 30))
            text(c, "OK", 49f, dh / 2 + 9, 24f, foreground, true, true)
        }
        text(c, label, dw / 2 + if (primary) 22 else 0, dh / 2 + 10, if (dh < 70) 25f else 30f, foreground, true, true, dw - if (primary) 100 else 30)
    }
}

@SuppressLint("ViewConstructor") // Programmatic scene; never inflated from XML.
internal class WeatherWorldView(context: Context, private val state: WeatherExploreState, private val assets: Map<String, Bitmap>) : ReferenceDrawing(context, 1920f, 1080f) {
    var ambientMs = 0L
    private val path = Path()
    private val bounds = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun drawScene(c: Canvas) {
        val s = state.value
        val asset = s.background
        assets[asset]?.let { bitmap ->
            val scale = max(1920f / bitmap.width, 1080f / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            bounds.set((1920 - w) / 2, (1080 - h) / 2, (1920 + w) / 2, (1080 + h) / 2)
            paint.style = Paint.Style.FILL; paint.shader = null; paint.color = Color.WHITE; paint.isFilterBitmap = true
            c.drawBitmap(bitmap, null, bounds, paint)
        }
        // Approved HTML's top/bottom navy scrim, preserving a clear physical world at the center.
        paint.shader = LinearGradient(0f, 0f, 0f, 1080f, intArrayOf(tint(NAVY, 210), tint(NAVY, 0), tint(NAVY, 0), tint(NAVY, 225)), floatArrayOf(0f, .32f, .74f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, paint); paint.shader = null
        when {
            s.screen == WeatherScreen.HOME -> home(c, s)
            s.isBuild -> build(c, s)
            s.screen in listOf(WeatherScreen.INTRO, WeatherScreen.PREDICTION, WeatherScreen.PLACING_ICE) -> intro(c, s)
            else -> comparison(c, s)
        }
        lumi(c, s, asset)
        round(c, 610f, 72f, 1220f, 78f, 39f, tint(NAVY, 235), tint(WEATHER_CYAN, 125), 1.5f)
        circle(c, 655f, 111f, 14f, WEATHER_CYAN)
        text(c, s.prompt(), 710f, 122f, 32f, CREAM, true, false, 1070f)
        if (s.paused) {
            round(c, 725f, 840f, 470f, 72f, 36f, NAVY, WEATHER_CYAN, 3f)
            text(c, "Пауза · OK — продолжить", 960f, 886f, 28f, CREAM, true, true)
        }
    }

    private fun home(c: Canvas, s: WeatherSnapshot) {
        ExploreDestination.entries.forEachIndexed { i, destination ->
            val r = WeatherGeometry.home[i]
            val focused = s.focus == "destination:${destination.name}"
            if (focused) {
                glow(c, r.centerX(), r.centerY(), r.width() * .7f, tint(WEATHER_CYAN, 100))
                ellipse(c, r.left + 10, r.bottom - 105, r.right - 10, r.bottom - 30, tint(WEATHER_CYAN, 30), WEATHER_CYAN, 4f)
            }
            val y = r.bottom - 4
            val labelWidth = when (destination) { ExploreDestination.WEATHER -> 330f; ExploreDestination.HOW -> 310f; else -> 215f }
            round(c, r.centerX() - labelWidth / 2, y - 63, labelWidth, 64f, 32f, tint(NAVY, 230), if (focused) WEATHER_CYAN else tint(Color.WHITE, 75), if (focused) 3f else 1f)
            text(c, destination.label, r.centerX(), y - 23, 30f, if (focused) WEATHER_CYAN else CREAM, true, true)
            if (focused) {
                text(c, if (destination.available) "OK  Исследовать" else "Скоро", r.centerX(), y + 31, 25f, CREAM, true, true)
            } else if (!destination.available) text(c, "Скоро", r.centerX(), y + 25, 20f, color("#bdcbd4"), centered = true)
        }
    }
    private fun intro(c: Canvas, s: WeatherSnapshot) {
        val predicting = s.screen == WeatherScreen.PREDICTION
        for ((place, x) in listOf(IcePlace.SUN to 650f, IcePlace.SHADE to 1260f)) {
            val focused = predicting && s.focus == "place:$place"
            if (focused) {
                glow(c, x, 622f, 215f, tint(WEATHER_CYAN, 125))
                ellipse(c, x - 170, 593f, x + 170, 680f, tint(WEATHER_CYAN, 28), WEATHER_CYAN, 5f)
                round(c, x - 70, 722f, 140f, 51f, 25f, WEATHER_CYAN)
                text(c, "OK  Сюда", x, 756f, 25f, NAVY, true, true)
            }
            pill(c, if (place == IcePlace.SUN) "На солнце" else "В тень", x, 675f, 28f, CREAM, tint(NAVY, 225), 280f)
        }
        val f = if (s.screen == WeatherScreen.PLACING_ICE) (s.elapsedMs / WeatherRules.PLACE_MS.toFloat()).coerceIn(0f, 1f) else 0f
        val eased = f * f * (3 - 2 * f)
        val target = if (s.hypothesis == IcePlace.SHADE) 1260f else 650f
        val x = 960 + (target - 960) * eased
        val y = 661 - 100 * sin(eased * PI).toFloat() - 14 * eased
        ice(c, x, y, 220f, 1f, 0f)
        if (predicting) {
            text(c, "‹", 418f, 653f, 56f, WEATHER_CYAN, true)
            text(c, "›", 1484f, 653f, 56f, WEATHER_CYAN, true)
        }
    }
    private fun comparison(c: Canvas, s: WeatherSnapshot) {
        s.samples.forEach { sample ->
            val x = if (sample.place == IcePlace.SUN) 580f else 1355f
            ice(c, x, 787f, 255f, sample.ice, sample.water)
            pill(c, if (sample.place == IcePlace.SUN) "На солнце" else "В тени", x, 853f, 28f, CREAM, tint(NAVY, 225), 260f)
        }
        if (s.screen == WeatherScreen.ASK) explanation(c, s)
    }
    private fun explanation(c: Canvas, s: WeatherSnapshot) {
        val cause = s.cause
        val pulse = .5f + .5f * sin(ambientMs / 230.0).toFloat()
        when (cause) {
            Cause.SUN -> { glow(c, 426f, 198f, 220f, tint(color("#ffd166"), (85 + pulse * 65).toInt())); ring(c, 426f, 198f, 147f, WEATHER_CYAN, 5f) }
            Cause.HEAT -> {
                glow(c, 490f, 457f, 220f, tint(color("#ffb553"), 115))
                for (i in 0..2) heatWave(c, 415f + i * 67, 350f, 245f, pulse)
            }
            // Follow the sunny sample all the way from heat to its remaining ice and puddle.
            Cause.ICE -> { glow(c, 580f, 753f, 100f, tint(WEATHER_CYAN, 105)); ellipse(c, 532f, 711f, 628f, 797f, Color.TRANSPARENT, WEATHER_CYAN, 5f) }
            Cause.WATER -> { glow(c, 580f, 786f, 235f, tint(WEATHER_CYAN, 125)); ellipse(c, 345f, 721f, 815f, 838f, Color.TRANSPARENT, WEATHER_CYAN, 5f) }
            Cause.DONE -> Unit
        }
        // Compact illustrated chain sits in the world; the two actual results remain visible.
        c.withTranslation(y = -35f) {
            val focused = s.focus == EXPLANATION_FOCUS
            round(c, 603f, 205f, 745f, 125f, 50f, tint(NAVY, 228), if (focused) WEATHER_CYAN else tint(WEATHER_CYAN, 80), if (focused) 3f else 1f)
            val labels = listOf("Солнце", "Тепло", "Лёд", "Вода")
            for (i in 0..3) {
                val x = 704f + i * 179
                val current = cause.ordinal == i
                if (current) glow(c, x, 262f, 75f, tint(WEATHER_CYAN, 150))
                when (i) {
                    0 -> sunIcon(c, x, 253f, 23f)
                    1 -> for (n in 0..2) heatWave(c, x - 21 + n * 20, 231f, 44f, pulse)
                    2 -> ice(c, x, 277f, 43f, 1f, 0f)
                    3 -> drop(c, x, 252f, 28f)
                }
                text(c, labels[i], x, 313f, 22f, if (current) WEATHER_CYAN else CREAM, current, true)
                if (i < 3) text(c, "→", x + 88, 270f, 34f, WEATHER_CYAN, true, true)
            }
        }
    }
    private fun build(c: Canvas, s: WeatherSnapshot) {
        val choosing = s.screen == WeatherScreen.BUILD_CHOOSE
        val fraction = s.buildFraction
        val iceX = 878f; val iceY = 750f
        if (choosing || s.screen == WeatherScreen.BUILD_PLACED) {
            ellipse(c, iceX - 141, iceY - 23, iceX + 145, iceY + 30, tint(color("#122132"), 60))
            ice(c, iceX, iceY, 200f, 1f, 0f)
        } else {
            // Equal starting cubes, one covered and one exposed, make the new consequence visible.
            ice(c, iceX, iceY, 200f, 1f - .16f * fraction, .18f * fraction)
            ice(c, 1420f, 767f, 200f, 1f - .78f * fraction, .9f * fraction)
            pill(c, "Под укрытием", iceX, 812f, 25f, CREAM, tint(NAVY, 225), 280f)
            pill(c, "На солнце", 1420f, 821f, 25f, CREAM, tint(NAVY, 225), 240f)
        }
        if (choosing) {
            for (cover in Shelter.entries) {
                val x = if (cover == Shelter.CANOPY) 560f else 1515f
                val y = if (cover == Shelter.CANOPY) 650f else 815f
                val focused = s.focus == "shelter:$cover"
                if (focused) {
                    glow(c, x, y - 120, 245f, tint(WEATHER_CYAN, 145))
                    ellipse(c, x - 210, y - 15, x + 210, y + 52, tint(WEATHER_CYAN, 18), WEATHER_CYAN, 4f)
                }
                shelter(c, cover, x, y, (if (cover == Shelter.LEAVES) .80f else 1f) * if (focused) 1.045f else 1f)
                pill(c, cover.label, x, y + 55, 26f, CREAM, tint(NAVY, 230), 350f)
                if (focused) text(c, "OK  Поставить", x, y + 143, 26f, WEATHER_CYAN, true, true)
            }
        } else s.shelter?.let { cover ->
            val f = if (s.screen == WeatherScreen.BUILD_PLACED) (s.elapsedMs / WeatherRules.PLACE_MS.toFloat()).coerceIn(0f, 1f) else 1f
            val ease = f * f * (3 - 2 * f)
            val fromX = if (cover == Shelter.CANOPY) 560f else 1515f
            val fromY = if (cover == Shelter.CANOPY) 650f else 815f
            val x = fromX + (iceX - fromX) * ease
            val y = fromY + (iceY - fromY) * ease - 65 * sin(ease * PI).toFloat()
            // Open-sided canopy keeps the protected cube visible throughout the experiment.
            shelter(c, cover, x, y, if (cover == Shelter.LEAVES) .8f + .2f * ease else 1f)
        }
    }

    private fun lumi(c: Canvas, s: WeatherSnapshot, asset: String) {
        val (x, y) = when (asset) {
            "explore_world" -> 1165f to 409f
            "weather_intro" -> 1328f to 415f
            "weather_build" -> 1355f to 420f
            else -> 989f to 431f
        }
        val wave = .5f + .5f * sin(ambientMs / if (s.mood == LumiMood.DISCOVERY) 200.0 else 430.0).toFloat()
        val strength = when (s.mood) { LumiMood.IDLE -> 25; LumiMood.CURIOSITY -> 70; LumiMood.OBSERVATION -> 85; LumiMood.EXPLANATION -> 120; LumiMood.DISCOVERY -> 145 }
        glow(c, x, y, 160f + wave * 15, tint(WEATHER_CYAN, (strength * (.65f + wave * .35f)).toInt()))
        if (s.mood != LumiMood.IDLE) {
            val dy = if (s.mood == LumiMood.DISCOVERY) -12f * wave else 0f
            c.withTranslation(y = dy) {
                arc(c, x - 132, y - 154, x + 132, y + 142, 215f, 110f, tint(WEATHER_CYAN, 150), 3f)
                arc(c, x - 145, y - 163, x + 145, y + 145, 40f, 58f, tint(WEATHER_CYAN, 150), 3f)
            }
        }
        if (s.mood == LumiMood.CURIOSITY) text(c, "?", x + 156, y - 52 - 7 * wave, 51f, CREAM, true, true)
        if (s.mood == LumiMood.OBSERVATION) {
            circle(c, x - 112 - 12 * wave, y + 30, 7f, WEATHER_CYAN)
            circle(c, x - 140 - 18 * wave, y + 51, 5f, tint(WEATHER_CYAN, 180))
        }
        if (s.mood == LumiMood.EXPLANATION && s.cause != Cause.DONE) {
            val target = when (s.cause) { Cause.SUN -> 426f to 198f; Cause.HEAT -> 490f to 457f; Cause.ICE -> 580f to 753f; else -> 580f to 786f }
            // Soft pointing dots, never a reward or a technical graph.
            for (i in 1..3) {
                val f = i / 5f
                circle(c, x + (target.first - x) * f, y + (target.second - y) * f, 6f + wave * 2, tint(WEATHER_CYAN, 205 - i * 30))
            }
        }
        if (s.mood == LumiMood.DISCOVERY) {
            for (i in 0..2) {
                val a = (-65 + i * 57) * PI / 180
                ring(c, x + cos(a).toFloat() * (153 + wave * 18), y + sin(a).toFloat() * (153 + wave * 18), 7f + wave * 4, tint(CREAM, 190), 3f)
            }
        }
    }

    private fun ice(c: Canvas, x: Float, base: Float, size: Float, remaining: Float, water: Float) {
        if (water > .005f) {
            val rx = size * (.48f + water * .55f)
            ellipse(c, x - rx, base - 22 - water * 16, x + rx, base + 16 + water * 20, color("#71dbef"), color("#e1ffff"), 4f)
            ellipse(c, x - rx * .81f, base - 17, x + rx * .71f, base + 17, tint(color("#459ec8"), 90))
            arc(c, x - rx * .74f, base - 23, x + rx * .72f, base + 20, 14f, 123f, color("#d7ffff"), 3f)
        }
        val w = size * remaining; val h = w * .9f
        c.withTranslation(x, base) {
            // Rounded, faceted blue ice with the same friendly face throughout all conditions.
            gradient(c, -w / 2, -h, w, h, max(12f, w * .13f), color("#b9f5ff"), color("#59b1d4"))
            polygon(c, floatArrayOf(-w * .43f, -h * .9f, -w * .18f, -h * 1.14f, w * .43f, -h * 1.05f, w * .48f, -h * .87f, -w * .14f, -h * .72f), color("#cbf7ff"), color("#edfefe"), 3f)
            polygon(c, floatArrayOf(w * .1f, -h * .74f, w * .47f, -h * .9f, w * .47f, -h * .13f, w * .1f, -h * .03f), tint(color("#347ba9"), 105))
            round(c, -w / 2, -h, w, h, max(12f, w * .13f), Color.TRANSPARENT, color("#dafaff"), max(2f, w * .02f))
            round(c, -w * .39f, -h * .73f, w * .1f, h * .37f, w * .04f, tint(Color.WHITE, 160))
            circle(c, -w * .19f, -h * .41f, max(3f, w * .036f), INK)
            circle(c, w * .1f, -h * .41f, max(3f, w * .036f), INK)
            arc(c, -w * .14f, -h * .34f, w * .045f, -h * .19f, 0f, 170f, INK, max(2f, w * .019f))
            ellipse(c, -w * .32f, -h * .3f, -w * .22f, -h * .23f, tint(color("#f8b2b8"), 170))
            ellipse(c, w * .16f, -h * .3f, w * .26f, -h * .23f, tint(color("#f8b2b8"), 170))
        }
    }
    private fun shelter(c: Canvas, cover: Shelter, x: Float, base: Float, scale: Float) {
        c.withTranslation(x, base) {
            c.scale(scale, scale)
            if (cover == Shelter.CANOPY) {
                // Two legs and open front: the child can see the cube under the real cover.
                round(c, -174f, -305f, 24f, 318f, 10f, color("#976746"), color("#573c33"), 3f)
                round(c, 151f, -305f, 24f, 318f, 10f, color("#c99557"), color("#573c33"), 3f)
                round(c, -183f, -322f, 362f, 22f, 9f, color("#d5a76b"), color("#795146"), 3f)
                val colors = listOf("#a8be80", "#f5dfad", "#c6857b", "#ecd3a2", "#84a7a0", "#e5c18e")
                for (i in 0..5) {
                    val left = -195f + i * 65
                    polygon(c, floatArrayOf(left + 20, -304f, left + 75, -304f, left + 65, -224f, left, -224f), color(colors[i]), color("#826352"), 2f)
                    ellipse(c, left, -240f, left + 65, -208f, color(colors[i]), color("#826352"), 2f)
                }
                round(c, -178f, -317f, 355f, 14f, 7f, color("#e4b577"))
            } else {
                round(c, 117f, -306f, 18f, 318f, 8f, color("#9d8552"), color("#5b6338"), 3f)
                // Five broad leaves form one domed umbrella, with a visible branch support.
                for (i in 0..4) {
                    val edge = -220f + i * 88f
                    path.reset(); path.moveTo(0f, -332f)
                    path.quadTo(edge * .72f, -341f, edge, -236f)
                    path.quadTo(edge + 44f, -205f, edge + 88f, -236f)
                    path.quadTo((edge + 88f) * .72f, -341f, 0f, -332f); path.close()
                    fillPath(c, if (i % 2 == 0) color("#85bc77") else color("#aed591"), color("#375e48"), 3f)
                    line(c, 0f, -323f, edge + 44f, -237f, color("#d6e8a7"), 3f)
                }
            }
        }
    }
    private fun heatWave(c: Canvas, x: Float, y: Float, h: Float, phase: Float) {
        path.reset(); path.moveTo(x, y); path.cubicTo(x - 25 * phase, y + h * .3f, x + 25 * phase, y + h * .7f, x, y + h)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = if (h > 100) 9f else 5f; paint.strokeCap = Paint.Cap.ROUND; paint.color = color("#ffc66f"); paint.shader = null
        c.drawPath(path, paint); paint.style = Paint.Style.FILL
    }
    private fun sunIcon(c: Canvas, x: Float, y: Float, r: Float) {
        for (i in 0..7) { val a = i * PI / 4; line(c, x + cos(a).toFloat() * (r + 5), y + sin(a).toFloat() * (r + 5), x + cos(a).toFloat() * (r + 11), y + sin(a).toFloat() * (r + 11), color("#ffd166"), 4f) }
        circle(c, x, y, r, color("#ffd166"))
    }
    private fun drop(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset(); path.moveTo(x, y - r); path.cubicTo(x - r * 1.5f, y + r * .7f, x + r * 1.5f, y + r * 1.1f, x, y - r); path.close()
        fillPath(c, color("#74dcec"), CREAM, 2f)
    }
    private fun polygon(c: Canvas, points: FloatArray, fill: Int, stroke: Int? = null, sw: Float = 1f) {
        path.reset(); path.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1]); path.close(); fillPath(c, fill, stroke, sw)
    }
    private fun fillPath(c: Canvas, fill: Int, stroke: Int? = null, sw: Float = 1f) {
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = fill; c.drawPath(path, paint)
        if (stroke != null) { paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.strokeJoin = Paint.Join.ROUND; paint.color = stroke; c.drawPath(path, paint); paint.style = Paint.Style.FILL }
    }
    private fun ellipse(c: Canvas, l: Float, t: Float, r: Float, b: Float, fill: Int, stroke: Int? = null, sw: Float = 1f) {
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = fill; c.drawOval(l, t, r, b, paint)
        if (stroke != null) { paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = stroke; c.drawOval(l, t, r, b, paint); paint.style = Paint.Style.FILL }
    }
    private fun circle(c: Canvas, x: Float, y: Float, radius: Float, fill: Int) { paint.shader = null; paint.color = fill; paint.style = Paint.Style.FILL; c.drawCircle(x, y, radius, paint) }
    private fun ring(c: Canvas, x: Float, y: Float, radius: Float, tint: Int, sw: Float) { paint.shader = null; paint.color = tint; paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; c.drawCircle(x, y, radius, paint); paint.style = Paint.Style.FILL }
    private fun arc(c: Canvas, l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, tint: Int, sw: Float) { paint.shader = null; paint.color = tint; paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.strokeCap = Paint.Cap.ROUND; c.drawArc(l, t, r, b, start, sweep, false, paint); paint.style = Paint.Style.FILL }
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tint: Int, sw: Float) { paint.shader = null; paint.color = tint; paint.strokeWidth = sw; paint.strokeCap = Paint.Cap.ROUND; c.drawLine(x1, y1, x2, y2, paint) }
}
