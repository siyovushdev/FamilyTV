package ru.familytv.games.world

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.familytv.content.PlatformServices
import ru.familytv.games.CountCombineCatalog
import ru.familytv.games.CountCombineDefinition
import ru.familytv.games.CountCombineHub
import ru.familytv.games.CountCombineLoad
import ru.familytv.games.GameColor
import ru.familytv.games.GameKind
import ru.familytv.parent.ActivityKind
import ru.familytv.home.HomeActivity
import ru.familytv.interaction.*
import ru.familytv.ui.ReferenceStage
import ru.familytv.voice.VoiceRuntime

/** Approved world/action/consequence presentation. Never acquires a voice/model lease. */
open class GamesActivity : ComponentActivity() {
    protected open val recordsActivity = true
    protected open val initialGame: GameKind? = null
    private val visit = GameVisit()
    private val state = GamesState()
    private lateinit var stage: ReferenceStage
    private lateinit var chrome: GamesChrome
    private var worldView: GamesWorldView? = null
    private val controls = linkedMapOf<String, View>()
    private var artBitmaps: Map<String, Bitmap> = emptyMap()
    private var focused = "game:0"
    private var motion: ValueAnimator? = null
    private var countJob: Job? = null
    private var reactionAnimator: ValueAnimator? = null
    private var busy = false
    private var loading = true
    private var blocked = false
    private var mathSets: List<CountCombineDefinition> = emptyList()
    private var narration: NarrationCoordinator? = null
    private val dispatcher by lazy { PlatformServices.actionDispatcher() }
    private val handler = FamilyActionHandler { _, action ->
        val command = GamesActions.resolve(state, action, busy || loading)
        when (command) {
            GamesCommand.Back -> back()
            GamesCommand.Home -> leaveHome()
            GamesCommand.Help -> help()
            GamesCommand.Repeat -> listen()
            GamesCommand.Select -> controls[focused]?.performClick()
            GamesCommand.Next -> next()
            GamesCommand.Pause -> { stopTransientActions(); narration?.stop(); render(focused) }
            GamesCommand.Resume -> render(focused)
            is GamesCommand.Number -> answerMath(command.value)
            is GamesCommand.Paint -> paint(command.value)
            null -> Unit
        }
        command != null
    }
    private val focusObserver = ViewTreeObserver.OnGlobalFocusChangeListener { _, view ->
        val key = view?.tag as? String
        if (key != null && controls[key] === view) {
            focused = key
            if (key.startsWith("game:")) state.gameFocus = key.substringAfter(':').toInt()
            if (key.startsWith("choice:") && state.world == GameWorld.MATH) {
                state.mathFocus = key.substringAfter(':').toInt()
            }
            if (key.startsWith("choice:") && state.world == GameWorld.COLOR) {
                state.paintChoice = state.paints[key.substringAfter(':').toInt()]
                worldView?.selectedPaint = state.paintChoice; worldView?.invalidate()
            }
            log("focus=$key")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val repo = PlatformServices.contentRepository(this)
        mathSets = CountCombineCatalog.playable(repo)
        val savedWorld = savedInstanceState?.getString("world")
        val requestedId = when {
            initialGame == GameKind.MATH -> intent.getStringExtra(EXTRA_CONTENT_ID)?.takeIf { it.isNotBlank() }
                ?: CountCombineHub.APPLES_CONTENT_ID
            savedWorld == GameWorld.MATH.name -> savedInstanceState?.getString("mathContentId")
            else -> null
        }
        if (requestedId != null) {
            when (val loaded = CountCombineCatalog.load(repo, requestedId)) {
                is CountCombineLoad.Ready -> state.bindMath(loaded.definition)
                is CountCombineLoad.Rejected -> {
                    blocked = initialGame == GameKind.MATH
                    Log.w("FamilyGames", "count_combine rejected content=$requestedId reason=${loaded.reason}")
                }
            }
        }
        if (savedInstanceState == null && initialGame != null && !blocked) {
            if (initialGame == GameKind.MATH && state.hasMath) state.enter(GameWorld.MATH)
            else if (initialGame == GameKind.COLOR) state.enter(GameWorld.COLOR)
            focused = state.defaultFocus()
        }
        savedInstanceState?.let {
            val restoredWorld = enumValueOr(it.getString("world"), GameWorld.HOME)
            val mathRestore = restoredWorld == GameWorld.MATH && state.hasMath
            state.world = if (restoredWorld == GameWorld.MATH && !state.hasMath) GameWorld.HOME else restoredWorld
            state.feedback = enumValueOr(it.getString("feedback"), GameFeedback.READY)
            state.gameFocus = it.getInt("gameFocus").coerceIn(0, 3)
            if (mathRestore) {
                state.mathRound = it.getInt("round").coerceIn(state.rounds.indices)
                state.combined = it.getBoolean("combined")
                state.mathChoice = if (it.containsKey("mathChoice")) it.getInt("mathChoice") else null
                state.mathFocus = it.getInt("mathFocus", 0).coerceIn(0, 2)
            }
            state.painted = GameColor.entries.find { color -> color.name == it.getString("painted") }
            state.paintChoice = enumValueOr(it.getString("paintChoice"), GameColor.BLUE)
            state.launched = it.getBoolean("launched")
            focused = it.getString("focus") ?: state.defaultFocus()
            visit.world = enumValueOr(it.getString("visitWorld"), GameWorld.HOME)
            visit.startedAt = it.getLong("visitStarted")
        }
        beginVisit()
        stage = ReferenceStage(this).apply { routeKey = ::routeKey }
        setContentView(stage)
        stage.viewTreeObserver.addOnGlobalFocusChangeListener(focusObserver)
        chrome = GamesChrome(this, state).apply { notice = "Открываем игровые миры…" }
        stage.place(chrome, 0f, 0f, 1920f, 1080f)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        lifecycleScope.launch {
            artBitmaps = withContext(Dispatchers.IO) {
                listOf("orchard_background", "workshop_background", "math_card").associateWith { name ->
                    this@GamesActivity.assets.open("games_v2/$name.webp").use { requireNotNull(BitmapFactory.decodeStream(it)) }
                }
            }
            loading = false
            render(focused)
            // The shared provider initializes TTS asynchronously. Warm it without speaking so
            // the child's first Repeat can speak too; this never acquires a voice/model lease.
            PlatformServices.narration(this@GamesActivity)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T = enumValues<T>().find { it.name == value } ?: fallback

    private fun add(key: String, view: View, x: Float, y: Float, w: Float, h: Float, click: () -> Unit) {
        // A TV can return from Settings in touch mode. Keep our explicit initial D-pad focus.
        view.isFocusableInTouchMode = view.isFocusable
        view.tag = key; controls[key] = view
        view.setOnClickListener { if (!busy) click() }
        stage.place(view, x, y, w, h)
    }
    private fun render(wanted: String) {
        if (loading || isDestroyed) return
        stopReaction()
        stage.removeAllViews(); controls.clear(); worldView = null
        chrome = GamesChrome(this, state)
        if (blocked) chrome.notice = "Это занятие не открылось."
        stage.place(chrome, 0f, 0f, 1920f, 1080f)
        add("back", GamesButton(this, "‹  Назад", 180f), 88f, 72f, 180f, 64f, ::back)
        if (blocked) {
            val target = wanted.takeIf { controls[it]?.isFocusable == true } ?: "back"
            stage.post { if (!isDestroyed && controls[target] != null) focus(target) }
            log("render")
            return
        }
        if (state.world == GameWorld.HOME) {
            for (i in 0..3) add("game:$i", GamesHomeCard(this, i, artBitmaps["math_card"]), 84f + i * 441, 253f, 410f, 570f) {
                if (i == 0) openMath(CountCombineHub.openFromHub(mathSets, "card:0"), "Набор яблок не открылся.")
                else if (i == 1) {
                    state.enter(GameWorld.COLOR)
                    beginVisit()
                    render("choice:1")
                } else { chrome.notice = "Этот мир скоро откроется. Поиграем с яблоками или ракетой!"; chrome.invalidate() }
            }
            val extras = CountCombineHub.extras(mathSets)
            extras.forEachIndexed { index, extra ->
                val key = if (index == 0) "more" else "more:$index"
                val (x, width) = extraButtonFrame(extras.size, index)
                add(key, GamesButton(this, extra.title, width), x, 868f, width, 64f) {
                    openMath(CountCombineHub.openFromHub(mathSets, key), "Этот набор не открылся.")
                }
            }
        } else {
            val math = state.world == GameWorld.MATH
            worldView = GamesWorldView(this, state, artBitmaps[if (math) "orchard_background" else "workshop_background"]).also { world ->
                if (math && !state.combined) add("world", world, 460f, 174f, 1000f, 520f, ::combine)
                else stage.place(world, if (math) 460f else 270f, 174f, if (math) 1000f else 1380f, if (math) 520f else 550f)
            }
            if (math && state.answersVisible) stage.place(AppleEquation(this, state), 650f, 709f, 620f, 68f)
            add("listen", GamesButton(this, "♪  Повторить", 250f), 160f, 852f, 250f, 64f, ::listen)
            if (state.feedback == GameFeedback.CORRECT) {
                val label = when { math && state.mathRound == state.rounds.lastIndex -> "К играм  →"; math -> "Следующее задание  →"; state.launched -> "К играм  →"; else -> "Запустить ракету!  ↑" }
                add("next", GamesButton(this, label, 550f, 100f, true), 685f, 814f, 550f, 100f, ::next)
            } else if (!math || state.answersVisible) {
                for (i in 0..2) add("choice:$i", GamesChoice(this, state, i).apply {
                    isFocusable = !state.counting
                    isEnabled = !state.counting
                }, 574f + i * 264, 789f, 244f, 170f) { choose(i) }
                add("help", GamesButton(this, "?  Подсказка", 250f), 1510f, 852f, 250f, 64f, ::help)
            }
        }
        val target = wanted.takeIf { controls[it]?.isFocusable == true } ?: defaultFocus()
        stage.post { if (!isDestroyed && controls[target] != null) focus(target); log("render size=${stage.width}x${stage.height} scale=${stage.sceneScale}") }
        log("render")
    }
    private fun openMath(definition: CountCombineDefinition?, unavailable: String) {
        if (definition == null) {
            chrome.notice = unavailable
            chrome.invalidate()
            return
        }
        state.bindMath(definition)
        state.enter(GameWorld.MATH)
        beginVisit()
        render("world")
    }
    /** One extra stays centered. Further extras share the same row, left to right. */
    private fun extraButtonFrame(count: Int, index: Int): Pair<Float, Float> {
        if (count <= 1) return 750f to 420f
        val width = 400f
        val gap = 40f
        val start = (1920f - (count * width + (count - 1) * gap)) / 2f
        return start + index * (width + gap) to width
    }
    private fun defaultFocus(): String = state.defaultFocus()
    private fun focus(key: String) { controls[key]?.requestFocus() }

    private fun animate(duration: Long, update: (Float) -> Unit, done: () -> Unit) {
        cancelMotion(); busy = true
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { update(it.animatedValue as Float); worldView?.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { motion = null; busy = false; done(); log("motion_end") }
            })
            start()
        }
    }
    private fun cancelMotion() { motion?.removeAllListeners(); motion?.cancel(); motion = null; busy = false }
    private fun showReaction(frame: LumiReactionFrame) {
        worldView?.reaction = frame; worldView?.invalidate()
        chrome.reaction = frame; chrome.invalidate()
    }
    private fun stopReaction() {
        reactionAnimator?.removeAllListeners(); reactionAnimator?.cancel(); reactionAnimator = null
        if (::chrome.isInitialized) showReaction(LumiReactionFrame())
    }
    private fun react(kind: LumiReaction, duration: Long = if (kind == LumiReaction.WRONG) 1150 else 1700) {
        stopReaction()
        log("lumi=$kind start")
        reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { showReaction(LumiReactionFrame(kind, it.animatedValue as Float)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { reactionAnimator = null; showReaction(LumiReactionFrame()); log("lumi=$kind end") }
            })
            start()
        }
    }
    private fun stopTransientActions() {
        countJob?.cancel(); countJob = null
        cancelMotion(); stopReaction(); state.stopTransientActions()
    }
    private fun combine() {
        if (!state.beginCombine()) return
        log("combine_start")
        animate(1200, { worldView?.combineFraction = it }) {
            state.finishCombine()
            render("choice:0")
        }
    }
    private fun choose(index: Int) {
        if (state.world == GameWorld.MATH) answerMath(state.answers[index])
        else if (state.world == GameWorld.COLOR) paint(state.paints[index])
    }
    private fun answerMath(value: Int) {
        if (state.answerMath(value) == null) return
        render(if (state.feedback == GameFeedback.CORRECT) "next" else "choice:${state.mathFocus}")
        react(if (state.feedback == GameFeedback.CORRECT) LumiReaction.SUCCESS else LumiReaction.WRONG)
        log("answer=$value")
    }
    private fun paint(value: GameColor) {
        val old = state.painted
        if (state.paint(value) == null) return
        worldView?.previousPaint = old
        animate(1050, { worldView?.paintFraction = it }) {
            render(if (state.feedback == GameFeedback.CORRECT) "next" else defaultFocus())
            react(if (state.feedback == GameFeedback.CORRECT) LumiReaction.SUCCESS else LumiReaction.WRONG)
        }
        log("paint=$value")
    }
    private fun help() {
        if (busy || state.world == GameWorld.HOME || state.feedback == GameFeedback.CORRECT) return
        if (!state.help()) return
        val math = state.world == GameWorld.MATH
        if (math) countApples() else { render("choice:1"); react(LumiReaction.HELP) }
    }
    private fun countApples() {
        // Help never moves focus to an answer. It stays on Help, including after the count.
        render("help"); worldView?.counted = 0; busy = true
        val n = state.apples.left + state.apples.right
        val spoken = state.countingNarration()
        val coordinator = narration ?: PlatformServices.narrationCoordinator(this).also { narration = it }
        coordinator.present(PresentationMessage(state.prompt(), spokenText = spoken, role = PresentationRole.HINT))
        log("count_narration=$spoken")
        countJob = lifecycleScope.launch {
            react(LumiReaction.HELP, 900)
            delay(900)
            // Counting beats are content: keep each number readable even at 0.25x system animations.
            for (i in 1..n) {
                worldView?.counted = i
                react(LumiReaction.HELP, 850)
                log("count=$i")
                delay(850)
            }
            animate(650, { worldView?.countAlpha = 1f - it }) {
                worldView?.counted = 0
                state.finishCounting()
                controls.filterKeys { it.startsWith("choice:") }.values.forEach { it.isFocusable = true; it.isEnabled = true }
                worldView?.invalidate()
                log("count_end")
            }
        }
    }
    private fun next() {
        when (state.world) {
            GameWorld.MATH -> {
                state.nextMath()
                if (state.world == GameWorld.HOME) {
                    endVisit("finite_math")
                    if (initialGame != null) { finish(); return }
                }
                render(defaultFocus())
            }
            GameWorld.COLOR -> if (state.launched) back() else if (state.launch()) {
                react(LumiReaction.LAUNCH, 1900)
                animate(1900, { worldView?.launchFraction = it }) { render("next") }
            }
            GameWorld.HOME -> Unit
        }
    }
    private fun listen() {
        if (busy) return
        val coordinator = narration ?: PlatformServices.narrationCoordinator(this).also { narration = it }
        coordinator.present(PresentationMessage(state.prompt()))
        log("repeat narration_requested")
    }
    private fun back() {
        stopTransientActions(); narration?.stop()
        if (state.world == GameWorld.HOME) finish()
        else {
            endVisit(if (state.launched) "rocket_finished" else "back")
            // Catalog/session callers keep their existing Activity return contract.
            if (initialGame != null) { finish(); return }
            state.backToGames()
            render(defaultFocus())
        }
    }
    private fun leaveHome() {
        stopTransientActions(); narration?.stop(); endVisit("home")
        if (recordsActivity) startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
    private fun beginVisit() {
        if (!recordsActivity || state.world == GameWorld.HOME || visit.world != GameWorld.HOME) return
        if (state.world == GameWorld.MATH && !state.hasMath) return
        val identity = visitIdentity(state.world, state.mathSet)
        visit.begin(state.world, System.currentTimeMillis(), identity.contentId, identity.title, identity.topics)
        val contentId = identity.contentId
        PlatformServices.journeyCoordinator(this).onContentOpened(contentId)
        log("visit_started content=$contentId")
    }
    private fun endVisit(reason: String) {
        if (!recordsActivity) return
        val end = visit.finish(System.currentTimeMillis()) ?: return
        PlatformServices.activityHistory(this).record(
            kind = ActivityKind.GAME, title = end.title, contentId = end.contentId,
            topics = end.topics, durationMs = end.durationMs,
        )
        // Preserve BaseVoiceGameActivity's established explicit-exit completion policy.
        // Merely pausing/recreating does not complete or record a visit.
        PlatformServices.journeyCoordinator(this).onContentCompleted(end.contentId)
        log("visit_finished content=${end.contentId} durationMs=${end.durationMs} reason=$reason history=GAME journey=completed")
    }
    private fun routeKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        val supported = key in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        if (!supported) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        if (busy || loading) return true
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { dispatcher.dispatch(InputSource.REMOTE, FamilyAction.Select); return true }
        val up = key == KeyEvent.KEYCODE_DPAD_UP; val down = key == KeyEvent.KEYCODE_DPAD_DOWN
        val left = key == KeyEvent.KEYCODE_DPAD_LEFT; val right = key == KeyEvent.KEYCODE_DPAD_RIGHT
        val target = when {
            focused == "back" -> if (down) defaultFocus() else "back"
            state.world == GameWorld.HOME && (focused == "more" || focused.startsWith("more:")) -> {
                val keys = controls.keys.filter { it == "more" || it.startsWith("more:") }.toList()
                val index = keys.indexOf(focused).coerceAtLeast(0)
                when {
                    up -> "game:${state.gameFocus}"
                    left && index > 0 -> keys[index - 1]
                    right && index < keys.lastIndex -> keys[index + 1]
                    else -> focused
                }
            }
            state.world == GameWorld.HOME -> when {
                down && controls.containsKey("more") -> "more"
                up -> "back"
                else -> "game:${(state.gameFocus + if (left) -1 else if (right) 1 else 0).coerceIn(0, 3)}"
            }
            focused == "world" -> if (up) "back" else if (left) "listen" else "world"
            focused == "help" -> when { up -> "back"; state.world == GameWorld.MATH -> "choice:${state.mathFocus}"; else -> "choice:1" }
            focused == "listen" -> when { controls.containsKey("world") -> "world"; up -> "back"; else -> if (controls.containsKey("next")) "next" else "choice:0" }
            focused == "next" -> if (up) "back" else if (left || down) "listen" else "next"
            focused.startsWith("choice:") -> {
                val i = focused.substringAfter(':').toInt()
                when { up -> "help"; down -> "listen"; left -> if (i == 0) "listen" else "choice:${i - 1}"; else -> if (i == 2) "help" else "choice:${i + 1}" }
            }
            else -> defaultFocus()
        }
        focus(target); return true
    }
    private fun log(event: String) {
        val content = when (state.world) {
            GameWorld.MATH -> state.mathContentId ?: "none"
            GameWorld.COLOR -> "game-color"
            GameWorld.HOME -> "none"
        }
        Log.i(if (recordsActivity) "FamilyGames" else "StitchGames", "$event world=${state.world} feedback=${state.feedback} round=${state.mathRound} combined=${state.combined} combining=${state.combining} counting=${state.counting} equation=${if (state.answersVisible) state.equationResult else "hidden"} choices=${controls.count { it.key.startsWith("choice:") && it.value.isFocusable && it.value.isEnabled }} focusKey=$focused mathChoice=${state.mathChoice} painted=${state.painted} launched=${state.launched} content=$content AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
    }

    override fun onResume() {
        super.onResume(); dispatcher.attach(handler)
        if (!loading) render(focused)
    }
    override fun onPause() { stopTransientActions(); narration?.stop(); dispatcher.detach(handler); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("visitWorld", visit.world.name)
        outState.putLong("visitStarted", visit.startedAt)
        outState.putString("world", state.world.name); outState.putString("feedback", state.feedback.name)
        outState.putInt("gameFocus", state.gameFocus); outState.putInt("round", state.mathRound)
        outState.putBoolean("combined", state.combined)
        state.mathChoice?.let { outState.putInt("mathChoice", it) }
        outState.putInt("mathFocus", state.mathFocus)
        outState.putString("painted", state.painted?.name); outState.putString("paintChoice", state.paintChoice.name)
        outState.putBoolean("launched", state.launched); outState.putString("focus", focused)
        state.mathContentId?.let { outState.putString("mathContentId", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (isFinishing) endVisit("finish")
        stopTransientActions(); dispatcher.detach(handler)
        if (stage.viewTreeObserver.isAlive) stage.viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONTENT_ID = "contentId"
    }
}
