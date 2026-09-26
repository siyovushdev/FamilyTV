package ru.familytv.games.world

import android.content.Context
import android.graphics.*
import android.view.View
import ru.familytv.games.GameColor
import ru.familytv.ui.ReferenceDrawing
import ru.familytv.ui.color

internal object GamesInk {
    val background = color("#080d1e")
    val panel = color("#111b38")
    val focus = color("#38d9d2")
    val text = color("#f1f5f9")
    val muted = color("#94a3b8")
    val amber = color("#fbbf24")
    val outline = color("#223260")
    fun paint(value: GameColor?) = when (value) {
        GameColor.RED -> color("#ef6262")
        GameColor.BLUE -> color("#398de9")
        GameColor.YELLOW -> color("#ffd35a")
        GameColor.GREEN -> color("#34d399")
        null -> color("#c5bda3")
    }
}

/** Approved Games V2 geometry and artwork; shared by production and the design harness. */
internal abstract class GamesDrawing(context: Context, w: Float, h: Float) : ReferenceDrawing(context, w, h) {
    protected fun circle(c: Canvas, x: Float, y: Float, r: Float, tint: Int) {
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = tint
        c.drawCircle(x, y, r, paint)
    }
    protected fun line(c: Canvas, x: Float, y: Float, xx: Float, yy: Float, tint: Int, width: Float = 3f) {
        paint.shader = null; paint.color = tint; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(x, y, xx, yy, paint)
    }
    protected fun shape(c: Canvas, path: Path, tint: Int, edge: Int? = null, width: Float = 3f) {
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = tint; c.drawPath(path, paint)
        if (edge != null) { paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.color = edge; c.drawPath(path, paint) }
        paint.style = Paint.Style.FILL
    }
    protected fun bitmap(c: Canvas, image: Bitmap?, rect: RectF, radius: Float = 24f) {
        if (image == null) return
        val scale = maxOf(rect.width() / image.width, rect.height() / image.height)
        val w = image.width * scale; val h = image.height * scale
        c.save(); c.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        paint.shader = null; paint.color = Color.WHITE; paint.isFilterBitmap = true
        c.drawBitmap(image, null, RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2), paint)
        c.restore()
    }
    protected fun apple(c: Canvas, x: Float, y: Float, r: Float, happy: Boolean = false) {
        c.save(); c.translate(x, y); c.scale(r / 30, r / 30)
        val p = Path().apply {
            moveTo(0f, -23f); cubicTo(-38f, -43f, -40f, 13f, -18f, 27f)
            cubicTo(-8f, 34f, -4f, 28f, 0f, 28f); cubicTo(7f, 28f, 10f, 34f, 21f, 24f)
            cubicTo(41f, 5f, 34f, -42f, 0f, -23f); close()
        }
        shape(c, p, color("#f36b55"), color("#823b36"), 2.5f)
        paint.shader = LinearGradient(-20f, -25f, 24f, 29f, color("#ff9d73"), color("#cb3746"), Shader.TileMode.CLAMP)
        c.drawPath(p, paint); paint.shader = null
        line(c, 0f, -22f, 4f, -39f, color("#765038"), 5f)
        shape(c, Path().apply { moveTo(3f, -30f); cubicTo(12f, -49f, 28f, -44f, 25f, -34f); quadraticBezierTo(14f, -23f, 3f, -30f) }, color("#83c874"), color("#477654"), 1.5f)
        line(c, -19f, -13f, -23f, -2f, color("#ffd2ab"), 5f)
        if (happy) {
            circle(c, -9f, 7f, 2f, color("#592f38")); circle(c, 10f, 7f, 2f, color("#592f38"))
            val smile = Path().apply { moveTo(-4f, 14f); quadTo(1f, 19f, 6f, 14f) }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = color("#592f38"); c.drawPath(smile, paint); paint.style = Paint.Style.FILL
        }
        c.restore()
    }
    protected fun basket(c: Canvas, x: Float, y: Float, w: Float, front: Boolean) {
        val edge = color("#63452e")
        if (!front) {
            paint.color = color("#76523a"); c.drawOval(x - w / 2, y - 15, x + w / 2, y + 22, paint)
            return
        }
        val p = Path().apply { moveTo(x - w / 2, y); lineTo(x + w / 2, y); lineTo(x + w / 2 - 18, y + 70); quadTo(x, y + 88, x - w / 2 + 18, y + 70); close() }
        shape(c, p, color("#b68047"), edge, 3f)
        c.save(); c.clipPath(p)
        for (i in -6..6) line(c, x + i * 28, y, x + i * 25, y + 86, color("#dca35e"), 7f)
        for (i in 1..3) line(c, x - w / 2, y + i * 20, x + w / 2, y + i * 20, color("#855533"), 4f)
        c.restore()
        round(c, x - w / 2 - 5, y - 5, w + 10, 15f, 7f, color("#e4b775"), edge, 2f)
    }
    protected fun jar(c: Canvas, x: Float, bottom: Float, size: Float, tint: Int, selected: Boolean = false) {
        c.save(); c.translate(x, bottom); c.scale(size / 100, size / 100)
        if (selected) glow(c, 0f, -42f, 82f, color("#7038d9d2"))
        line(c, 20f, -90f, 30f, -134f, color("#e6bc80"), 9f)
        round(c, 19f, -143f, 18f, 28f, 6f, tint, color("#342e45"), 2f)
        gradient(c, -40f, -83f, 80f, 81f, 12f, tint, color("#635f74"))
        round(c, -40f, -83f, 80f, 81f, 12f, Color.TRANSPARENT, color("#302d46"), 3f)
        round(c, -44f, -88f, 88f, 17f, 8f, color("#ece6d6"), color("#60515c"), 2f)
        round(c, -38f, -83f, 76f, 10f, 5f, tint)
        round(c, -18f, -78f, 12f, 25f, 6f, tint)
        circle(c, -14f, -40f, 3f, color("#32384e")); circle(c, 14f, -40f, 3f, color("#32384e"))
        line(c, -5f, -28f, 5f, -28f, color("#32384e"), 3f)
        line(c, -29f, -61f, -29f, -23f, color("#70ffffff"), 5f)
        c.restore()
    }
    protected fun rocket(c: Canvas, x: Float, base: Float, scale: Float, wing: Int, launch: Boolean = false) {
        c.save(); c.translate(x, base); c.scale(scale, scale)
        if (launch) {
            shape(c, Path().apply { moveTo(-34f, -12f); quadTo(-35f, 50f, 0f, 103f); quadTo(36f, 48f, 34f, -12f); close() }, color("#ffd35a"))
            shape(c, Path().apply { moveTo(-16f, -8f); quadTo(-18f, 40f, 0f, 66f); quadTo(18f, 30f, 16f, -8f); close() }, color("#fff2c2"))
        }
        val edge = color("#664943")
        shape(c, Path().apply { moveTo(-58f, -178f); quadTo(-119f, -131f, -116f, -18f); lineTo(-51f, -52f); close() }, color("#567cb8"), edge, 4f)
        val right = Path().apply { moveTo(58f, -178f); quadTo(122f, -133f, 117f, -18f); lineTo(50f, -51f); close() }
        shape(c, right, wing, GamesInk.focus, 4f)
        line(c, 80f, -124f, 104f, -48f, color("#55ffffff"), 5f)
        val body = Path().apply { moveTo(0f, -322f); cubicTo(71f, -275f, 79f, -132f, 58f, -25f); quadTo(0f, 2f, -58f, -25f); cubicTo(-79f, -132f, -71f, -275f, 0f, -322f); close() }
        shape(c, body, color("#dfb884"), edge, 4f)
        c.save(); c.clipPath(body)
        for (i in -2..2) line(c, i * 26f, -305f, i * 26f, -15f, color("#aa865f"), 2f)
        shape(c, Path().apply { moveTo(0f, -322f); lineTo(70f, -248f); quadTo(0f, -219f, -70f, -248f); close() }, color("#dc795d"), edge, 3f)
        c.restore()
        circle(c, 0f, -181f, 43f, edge); circle(c, 0f, -181f, 38f, color("#e5ba69")); circle(c, 0f, -181f, 29f, color("#497f94"))
        line(c, -15f, -190f, 8f, -204f, color("#a8d8dc"), 7f)
        round(c, -54f, -46f, 108f, 25f, 9f, color("#6089ad"), edge, 3f)
        for (a in listOf(-1, 1)) { circle(c, a * 42f, -67f, 4f, edge); circle(c, a * 25f, -264f, 3f, color("#ffd49d")) }
        c.restore()
    }
    protected fun lumi(c: Canvas, x: Float, y: Float) {
        glow(c, x, y, 50f, color("#4038d9d2"))
        round(c, x - 31, y - 24, 62f, 48f, 20f, color("#9befe1"), color("#399db3"), 3f)
        round(c, x - 24, y - 17, 48f, 32f, 13f, color("#124157"))
        circle(c, x - 10, y - 5, 4f, GamesInk.focus); circle(c, x + 10, y - 5, 4f, GamesInk.focus)
        line(c, x - 5, y + 6, x + 5, y + 6, GamesInk.focus, 3f)
        line(c, x, y - 25, x, y - 36, color("#83cdbb")); circle(c, x, y - 38, 4f, GamesInk.amber)
    }
    private fun Path.quadraticBezierTo(x: Float, y: Float, endX: Float, endY: Float) = quadTo(x, y, endX, endY)
}

internal class GamesChrome(context: Context, private val state: GamesState) : GamesDrawing(context, 1920f, 1080f) {
    var notice: String? = null
    var reaction = LumiReactionFrame()
    override fun drawScene(c: Canvas) {
        c.drawColor(GamesInk.background)
        glow(c, 440f, 410f, 650f, color("#151b5571")); glow(c, 1530f, 580f, 650f, color("#162d245e"))
        if (reaction.strength > 0f) glow(c, 512f, 111f, 64f, ((reaction.strength * 100).toInt() shl 24) or ((if (reaction.warm) GamesInk.amber else GamesInk.focus) and 0xffffff))
        c.save()
        c.translate(0f, reaction.bounce)
        c.rotate(reaction.tilt, 512f, 111f)
        lumi(c, 512f, 111f)
        c.restore()
        round(c, 570f, 72f, 805f, 78f, 28f, GamesInk.panel, GamesInk.outline, 1.5f)
        text(c, notice ?: state.prompt(), 972f, 120f, 25f, GamesInk.text, heavy = true, centered = true, maxWidth = 754f)
        text(c, "Пульт · OK", 1800f, 113f, 20f, GamesInk.muted, centered = true)
        val hint = when (state.world) {
            GameWorld.HOME -> "◀  ▶   Выбрать мир                       OK   Играть                       Назад   Выйти"
            GameWorld.MATH -> if (!state.combined) "OK   Соединить яблоки                       Назад   К играм"
                else "◀  ▶   Выбрать                 OK   Действие                 ↑   Подсказка                 Назад   К играм"
            GameWorld.COLOR -> if (state.feedback == GameFeedback.CORRECT) {
                if (state.launched) "OK   К играм                       Назад   К играм"
                else "OK   Запустить ракету                       Назад   К играм"
            } else "◀  ▶   Выбрать краску                 OK   Покрасить                 ↑   Подсказка                 Назад   К играм"
        }
        line(c, 88f, 970f, 1832f, 970f, GamesInk.outline, 1f)
        text(c, hint, 960f, 1006f, 20f, GamesInk.muted, centered = true)
    }
}

internal class GamesButton(context: Context, private val label: String, private val w: Float, private val h: Float = 64f, private val primary: Boolean = false) : GamesDrawing(context, w, h) {
    init { isFocusable = true; isClickable = true; contentDescription = label; onFocusChangeListener = View.OnFocusChangeListener { _, focused -> scaleX = if (focused) 1.05f else 1f; scaleY = scaleX; invalidate() } }
    override fun drawScene(c: Canvas) {
        round(c, 3f, 3f, w - 6, h - 6, 22f, if (primary) GamesInk.focus else GamesInk.panel, if (isFocused) GamesInk.focus else GamesInk.outline, if (isFocused) 4f else 1f)
        if (isFocused && primary) round(c, 8f, 8f, w - 16, h - 16, 18f, Color.TRANSPARENT, GamesInk.text, 2f)
        text(c, label, w / 2, h / 2 + 9, if (primary) 30f else 23f, if (primary) color("#041d1c") else GamesInk.text, heavy = true, centered = true, maxWidth = w - 28)
    }
}

internal class GamesHomeCard(context: Context, private val index: Int, private val art: Bitmap?) : GamesDrawing(context, 410f, 570f) {
    init { isFocusable = true; isClickable = true; contentDescription = listOf("Математика. Соединяем и считаем яблоки", "Цвета и формы. Красим ракету", "Погода и пар. Скоро", "Лабиринты звёзд. Скоро")[index]; onFocusChangeListener = View.OnFocusChangeListener { _, focused -> scaleX = if (focused) 1.05f else 1f; scaleY = scaleX; invalidate() } }
    override fun drawScene(c: Canvas) {
        val palette = listOf("#164050", "#35204f", "#103a5b", "#43301e")
        val y = if (isFocused) 20f else 40f
        gradient(c, 12f, y, 386f, 500f, 26f, color(palette[index]), GamesInk.panel)
        if (index == 0) {
            bitmap(c, art, RectF(15f, y + 4, 395f, y + 290))
            gradient(c, 15f, y + 237, 380f, 68f, 0f, Color.TRANSPARENT, GamesInk.panel)
            pill(c, "СЧЁТ 1–5", 104f, y + 22, 16f, GamesInk.text, color("#a0080d1e"))
        } else if (index == 1) {
            round(c, 107f, y + 57, 196f, 192f, 32f, color("#553658"))
            rocket(c, 205f, y + 236, .49f, GamesInk.paint(GameColor.BLUE))
            for (j in 0..2) circle(c, 175f + j * 30f, y + 281, 8f, GamesInk.paint(listOf(GameColor.RED, GameColor.BLUE, GameColor.YELLOW)[j]))
        } else if (index == 2) {
            round(c, 166f, y + 80, 32f, 145f, 16f, color("#9bdfee")); circle(c, 182f, y + 218, 31f, color("#38bdf8"))
            circle(c, 243f, y + 155, 26f, color("#acdce6")); circle(c, 263f, y + 168, 25f, color("#acdce6")); circle(c, 223f, y + 168, 21f, color("#acdce6"))
        } else {
            circle(c, 205f, y + 163, 54f, color("#edaa56"));
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 12f; paint.color = color("#f5cc8b"); c.save(); c.rotate(-25f, 205f, y + 163); c.drawOval(108f, y + 143, 302f, y + 184, paint); c.restore(); paint.style = Paint.Style.FILL
            text(c, "✦", 299f, y + 93, 42f, GamesInk.amber)
        }
        val title = listOf("Математика", "Цвета и формы", "Погода и пар", "Лабиринты звёзд")[index]
        text(c, title, 40f, y + 349, 31f, heavy = true, maxWidth = 336f)
        text(c, listOf("Счёт и яблоки", "Ракета и краски", "Остров науки", "Остров логики")[index], 40f, y + 389, 22f, GamesInk.muted)
        if (isFocused) {
            round(c, 12f, y, 386f, 500f, 26f, Color.TRANSPARENT, GamesInk.focus, 4f)
            pill(c, "ВЫБРАНО", 205f, 2f, 16f, color("#041d1c"), GamesInk.focus)
            round(c, 32f, y + 425, 346f, 54f, 16f, GamesInk.focus)
            text(c, if (index < 2) "OK   Играть" else "Скоро", 205f, y + 461, 24f, color("#041d1c"), true, true)
        } else text(c, if (index < 2) "Игровой мир" else "Скоро", 40f, y + 461, 20f, GamesInk.muted)
    }
}

internal class GamesChoice(context: Context, private val state: GamesState, val index: Int) : GamesDrawing(context, 244f, 170f) {
    private val math get() = state.world == GameWorld.MATH
    init {
        isFocusable = true; isClickable = true
        contentDescription = if (math) "${state.answers[index]} яблок" else "${state.paints[index].answer}. Покрасить крыло"
        onFocusChangeListener = View.OnFocusChangeListener { _, focused -> scaleX = if (focused) 1.05f else 1f; scaleY = scaleX; invalidate() }
    }
    override fun drawScene(c: Canvas) {
        val selectedWrong = state.feedback == GameFeedback.WRONG && if (math) state.mathChoice == state.answers[index] else state.painted == state.paints[index]
        val border = if (isFocused) GamesInk.focus else if (selectedWrong) GamesInk.amber else GamesInk.outline
        round(c, 6f, 10f, 232f, 150f, 24f, if (isFocused) color("#123c45") else GamesInk.panel, border, if (isFocused || selectedWrong) 4f else 1f)
        if (math) {
            text(c, state.answers[index].toString(), 122f, 99f, 67f, if (isFocused) GamesInk.focus else GamesInk.text, true, true)
            val n = state.answers[index]
            for (i in 0 until n) circle(c, 122f - (n - 1) * 10 + i * 20, 127f, 5f, if (isFocused) GamesInk.focus else GamesInk.muted)
        } else {
            jar(c, 63f, 137f, 70f, GamesInk.paint(state.paints[index]))
            text(c, listOf("Красный", "Синий", "Жёлтый")[index], 155f, 86f, 24f, GamesInk.text, true, true)
            if (isFocused) text(c, "OK  Покрасить", 157f, 121f, 17f, GamesInk.focus, centered = true)
        }
        if (isFocused) pill(c, "ВЫБРАНО", 122f, 0f, 14f, color("#041d1c"), GamesInk.focus)
        else if (selectedWrong) pill(c, "ПОПРОБУЕМ ЕЩЁ", 122f, 0f, 14f, color("#493520"), GamesInk.amber)
    }
}

internal class AppleEquation(context: Context, private val state: GamesState) : GamesDrawing(context, 620f, 68f) {
    init { contentDescription = "Яблоки: ${state.apples.left} + ${state.apples.right} = ${state.equationResult}" }
    override fun drawScene(c: Canvas) {
        round(c, 3f, 3f, 614f, 62f, 29f, GamesInk.panel, GamesInk.outline)
        fun group(n: Int, center: Float) { for (i in 0 until n) apple(c, center - (n - 1) * 19f + i * 38, 38f, 14f) }
        group(state.apples.left, 120f); text(c, "+", 244f, 47f, 34f, GamesInk.muted, centered = true)
        group(state.apples.right, 337f); text(c, "=", 452f, 47f, 34f, GamesInk.muted, centered = true)
        text(c, state.equationResult, 535f, 49f, 38f, GamesInk.focus, true, true)
    }
}
