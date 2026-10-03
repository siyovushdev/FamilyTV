package ru.familytv.space

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import ru.familytv.ui.ReferenceDrawing
import ru.familytv.ui.color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val CYAN = color("#5ff6ee")
private val TEAL = color("#38d9d2")
private val NAVY = color("#080d1e")
private val CREAM = color("#dde1fa")
private val MUTED = color("#bacac8")
private val AMBER = color("#fbbf24")
private val PURPLE = color("#c084fc")
private val SKY = color("#7bd0ff")
private val GREEN = color("#34d399")
private val CORAL = color("#fb7185")
private val HIGH = color("#18254b")
private val CARD = color("#1a1f31")
private val INNER = color("#223260")
private val INK = color("#003735")
private val EARTH = color("#3b82c4")
private val LAND = color("#4ade80")
private fun tint(c: Int, a: Int) = (c and 0xffffff) or (a.coerceIn(0, 255) shl 24)

@SuppressLint("ViewConstructor")
internal class SpaceWorldView(
    context: Context,
    private val state: SpaceExpeditionState,
    var art: Map<String, Bitmap> = emptyMap(),
) : ReferenceDrawing(context, 1920f, 1080f) {
    var ambientMs = 0L
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jakarta = runCatching {
        Typeface.createFromAsset(context.assets, SpaceRoutes.FONT)
    }.getOrDefault(Typeface.create("sans-serif", Typeface.BOLD))
    private val type = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun drawScene(c: Canvas) {
        val s = state.value
        val bg = backgroundOf(s)
        val travel = if (s.screen == SpaceScreen.SUCCESS && s.beat == SpaceBeat.TRAVEL) {
            ((s.successMs - 1200f) / 2000f).coerceIn(0f, 1f)
        } else 0f
        art[bg]?.let { drawBitmap(c, it, 1.02f + 0.08f * travel, -28f * travel) } ?: c.drawColor(NAVY)
        drawVeil(c, s)
        when (s.screen) {
            SpaceScreen.ENTRY -> drawEntry(c, s)
            SpaceScreen.JOURNEY -> drawJourney(c, s)
            SpaceScreen.HUB -> drawHub(c, s)
            SpaceScreen.INTRO -> drawIntro(c, s)
            SpaceScreen.CHALLENGE -> drawChallenge(c, s)
            SpaceScreen.SUCCESS -> drawSuccess(c, s)
        }
        if (s.paused) {
            c.drawRect(0f, 0f, 1920f, 1080f, Paint().apply { color = tint(NAVY, 184) })
            round(c, 560f, 400f, 800f, 280f, 36f, tint(HIGH, 235), TEAL, 4f)
            label(c, "Пауза", 960f, 520f, 44f, CREAM, true)
            label(c, "Приключение ждёт на том же месте.", 960f, 580f, 22f, MUTED)
        }
    }

    private fun backgroundOf(s: SpaceSnapshot): String = when (s.screen) {
        SpaceScreen.ENTRY -> "entry"
        SpaceScreen.JOURNEY, SpaceScreen.HUB -> "journey"
        SpaceScreen.INTRO -> "intro"
        SpaceScreen.CHALLENGE -> "challenge"
        SpaceScreen.SUCCESS -> "success"
    }

    private fun drawBitmap(c: Canvas, bitmap: Bitmap, scale: Float = 1f, dx: Float = 0f) {
        val src = Rect(0, 0, bitmap.width, bitmap.height)
        val extra = (1920f * (scale - 1f) / 2f)
        val dest = RectF(-extra + dx, -extra * 1080f / 1920f, 1920f + extra + dx, 1080f + extra * 1080f / 1920f)
        c.drawBitmap(bitmap, src, dest, paint)
    }

    private fun drawVeil(c: Canvas, s: SpaceSnapshot) {
        fill.shader = LinearGradient(
            0f, 0f, 0f, 1080f,
            tint(NAVY, if (s.screen == SpaceScreen.CHALLENGE) 200 else 160),
            tint(NAVY, if (s.screen == SpaceScreen.ENTRY) 40 else 210),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        fill.shader = LinearGradient(
            0f, 0f, 1920f, 0f,
            tint(NAVY, 150),
            tint(NAVY, 40),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, 1920f, 1080f, fill)
        fill.shader = null
        c.drawRect(0f, 0f, 1920f, 1080f, Paint().apply { color = tint(NAVY, 50) })
    }

    private fun drawEntry(c: Canvas, s: SpaceSnapshot) {
        pill(c, 88f, 80f, 220f, 64f, "←  Домой", s.focus == "home")
        guide(c, 360f, 72f, 1120f, s.guideLine, "Луми:")
        val meter = if (s.started) "Пройдено ${s.requiredDone} из ${s.requiredTotal}   ${s.currentLabel}" else "Экспедиция   ${s.currentLabel}"
        pill(c, 1508f, 80f, 324f, 64f, "★  $meter", false, AMBER)
        val pct = if (s.requiredTotal == 0) 0.18f else (s.requiredDone.toFloat() / s.requiredTotal).coerceIn(0.12f, 1f)
        round(c, 700f, 430f, 520f, 48f, 24f, tint(NAVY, 120), tint(INNER, 180), 1f)
        label(c, if (s.started) "Пройдено ${s.requiredDone} из ${s.requiredTotal}" else "Корабль ждёт команды", 820f, 462f, 16f, MUTED, false, false)
        round(c, 1088f, 448f, 112f, 8f, 4f, INNER)
        round(c, 1088f, 448f, 112f * pct, 8f, 4f, CYAN)
        round(c, 448f, 560f, 1024f, 420f, 16f, tint(CARD, 220))
        label(c, "✦  КОСМИЧЕСКАЯ ОДИССЕЯ ДЛЯ ВСЕЙ СЕМЬИ  ✦", 960f, 610f, 16f, CYAN, true)
        label(c, s.journeyTitle, 960f, 670f, 40f, CREAM, true)
        val sub = if (s.started) {
            "Корабль «Северное сияние» ждёт у точки «${s.currentLabel}»."
        } else {
            "Корабль «Северное сияние» ждёт. Впереди — сбор звёздной энергии!"
        }
        label(c, sub, 960f, 716f, 22f, MUTED, false, true, 840f)
        ghost(c, 468f, 780f, 300f, 88f, "Карта маршрута", s.focus == "journey")
        cta(c, 790f, 760f, 340f, 128f, if (s.started) "Продолжить полёт" else "Начать экспедицию", s.focus == "continue")
        ghost(c, 1152f, 780f, 300f, 88f, "Бортовой журнал", s.focus == "journal")
        round(c, 620f, 1008f, 680f, 40f, 20f, tint(NAVY, 180))
        label(c, "OK  Нажмите центральную кнопку на пульте  ·  Стрелки для выбора", 960f, 1036f, 16f, MUTED)
    }

    private fun drawJourney(c: Canvas, s: SpaceSnapshot) {
        pill(c, 88f, 72f, 250f, 64f, "←  Назад", s.focus == "back")
        guide(c, 370f, 56f, 1120f, s.guideLine, "БОРТОВОЙ СПУТНИК ЛУМИ", stacked = true)
        pill(c, 1540f, 72f, 292f, 72f, "Прогресс  ${s.requiredDone} / ${s.requiredTotal}", false, CYAN)
        drawSpacePath(c, s)
        if (s.beat == SpaceBeat.SETTLE) {
            s.waypoints.firstOrNull { it.visual == SpaceNodeVisual.CURRENT }?.let { node ->
                glow(c, node.x, node.y, 140f, tint(CYAN, 110))
            }
        }
        for (node in s.waypoints) drawDestination(c, node, s.focus == "node:${node.nodeId}", s)
        cta(c, 88f, 920f, 620f, 96f, "Продолжить: ${s.currentLabel}", s.focus == "continue", compact = true)
        ghost(c, 1480f, 930f, 352f, 80f, "Наши открытия", s.focus == "journal")
    }

    private fun drawHub(c: Canvas, s: SpaceSnapshot) {
        pill(c, 88f, 72f, 250f, 64f, "←  Назад", s.focus == "back")
        guide(c, 360f, 56f, 1200f, s.guideLine)
        label(c, s.hubTitle.ifBlank { s.currentLabel }, 960f, 220f, 36f, CREAM, true)
        for (branch in s.branches) {
            val r = SpaceGeometry.rect("branch:${branch.id}", s) ?: continue
            val focused = s.focus == "branch:${branch.id}"
            round(c, r.left, r.top, r.width(), r.height(), 28f, tint(HIGH, if (focused) 220 else 150), if (focused) CYAN else tint(SKY, 50), if (focused) 4f else 2f)
            label(c, branch.label, r.centerX(), r.centerY() + 10f, 26f, CREAM, true)
        }
    }

    private fun drawIntro(c: Canvas, s: SpaceSnapshot) {
        pill(c, 80f, 80f, 280f, 64f, "←  На карту пути", s.focus == "back")
        guide(c, 390f, 72f, 1100f, s.guideLine, "Луми:")
        pill(c, 1540f, 80f, 300f, 56f, "Северное сияние", false, SKY)
        round(c, 88f, 430f, 200f, 36f, 18f, tint(HIGH, 200))
        label(c, "★  ИСХОДНОЕ ИСПЫТАНИЕ", 188f, 456f, 14f, AMBER, true)
        label(c, "Двигатель молчит", 88f, 530f, 40f, CREAM, true, false, 520f)
        label(c, "Пустые гнёзда у двигателя. Без энергии корабль не сдвинется.", 88f, 580f, 20f, MUTED, false, false, 480f)
        round(c, 1180f, 280f, 620f, 520f, 16f, tint(CARD, 220))
        round(c, 1212f, 312f, 64f, 64f, 12f, tint(CYAN, 40))
        label(c, "🔑", 1244f, 356f, 28f, CYAN, true)
        label(c, "ЗАРЯД ПОРТАЛА", 1296f, 336f, 14f, SKY, false, false)
        label(c, "Энергия для полёта", 1296f, 372f, 28f, CREAM, true, false)
        for (i in 0 until 3) {
            val x = 1224f + i * 184f
            round(c, x, 408f, 168f, 88f, 16f, tint(NAVY, 180), tint(CYAN, 90), 2f)
            label(c, "○", x + 84f, 464f, 28f, tint(CYAN, 120), true)
        }
        round(c, 1212f, 516f, 556f, 112f, 12f, tint(HIGH, 180))
        label(c, "Кораблю нужна энергия в пустые места. Смотри на тёмный двигатель — он ждёт искру.", 1230f, 560f, 20f, CREAM, false, false, 520f)
        round(c, 1212f, 644f, 556f, 120f, 12f, tint(NAVY, 180))
        label(c, "Пустые места = задача в мире", 1230f, 690f, 20f, CYAN, true, false, 480f)
        label(c, "Не кнопка «Дальше», а живой корабль перед нами.", 1230f, 728f, 16f, MUTED, false, false, 480f)
        cta(c, 640f, 860f, 640f, 100f, "Полетели чинить корабль!", s.focus == "continue", compact = true)
        label(c, "Пульт можно положить рядом или держать стрелочки ◄ ►", 960f, 1008f, 16f, MUTED)
    }

    private fun drawChallenge(c: Canvas, s: SpaceSnapshot) {
        when (s.mechanicType) {
            "PATTERN_SEQUENCE" -> if (s.helping) drawHelp(c, s) else drawPattern(c, s)
            "PATH_MAZE" -> drawMaze(c, s)
            "VISUAL_SEARCH" -> drawSearch(c, s)
            "SORT_AND_CLASSIFY" -> drawSort(c, s)
            else -> drawStoryWorld(c, s)
        }
        if (s.mechanicType != "PATTERN_SEQUENCE") {
            drawGuide(c, if (s.wrongMs > 0) s.localHint ?: s.guideLine else s.guideLine)
        }
    }

    private fun drawSuccess(c: Canvas, s: SpaceSnapshot) {
        glow(c, 960f, 180f, 420f, tint(CYAN, 50))
        val shipX = 560f + when (s.beat) {
            SpaceBeat.IGNITE -> 48f
            SpaceBeat.TRAVEL -> 48f + ((s.successMs - 1200f) / 2000f).coerceIn(0f, 1f) * 520f
            else -> 0f
        }
        val shipY = 310f - if (s.beat == SpaceBeat.TRAVEL) 18f else 0f
        if (s.beat == SpaceBeat.INSTALL || s.beat == SpaceBeat.IGNITE) {
            glow(c, 860f, 360f, 70f, tint(CYAN, 220))
            fill.shader = RadialGradient(860f, 360f, 42f, CREAM, CYAN, Shader.TileMode.CLAMP)
            c.drawCircle(860f, 360f, 28f, fill)
            fill.shader = null
        }
        if (s.beat == SpaceBeat.IGNITE || s.beat == SpaceBeat.TRAVEL) {
            glow(c, shipX - 80f, shipY + 40f, 90f, tint(AMBER, 210))
            glow(c, shipX - 40f, shipY + 36f, 70f, tint(CYAN, 180))
        }
        fill.color = tint(CYAN, 200)
        val hull = Path()
        hull.moveTo(shipX - 90f, shipY + 30f)
        hull.lineTo(shipX + 110f, shipY)
        hull.lineTo(shipX - 70f, shipY + 70f)
        hull.close()
        c.drawPath(hull, fill)
        if (s.beat == SpaceBeat.TRAVEL) {
            fill.color = tint(CYAN, 140)
            for (i in 1..7) {
                c.drawCircle(shipX - 90f - i * 18f, shipY + 36f + sin((ambientMs + i * 40) / 120f) * 8f, 6f, fill)
            }
        }
        guide(c, 560f, 56f, 800f, s.guideLine, "Луми:")
        round(c, 370f, 620f, 1180f, 360f, 24f, tint(CARD, 220))
        glow(c, 960f, 620f, 280f, tint(CYAN, 40))
        val kicker = when (s.beat) {
            SpaceBeat.INSTALL -> "Энергия на месте"
            SpaceBeat.IGNITE -> "Двигатель заряжен"
            SpaceBeat.TRAVEL -> "Корабль в полёте"
            else -> "Двигатель заряжен • Путь открыт"
        }
        round(c, 820f, 648f, 280f, 36f, 18f, tint(CYAN, 40))
        label(c, kicker.uppercase(), 960f, 674f, 14f, CYAN, true)
        val title = if (s.currentLabel.isBlank()) "Получилось!" else "${s.currentLabel}: получилось!"
        label(c, title, 960f, 740f, 40f, CREAM, true)
        label(c, "Энергия на месте. Двигатель проснулся. Корабль летит дальше по маршруту.", 960f, 790f, 22f, MUTED, false, true, 880f)
        cta(c, 700f, 820f, 520f, 88f, "[ OK ] Вперёд по маршруту!", s.focus == "continue", compact = true)
        label(c, "Тяга: растёт   ·   Двигатель горит   ·   ${s.currentLabel}", 960f, 948f, 16f, MUTED)
    }

    private fun drawHelp(c: Canvas, s: SpaceSnapshot) {
        c.drawRect(0f, 0f, 1920f, 1080f, Paint().apply { color = tint(NAVY, 210) })
        glow(c, 640f, 80f, 280f, tint(CYAN, 30))
        glow(c, 1400f, 980f, 240f, tint(AMBER, 28))
        pill(c, 88f, 72f, 360f, 64f, "←  Вернуться к полёту", s.focus == "back")
        guide(c, 480f, 56f, 960f, s.localHint ?: s.guideLine, "ЛУМИ ПОДСКАЗЫВАЕТ · УЧИМСЯ ИГРАЯ", stacked = true)
        pill(c, 1600f, 80f, 232f, 48f, "Пульт TV", false, MUTED)
        round(c, 88f, 200f, 1180f, 600f, 16f, tint(HIGH, 210))
        label(c, "КАК РАСТЁТ РЯД", 120f, 250f, 16f, CYAN, false, false)
        val seq = s.sequenceDisplay.ifEmpty { listOf("2", "4", "?", "8") }
        var x = 220f
        seq.forEachIndexed { i, token ->
            val gap = token == "?" || token == "…"
            fill.color = if (gap) tint(CYAN, 30) else tint(CARD, 230)
            c.drawCircle(x, 360f, 44f, fill)
            if (gap) {
                stroke.color = tint(CYAN, 160)
                stroke.strokeWidth = 3f
                stroke.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
                c.drawCircle(x, 360f, 44f, stroke)
                stroke.pathEffect = null
            }
            label(c, token, x, 372f, 32f, if (gap) CYAN else CREAM, true)
            if (i < seq.lastIndex) label(c, "+2", x + 70f, 368f, 22f, CYAN, true)
            x += 160f
        }
        round(c, 128f, 440f, 1100f, 200f, 16f, tint(NAVY, 160))
        round(c, 160f, 468f, 480f, 144f, 12f, tint(CARD, 200))
        label(c, "Пара", 400f, 516f, 16f, MUTED, true)
        drawStars(c, 368f, 548f, 2, AMBER, 22f)
        label(c, "+", 680f, 548f, 28f, CYAN, true)
        round(c, 736f, 468f, 460f, 144f, 12f, tint(CARD, 200))
        label(c, "Пара", 966f, 516f, 16f, MUTED, true)
        drawStars(c, 934f, 548f, 2, AMBER, 22f)
        round(c, 128f, 668f, 1100f, 88f, 12f, tint(CYAN, 40))
        label(c, "Чётное собирается парами. Никто не остаётся один.", 160f, 724f, 20f, CREAM, false, false, 1040f)
        round(c, 1300f, 200f, 532f, 600f, 16f, tint(HIGH, 210))
        fill.color = color("#2f3447")
        c.drawCircle(1380f, 300f, 40f, fill)
        label(c, "3", 1380f, 312f, 36f, MUTED, true)
        label(c, "А ЕСЛИ БЕЗ ПАРЫ?", 1450f, 286f, 14f, CORAL, false, false)
        label(c, "Одна звезда одна", 1450f, 324f, 24f, CREAM, true, false)
        round(c, 1332f, 360f, 468f, 56f, 12f, tint(CORAL, 50))
        label(c, "Осталась одна          ★", 1566f, 398f, 16f, color("#ffb4ab"), true)
        label(c, "Если звёздочка без пары — это не тот кристалл для двигателя.", 1332f, 460f, 16f, MUTED, false, false, 468f)
        ghost(c, 88f, 860f, 420f, 80f, "Послушать Луми ещё раз", false)
        cta(c, 560f, 848f, 800f, 96f, "Понятно! Вернуться к звёздам", s.focus == "help", compact = true)
        label(c, "Нажми центральную кнопку пульта", 1540f, 900f, 16f, MUTED)
    }

    private fun drawPattern(c: Canvas, s: SpaceSnapshot) {
        pill(c, 88f, 72f, 260f, 64f, "⏸  Пауза", s.focus == "pause")
        val line = if (s.wrongMs > 0 && !s.localHint.isNullOrBlank()) s.localHint!! else PATTERN_GUIDE
        guide(c, 380f, 56f, 1020f, line, "Луми:", wrong = s.wrongMs > 0)
        round(c, 1448f, 64f, 384f, 72f, 36f, tint(CARD, 180))
        drawStars(c, 1490f, 100f, 2, CYAN, 12f)
        fill.color = tint(CYAN, 40)
        c.drawCircle(1608f, 100f, 16f, fill)
        label(c, "ЦЕЛЬ МИССИИ", 1720f, 92f, 12f, AMBER, true)
        label(c, "Осталась 1 звёздочка!", 1720f, 118f, 16f, CREAM, true)
        val orbs = s.choices.filter { it.key.startsWith("opt:") }
        val focus = orbs.indexOfFirst { it.key == s.focus }.takeIf { it >= 0 } ?: 1
        val sides = listOf("Орбита А", "Орбита 2", "Орбита В")
        for ((i, orb) in orbs.withIndex()) {
            val r = SpaceGeometry.sequenceOrb(i, orbs.size, focus)
            drawGlassCard(c, r, orb.label, i == focus, sides.getOrElse(i) { "Орбита ${i + 1}" }, s.wrongMs > 0 && i == focus)
        }
        if (s.wrongMs > 0) {
            round(c, 700f, 820f, 520f, 48f, 24f, tint(AMBER, 50))
            label(c, s.localHint ?: "Почти. Посмотри ещё раз и попробуй другую звезду.", 960f, 852f, 18f, AMBER, true, true, 480f)
        }
        round(c, 520f, 960f, 880f, 64f, 32f, tint(HIGH, 220))
        label(c, "◄ ►  Выбирай стрелками на пульте   ·   OK  Запустить в двигатель", 960f, 1002f, 20f, CREAM)
    }

    private fun drawGlassCard(c: Canvas, r: RectF, value: String, focused: Boolean, side: String, wobble: Boolean) {
        val n = value.toIntOrNull() ?: 0
        val pairs = n / 2
        val leftover = n % 2
        if (focused) {
            glow(c, r.centerX(), r.centerY(), r.width() * 0.62f, tint(CYAN, 70))
            round(c, r.left - 6f, r.top - 6f, r.width() + 12f, r.height() + 12f, 28f, tint(CYAN, 180))
            round(c, r.left, r.top, r.width(), r.height(), 22f, tint(INNER, 245))
            round(c, r.left + 24f, r.top + 20f, 168f, 36f, 18f, tint(CYAN, 50))
            label(c, "●  ВЫБРАНА", r.left + 108f, r.top + 46f, 14f, CYAN, true)
            label(c, "✓", r.right - 36f, r.top + 48f, 22f, CYAN, true)
            val cx = r.centerX()
            val cy = r.top + 210f
            fill.shader = LinearGradient(cx, cy - 120f, cx, cy + 120f, tint(CYAN, 80), tint(NAVY, 230), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, 120f, fill)
            fill.shader = null
            label(c, value, cx, cy + 40f, 108f, CYAN, true)
            round(c, r.left + 24f, r.bottom - 168f, r.width() - 48f, 88f, 16f, tint(CARD, 230))
            if (leftover == 0) {
                label(c, "Все звёздочки парами! (Чётное)", cx, r.bottom - 132f, 14f, CYAN, true)
                drawStars(c, cx - pairs * 18f, r.bottom - 100f, pairs.coerceAtMost(4) * 2, CYAN, 9f, grouped = true)
            } else {
                label(c, "Проверка на пары:", cx, r.bottom - 132f, 14f, MUTED, true)
                drawPairRow(c, cx, r.bottom - 100f, pairs, leftover, true)
            }
            round(c, r.left + 24f, r.bottom - 68f, r.width() - 48f, 48f, 12f, CYAN)
            label(c, "OK   Запустить в двигатель", cx, r.bottom - 36f, 18f, INK, true)
        } else {
            round(c, r.left, r.top, r.width(), r.height(), 24f, tint(color("#161b2c"), 160))
            if (side.isNotBlank()) {
                round(c, r.left + 20f, r.top + 20f, 140f, 32f, 16f, tint(color("#2f3447"), 200))
                label(c, side, r.left + 90f, r.top + 44f, 14f, MUTED, true)
            }
            val cx = r.centerX()
            val cy = r.top + 200f
            fill.shader = LinearGradient(cx, cy - 100f, cx, cy + 100f, tint(color("#34384b"), 100), tint(NAVY, 210), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, 104f, fill)
            fill.shader = null
            label(c, value, cx, cy + 34f, 88f, tint(CREAM, 210), true)
            round(c, r.left + 24f, r.bottom - 120f, r.width() - 48f, 88f, 16f, tint(CARD, 180))
            label(c, "Проверка на пары:", cx, r.bottom - 86f, 14f, MUTED, true)
            drawPairRow(c, cx, r.bottom - 54f, pairs, leftover, false)
        }
        if (wobble) {
            stroke.color = tint(AMBER, 180)
            stroke.strokeWidth = 3f
            c.drawRoundRect(r.left, r.top, r.right, r.bottom, 22f, 22f, stroke)
        }
    }

    private fun drawPairRow(c: Canvas, cx: Float, y: Float, pairs: Int, leftover: Int, focused: Boolean) {
        val shown = pairs.coerceAtMost(4)
        val totalW = shown * 52f + if (leftover > 0) 48f else 0f
        var x = cx - totalW / 2f
        repeat(shown) {
            round(c, x, y - 14f, 44f, 28f, 8f, if (focused) tint(CYAN, 40) else tint(color("#2f3447"), 160))
            drawStars(c, x + 10f, y, 2, if (focused) CYAN else SKY, 8f)
            x += 52f
        }
        if (leftover > 0) {
            if (shown > 0) {
                label(c, "+", x - 4f, y + 6f, 16f, tint(MUTED, 160), true)
                x += 10f
            }
            round(c, x, y - 14f, 36f, 28f, 8f, tint(CORAL, 50))
            drawStars(c, x + 10f, y, 1, CORAL, 8f)
        }
    }

    private fun drawStars(c: Canvas, x: Float, y: Float, count: Int, tint: Int, r: Float, grouped: Boolean = false) {
        fill.color = tint
        val gap = if (grouped) r * 2.6f else r * 2.4f
        for (i in 0 until count) {
            val px = x + i * gap + if (grouped && i % 2 == 1) 2f else 0f
            star(c, px, y, r)
        }
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float) {
        val p = Path()
        for (i in 0..9) {
            val a = (-PI / 2f) + i * PI / 5f
            val rad = if (i % 2 == 0) r else r * 0.42f
            val px = x + cos(a).toFloat() * rad
            val py = y + sin(a).toFloat() * rad
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        c.drawPath(p, fill)
    }

    private fun drawSpacePath(c: Canvas, s: SpaceSnapshot) {
        val route = s.waypoints.filter { !it.optional }
        if (route.size < 2) return
        val path = Path()
        path.moveTo(route.first().x, route.first().y)
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            path.quadTo((a.x + b.x) / 2f, min(a.y, b.y) - 40f, b.x, b.y)
        }
        stroke.pathEffect = null
        stroke.strokeWidth = 6f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = tint(CYAN, 90)
        c.drawPath(path, stroke)
    }

    private fun drawDestination(c: Canvas, node: SpaceWaypoint, focused: Boolean, s: SpaceSnapshot) {
        val current = node.visual == SpaceNodeVisual.CURRENT
        val orb = if (current) 64f else 48f
        if (current) glow(c, node.x, node.y, 90f, tint(CYAN, 90))
        fill.color = tint(HIGH, if (current) 240 else 210)
        c.drawCircle(node.x, node.y, orb, fill)
        fill.color = when (node.visual) {
            SpaceNodeVisual.COMPLETED -> tint(GREEN, 60)
            SpaceNodeVisual.CURRENT -> CYAN
            SpaceNodeVisual.AVAILABLE -> tint(SKY, 50)
            SpaceNodeVisual.LOCKED -> tint(NAVY, 200)
            SpaceNodeVisual.MISSING -> tint(NAVY, 160)
        }
        c.drawCircle(node.x, node.y, orb * 0.62f, fill)
        val mark = when (node.nodeId) {
            "launch" -> "🌍"
            "moon-hub" -> "☾"
            "light-lab" -> "💡"
            "temp-lab" -> "🌡"
            "stretch" -> "🏃"
            "final-mission" -> "✦"
            "calm-end" -> "⌂"
            else -> "📖"
        }
        label(c, mark, node.x, node.y + 12f, if (current) 32f else 22f, if (current) INK else CREAM, true)
        if (node.visual == SpaceNodeVisual.COMPLETED) {
            fill.color = AMBER
            c.drawCircle(node.x + orb * 0.7f, node.y + orb * 0.55f, 14f, fill)
            label(c, "✓", node.x + orb * 0.7f, node.y + orb * 0.55f + 8f, 14f, color("#2a1700"), true)
        }
        if (current) {
            round(c, node.x - 70f, node.y - orb - 56f, 140f, 32f, 16f, CYAN)
            label(c, "СЕВЕРНОЕ СИЯНИЕ", node.x, node.y - orb - 34f, 11f, INK, true)
            round(c, node.x - 56f, node.y + orb - 8f, 112f, 24f, 12f, CYAN)
            label(c, "МЫ ЗДЕСЬ", node.x, node.y + orb + 10f, 12f, INK, true)
            round(c, node.x - 90f, node.y + orb + 28f, 180f, 64f, 12f, tint(INNER, 240))
            label(c, "АКТИВНО", node.x, node.y + orb + 50f, 12f, CYAN, true)
            label(c, node.label, node.x, node.y + orb + 78f, 22f, CREAM, true)
        } else {
            val chip = when (node.visual) {
                SpaceNodeVisual.COMPLETED -> if (s.worldReacted && node.label == s.lastCompletedLabel) "Только что" else "Пройдено"
                SpaceNodeVisual.AVAILABLE -> "Открыто"
                SpaceNodeVisual.LOCKED -> "Впереди"
                else -> "Скоро"
            }
            val chipTint = when (node.visual) {
                SpaceNodeVisual.COMPLETED -> GREEN
                SpaceNodeVisual.AVAILABLE -> SKY
                else -> AMBER
            }
            label(c, chip.uppercase(), node.x, node.y + orb + 28f, 12f, chipTint, true)
            label(c, node.label, node.x, node.y + orb + 52f, 20f, CREAM, true)
        }
        if (focused && !current) {
            stroke.color = CYAN
            stroke.strokeWidth = 4f
            stroke.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            c.drawCircle(node.x, node.y, orb + 10f, stroke)
            stroke.pathEffect = null
        }
    }

    private fun drawMaze(c: Canvas, s: SpaceSnapshot) {
        if (s.helping) c.drawRect(0f, 180f, 1920f, 1080f, Paint().apply { color = tint(NAVY, 120) })
        val cells = s.choices.filter { it.key.startsWith("cell:") }
        for (cell in cells) {
            val r = SpaceGeometry.rect(cell.key, s) ?: continue
            val cx = r.centerX()
            val cy = r.centerY()
            when {
                cell.blocked -> {
                    fill.color = tint(HIGH, 200)
                    c.drawCircle(cx, cy, 36f, fill)
                }
                cell.even == false -> {
                    fill.color = tint(PURPLE, if (s.helping) 40 else 80)
                    c.drawCircle(cx, cy, 28f, fill)
                    label(c, cell.label, cx, cy + 10f, 18f, tint(CREAM, 120))
                }
                else -> {
                    glow(c, cx, cy, 48f, tint(CYAN, if (cell.onPath || cell.cursor) 140 else 60))
                    fill.color = if (cell.onPath || cell.cursor) CYAN else tint(SKY, 160)
                    c.drawCircle(cx, cy, if (s.focus == cell.key) 44f else 36f, fill)
                    label(c, cell.label, cx, cy + 12f, 24f, INK, true)
                }
            }
            if (cell.goal) label(c, "✦", cx, r.top + 18f, 18f, AMBER)
            if (cell.cursor) drawShip(c, cx, cy - 52f, true, s.beat == SpaceBeat.REJECT, 0.32f)
        }
    }

    private fun drawSearch(c: Canvas, s: SpaceSnapshot) {
        if (s.helping) c.drawRect(0f, 180f, 1920f, 1080f, Paint().apply { color = tint(NAVY, 120) })
        if (s.screen != SpaceScreen.SUCCESS) drawShip(c, 560f, 480f, s.engineOn, false, 0.9f)
        label(c, "Гнёзда энергии", 560f, 360f, 18f, MUTED)
        val selected = s.choices.filter { it.key.startsWith("obj:") && it.selected }
        for (i in 0 until 3) {
            val bay = SpaceGeometry.searchBay(i)
            val filled = selected.getOrNull(i)
            round(c, bay.left, bay.top, bay.width(), bay.height(), 18f, tint(NAVY, 180), if (filled != null) CYAN else tint(CORAL, 140), 3f)
            if (filled != null) {
                fill.shader = RadialGradient(bay.centerX(), bay.centerY(), 36f, CREAM, CYAN, Shader.TileMode.CLAMP)
                c.drawCircle(bay.centerX(), bay.centerY(), 28f, fill)
                fill.shader = null
            }
        }
        for ((i, obj) in s.choices.filter { it.key.startsWith("obj:") }.withIndex()) {
            if (obj.selected) continue
            val r = SpaceGeometry.searchObject(i, s.choices.size)
            val energy = obj.required || obj.label.contains("Энерг", true)
            if (s.helping && !energy) continue
            val focused = s.focus == obj.key
            if (focused) glow(c, r.centerX(), r.centerY(), 70f, tint(CYAN, 120))
            fill.color = if (energy) CYAN else AMBER
            c.drawCircle(r.centerX(), r.centerY(), if (focused) 48f else 40f, fill)
            label(c, obj.label.take(1), r.centerX(), r.centerY() + 12f, 22f, INK, true)
        }
    }

    private fun drawSort(c: Canvas, s: SpaceSnapshot) {
        val cats = s.categories
        val items = s.choices.filter { it.key.startsWith("item:") }
        val current = items.firstOrNull { it.key.removePrefix("item:") == s.sortHold }
            ?: items.firstOrNull { it.placedIn == null }
        val home = SpaceGeometry.sortCurrent()
        val dest = s.installedKey?.let { SpaceGeometry.rect(it, s) }
        val t = (s.beatMs / 480f).coerceIn(0f, 1f)
        val fly = when {
            dest == null -> home
            s.beat == SpaceBeat.INSTALL -> lerpRect(home, shrink(dest, 0.42f), easeOut(t))
            s.beat == SpaceBeat.REJECT -> {
                val u = if (t < 0.42f) t / 0.42f else 1f - (t - 0.42f) / 0.58f
                lerpRect(home, shrink(dest, 0.38f), easeOut(u.coerceIn(0f, 1f)) * 0.7f)
            }
            else -> home
        }
        for ((i, cat) in cats.withIndex()) {
            val r = SpaceGeometry.sortZone(i, cats.size)
            val focused = s.focus == cat.key
            val hinted = s.helping && s.hintTarget == cat.key
            val rejected = s.beat == SpaceBeat.REJECT && s.installedKey == cat.key
            val accepted = s.installedKey == cat.key && (s.beat == SpaceBeat.INSTALL || s.worldReacted)
            drawWorld(c, cat, r, focused, hinted, rejected, accepted, s)
            val hosted = items.filter { it.placedIn == cat.key.removePrefix("cat:") && it.key != s.flyingKey }
            hosted.forEachIndexed { hi, item ->
                val nest = nestRect(r, hi, hosted.size)
                drawFind(c, item, nest, focused = false, large = false, s)
            }
        }
        val moving = items.firstOrNull { it.key == s.flyingKey }
        if (s.beat == SpaceBeat.INSTALL && moving != null) {
            drawFind(c, moving, fly, focused = false, large = true, s)
        } else if (s.beat == SpaceBeat.REJECT && current != null) {
            drawFind(c, current, fly, focused = true, large = true, s)
        } else if (s.beat != SpaceBeat.INSTALL && current != null) {
            if (s.helping && s.hintTarget != null) {
                val hintZone = SpaceGeometry.rect(s.hintTarget, s)
                if (hintZone != null) drawHintWave(c, current, home, hintZone, s)
            }
            drawFind(c, current, home, focused = cats.any { s.focus == it.key }, large = true, s)
        }
        label(c, "◄ ►  Выбери мир    ·    OK  Положить находку", 960f, 1048f, 16f, MUTED)
    }

    private fun drawWorld(
        c: Canvas,
        cat: SpaceChoice,
        r: RectF,
        focused: Boolean,
        hinted: Boolean,
        rejected: Boolean,
        accepted: Boolean,
        s: SpaceSnapshot,
    ) {
        val family = worldFamily(cat.key.removePrefix("cat:"))
        if (focused || hinted) glow(c, r.centerX(), r.centerY(), r.width() * 0.52f, tint(if (hinted) CYAN else CYAN, if (hinted) 140 else 90))
        if (accepted && s.beat == SpaceBeat.INSTALL) glow(c, r.centerX(), r.centerY() - 20f, 160f, tint(CYAN, 80))
        val plate = art["world_${cat.key.removePrefix("cat:")}"]
        round(c, r.left, r.top, r.width(), r.height(), 36f, tint(NAVY, 180), if (focused) CYAN else if (rejected) tint(AMBER, 140) else tint(CREAM, 28), if (focused) 5f else 2f)
        val inner = RectF(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom - 52f)
        if (plate != null) {
            drawFitted(c, plate, inner, 28f)
        } else {
            drawWorldFallback(c, inner, family)
        }
        if (family == "water") drawWaves(c, inner, hinted || accepted)
        if (hinted) {
            stroke.color = tint(CYAN, 200)
            stroke.strokeWidth = 4f
            stroke.pathEffect = DashPathEffect(floatArrayOf(16f, 10f), (ambientMs / 18f) % 26f)
            c.drawRoundRect(inner, 28f, 28f, stroke)
            stroke.pathEffect = null
        }
        label(c, cat.label, r.centerX(), r.bottom - 18f, 18f, if (focused) CYAN else MUTED, true)
    }

    private fun drawFind(
        c: Canvas,
        item: SpaceChoice,
        r: RectF,
        focused: Boolean,
        large: Boolean,
        s: SpaceSnapshot,
    ) {
        val id = item.key.removePrefix("item:")
        val plate = art["item_$id"]
        val pad = if (large) 8f else 4f
        val bubble = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - if (large) 36f else 8f)
        if (focused && large) glow(c, bubble.centerX(), bubble.centerY(), bubble.width() * 0.62f, tint(CYAN, 110))
        if (plate != null) {
            drawFitted(c, plate, bubble, if (large) 28f else 18f)
        } else {
            drawObjectFallback(c, bubble, id)
        }
        if (large) label(c, item.label, r.centerX(), r.bottom - 8f, 16f, MUTED, true)
    }

    private fun drawHintWave(c: Canvas, item: SpaceChoice, from: RectF, zone: RectF, s: SpaceSnapshot) {
        val midX = (from.centerX() + zone.centerX()) / 2f
        val midY = from.bottom + 12f
        stroke.color = tint(CYAN, 140)
        stroke.strokeWidth = 3f
        stroke.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), (ambientMs / 16f) % 20f)
        val path = Path()
        path.moveTo(from.centerX(), from.bottom - 8f)
        path.quadTo(midX, midY + 20f, zone.centerX(), zone.top + 24f)
        c.drawPath(path, stroke)
        stroke.pathEffect = null
        fill.color = tint(CYAN, 160)
        c.drawCircle(zone.centerX(), zone.top + 28f, 10f + 3f * sin(ambientMs / 180f), fill)
    }

    private fun drawWorldFallback(c: Canvas, r: RectF, family: String) {
        val top = when (family) {
            "water" -> color("#1d4e89")
            "land" -> color("#2f6b3a")
            "air" -> color("#4b7ea8")
            else -> INNER
        }
        val bot = when (family) {
            "water" -> color("#0b3a5c")
            "land" -> color("#6b4a28")
            "air" -> color("#d7e7f7")
            else -> NAVY
        }
        fill.shader = LinearGradient(r.centerX(), r.top, r.centerX(), r.bottom, top, bot, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 28f, 28f, fill)
        fill.shader = null
        when (family) {
            "water" -> {
                fill.color = tint(CYAN, 90)
                c.drawOval(r.left + 30f, r.centerY(), r.right - 30f, r.bottom - 16f, fill)
            }
            "land" -> {
                fill.color = tint(LAND, 200)
                c.drawOval(r.left + 40f, r.centerY() - 10f, r.right - 40f, r.bottom - 8f, fill)
                fill.color = color("#3f2a14")
                c.drawRect(r.centerX() - 10f, r.centerY() - 20f, r.centerX() + 10f, r.centerY() + 50f, fill)
                fill.color = tint(GREEN, 220)
                c.drawCircle(r.centerX(), r.centerY() - 28f, 36f, fill)
            }
            "air" -> {
                fill.color = tint(CREAM, 210)
                c.drawOval(r.left + 50f, r.top + 36f, r.left + 170f, r.top + 100f, fill)
                c.drawOval(r.centerX() - 40f, r.top + 70f, r.centerX() + 130f, r.top + 140f, fill)
            }
        }
    }

    private fun drawObjectFallback(c: Canvas, r: RectF, id: String) {
        val cx = r.centerX()
        val cy = r.centerY()
        val family = worldFamily(id)
        when {
            id.contains("whale") -> {
                fill.color = color("#3d7ca8")
                c.drawOval(cx - 70f, cy - 28f, cx + 54f, cy + 32f, fill)
                fill.color = color("#d7e7f3")
                c.drawOval(cx - 40f, cy - 4f, cx + 28f, cy + 28f, fill)
                fill.color = color("#3d7ca8")
                val tail = Path()
                tail.moveTo(cx + 50f, cy)
                tail.lineTo(cx + 88f, cy - 28f)
                tail.lineTo(cx + 88f, cy + 28f)
                tail.close()
                c.drawPath(tail, fill)
                fill.color = CREAM
                c.drawCircle(cx - 36f, cy - 8f, 6f, fill)
            }
            id.contains("tree") || id.contains("fir") -> {
                fill.color = color("#6b4a28")
                c.drawRect(cx - 10f, cy + 8f, cx + 10f, cy + 48f, fill)
                fill.color = tint(GREEN, 230)
                c.drawCircle(cx, cy - 8f, 42f, fill)
            }
            id.contains("cloud") -> {
                fill.color = CREAM
                c.drawCircle(cx - 28f, cy + 4f, 28f, fill)
                c.drawCircle(cx + 24f, cy + 6f, 24f, fill)
                c.drawCircle(cx, cy - 16f, 32f, fill)
            }
            id.contains("fish") -> {
                fill.color = color("#2fb1c7")
                c.drawOval(cx - 54f, cy - 22f, cx + 40f, cy + 22f, fill)
                val tail = Path()
                tail.moveTo(cx + 36f, cy)
                tail.lineTo(cx + 70f, cy - 22f)
                tail.lineTo(cx + 70f, cy + 22f)
                tail.close()
                c.drawPath(tail, fill)
                fill.color = CREAM
                c.drawCircle(cx - 28f, cy - 4f, 5f, fill)
            }
            else -> {
                fill.shader = RadialGradient(cx, cy, r.width() / 2f, if (family == "land") GREEN else CYAN, INNER, Shader.TileMode.CLAMP)
                c.drawOval(r, fill)
                fill.shader = null
            }
        }
    }

    private fun drawWaves(c: Canvas, r: RectF, lively: Boolean) {
        stroke.color = tint(CYAN, if (lively) 180 else 90)
        stroke.strokeWidth = 3f
        val path = Path()
        val y = r.bottom - 36f
        path.moveTo(r.left + 16f, y)
        var x = r.left + 16f
        var i = 0
        while (x < r.right - 16f) {
            val amp = (if (lively) 10f else 6f) + 3f * sin((ambientMs / 160f) + i)
            path.quadTo(x + 18f, y - amp, x + 36f, y)
            x += 36f
            i++
        }
        c.drawPath(path, stroke)
    }

    private fun drawFitted(c: Canvas, bmp: Bitmap, dest: RectF, radius: Float) {
        c.save()
        val clip = Path()
        clip.addRoundRect(dest, radius, radius, Path.Direction.CW)
        c.clipPath(clip)
        val scale = max(dest.width() / bmp.width, dest.height() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val fitted = RectF(dest.centerX() - w / 2f, dest.centerY() - h / 2f, dest.centerX() + w / 2f, dest.centerY() + h / 2f)
        c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), fitted, paint)
        c.restore()
    }

    private fun worldFamily(id: String): String {
        val key = id.lowercase()
        return when {
            key.contains("water") || key.contains("ocean") || key.contains("sea") || key.contains("whale") || key.contains("fish") -> "water"
            key.contains("land") || key.contains("earth") || key.contains("island") || key.contains("plant") || key.contains("tree") || key.contains("fir") -> "land"
            key.contains("air") || key.contains("sky") || key.contains("wind") || key.contains("cloud") -> "air"
            else -> "generic"
        }
    }

    private fun nestRect(zone: RectF, index: Int, count: Int): RectF {
        val w = 92f
        val gap = 12f
        val total = count * w + (count - 1) * gap
        val left = zone.centerX() - total / 2f + index * (w + gap)
        val top = zone.centerY() - 10f
        return RectF(left, top, left + w, top + 92f)
    }

    private fun lerpRect(a: RectF, b: RectF, t: Float): RectF {
        val u = t.coerceIn(0f, 1f)
        return RectF(
            a.left + (b.left - a.left) * u,
            a.top + (b.top - a.top) * u,
            a.right + (b.right - a.right) * u,
            a.bottom + (b.bottom - a.bottom) * u,
        )
    }

    private fun shrink(r: RectF, scale: Float): RectF {
        val cx = r.centerX()
        val cy = r.centerY()
        val w = r.width() * scale / 2f
        val h = r.height() * scale / 2f
        return RectF(cx - w, cy - h, cx + w, cy + h)
    }

    private fun easeOut(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return 1f - (1f - u) * (1f - u)
    }

    private fun drawStoryWorld(c: Canvas, s: SpaceSnapshot) {
        drawShip(c, 960f, 520f, s.engineOn || s.shipEnergy > 0, s.screen == SpaceScreen.SUCCESS, 0.9f)
        label(c, s.currentLabel, 960f, 780f, 24f, SKY)
    }

    private fun drawGuide(c: Canvas, line: String) {
        if (line.isBlank()) return
        round(c, 300f, 36f, 1280f, 84f, 42f, tint(NAVY, 150), tint(TEAL, 50), 2f)
        label(c, line, 960f, 90f, 22f, CREAM, false, true, 1180f)
    }

    private fun drawShip(c: Canvas, x: Float, y: Float, engine: Boolean, flying: Boolean, scale: Float) {
        val w = 240f * scale
        val h = 78f * scale
        fill.color = color("#dbe7f3")
        c.drawRoundRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, 28f * scale, 28f * scale, fill)
        fill.color = color("#94a3b8")
        c.drawRoundRect(x - w * 0.18f, y + h * 0.12f, x + w * 0.08f, y + h * 0.42f, 8f * scale, 8f * scale, fill)
        fill.shader = RadialGradient(x + w * 0.22f, y - h * 0.1f, 22f * scale, tint(CREAM, 220), SKY, Shader.TileMode.CLAMP)
        c.drawOval(x + w * 0.12f, y - h * 0.32f, x + w * 0.46f, y + h * 0.12f, fill)
        fill.shader = null
        if (engine) {
            glow(c, x - w / 2, y, 42f * scale, tint(AMBER, 210))
            fill.color = AMBER
            c.drawCircle(x - w / 2 - 10f * scale, y, 16f * scale, fill)
        } else {
            fill.color = tint(CORAL, 160)
            c.drawCircle(x - w / 2, y, 12f * scale, fill)
        }
        if (flying) {
            fill.color = tint(CYAN, 140)
            for (i in 1..5) {
                c.drawCircle(x - w / 2 - i * 16f * scale, y + sin((ambientMs + i * 70) / 130f) * 7f, 5f * scale, fill)
            }
        }
    }

    private fun pill(c: Canvas, x: Float, y: Float, w: Float, h: Float, value: String, focused: Boolean, accent: Int = SKY) {
        round(c, x, y, w, h, h / 2f, tint(HIGH, if (focused) 230 else 180), if (focused) CYAN else tint(accent, 40), if (focused) 3f else 0f)
        label(c, value, x + w / 2f, y + h * 0.68f, 22f, CREAM, true)
    }

    private fun ghost(c: Canvas, x: Float, y: Float, w: Float, h: Float, value: String, focused: Boolean) {
        if (focused) round(c, x, y, w, h, 12f, tint(HIGH, 230), CYAN, 3f)
        else round(c, x, y, w, h, 12f, tint(HIGH, 180))
        label(c, value, x + w / 2f, y + h * 0.64f, 22f, CREAM, true, true, w - 24f)
    }

    private fun cta(c: Canvas, x: Float, y: Float, w: Float, h: Float, value: String, focused: Boolean, compact: Boolean = false) {
        glow(c, x + w / 2f, y + h / 2f, w * 0.45f, tint(CYAN, if (focused) 90 else 50))
        round(c, x, y, w, h, h / 2f, CYAN)
        round(c, x + w - 96f, y - 16f, 72f, 28f, 14f, INK)
        label(c, "[ OK ]", x + w - 60f, y + 6f, 12f, CYAN, true)
        label(c, value, x + w / 2f, y + h * 0.62f, if (compact) 26f else 30f, INK, true, true, w - 48f)
    }

    private fun guide(c: Canvas, x: Float, y: Float, w: Float, line: String, name: String = "Луми:", stacked: Boolean = false, wrong: Boolean = false) {
        round(c, x, y, w, if (stacked) 88f else 72f, 36f, tint(INNER, 220), if (wrong) AMBER else tint(CYAN, 40), if (wrong) 3f else 2f)
        fill.shader = LinearGradient(x + 16f, y + 12f, x + 64f, y + 60f, PURPLE, SKY, Shader.TileMode.CLAMP)
        c.drawCircle(x + 40f, y + if (stacked) 44f else 36f, 24f, fill)
        fill.shader = null
        val quoted = if (line.startsWith("«")) line else "«$line»"
        if (stacked) {
            label(c, name, x + 80f, y + 32f, 14f, CYAN, true, false)
            label(c, quoted, x + 80f, y + 64f, 20f, CREAM, false, false, w - 110f)
        } else {
            label(c, "$name $quoted", x + 80f, y + 48f, 22f, CREAM, false, false, w - 110f)
        }
    }

    private fun label(
        c: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        tint: Int,
        heavy: Boolean = false,
        centered: Boolean = true,
        maxWidth: Float = 2000f,
    ) {
        type.color = tint
        type.textSize = size
        type.typeface = Typeface.create(jakarta, if (heavy) Typeface.BOLD else Typeface.NORMAL)
        type.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        val fitting = TextUtils.ellipsize(value, type, maxWidth, TextUtils.TruncateAt.END).toString()
        c.drawText(fitting, x, baseline, type)
    }

    companion object {
        const val PATTERN_GUIDE = "Выбирай чётные звёздочки, чтобы зарядить корабль!"
    }
}
