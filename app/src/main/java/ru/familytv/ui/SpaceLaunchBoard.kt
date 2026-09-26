package ru.familytv.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ru.familytv.R
import ru.familytv.adventure.ScaffoldLevel
import ru.familytv.adventure.SearchObject

/**
 * Space launch vertical-slice board: recognizable ship + energy slots + world objects.
 * Presentation only — correctness stays in AdventureRuntime / VisualSearch.
 */
class SpaceLaunchBoard(context: Context) : LinearLayout(context) {

    enum class ShipState {
        BROKEN,
        PARTIAL,
        POWERED,
        ENGINE_ON,
        LAUNCHING,
    }

    fun interface ObjectPickListener {
        fun onPick(objectId: String)
    }

    var onPick: ObjectPickListener? = null

    private val dens = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()

    private val shipPanel = FrameLayout(context)
    private val shipImage = ImageView(context)
    private val engineGlow = ImageView(context)
    private val problemPulse = View(context)
    private val lumiPointer = View(context)
    private val slotsRow = LinearLayout(context)
    private val slotViews = ArrayList<ImageView>()
    private val objectsRow = LinearLayout(context)
    private val objectViews = LinkedHashMap<String, View>()
    private val objectFocusIds = ArrayList<Int>()
    private val statusLine = TextView(context)

    private var requiredIds: Set<String> = emptySet()
    private var installedOrder: List<String> = emptyList()
    private var highlightIds: Set<String> = emptySet()
    private var scaffold: ScaffoldLevel = ScaffoldLevel.NONE
    private var shipState: ShipState = ShipState.BROKEN
    private var pulseAnimator: ObjectAnimator? = null
    private var energyFlowAnimator: ValueAnimator? = null

    /** Focus ring color — distinct from Help yellow (#FFDC64). */
    private val focusStroke = Color.rgb(80, 230, 210)
    private val helpStroke = Color.rgb(255, 220, 100)
    private val idleStroke = Color.argb(80, 200, 220, 255)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false

        shipPanel.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(136))
        shipPanel.clipChildren = false
        shipPanel.clipToPadding = false

        shipImage.setImageResource(R.drawable.space_ship)
        shipImage.scaleType = ImageView.ScaleType.FIT_CENTER
        shipImage.contentDescription = "Корабль"
        shipPanel.addView(
            shipImage,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(118), Gravity.CENTER),
        )

        engineGlow.setImageResource(R.drawable.space_engine_glow)
        engineGlow.scaleType = ImageView.ScaleType.FIT_CENTER
        engineGlow.alpha = 0f
        engineGlow.contentDescription = "Двигатель"
        shipPanel.addView(
            engineGlow,
            FrameLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                marginStart = dp(8)
            },
        )

        problemPulse.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(90, 255, 180, 60))
        }
        problemPulse.alpha = 0f
        shipPanel.addView(
            problemPulse,
            FrameLayout.LayoutParams(dp(80), dp(80)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                marginStart = dp(4)
            },
        )

        lumiPointer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(120, 120, 220, 255))
        }
        lumiPointer.alpha = 0f
        shipPanel.addView(
            lumiPointer,
            FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            },
        )

        slotsRow.orientation = HORIZONTAL
        slotsRow.gravity = Gravity.CENTER
        repeat(3) { idx ->
            val slot = ImageView(context).apply {
                setImageResource(R.drawable.space_slot_empty)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Пустое место для энергии ${idx + 1}"
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(60, 20, 30, 50))
                    setStroke(dp(2), Color.argb(160, 180, 220, 255))
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            slotViews.add(slot)
            slotsRow.addView(slot, LayoutParams(dp(52), dp(64)).apply {
                marginEnd = if (idx < 2) dp(8) else 0
            })
        }
        shipPanel.addView(
            slotsRow,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                topMargin = dp(14)
            },
        )

        addView(shipPanel)

        statusLine.setTextColor(Color.rgb(200, 230, 255))
        statusLine.textSize = 15f
        statusLine.gravity = Gravity.CENTER
        statusLine.setPadding(0, dp(2), 0, dp(4))
        statusLine.maxLines = 1
        addView(statusLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        objectsRow.orientation = HORIZONTAL
        objectsRow.gravity = Gravity.CENTER
        objectsRow.isClickable = false
        objectsRow.isFocusable = false
        objectsRow.clipChildren = false
        objectsRow.clipToPadding = false
        addView(
            objectsRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
            },
        )
    }

    fun bind(
        objects: List<SearchObject>,
        requiredIds: Set<String>,
        selectedIds: Set<String>,
        highlightIds: Set<String>,
        scaffold: ScaffoldLevel,
        celebrating: Boolean,
        interactive: Boolean,
    ) {
        this.requiredIds = requiredIds
        this.highlightIds = highlightIds
        this.scaffold = scaffold
        installedOrder = requiredIds.filter { it in selectedIds }
        shipState = when {
            celebrating || installedOrder.size >= requiredIds.size -> ShipState.ENGINE_ON
            installedOrder.isNotEmpty() -> ShipState.PARTIAL
            else -> ShipState.BROKEN
        }
        if (celebrating) shipState = ShipState.ENGINE_ON
        renderSlots()
        renderObjects(objects, selectedIds, interactive)
        renderShipVisuals()
        statusLine.text = when (shipState) {
            ShipState.BROKEN -> "Двигатель молчит. Нужна энергия."
            ShipState.PARTIAL -> "Энергия ${installedOrder.size} из ${requiredIds.size}"
            ShipState.POWERED, ShipState.ENGINE_ON, ShipState.LAUNCHING -> "Двигатель готов!"
        }
        if (scaffold == ScaffoldLevel.HINT1 || scaffold == ScaffoldLevel.HINT2_VISUAL) {
            pulseProblemArea()
        } else if (shipState == ShipState.BROKEN) {
            pulseProblemArea()
        } else {
            stopPulse()
        }
    }

    /** Wire object row ↔ actions for predictable D-pad UP/DOWN. */
    fun wireFocusToActions(actions: LinearLayout) {
        val actionFocusables = ArrayList<View>()
        for (i in 0 until actions.childCount) {
            val c = actions.getChildAt(i)
            if (c.isFocusable) actionFocusables.add(c)
        }
        if (actionFocusables.isEmpty() || objectFocusIds.isEmpty()) return
        val firstAction = actionFocusables.first()
        val lastAction = actionFocusables.last()
        val firstObj = findViewById<View>(objectFocusIds.first()) ?: return
        val lastObj = findViewById<View>(objectFocusIds.last()) ?: return
        objectFocusIds.forEach { id ->
            findViewById<View>(id)?.let { v ->
                v.nextFocusDownId = firstAction.id
                // Keep UP inside the object row — do not escape to header Home.
                v.nextFocusUpId = id
            }
        }
        actionFocusables.forEach { v ->
            if (v.id == View.NO_ID) v.id = View.generateViewId()
            v.nextFocusUpId = firstObj.id
        }
        // Horizontal wrap within objects
        for (i in objectFocusIds.indices) {
            val cur = findViewById<View>(objectFocusIds[i]) ?: continue
            if (i > 0) cur.nextFocusLeftId = objectFocusIds[i - 1]
            if (i < objectFocusIds.lastIndex) cur.nextFocusRightId = objectFocusIds[i + 1]
        }
        firstObj.nextFocusLeftId = lastObj.id
        lastObj.nextFocusRightId = firstObj.id
        lastAction.nextFocusRightId = firstAction.id
        firstAction.nextFocusLeftId = lastAction.id
    }

    fun requestFirstObjectFocus() {
        objectFocusIds.firstOrNull()?.let { findViewById<View>(it)?.requestFocus() }
    }

    /**
     * Wrong object: lean toward slots → soft reject flash → return.
     * Readable without reading text (~550–700 ms).
     */
    fun playSoftReject(objectId: String) {
        val view = objectViews[objectId] ?: return
        view.animate().cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        // Direction toward ship center (up)
        val towardY = -dp(36).toFloat()
        val lean = if (view.left + view.width / 2 < width / 2) 10f else -10f
        flashSlotsReject()
        view.animate()
            .translationY(towardY)
            .rotation(lean)
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    view.animate()
                        .translationY(0f)
                        .translationX(0f)
                        .rotation(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(320)
                        .setInterpolator(OvershootInterpolator(1.1f))
                        .withEndAction {
                            if (view.isFocusable) view.requestFocus()
                        }
                        .start()
                }
            .start()
        ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.65f, 1f).apply {
            duration = 600
            start()
        }
    }

    fun playInstall(objectId: String, slotIndex: Int, onEnd: (() -> Unit)? = null) {
        val from = objectViews[objectId] ?: run {
            onEnd?.invoke()
            return
        }
        val slot = slotViews.getOrNull(slotIndex) ?: run {
            onEnd?.invoke()
            return
        }
        from.animate()
            .scaleX(0.55f)
            .scaleY(0.55f)
            .alpha(0.35f)
            .setDuration(240)
            .withEndAction {
                slot.setImageResource(drawableFor(objectId))
                slot.alpha = 0f
                slot.scaleX = 0.4f
                slot.scaleY = 0.4f
                slot.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .withEndAction { onEnd?.invoke() }
                    .start()
                from.alpha = 0.25f
                from.isEnabled = false
                from.isFocusable = false
            }
            .start()
    }

    /**
     * ~1.6–2.0 s success beat: slots glow → energy flow → engine thrust → ship ready lift.
     */
    fun playEngineStart(onEnd: (() -> Unit)? = null) {
        shipState = ShipState.ENGINE_ON
        stopPulse()
        statusLine.text = "Двигатель запустился!"

        // 1) Slots activate
        slotViews.forEachIndexed { i, slot ->
            slot.postDelayed({
                (slot.background as? GradientDrawable)?.apply {
                    setColor(Color.argb(160, 40, 140, 130))
                    setStroke(dp(4), Color.rgb(255, 230, 120))
                }
                slot.animate().scaleX(1.12f).scaleY(1.12f).setDuration(180)
                    .withEndAction { slot.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }
                    .start()
            }, (i * 90).toLong())
        }

        // 2) Energy flow pulse across hull
        energyFlowAnimator?.cancel()
        energyFlowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            startDelay = 200
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                shipImage.alpha = 0.85f + 0.15f * t
            }
            start()
        }

        // 3) Engine lights
        engineGlow.animate()
            .alpha(1f)
            .scaleX(1.35f)
            .scaleY(1.35f)
            .setStartDelay(280)
            .setDuration(420)
            .start()

        // 4) Ship ready vibration
        shipPanel.postDelayed({
            shipPanel.animate()
                .translationX((-dp(4)).toFloat())
                .setDuration(70)
                .withEndAction {
                    shipPanel.animate()
                        .translationX(dp(4).toFloat())
                        .setDuration(70)
                        .withEndAction {
                            shipPanel.animate().translationX(0f).setDuration(70).start()
                        }
                        .start()
                }
                .start()
        }, 650)

        // 5) Launch lift
        shipPanel.postDelayed({
            playLaunch(onEnd)
        }, 900)
    }

    fun playLaunch(onEnd: (() -> Unit)? = null) {
        shipState = ShipState.LAUNCHING
        engineGlow.animate().scaleX(1.5f).scaleY(1.5f).alpha(1f).setDuration(500).start()
        val set = AnimatorSet()
        val up = ObjectAnimator.ofFloat(shipPanel, View.TRANSLATION_Y, 0f, -dp(36).toFloat())
        val fadeTrail = ObjectAnimator.ofFloat(engineGlow, View.ALPHA, 1f, 0.75f, 1f)
        up.duration = 850
        fadeTrail.duration = 850
        up.interpolator = AccelerateDecelerateInterpolator()
        set.playTogether(up, fadeTrail)
        set.start()
        shipPanel.postDelayed({ onEnd?.invoke() }, 900)
    }

    fun pulseProblemArea() {
        stopPulse()
        problemPulse.alpha = 0.85f
        lumiPointer.alpha = 0.9f
        pulseAnimator = ObjectAnimator.ofFloat(problemPulse, View.ALPHA, 0.35f, 0.95f).apply {
            duration = 700
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        ObjectAnimator.ofFloat(lumiPointer, View.TRANSLATION_Y, 0f, -8f, 0f).apply {
            duration = 900
            repeatCount = 2
            start()
        }
    }

    fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        problemPulse.alpha = 0f
        if (scaffold == ScaffoldLevel.NONE) lumiPointer.alpha = 0f
    }

    private fun flashSlotsReject() {
        slotViews.forEach { slot ->
            val bg = slot.background as? GradientDrawable ?: return@forEach
            bg.setStroke(dp(3), Color.rgb(255, 200, 140))
            slot.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160)
                .withEndAction {
                    slot.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    val help = scaffold == ScaffoldLevel.HINT1 || scaffold == ScaffoldLevel.HINT2_VISUAL
                    bg.setStroke(
                        dp(if (help) 4 else 2),
                        if (help) helpStroke else Color.argb(160, 180, 220, 255),
                    )
                }
                .start()
        }
    }

    private fun renderSlots() {
        slotViews.forEachIndexed { idx, slot ->
            val filledId = installedOrder.getOrNull(idx)
            if (filledId != null) {
                slot.setImageResource(drawableFor(filledId))
                slot.contentDescription = "Энергия установлена"
                slot.alpha = 1f
                (slot.background as? GradientDrawable)?.apply {
                    setColor(Color.argb(100, 40, 120, 110))
                    setStroke(dp(3), Color.rgb(120, 240, 220))
                }
            } else {
                slot.setImageResource(R.drawable.space_slot_empty)
                slot.contentDescription = "Пустое место для энергии"
                slot.alpha = 1f
                val helpStrokeOn = scaffold == ScaffoldLevel.HINT1 || scaffold == ScaffoldLevel.HINT2_VISUAL
                (slot.background as? GradientDrawable)?.apply {
                    setColor(Color.argb(60, 20, 30, 50))
                    setStroke(
                        dp(if (helpStrokeOn) 4 else 2),
                        if (helpStrokeOn) helpStroke else Color.argb(160, 180, 220, 255),
                    )
                }
            }
        }
    }

    private fun renderObjects(
        objects: List<SearchObject>,
        selectedIds: Set<String>,
        interactive: Boolean,
    ) {
        objectsRow.removeAllViews()
        objectViews.clear()
        objectFocusIds.clear()
        val tileSize = dp(80)
        objects.forEach { obj ->
            val installed = obj.id in installedOrder
            val highlighted = obj.id in highlightIds ||
                (scaffold == ScaffoldLevel.HINT2_VISUAL && obj.id in requiredIds)

            // Fixed-size shell prevents inflated a11y/hit bounds on first tile.
            val shell = FrameLayout(context).apply {
                layoutParams = LayoutParams(tileSize, tileSize).also { it.marginEnd = dp(8) }
                clipChildren = false
                clipToPadding = false
                isFocusable = false
                isClickable = false
            }

            val tileId = View.generateViewId()
            val tile = ImageView(context).apply {
                id = tileId
                setImageResource(drawableFor(obj.id))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = tileBackground(installed, highlighted, focused = false)
                isFocusable = interactive && !installed
                isFocusableInTouchMode = false
                isClickable = interactive && !installed
                isEnabled = !installed
                alpha = if (installed) 0.35f else 1f
                contentDescription = obj.label
                if (interactive && !installed) {
                    setOnClickListener { onPick?.onPick(obj.id) }
                    setOnFocusChangeListener { v, hasFocus ->
                        v.background = tileBackground(installed, highlighted, focused = hasFocus)
                        val scale = if (hasFocus) 1.18f else 1f
                        v.animate().cancel()
                        v.animate().scaleX(scale).scaleY(scale).setDuration(130).start()
                        v.elevation = if (hasFocus) dp(10).toFloat() else 0f
                        // Soft brighten without hiding the object art.
                        v.alpha = if (hasFocus) 1f else if (installed) 0.35f else 1f
                    }
                }
            }
            shell.addView(tile, FrameLayout.LayoutParams(tileSize, tileSize))
            objectsRow.addView(shell)
            objectViews[obj.id] = tile
            if (interactive && !installed) objectFocusIds.add(tileId)
        }
    }

    private fun tileBackground(installed: Boolean, highlighted: Boolean, focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(
                when {
                    installed -> Color.rgb(30, 70, 65)
                    focused -> Color.rgb(28, 72, 88)
                    highlighted -> Color.rgb(70, 90, 40)
                    else -> Color.rgb(36, 48, 72)
                },
            )
            when {
                focused -> setStroke(dp(6), focusStroke)
                highlighted -> setStroke(dp(4), helpStroke)
                else -> setStroke(dp(2), idleStroke)
            }
        }
    }

    private fun renderShipVisuals() {
        when (shipState) {
            ShipState.BROKEN -> {
                engineGlow.alpha = 0.05f
                shipImage.alpha = 0.92f
            }
            ShipState.PARTIAL -> {
                engineGlow.alpha = 0.25f + installedOrder.size * 0.15f
            }
            ShipState.POWERED, ShipState.ENGINE_ON, ShipState.LAUNCHING -> {
                engineGlow.alpha = 1f
                engineGlow.scaleX = 1.1f
                engineGlow.scaleY = 1.1f
            }
        }
    }

    companion object {
        fun drawableFor(id: String): Int = when (id) {
            "cell_a", "cell_b", "cell_c", "energy", "battery" -> R.drawable.space_energy_cell
            "rock", "stone" -> R.drawable.space_rock
            "wrench", "tool" -> R.drawable.space_wrench
            "star" -> R.drawable.space_star_obj
            else -> R.drawable.space_energy_cell
        }

        fun isSpaceLaunchChallenge(challengeId: String, sceneId: String): Boolean =
            challengeId == "ship-power" ||
                sceneId == "launch_repair" ||
                sceneId == "launch_search"
    }
}
