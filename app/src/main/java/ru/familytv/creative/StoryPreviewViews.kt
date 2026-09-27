package ru.familytv.creative

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import ru.familytv.ui.ReferenceDrawing
import ru.familytv.ui.color
import kotlin.math.*

private val CYAN = color("#38d9d2")
private val NAVY = color("#080d1e")
private val CREAM = color("#fff6dc")
private val AMBER = color("#fbbf24")
private val GOLD = color("#ffd36b")
private val PURPLE = color("#c084fc")
private fun tint(c: Int, a: Int) = (c and 0xffffff) or (a.coerceIn(0, 255) shl 24)
private fun ease(f: Float) = f.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

/** Scene anchors in the HTML's full 1920 × 1080 space, measured on the approved full-width art. */
internal object StoryGeometry {
    val place = mapOf(HomePlace.THEATER to RectF(96f, 200f, 372f, 770f), HomePlace.BOOK to RectF(640f, 420f, 1250f, 775f), HomePlace.DRAWING to RectF(1510f, 470f, 1725f, 700f))
    val hero = mapOf(StoryHero.TIM to RectF(470f, 262f, 822f, 812f), StoryHero.ROBI to RectF(822f, 372f, 1140f, 812f), StoryHero.PUSHISTIK to RectF(1140f, 380f, 1452f, 812f))
    val heroCenter = mapOf(StoryHero.TIM to 640f, StoryHero.ROBI to 980f, StoryHero.PUSHISTIK to 1295f)
    val portal = mapOf(StoryWorld.SPACE to PointF(330f, 560f), StoryWorld.ISLAND to PointF(1590f, 560f))
    const val PORTAL_R = 118f
    val forest = RectF(760f, 360f, 1210f, 905f)
    val orb = mapOf(StoryObject.KEY to PointF(608f, 628f), StoryObject.MAP to PointF(1190f, 600f), StoryObject.LANTERN to PointF(1610f, 620f))
    /** The key orb must cover the key already painted in Robi's hand until the family picks it. */
    const val ORB_R = 92f
    val door = RectF(355f, 225f, 650f, 750f)
    val keyhole = PointF(520f, 560f)
    val keyInHand = PointF(606f, 600f)
    val trail = floatArrayOf(780f, 960f, 1050f, 940f, 1225f, 900f, 1370f, 815f, 1375f, 725f, 1310f, 660f, 1250f, 600f, 1245f, 560f, 1300f, 520f, 1360f, 500f)
    val path = RectF(1000f, 470f, 1430f, 970f)
    val doorway = PointF(1070f, 450f)
    /** Lantern-lit library wall of consequence.jpg (bitmap pixels), right of Robi so he is never doubled in the doorway. */
    val libraryCrop = Rect(792, 175, 886, 345)
    val consequenceKey = PointF(855f, 375f)
    val endingKey = PointF(909f, 305f)
    val endingCard = RectF(330f, 790f, 1590f, 1004f)

    fun target(key: String): RectF? {
        val name = key.substringAfter(':')
        fun circle(p: PointF, r: Float) = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
        return when {
            key.startsWith("place:") -> place[HomePlace.valueOf(name)]
            key.startsWith("hero:") -> hero[StoryHero.valueOf(name)]
            key == "world:FOREST" -> forest
            key.startsWith("world:") -> circle(portal.getValue(StoryWorld.valueOf(name)), PORTAL_R)
            key.startsWith("object:") -> circle(orb.getValue(StoryObject.valueOf(name)), ORB_R)
            key == "action:DOOR" -> door
            key == "action:PATH" -> path
            key == "scene" -> RectF(200f, 180f, 1720f, 880f)
            else -> null
        }
    }
}

/** Transparent accessible focus target. The world paints the illuminated physical thing itself. */
@SuppressLint("ViewConstructor") // Programmatic scene; never inflated from XML.
internal class StoryTarget(context: Context, label: String) : View(context) {
    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = label; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

internal enum class Glyph { NONE, BACK, PLAY, HEART, HEART_FILLED, SPARKLE, ARROW }

@SuppressLint("ViewConstructor") // Programmatic scene; never inflated from XML.
internal class StoryButton(context: Context, val label: String, private val dw: Float, private val dh: Float, private val primary: Boolean, private val glyph: Glyph) : ReferenceDrawing(context, dw, dh) {
    private val path = Path()
    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = label }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) { super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); invalidate() }
    override fun drawScene(c: Canvas) {
        val lit = isFocused || primary
        if (isFocused) glow(c, dw / 2, dh / 2, dw / 1.6f, tint(CYAN, 90))
        round(c, 5f, 5f, dw - 10, dh - 10, (dh - 10) / 2, if (isFocused) CYAN else tint(NAVY, 225), if (isFocused) Color.WHITE else tint(if (lit) CYAN else Color.WHITE, 110), if (isFocused) 4f else 1.5f)
        val ink = if (isFocused) NAVY else CREAM
        val hasGlyph = glyph != Glyph.NONE
        val textX = dw / 2 + if (hasGlyph) 22 else 0
        if (hasGlyph) {
            paint.textSize = if (dh < 76) 26f else 30f
            val w = paint.measureText(label) * 1.05f
            drawGlyph(c, textX - w / 2 - 34, dh / 2, ink)
        }
        text(c, label, textX, dh / 2 + 10, if (dh < 76) 26f else 30f, ink, true, true, dw - if (hasGlyph) 110 else 40)
    }
    private fun drawGlyph(c: Canvas, x: Float, y: Float, ink: Int) {
        paint.shader = null; paint.style = Paint.Style.FILL
        path.reset()
        when (glyph) {
            Glyph.BACK -> { paint.color = if (isFocused) NAVY else CYAN; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.strokeCap = Paint.Cap.ROUND; path.moveTo(x + 6, y - 11); path.lineTo(x - 6, y); path.lineTo(x + 6, y + 11); c.drawPath(path, paint) }
            Glyph.PLAY -> { paint.color = ink; path.moveTo(x - 9, y - 13); path.lineTo(x + 13, y); path.lineTo(x - 9, y + 13); path.close(); c.drawPath(path, paint) }
            Glyph.HEART, Glyph.HEART_FILLED -> {
                paint.color = if (glyph == Glyph.HEART_FILLED) color("#fb7185") else if (isFocused) NAVY else color("#fb7185")
                path.moveTo(x, y + 13); path.cubicTo(x - 22, y - 1, x - 12, y - 20, x, y - 8); path.cubicTo(x + 12, y - 20, x + 22, y - 1, x, y + 13); path.close()
                if (glyph == Glyph.HEART) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f }
                c.drawPath(path, paint)
            }
            Glyph.SPARKLE -> { paint.color = if (isFocused) NAVY else AMBER; star(x, y, 15f, 5f); c.drawPath(path, paint) }
            Glyph.ARROW -> { paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.strokeCap = Paint.Cap.ROUND; c.drawLine(x - 12, y, x + 12, y, paint); path.moveTo(x + 2, y - 10); path.lineTo(x + 12, y); path.lineTo(x + 2, y + 10); c.drawPath(path, paint) }
            Glyph.NONE -> Unit
        }
        paint.style = Paint.Style.FILL
    }
    private fun star(x: Float, y: Float, outer: Float, inner: Float) {
        for (i in 0 until 8) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / 4
            val px = x + cos(a).toFloat() * r; val py = y + sin(a).toFloat() * r
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }
}

@SuppressLint("ViewConstructor") // Programmatic scene; never inflated from XML.
internal class StoryWorldView(context: Context, private val state: StoryPreviewState, private val domain: StoryPreviewDomain, private val art: Map<String, Bitmap>) : ReferenceDrawing(context, 1920f, 1080f) {
    var ambientMs = 0L
    private val path = Path()
    private val bounds = RectF()
    private val src = Rect()
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val matrix = Matrix()
    private val textMeasure = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private val wave get() = .5f + .5f * sin(ambientMs / 420.0).toFloat()

    override fun drawScene(c: Canvas) {
        val s = state.value
        when (s.screen) {
            StoryScreen.CREATE_HOME -> { background(c, "create_home"); scrim(c); home(c, s) }
            StoryScreen.HERO -> { background(c, "choose_hero"); scrim(c); heroes(c, s.heroFocus.takeIf { s.focus.startsWith("hero:") }, true) }
            StoryScreen.WORLD -> { background(c, "choose_world"); scrim(c); worlds(c, s) }
            StoryScreen.OBJECT -> { background(c, "forest_door"); scrim(c); objects(c, s) }
            StoryScreen.STORY_CHOICE -> { background(c, "forest_door"); scrim(c); choice(c, s) }
            StoryScreen.CONSEQUENCE -> consequence(c, s)
            StoryScreen.ENDING -> { background(c, "ending"); scrim(c, bottom = 235); ending(c) }
            StoryScreen.REPLAY -> replay(c, s)
        }
        lumi(c, s)
        if (s.paused) {
            round(c, 725f, 700f, 470f, 72f, 36f, tint(NAVY, 235), CYAN, 3f)
            text(c, "Пауза · OK — продолжить", 960f, 746f, 28f, CREAM, true, true)
        }
    }

    // ---- art ----
    private fun background(c: Canvas, name: String, alpha: Int = 255) {
        val bitmap = art[name] ?: return
        val scale = max(1920f / bitmap.width, 1080f / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        bounds.set((1920 - w) / 2, (1080 - h) / 2, (1920 + w) / 2, (1080 + h) / 2)
        bitmapPaint.alpha = alpha
        c.drawBitmap(bitmap, null, bounds, bitmapPaint)
        bitmapPaint.alpha = 255
    }
    /** Maps a 1920-space rect to source pixels of the art currently in [bounds]. */
    private fun source(bitmap: Bitmap, r: RectF) {
        val sx = bitmap.width / bounds.width(); val sy = bitmap.height / bounds.height()
        src.set(((r.left - bounds.left) * sx).roundToInt(), ((r.top - bounds.top) * sy).roundToInt(), ((r.right - bounds.left) * sx).roundToInt(), ((r.bottom - bounds.top) * sy).roundToInt())
    }
    private fun scrim(c: Canvas, bottom: Int = 170) {
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, 0f, 0f, 1080f, intArrayOf(tint(NAVY, 190), tint(NAVY, 0), tint(NAVY, 0), tint(NAVY, bottom)), floatArrayOf(0f, .24f, .76f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, paint); paint.shader = null
    }
    private fun dim(c: Canvas, a: Int) { paint.shader = null; paint.color = tint(NAVY, a); c.drawRect(0f, 0f, 1920f, 1080f, paint) }
    /** Redraws one painted figure slightly larger and undimmed, feathered so no seam shows. */
    private fun lift(c: Canvas, name: String, r: RectF, amount: Float) {
        val bitmap = art[name] ?: return
        val pad = 40f
        val layer = c.saveLayer(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, null)
        c.withScale(amount, amount, r.centerX(), r.bottom) {
            clipRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
            drawBitmap(bitmap, null, bounds, bitmapPaint)
        }
        val gradient = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, .72f, 1f), Shader.TileMode.CLAMP)
        matrix.reset(); matrix.setScale(r.width() / 2 + pad, r.height() / 2 + pad); matrix.postTranslate(r.centerX(), r.centerY())
        gradient.setLocalMatrix(matrix); maskPaint.shader = gradient
        c.drawRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, maskPaint)
        c.restoreToCount(layer)
    }

    // ---- screens ----
    private fun home(c: Canvas, s: StorySnapshot) {
        HomePlace.entries.forEach { place ->
            val r = StoryGeometry.place.getValue(place)
            val focused = s.focus == "place:${place.name}"
            if (place == HomePlace.BOOK) {
                // The glowing book itself is the entrance: warm light, cyan outline around the pages.
                glow(c, r.centerX(), r.centerY() - 10, 420f, tint(CYAN, if (focused) 70 + (40 * wave).toInt() else 30))
                if (focused) ellipse(c, r.left + 10, r.top + 40, r.right - 10, r.bottom - 20, Color.TRANSPARENT, tint(CYAN, 220), 5f)
                sparkles(c, r.centerX(), r.top + 40, 260f, 7, CREAM)
            } else if (focused) {
                glow(c, r.centerX(), r.centerY(), r.width() * .8f, tint(CYAN, 70))
                round(c, r.left, r.top, r.width(), r.height(), 32f, Color.TRANSPARENT, tint(CYAN, 200), 4f)
            }
            val y = if (place == HomePlace.BOOK) r.bottom + 24 else r.bottom - 70
            chip(c, place.label, r.centerX(), y, focused, place.available)
        }
    }

    private fun heroes(c: Canvas, focused: StoryHero?, chips: Boolean) {
        dim(c, 95)
        StoryHero.entries.forEach { hero ->
            val r = StoryGeometry.hero.getValue(hero)
            if (hero == focused) {
                glow(c, r.centerX(), r.centerY(), r.width() * .85f, tint(CYAN, 55))
                lift(c, "choose_hero", r, 1.035f + .01f * wave)
                ellipse(c, r.centerX() - r.width() * .42f, r.bottom - 26, r.centerX() + r.width() * .42f, r.bottom + 22, tint(CYAN, 40), CYAN, 5f)
            }
            if (chips) chip(c, hero.label, StoryGeometry.heroCenter.getValue(hero), r.bottom + 40, hero == focused, state.isAvailable("hero:${hero.name}"))
        }
    }

    private fun worlds(c: Canvas, s: StorySnapshot) {
        val focus = s.worldFocus.takeIf { s.focus.startsWith("world:") }
        val f = StoryGeometry.forest
        when (focus) {
            StoryWorld.FOREST -> {
                glow(c, f.centerX(), f.centerY(), 520f, tint(CYAN, 40 + (25 * wave).toInt()))
                ellipse(c, f.centerX() - 230, f.bottom - 50, f.centerX() + 230, f.bottom + 10, tint(CYAN, 40), CYAN, 5f)
            }
            StoryWorld.SPACE -> { dim(c, 120); spaceWash(c) }
            StoryWorld.ISLAND -> { dim(c, 110); islandWash(c) }
            null -> Unit
        }
        chip(c, StoryWorld.FOREST.label, f.centerX(), 300f, focus == StoryWorld.FOREST, true)
        for (world in listOf(StoryWorld.SPACE, StoryWorld.ISLAND)) {
            val p = StoryGeometry.portal.getValue(world)
            val on = focus == world
            val r = StoryGeometry.PORTAL_R * if (on) 1.07f else 1f
            if (on) glow(c, p.x, p.y, r * 1.9f, tint(CYAN, 110))
            path.reset(); path.addCircle(p.x, p.y, r, Path.Direction.CW)
            c.withClip(path) { if (world == StoryWorld.SPACE) spaceWindow(c, p, r) else islandWindow(c, p, r) }
            ring(c, p.x, p.y, r, if (on) CYAN else tint(CREAM, 120), if (on) 6f else 2.5f)
            if (!on) { paint.shader = null; paint.color = tint(NAVY, 70); c.drawCircle(p.x, p.y, r, paint) }
            chip(c, world.label, p.x, p.y + r + 26, on, state.isAvailable("world:${world.name}"))
        }
    }

    private fun objects(c: Canvas, s: StorySnapshot) {
        val reveal = if (s.revealing) (s.elapsedMs / StoryRules.KEY_REVEAL_MS.toFloat()).coerceIn(0f, 1f) else 0f
        if (s.revealing) {
            // The world answers the choice: the key lands with Robi and the oak door wakes up.
            val door = ease((reveal - .25f) / .6f)
            glow(c, StoryGeometry.door.centerX(), StoryGeometry.door.centerY(), 360f, tint(AMBER, (door * (95 + 40 * wave)).toInt()))
            doorOutline(c, tint(GOLD, (door * 200).toInt()), 4f)
            glow(c, StoryGeometry.keyhole.x, StoryGeometry.keyhole.y, 70f, tint(GOLD, (door * 190).toInt()))
            val k = ease(reveal / .45f)
            glow(c, StoryGeometry.keyInHand.x, StoryGeometry.keyInHand.y, 90f + 40 * k, tint(GOLD, (70 + 110 * (1 - abs(k - .5f) * 2)).toInt()))
            sparkles(c, StoryGeometry.keyInHand.x, StoryGeometry.keyInHand.y, 70f + 60 * k, 6, GOLD)
        }
        StoryObject.entries.forEach { item ->
            val p = StoryGeometry.orb.getValue(item)
            val focused = s.focus == "object:${item.name}" && !s.revealing
            val picked = s.item == item
            val fade = when { picked -> 1 - ease(reveal / .35f); s.revealing -> 1 - ease(reveal / .25f); else -> 1f }
            if (fade <= .01f) return@forEach
            val r = StoryGeometry.ORB_R * (if (focused) 1.06f else 1f) * (if (picked) .55f + .45f * fade else 1f)
            if (focused) glow(c, p.x, p.y, r * 2f, tint(CYAN, 120))
            paint.shader = RadialGradient(p.x - r * .3f, p.y - r * .35f, r * 1.4f, tint(color("#1e2a55"), (245 * fade).toInt()), tint(NAVY, (240 * fade).toInt()), Shader.TileMode.CLAMP)
            c.drawCircle(p.x, p.y, r, paint); paint.shader = null
            ring(c, p.x, p.y, r, if (focused) CYAN else tint(CREAM, (110 * fade).toInt()), if (focused) 6f else 2f)
            val a = (255 * fade).toInt()
            when (item) {
                StoryObject.KEY -> key(c, p.x, p.y, r * .8f, -35f, a)
                StoryObject.MAP -> map(c, p.x, p.y, r * .78f, a)
                StoryObject.LANTERN -> lantern(c, p.x, p.y, r * .78f, a)
            }
            if (!s.revealing) chip(c, item.label, p.x, p.y + StoryGeometry.ORB_R + 24, focused, state.isAvailable("object:${item.name}"))
        }
    }

    private fun choice(c: Canvas, s: StorySnapshot) {
        val door = s.focus == "action:DOOR"; val trail = s.focus == "action:PATH"
        // The picked key stays with Robi; its glint and the waiting keyhole keep the story continuous.
        glow(c, StoryGeometry.keyInHand.x, StoryGeometry.keyInHand.y, 70f, tint(GOLD, 90 + (60 * wave).toInt()))
        glow(c, StoryGeometry.door.centerX(), StoryGeometry.door.centerY(), 340f, tint(AMBER, if (door) 90 else 45))
        if (door) {
            doorOutline(c, tint(CYAN, 90), 16f); doorOutline(c, CYAN, 5f)
            glow(c, StoryGeometry.keyhole.x, StoryGeometry.keyhole.y, 80f + 12 * wave, tint(GOLD, 200))
        }
        if (trail) {
            trailPath(c, tint(CYAN, 70), 30f)
            trailSparks(c)
        } else trailPath(c, tint(CREAM, 26), 18f)
        chip(c, StoryAction.DOOR.label, StoryGeometry.door.centerX(), StoryGeometry.door.bottom + 22, door, state.isAvailable("action:DOOR"))
        chip(c, StoryAction.PATH.label, 1405f, 420f, trail, state.isAvailable("action:PATH"))
    }

    private fun consequence(c: Canvas, s: StorySnapshot) {
        val t = s.elapsedMs.toFloat()
        val bitmap = art["forest_door"]
        background(c, "forest_door")
        val d = StoryGeometry.door
        if (bitmap != null && t < StoryRules.ROOM_FADE_END_MS) {
            // 1. The key leaves Robi's hand, travels to the keyhole and turns.
            val travel = ease(t / (StoryRules.KEY_TURN_MS * .65f))
            val turn = ease((t - StoryRules.KEY_TURN_MS * .65f) / (StoryRules.KEY_TURN_MS * .35f))
            val kx = StoryGeometry.keyInHand.x + (StoryGeometry.keyhole.x - StoryGeometry.keyInHand.x) * travel
            val ky = StoryGeometry.keyInHand.y + (StoryGeometry.keyhole.y - StoryGeometry.keyInHand.y) * travel
            // 2. The door leaf swings on its left hinge and warm light pours out of the oak.
            val open = ease((t - StoryRules.KEY_TURN_MS) / (StoryRules.DOOR_OPEN_END_MS - StoryRules.KEY_TURN_MS))
            if (open > 0f) {
                archPath()
                c.withClip(path) {
                    art["consequence"]?.let { room -> src.set(StoryGeometry.libraryCrop); drawBitmap(room, src, d, bitmapPaint) }
                    paint.shader = RadialGradient(d.centerX(), d.centerY() + 60, d.height() * .7f, intArrayOf(tint(color("#fff1c1"), 170), tint(color("#ffc163"), 70), tint(color("#6b3410"), 150)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
                    drawRect(d, paint); paint.shader = null
                    source(bitmap, d)
                    val w = d.width() * cos(open * 1.35f)
                    dst.set(d.left, d.top, d.left + w, d.bottom)
                    drawBitmap(bitmap, src, dst, bitmapPaint)
                    paint.color = tint(Color.BLACK, (open * 120).toInt()); drawRect(dst, paint)
                }
                glow(c, d.centerX() + 40, d.centerY() + 40, 380f + 240f * open, tint(color("#ffd98a"), (open * 170).toInt()))
            }
            if (open < .6f) {
                glow(c, kx, ky, 60f, tint(GOLD, 200))
                key(c, kx, ky, 42f, -35f + 90f * turn, 255)
            }
        }
        // 3. The same place, now open: the secret library behind the door.
        val room = ease((t - StoryRules.DOOR_OPEN_END_MS) / (StoryRules.ROOM_FADE_END_MS - StoryRules.DOOR_OPEN_END_MS))
        if (room > 0f) background(c, "consequence", (255 * room).toInt())
        scrim(c)
        if (room > 0f) {
            glow(c, StoryGeometry.doorway.x, StoryGeometry.doorway.y, 330f + 30 * wave, tint(color("#ffd98a"), (room * (70 + 30 * wave)).toInt()))
            glow(c, StoryGeometry.consequenceKey.x, StoryGeometry.consequenceKey.y, 70f, tint(GOLD, (room * 150).toInt()))
            sparkles(c, StoryGeometry.doorway.x, StoryGeometry.doorway.y, 300f, 9, color("#ffe9b0"))
        }
    }

    private fun ending(c: Canvas) {
        glow(c, StoryGeometry.endingKey.x, StoryGeometry.endingKey.y, 80f + 14 * wave, tint(GOLD, 150))
        val r = StoryGeometry.endingCard
        round(c, r.left, r.top, r.width(), r.height(), 36f, tint(color("#0c142b"), 232), tint(CYAN, 70), 1.5f)
        text(c, domain.title(state.value), r.centerX(), r.top + 58, 40f, CREAM, true, true, r.width() - 80)
    }

    private fun replay(c: Canvas, s: StorySnapshot) {
        val beat = s.replayBeat
        val inBeat = s.elapsedMs - beat * StoryRules.REPLAY_BEAT_MS
        val names = listOf("choose_hero", "choose_world", "forest_door", "consequence", "ending")
        if (beat > 0 && inBeat < 500) background(c, names[beat - 1])
        background(c, names[beat], if (beat > 0) (255 * ease(inBeat / 500f)).toInt() else 255)
        scrim(c)
        when (beat) {
            0 -> heroes(c, StoryHero.ROBI, false)
            1 -> glow(c, StoryGeometry.forest.centerX(), StoryGeometry.forest.centerY(), 480f, tint(CYAN, 45))
            2 -> { glow(c, StoryGeometry.keyInHand.x, StoryGeometry.keyInHand.y, 90f, tint(GOLD, 160)); glow(c, StoryGeometry.door.centerX(), StoryGeometry.door.centerY(), 340f, tint(AMBER, 80)) }
            3 -> glow(c, StoryGeometry.doorway.x, StoryGeometry.doorway.y, 340f, tint(color("#ffd98a"), 90))
            else -> glow(c, StoryGeometry.endingKey.x, StoryGeometry.endingKey.y, 90f, tint(GOLD, 150))
        }
        for (i in 0 until StoryRules.REPLAY_BEATS) {
            val on = i == beat
            round(c, 960f - 90 + i * 38 - if (on) 10 else 0, 990f, if (on) 34f else 14f, 14f, 7f, if (on) CYAN else tint(CREAM, if (i < beat) 170 else 70))
        }
    }

    // ---- Lumi ----
    private fun lumi(c: Canvas, s: StorySnapshot) {
        val prompt = state.prompt()
        if (prompt.isBlank()) return
        textMeasure.textSize = 32f
        val w = min(textMeasure.measureText(prompt) + 140f, 1200f)
        val left = 960f - w / 2
        round(c, left, 72f, w, 80f, 40f, tint(NAVY, 232), tint(CYAN, 110), 1.5f)
        val x = left + 48; val y = 112f
        val mood = s.mood
        val pulse = wave
        when (mood) {
            LumiMood.CURIOSITY -> glow(c, x, y, 34f + 4 * pulse, tint(CYAN, 110))
            LumiMood.SELECTION -> { glow(c, x, y, 40f, tint(CYAN, 140)); ring(c, x, y, 24f + 16f * ((ambientMs % 900) / 900f), tint(CYAN, (200 * (1 - (ambientMs % 900) / 900f)).toInt()), 3f) }
            LumiMood.DISCOVERY -> { glow(c, x, y, 46f, tint(GOLD, 150)); for (i in 0..3) c.withRotation(ambientMs / 20f + i * 90, x, y) { line(c, x, y - 25, x, y - 31, GOLD, 4f) } }
            LumiMood.WARM -> glow(c, x, y, 44f + 3 * pulse, tint(AMBER, 120))
        }
        paint.shader = LinearGradient(x - 20, y - 20, x + 20, y + 20, CYAN, PURPLE, Shader.TileMode.CLAMP)
        c.drawCircle(x, y + if (mood == LumiMood.CURIOSITY) -3 * pulse else 0f, 20f, paint); paint.shader = null
        sparkleStar(c, x, y + if (mood == LumiMood.CURIOSITY) -3 * pulse else 0f, 11f, CREAM)
        text(c, prompt, left + 86, 123f, 32f, CREAM, true, false, w - 110)
    }

    // ---- world props ----
    private fun chip(c: Canvas, label: String, x: Float, top: Float, focused: Boolean, available: Boolean) {
        textMeasure.textSize = if (focused) 30f else 26f
        val w = textMeasure.measureText(label) + if (focused && available) 120f else 48f
        val h = if (focused) 62f else 52f
        round(c, x - w / 2, top, w, h, h / 2, tint(NAVY, if (focused) 238 else 205), if (focused) CYAN else tint(Color.WHITE, 70), if (focused) 3f else 1f)
        if (focused && available) {
            round(c, x - w / 2 + 16, top + 14, 52f, 34f, 10f, CYAN)
            text(c, "OK", x - w / 2 + 42, top + 40, 22f, NAVY, true, true)
            text(c, label, x + 36, top + 42, 30f, CYAN, true, true)
        } else text(c, label, x, top + if (focused) 42 else 35, if (focused) 30f else 26f, if (focused) CYAN else CREAM, focused, true)
        if (!available) text(c, "Скоро", x, top + h + 30, 22f, color("#c6d3dc"), false, true)
    }
    private fun archPath() {
        val d = StoryGeometry.door
        path.reset(); path.moveTo(d.left, d.bottom); path.lineTo(d.left, d.top + d.width() / 2)
        path.arcTo(RectF(d.left, d.top, d.right, d.top + d.width()), 180f, 180f); path.lineTo(d.right, d.bottom); path.close()
    }
    private fun doorOutline(c: Canvas, color: Int, width: Float) {
        archPath(); paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeJoin = Paint.Join.ROUND; paint.color = color
        c.drawPath(path, paint); paint.style = Paint.Style.FILL
    }
    private fun trailPath(c: Canvas, color: Int, width: Float) {
        val p = StoryGeometry.trail
        path.reset(); path.moveTo(p[0], p[1]); for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = color
        c.drawPath(path, paint); paint.style = Paint.Style.FILL
    }
    private fun trailSparks(c: Canvas) {
        val p = StoryGeometry.trail
        val segments = p.size / 2 - 1
        for (n in 0 until 12) {
            val f = ((n / 12f) + ambientMs / 5200f) % 1f
            val seg = (f * segments).toInt().coerceAtMost(segments - 1)
            val local = f * segments - seg
            val x = p[seg * 2] + (p[seg * 2 + 2] - p[seg * 2]) * local
            val y = p[seg * 2 + 1] + (p[seg * 2 + 3] - p[seg * 2 + 1]) * local
            glow(c, x, y, 26f, tint(CYAN, 150)); circle(c, x, y, 6f, tint(Color.WHITE, 230))
        }
    }
    private fun spaceWash(c: Canvas) {
        val f = StoryGeometry.forest
        glow(c, 960f, 380f, 620f, tint(color("#3b5bdb"), 90))
        for (i in 0 until 26) {
            val x = 330f + (i * 173 % 1260); val y = 160f + (i * 97 % 520)
            val tw = .5f + .5f * sin(ambientMs / 300.0 + i).toFloat()
            circle(c, x, y, 2.5f + 2f * tw, tint(Color.WHITE, (120 + 120 * tw).toInt()))
        }
        planet(c, 1380f, 300f, 60f)
        glow(c, f.centerX(), f.centerY(), 300f, tint(color("#7bd0ff"), 40))
    }
    private fun islandWash(c: Canvas) {
        glow(c, 960f, 420f, 620f, tint(color("#2dd4bf"), 80))
        for (i in 0..3) {
            path.reset(); val y = 880f + i * 26
            path.moveTo(300f, y); var x = 300f
            while (x < 1620f) { path.quadTo(x + 40, y - 16 - 4 * wave, x + 80, y); x += 80 }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = tint(color("#a5f3fc"), 140 - i * 25); c.drawPath(path, paint); paint.style = Paint.Style.FILL
        }
        glow(c, 1400f, 260f, 110f, tint(GOLD, 150))
    }
    private fun spaceWindow(c: Canvas, p: PointF, r: Float) {
        paint.shader = LinearGradient(p.x, p.y - r, p.x, p.y + r, color("#1b2a6b"), color("#050816"), Shader.TileMode.CLAMP); c.drawCircle(p.x, p.y, r, paint); paint.shader = null
        for (i in 0 until 14) circle(c, p.x - r + (i * 53 % (2 * r.toInt())), p.y - r + (i * 71 % (2 * r.toInt())), 2.5f, tint(Color.WHITE, 200))
        planet(c, p.x + r * .15f, p.y + r * .1f, r * .38f)
        c.withTranslation(p.x - r * .45f, p.y - r * .35f) { c.withRotation(-35f) { round(c, -8f, -26f, 16f, 44f, 8f, color("#e2e8f0")); circle(c, 0f, -6f, 5f, color("#38bdf8")); glow(c, 0f, 26f, 18f, tint(AMBER, 220)) } }
    }
    private fun islandWindow(c: Canvas, p: PointF, r: Float) {
        paint.shader = LinearGradient(p.x, p.y - r, p.x, p.y + r, intArrayOf(color("#fbbf77"), color("#5fd3e0"), color("#1f7fa8")), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP); c.drawCircle(p.x, p.y, r, paint); paint.shader = null
        circle(c, p.x + r * .35f, p.y - r * .35f, r * .18f, color("#fff3b0"))
        ellipse(c, p.x - r * .7f, p.y + r * .12f, p.x + r * .45f, p.y + r * .55f, color("#e9c46a"))
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f; paint.color = color("#7c4a2d"); paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(p.x - r * .2f, p.y + r * .2f, p.x - r * .05f, p.y - r * .35f, paint); paint.style = Paint.Style.FILL
        for (a in listOf(-160f, -110f, -60f, -20f)) c.withRotation(a, p.x - r * .05f, p.y - r * .35f) { ellipse(c, p.x - r * .05f, p.y - r * .42f, p.x + r * .38f, p.y - r * .28f, color("#34d399")) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = tint(Color.WHITE, 170)
        c.drawArc(p.x - r * .9f, p.y + r * .5f, p.x + r * .9f, p.y + r * .8f, 200f, 140f, false, paint); paint.style = Paint.Style.FILL
    }
    private fun planet(c: Canvas, x: Float, y: Float, r: Float) {
        paint.shader = LinearGradient(x - r, y - r, x + r, y + r, color("#c084fc"), color("#6d28d9"), Shader.TileMode.CLAMP); c.drawCircle(x, y, r, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = r * .12f; paint.color = tint(color("#fde68a"), 220)
        c.drawOval(x - r * 1.7f, y - r * .35f, x + r * 1.7f, y + r * .35f, paint); paint.style = Paint.Style.FILL
    }
    private fun key(c: Canvas, x: Float, y: Float, size: Float, angle: Float, alpha: Int) {
        c.withRotation(angle, x, y) {
            val gold = tint(GOLD, alpha); val deep = tint(color("#b7791f"), alpha)
            glow(c, x, y, size * 1.1f, tint(AMBER, alpha / 3))
            // Ornate bow, long shaft and two bits: the same golden key the story art carries.
            for (i in 0..2) { val a = -PI / 2 + i * 2 * PI / 3; circle(c, x - size * .45f + cos(a).toFloat() * size * .2f, y + sin(a).toFloat() * size * .2f, size * .2f, gold) }
            circle(c, x - size * .45f, y, size * .1f, tint(NAVY, alpha))
            round(c, x - size * .25f, y - size * .07f, size * .95f, size * .14f, size * .07f, gold, deep, 2f)
            round(c, x + size * .45f, y, size * .1f, size * .25f, 3f, gold, deep, 2f)
            round(c, x + size * .6f, y, size * .1f, size * .18f, 3f, gold, deep, 2f)
        }
    }
    private fun map(c: Canvas, x: Float, y: Float, size: Float, alpha: Int) {
        val w = size * 1.3f; val h = size * .95f
        round(c, x - w / 2, y - h / 2, w, h, 8f, tint(color("#f3e2b8"), alpha), tint(color("#a3824a"), alpha), 2f)
        for (i in 1..2) line(c, x - w / 2 + w * i / 3, y - h / 2, x - w / 2 + w * i / 3, y + h / 2, tint(color("#c9ad76"), alpha), 2f)
        for (i in 0..4) circle(c, x - w * .32f + i * w * .13f, y + h * .18f - (i % 2) * h * .2f, 3.5f, tint(color("#38bdf8"), alpha))
        glow(c, x + w * .28f, y - h * .12f, 22f, tint(CYAN, alpha * 3 / 4))
        text(c, "✕", x + w * .28f, y - h * .02f, 24f, tint(color("#e11d48"), alpha), true, true)
    }
    private fun lantern(c: Canvas, x: Float, y: Float, size: Float, alpha: Int) {
        glow(c, x, y + size * .1f, size * 1.1f, tint(PURPLE, alpha / 2))
        ring(c, x, y - size * .55f, size * .16f, tint(color("#94a3b8"), alpha), 4f)
        round(c, x - size * .38f, y - size * .42f, size * .76f, size * .95f, 14f, tint(color("#312e81"), alpha), tint(color("#a5b4fc"), alpha), 3f)
        glow(c, x, y + size * .05f, size * .45f, tint(color("#fde68a"), alpha))
        sparkleStar(c, x, y + size * .05f, size * .22f, tint(Color.WHITE, alpha))
        round(c, x - size * .45f, y + size * .5f, size * .9f, size * .12f, 6f, tint(color("#a5b4fc"), alpha))
    }
    private fun sparkles(c: Canvas, x: Float, y: Float, spread: Float, count: Int, tintColor: Int) {
        for (i in 0 until count) {
            val a = i * 2.4 + ambientMs / 1800.0
            val d = spread * (.35f + .65f * ((i * 37 % 100) / 100f))
            val tw = .5f + .5f * sin(ambientMs / 260.0 + i * 1.7).toFloat()
            sparkleStar(c, x + cos(a).toFloat() * d, y + sin(a).toFloat() * d * .6f, 5f + 6f * tw, tint(tintColor, (90 + 150 * tw).toInt()))
        }
    }
    private fun sparkleStar(c: Canvas, x: Float, y: Float, r: Float, fill: Int) {
        path.reset()
        for (i in 0 until 8) {
            val rr = if (i % 2 == 0) r else r * .32f
            val a = -PI / 2 + i * PI / 4
            val px = x + cos(a).toFloat() * rr; val py = y + sin(a).toFloat() * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); paint.shader = null; paint.style = Paint.Style.FILL; paint.color = fill; c.drawPath(path, paint)
    }
    private fun ellipse(c: Canvas, l: Float, t: Float, r: Float, b: Float, fill: Int, stroke: Int? = null, sw: Float = 1f) {
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = fill; c.drawOval(l, t, r, b, paint)
        if (stroke != null) { paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = stroke; c.drawOval(l, t, r, b, paint); paint.style = Paint.Style.FILL }
    }
    private fun circle(c: Canvas, x: Float, y: Float, radius: Float, fill: Int) { paint.shader = null; paint.color = fill; paint.style = Paint.Style.FILL; c.drawCircle(x, y, radius, paint) }
    private fun ring(c: Canvas, x: Float, y: Float, radius: Float, color: Int, sw: Float) { paint.shader = null; paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; c.drawCircle(x, y, radius, paint); paint.style = Paint.Style.FILL }
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, sw: Float) { paint.shader = null; paint.color = color; paint.strokeWidth = sw; paint.strokeCap = Paint.Cap.ROUND; c.drawLine(x1, y1, x2, y2, paint) }
}
