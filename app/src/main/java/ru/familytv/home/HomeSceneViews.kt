package ru.familytv.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import ru.familytv.R
import kotlin.math.roundToInt

internal typealias HomeStage = ru.familytv.ui.ReferenceStage
internal typealias HomeDrawing = ru.familytv.ui.ReferenceDrawing

internal fun color(hex: String): Int = Color.parseColor(hex)
internal val cyan = color("#58C3E5")
internal val white = color("#F8FAFC")
internal val muted = color("#94B8CF")
private fun alpha(c: Int, a: Int) = (c and 0x00ffffff) or (a.coerceIn(0, 255) shl 24)

internal class HomeBackdrop(context: Context) : HomeDrawing(context, 1920f, 1080f) {
    var selectedWorld = 0
        set(value) { field = value; invalidate() }

    override fun drawScene(c: Canvas) {
        paint.shader = LinearGradient(0f, 0f, 0f, 1080f,
            intArrayOf(color("#090D21"), color("#12193E"), color("#1E2652")), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1920f, 1080f, paint)
        paint.shader = null
        c.save()
        c.scale(1.8f, 0.8f)
        glow(c, 500f, 50f, 430f, color("#2410B9B5"))
        glow(c, 945f, 560f, 330f, color("#245D287D"))
        c.restore()
        glow(c, 245f + 357.5f * selectedWorld, 412f, 275f, color("#2848BFD0"))
        listOf(floatArrayOf(234f, 90f, 4f), floatArrayOf(656f, 176f, 3f), floatArrayOf(1119f, 124f, 5f), floatArrayOf(1613f, 238f, 3f)).forEachIndexed { i, s ->
            if (i % 2 == 0) glow(c, s[0], s[1], 14f, color("#387DE0E2"))
            paint.color = if (i == 2) color("#708A8555") else color("#708DC4D9")
            c.drawCircle(s[0], s[1], s[2], paint)
        }
        paint.color = color("#50151C42")
        val mountains = Path().apply {
            moveTo(0f, 980f); lineTo(180f, 920f); cubicTo(320f, 873f, 470f, 950f, 620f, 900f)
            lineTo(790f, 840f); cubicTo(940f, 790f, 1110f, 900f, 1260f, 870f)
            lineTo(1450f, 830f); cubicTo(1610f, 797f, 1760f, 880f, 1920f, 850f)
            lineTo(1920f, 1080f); lineTo(0f, 1080f); close()
        }
        c.drawPath(mountains, paint)
        paint.color = color("#550D132D")
        val foreground = Path().apply {
            moveTo(0f, 1040f); lineTo(240f, 990f); cubicTo(410f, 955f, 560f, 1020f, 740f, 985f)
            lineTo(960f, 940f); cubicTo(1180f, 900f, 1320f, 990f, 1520f, 955f)
            lineTo(1740f, 920f); lineTo(1920f, 930f); lineTo(1920f, 1080f); lineTo(0f, 1080f); close()
        }
        c.drawPath(foreground, paint)
        text(c, "FamilyTV", 164f, 116f, 30f, heavy = true)
        pill(c, "ДЕТСКИЙ РЕЖИМ", 374f, 94f, 12f, color("#6EE7B7"), color("#2034D399"))
        text(c, "Волшебная Поляна: выбирай остров стрелками на пульте", 164f, 144f, 18f, muted)
        round(c, 80f, 957f, 1760f, 1f, 0f, color("#20FFFFFF"))
        round(c, 80f, 975f, 44f, 25f, 7f, color("#25FFFFFF"), color("#40FFFFFF"))
        text(c, "◀ ▶", 102f, 993f, 12f, centered = true)
        text(c, "Переход между островами", 132f, 992f, 14f, color("#809EBF"))
        round(c, 338f, 975f, 44f, 25f, 7f, color("#25FFFFFF"), color("#40FFFFFF"))
        text(c, "▲ ▼", 360f, 993f, 12f, centered = true)
        text(c, "К миссии / в меню", 390f, 992f, 14f, color("#809EBF"))
        round(c, 552f, 975f, 42f, 25f, 7f, color("#34D399"))
        text(c, "OK", 573f, 993f, 12f, color("#071D27"), true, true)
        text(c, "Войти в мир", 604f, 992f, 14f)
        text(c, "НАЗАД", 1630f, 992f, 12f, heavy = true)
        text(c, "Вернуться домой", 1690f, 992f, 14f, muted)
    }
}

internal data class HomeWorld(val title: String, val subtitle: String, val badge: String, val art: Int, val accent: Int, val panelTop: Int, val panelBottom: Int, val artTop: Int, val artBottom: Int) {
    companion object {
        val ALL = listOf(
            HomeWorld("Смотреть", "Кино-дирижабль и сказки", "Мультфильмы & Истории", R.drawable.stitch_c_watch, color("#38BDF8"), color("#1C2454"), color("#121838"), color("#2B2D70"), color("#1E376D")),
            HomeWorld("Играть", "Крепость весёлых игр", "Логика и Счёт", R.drawable.stitch_c_play, color("#6EE7B7"), color("#1E2D62"), color("#141C47"), color("#0B3037"), color("#05302F")),
            HomeWorld("Исследовать", "Дом-обсерватория в кроне", "Наука & Погода", R.drawable.stitch_c_explore, color("#5EEAD4"), color("#1C274E"), color("#111933"), color("#102D37"), color("#083043")),
            HomeWorld("Создавать", "Поляна живых красок", "Краски & Истории", R.drawable.stitch_c_create, color("#F472B6"), color("#2B1E48"), color("#1A122E"), color("#321251"), color("#470D2F")),
            HomeWorld("Двигаться", "Трамплины и ритм-парк", "Зарядка & Танцы", R.drawable.stitch_c_move, color("#FCD34D"), color("#2E2416"), color("#1B140B"), color("#382515"), color("#403015")),
        )
    }
}

internal class HomeWorldView(context: Context, val index: Int) : HomeDrawing(context, 410f, 436f) {
    private val world = HomeWorld.ALL[index]
    private var focusAmount = 0f
    private var focusAnimator: ValueAnimator? = null
    var representative = "Открываем остров…"
        set(value) { field = value; updateDescription(); invalidate() }
    var onFocused: (() -> Unit)? = null

    init {
        id = generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
        setOnFocusChangeListener { _, focused ->
            focusAnimator?.cancel()
            if (focused) { translationZ = 8f; onFocused?.invoke() }
            focusAnimator = ValueAnimator.ofFloat(focusAmount, if (focused) 1f else 0f).apply {
                duration = 260L
                interpolator = DecelerateInterpolator()
                addUpdateListener { applyFocus(it.animatedValue as Float) }
                start()
            }
        }
    }

    private fun updateDescription() {
        contentDescription = "${world.title}. $representative"
    }

    private fun applyFocus(value: Float) {
        focusAmount = value
        scaleX = 1f + 0.08f * value
        scaleY = scaleX
        val s = width / 410f
        translationY = -14f * s * value
        // Preserve the reference's 80px edge safe area, including expanded outer rings.
        translationX = when (index) { 0 -> 28f; 4 -> -28f; else -> 0f } * s * value
        if (value == 0f) translationZ = 0f
        invalidate()
    }

    fun settle() { focusAnimator?.cancel(); applyFocus(if (hasFocus()) 1f else 0f) }

    override fun onDetachedFromWindow() { focusAnimator?.cancel(); super.onDetachedFromWindow() }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.isSelected = hasFocus()
    }

    override fun drawScene(c: Canvas) {
        val top = 40f - 20f * focusAmount
        val h = 356f + 20f * focusAmount
        if (focusAmount > 0f) {
            // Cheap concentric strokes give the broad cyan halo without software blur/layers.
            for (spread in 28 downTo 2 step 2) {
                round(c, 28f - spread / 2f, top - 12 - spread / 2f, 354f + spread, h + 24 + spread,
                    31f + spread / 2, Color.TRANSPARENT, alpha(cyan, (7 * focusAmount).toInt()), 4f)
            }
            round(c, 28f, top - 12, 354f, h + 24, 30f, Color.TRANSPARENT, alpha(cyan, (255 * focusAmount).toInt()), 5f)
            round(c, 31f, top - 9, 348f, h + 18, 28f, Color.TRANSPARENT, alpha(color("#34D399"), (240 * focusAmount).toInt()), 2f)
        }
        gradient(c, 40f, top, 330f, h, 24f, world.panelTop, world.panelBottom)
        round(c, 40f, top, 330f, h, 24f, Color.TRANSPARENT,
            alpha(if (hasFocus()) color("#34D399") else world.accent, if (hasFocus()) 230 else 76), 2f)
        gradient(c, 61f, top + 21, 288f, 176f, 16f, world.artTop, world.artBottom)
        icon(c, world.art, 141f, top + 45, 128f)
        pill(c, world.badge, 205f, top + 167, 12f, world.accent, alpha(world.accent, 42), 276f)
        text(c, "ОСТРОВ ${index + 1}" + if (hasFocus()) " • ВЫБРАН" else "", 205f, top + 230, 12f, world.accent, true, true)
        text(c, world.title, 205f, top + 262, 25f, heavy = true, centered = true)
        text(c, world.subtitle, 205f, top + 287, 14f, muted, centered = true)
        val badgeTop = top + 306f
        val badgeH = 30f + 14f * focusAmount
        round(c, 61f, badgeTop, 288f, badgeH, 14f,
            if (hasFocus()) color("#082C30") else color("#18FFFFFF"), alpha(world.accent, if (hasFocus()) 120 else 45))
        if (hasFocus()) {
            round(c, 70f, badgeTop + 9, 25f, 25f, 7f, alpha(world.accent, 42))
            text(c, "•", 82.5f, badgeTop + 27, 13f, world.accent, true, true)
            text(c, representative, 103f, badgeTop + 26, 12f, heavy = true, maxWidth = 197f)
            round(c, 306f, badgeTop + 10, 34f, 23f, 6f, alpha(world.accent, 35))
            text(c, "OK", 323f, badgeTop + 26, 11f, world.accent, true, true)
        } else {
            text(c, representative, 205f, badgeTop + 20, 12f, color("#BDCDE0"), true, true, 267f)
        }
    }
}

internal class HomeControl(context: Context, private val label: String, private val res: Int, private val designWidth: Float) : HomeDrawing(context, designWidth, 64f) {
    init {
        id = generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        contentDescription = label
        setOnFocusChangeListener { _, focused ->
            animate().translationY(if (focused) -2f else 0f).setDuration(180).start()
            invalidate()
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.className = "android.widget.Button"
    }
    override fun drawScene(c: Canvas) {
        round(c, 3f, 4f, designWidth - 6, 56f, 16f, color("#18FFFFFF"), if (hasFocus()) cyan else color("#30FFFFFF"), if (hasFocus()) 4f else 1f)
        icon(c, res, 23f, 20f, 24f)
        text(c, label, 59f, 39f, 18f, heavy = true, maxWidth = designWidth - 78)
    }
}

internal class HomeMissionView(context: Context) : HomeDrawing(context, 1792f, 162f) {
    var data: HomeWorldContent? = null
        set(value) {
            field = value
            contentDescription = value?.let { "${it.missionAction}. ${it.missionTitle}. ${it.missionCaption}" } ?: "Путешествие"
            invalidate()
        }
    init {
        id = generateViewId(); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        setOnFocusChangeListener { _, focused ->
            animate().translationY(if (focused) -4f else 0f).setDuration(220).start()
            invalidate()
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.className = "android.widget.Button"
    }
    override fun drawScene(c: Canvas) {
        val d = data
        gradient(c, 16f, 12f, 1760f, 138f, 32f, color("#17234F"), color("#1E306E"), true)
        round(c, 16f, 12f, 1760f, 138f, 32f, Color.TRANSPARENT,
            if (hasFocus()) color("#67E8F9") else color("#129CBD"), if (hasFocus()) 4f else 2f)
        if (hasFocus()) round(c, 10f, 6f, 1772f, 150f, 38f, Color.TRANSPARENT, color("#8058C3E5"), 3f)
        glow(c, 90f, 105f, 68f, color("#4022D3EE"))
        round(c, 50f, 42f, 80f, 80f, 16f, color("#111C3D"), color("#22BDEE"), 2f)
        icon(c, R.drawable.stitch_c_rocket, 66f, 58f, 48f)
        round(c, 154f, 38f, 365f, 22f, 11f, color("#3058C3E5"), color("#6058C3E5"))
        text(c, d?.missionBadge ?: "ПУТЕШЕСТВИЕ С ЛУМИ", 166f, 53f, 12f, color("#67E8F9"), true, maxWidth = 341f)
        text(c, d?.missionTitle ?: "Готовим путешествие…", 154f, 91f, 30f, heavy = true, maxWidth = if (d?.continueVideo != null) 995f else 1210f)
        text(c, d?.missionCaption ?: "Луми рядом", 154f, 119f, 16f, muted, maxWidth = 1010f)
        gradient(c, 1490f, 49f, 250f, 64f, 16f, color("#22D3EE"), color("#7DD3FC"), true)
        text(c, d?.missionAction ?: "Открыть", 1601f, 90f, 23f, color("#08172B"), true, true)
        text(c, "▶", 1701f, 89f, 22f, color("#08172B"), true, true)
    }
}
