package ru.familytv.space

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import ru.familytv.ui.ReferenceDrawing
import ru.familytv.ui.color
import kotlin.math.PI
import kotlin.math.sin

private val CYAN = color("#38d9d2")
private val CREAM = color("#f1f5f9")
private val SKY = color("#7bd0ff")
private val HIGH = color("#18254b")
private fun tint(c: Int, a: Int) = (c and 0xffffff) or (a.coerceIn(0, 255) shl 24)

internal object SpaceGeometry {
    fun waypoint(nodeId: String, progress: Float, optional: Boolean): Pair<Float, Float> {
        if (optional) return 520f to 900f
        return when (nodeId) {
            "launch" -> 250f to 690f
            "moon-hub" -> 520f to 380f
            "light-lab" -> 820f to 240f
            "temp-lab" -> 860f to 640f
            "stretch" -> 1160f to 420f
            "final-mission" -> 1460f to 280f
            "calm-end" -> 1680f to 700f
            else -> {
                val x = 200f + progress.coerceIn(0f, 1f) * 1480f
                val y = 540f - sin(progress * PI.toFloat()) * 180f
                x to y
            }
        }
    }

    fun sequenceSlot(index: Int, count: Int): RectF {
        val n = count.coerceAtLeast(1)
        val w = 148f
        val gap = 72f
        val total = n * w + (n - 1) * gap
        val left = (1920f - total) / 2f + 80f
        return RectF(left + index * (w + gap), 250f, left + index * (w + gap) + w, 400f)
    }

    fun sequenceOrb(index: Int, count: Int, focusIndex: Int = 1): RectF {
        if (count == 3) {
            val idleW = 380f
            val idleH = 460f
            val focusW = 430f
            val focusH = 520f
            val gap = 48f
            val focus = focusIndex.coerceIn(0, 2)
            val widths = FloatArray(3) { if (it == focus) focusW else idleW }
            val total = widths.sum() + gap * 2f
            var x = (1920f - total) / 2f
            var result = RectF()
            for (i in 0..2) {
                val w = widths[i]
                val h = if (i == focus) focusH else idleH
                val top = 540f - h / 2f
                if (i == index) result = RectF(x, top, x + w, top + h)
                x += w + gap
            }
            return result
        }
        val n = count.coerceAtLeast(1)
        val w = 156f
        val gap = 56f
        val total = n * w + (n - 1) * gap
        val left = (1920f - total) / 2f
        return RectF(left + index * (w + gap), 690f, left + index * (w + gap) + w, 846f)
    }

    fun searchBay(index: Int): RectF {
        val left = 430f + index * 118f
        return RectF(left, 430f, left + 96f, 534f)
    }

    fun searchObject(index: Int, count: Int): RectF {
        val spots = listOf(
            RectF(980f, 260f, 1140f, 420f),
            RectF(1220f, 320f, 1380f, 480f),
            RectF(1480f, 250f, 1640f, 410f),
            RectF(1040f, 520f, 1200f, 680f),
            RectF(1280f, 580f, 1440f, 740f),
            RectF(1540f, 500f, 1700f, 660f),
        )
        return spots.getOrElse(index) {
            val x = 980f + (index % 3) * 240f
            val y = 260f + (index / 3) * 260f
            RectF(x, y, x + 160f, y + 160f)
        }.takeIf { index < count } ?: spots.last()
    }

    fun sortItem(index: Int, count: Int): RectF {
        if (count <= 1) return sortCurrent()
        val n = count.coerceAtLeast(1)
        val w = 150f
        val gap = 36f
        val total = n * w + (n - 1) * gap
        val left = (1920f - total) / 2f
        return RectF(left + index * (w + gap), 240f, left + index * (w + gap) + w, 400f)
    }

    fun sortCurrent(): RectF = RectF(780f, 168f, 1140f, 560f)

    fun sortZone(index: Int, count: Int = 3): RectF {
        val n = count.coerceAtLeast(1)
        val w = if (n <= 3) 520f else (1680f / n - 24f)
        val gap = 28f
        val total = n * w + (n - 1) * gap
        val left = ((1920f - total) / 2f).coerceAtLeast(48f)
        return RectF(left + index * (w + gap), 608f, left + index * (w + gap) + w, 1000f)
    }

    fun rect(key: String, snap: SpaceSnapshot): RectF? = when (key) {
        "home", "back" -> when {
            snap.screen == SpaceScreen.ENTRY -> RectF(88f, 74f, 280f, 148f)
            snap.screen == SpaceScreen.JOURNEY -> RectF(88f, 72f, 340f, 148f)
            snap.screen == SpaceScreen.INTRO -> RectF(80f, 80f, 380f, 160f)
            snap.helping -> RectF(88f, 72f, 480f, 148f)
            else -> RectF(48f, 36f, 250f, 108f)
        }
        "pause" -> when {
            snap.screen == SpaceScreen.CHALLENGE && snap.mechanicType == "PATTERN_SEQUENCE" ->
                RectF(88f, 72f, 352f, 140f)
            else -> RectF(48f, 36f, 250f, 108f)
        }
        "journal" -> when (snap.screen) {
            SpaceScreen.ENTRY -> RectF(1240f, 800f, 1560f, 888f)
            else -> RectF(1280f, 930f, 1680f, 1032f)
        }
        "journey" -> RectF(380f, 800f, 680f, 888f)
        "continue" -> when (snap.screen) {
            SpaceScreen.ENTRY -> RectF(700f, 770f, 1220f, 910f)
            SpaceScreen.JOURNEY -> RectF(88f, 920f, 780f, 1036f)
            SpaceScreen.INTRO -> RectF(640f, 860f, 1280f, 990f)
            SpaceScreen.SUCCESS -> RectF(370f, 640f, 1550f, 900f)
            else -> RectF(560f, 500f, 960f, 600f)
        }
        "finish" -> RectF(980f, 500f, 1360f, 600f)
        "help" -> when {
            snap.helping -> RectF(560f, 860f, 1360f, 1000f)
            snap.screen == SpaceScreen.CHALLENGE && snap.mechanicType == "PATTERN_SEQUENCE" ->
                RectF(1f, 1f, 2f, 2f)
            else -> RectF(1648f, 36f, 1868f, 108f)
        }
        "check" -> RectF(800f, 940f, 1120f, 1024f)
        else -> {
            snap.waypoints.find { "node:${it.nodeId}" == key }?.let {
                RectF(it.x - 100f, it.y - 110f, it.x + 100f, it.y + 140f)
            } ?: snap.branches.find { "branch:${it.id}" == key }?.let { b ->
                val i = snap.branches.indexOf(b)
                RectF(520f, 360f + i * 140f, 1400f, 470f + i * 140f)
            } ?: choiceRect(key, snap)
        }
    }

    private fun choiceRect(key: String, snap: SpaceSnapshot): RectF? {
        val maze = snap.choices.filter { it.key.startsWith("cell:") }
        if (key.startsWith("cell:") && maze.isNotEmpty()) {
            val i = maze.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return null
            val width = maze.maxOf { it.key.removePrefix("cell:").substringBefore(',').toIntOrNull() ?: 0 } + 1
            val x = i % width
            val y = i / width
            val cell = 168f
            val left = 1920f / 2f - width * cell / 2f
            val top = 250f
            return RectF(left + x * cell, top + y * cell, left + x * cell + 150f, top + y * cell + 150f)
        }
        val orbs = snap.choices.filter { it.key.startsWith("opt:") }
        if (key in orbs.map { it.key }) {
            val i = orbs.indexOfFirst { it.key == key }
            val focus = orbs.indexOfFirst { it.key == snap.focus }.takeIf { it >= 0 } ?: 1
            return sequenceOrb(i, orbs.size, focus)
        }
        val objs = snap.choices.filter { it.key.startsWith("obj:") }
        if (key in objs.map { it.key }) {
            val i = objs.indexOfFirst { it.key == key }
            val selected = objs[i].selected
            return if (selected) {
                val bay = objs.take(i + 1).count { it.selected } - 1
                searchBay(bay.coerceIn(0, 2))
            } else searchObject(i, objs.size)
        }
        val items = snap.choices.filter { it.key.startsWith("item:") }
        if (key in items.map { it.key }) {
            val i = items.indexOfFirst { it.key == key }
            return sortItem(i, items.size)
        }
        val cats = snap.categories
        if (key in cats.map { it.key }) {
            val i = cats.indexOfFirst { it.key == key }
            return sortZone(i, cats.size)
        }
        return null
    }
}

@SuppressLint("ViewConstructor")
internal class SpaceTarget(context: Context, label: String) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = label
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

internal enum class SpaceGlyph { NONE, BACK, PAUSE, PLAY, MAP, JOURNAL, HELP, CHECK }

@SuppressLint("ViewConstructor")
internal class SpaceButton(
    context: Context,
    val label: String,
    private val dw: Float,
    private val dh: Float,
    private val primary: Boolean,
    private val glyph: SpaceGlyph,
) : ReferenceDrawing(context, dw, dh) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = label
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
    override fun drawScene(c: Canvas) {
        val focused = isFocused
        val fill = when {
            primary && focused -> CYAN
            primary -> color("#14b8a6")
            focused -> tint(CYAN, 48)
            else -> tint(HIGH, 140)
        }
        val stroke = if (focused) CYAN else tint(SKY, 70)
        round(c, 2f, 2f, dw - 4f, dh - 4f, dh / 2f, fill, stroke, if (focused) 6f else 2f)
        val ink = if (primary) color("#041d1c") else CREAM
        text(c, label, dw / 2f, dh / 2f + 10f, if (dw > 360f) 26f else 20f, ink, heavy = true, centered = true, maxWidth = dw - 36f)
    }
}
