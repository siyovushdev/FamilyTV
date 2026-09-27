package ru.familytv.creative.stitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.familytv.content.PlatformServices
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.interaction.InputSource
import ru.familytv.interaction.NarrationCoordinator
import ru.familytv.interaction.PresentationMessage
import ru.familytv.ui.ReferenceStage
import ru.familytv.voice.VoiceRuntime

/**
 * Debug-only native preview of the approved Create / Story Builder V1 design.
 * No History, Journey, StoryStore, trial or settings writes; no microphone, STT or model lease.
 */
class StitchStoryBuilderPreviewActivity : ComponentActivity() {
    private lateinit var domain: StoryPreviewDomain
    private lateinit var state: StoryPreviewState
    private lateinit var stage: ReferenceStage
    private var world: StoryWorldView? = null
    private var art = emptyMap<String, Bitmap>()
    private val controls = linkedMapOf<String, View>()
    private val clock = Handler(Looper.getMainLooper())
    private var resumed = false
    private var lastTick = 0L
    private var ambientMs = 0L
    private var narration: NarrationCoordinator? = null
    private val dispatcher by lazy { PlatformServices.actionDispatcher() }
    private val actionHandler = FamilyActionHandler { _, action -> handle(action) }
    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            val now = SystemClock.uptimeMillis()
            val delta = (now - lastTick).coerceIn(0, 250)
            lastTick = now
            if (art.isNotEmpty()) {
                val previous = state.value
                val oldControls = state.controls()
                state.tick(delta)
                ambientMs += delta
                update(previous, oldControls)
            }
            world?.ambientMs = ambientMs
            world?.invalidate()
            clock.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        domain = StoryPreviewDomain(assets.open("$ASSETS/story.json").bufferedReader().use { it.readText() })
        val restored = savedInstanceState?.let { b -> StorySnapshot.decode(b.keySet().filter { it.startsWith(KEY) }.associate { it.removePrefix(KEY) to b.getString(it) }) }
        state = StoryPreviewState(domain, restored ?: StorySnapshot())
        ambientMs = savedInstanceState?.getLong("ambient") ?: 0L
        stage = ReferenceStage(this); setContentView(stage)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handle(FamilyAction.Back) }
        })
        lifecycleScope.launch {
            art = withContext(Dispatchers.IO) {
                ART.associateWith { name -> assets.open("$ASSETS/$name.jpg").use { requireNotNull(BitmapFactory.decodeStream(it)) } }
            }
            if (isDestroyed) return@launch
            render()
            // Shared offline narration only; this never acquires a microphone or model lease.
            narration = PlatformServices.narrationCoordinator(this@StitchStoryBuilderPreviewActivity)
            log("ready")
            speak()
        }
    }

    private fun render() {
        if (art.isEmpty() || isDestroyed) return
        controls.clear()
        stage = ReferenceStage(this).apply { routeKey = ::routeKey }
        world = StoryWorldView(this, state, domain, art).also { it.ambientMs = ambientMs; stage.place(it, 0f, 0f, 1920f, 1080f) }
        fun add(key: String, view: View, rect: RectF) {
            view.setOnFocusChangeListener { _, focused -> if (focused && state.value.focus != key) { state.focus(key); world?.invalidate(); log("focus") } }
            view.setOnClickListener { state.focus(key); handle(FamilyAction.Select) }
            controls[key] = view
            stage.place(view, rect.left, rect.top, rect.width(), rect.height())
        }
        val s = state.value
        val ending = s.screen == StoryScreen.ENDING
        add("back", StoryButton(this, if (ending) "В мастерскую" else "Назад", if (ending) 380f else 290f, 76f, false, Glyph.BACK), RectF(88f, 72f, 88f + if (ending) 380f else 290f, 148f))
        for (key in state.controls()) {
            val rect = StoryGeometry.target(key)
            if (rect != null) add(key, StoryTarget(this, description(key)), rect)
        }
        if ("inside" in state.controls()) add("inside", StoryButton(this, "Заглянуть внутрь", 560f, 88f, true, Glyph.ARROW), RectF(680f, 900f, 1240f, 988f))
        if (ending) {
            val y = StoryGeometry.endingCard.top + 92
            add("replay", StoryButton(this, "Посмотреть ещё раз", 430f, 88f, true, Glyph.PLAY), RectF(360f, y, 790f, y + 88))
            add("save", StoryButton(this, if (s.saved) "В наших историях" else "В наши истории", 390f, 88f, false, if (s.saved) Glyph.HEART_FILLED else Glyph.HEART), RectF(810f, y, 1200f, y + 88))
            add("new", StoryButton(this, "Новая история", 340f, 88f, false, Glyph.SPARKLE), RectF(1220f, y, 1560f, y + 88))
        }
        val wanted = state.value.focus.takeIf { it in controls } ?: state.defaultFocus()
        setContentView(stage)
        stage.post { state.focus(wanted); controls[wanted]?.requestFocus(); log("render size=${stage.width}x${stage.height} scale=${stage.sceneScale}") }
    }

    private fun description(key: String): String {
        val name = key.substringAfter(':')
        val label = when {
            key.startsWith("place:") -> HomePlace.valueOf(name).label
            key.startsWith("hero:") -> StoryHero.valueOf(name).label
            key.startsWith("world:") -> StoryWorld.valueOf(name).label
            key.startsWith("object:") -> StoryObject.valueOf(name).label
            key.startsWith("action:") -> StoryAction.valueOf(name).label
            state.value.screen == StoryScreen.REPLAY -> "Наша история"
            else -> "Дверь открывается"
        }
        return if (state.isAvailable(key)) label else "$label. Скоро"
    }

    private fun update(previous: StorySnapshot, oldControls: List<String>) {
        val s = state.value
        val rebuild = previous.screen != s.screen || oldControls != state.controls() || previous.saved != s.saved
        if (rebuild) render() else if (controls[s.focus]?.isFocused == false) controls[s.focus]?.requestFocus()
        val consequenceSpeaks = s.screen == StoryScreen.CONSEQUENCE && previous.elapsedMs < StoryRules.NARRATE_AT_MS && s.elapsedMs >= StoryRules.NARRATE_AT_MS
        val replayBeat = s.screen == StoryScreen.REPLAY && previous.screen == StoryScreen.REPLAY && previous.replayBeat != s.replayBeat
        val revealed = s.revealing && !previous.revealing
        if (previous.screen != s.screen || consequenceSpeaks || replayBeat || revealed || (s.soon != null && s.soon != previous.soon) || s.saved != previous.saved) speak()
        if (previous.screen != s.screen) log("enter")
        if (consequenceSpeaks) log("consequence_narration")
        if (replayBeat) log("replay_beat")
        if (revealed) log("object_revealing")
        if (s.canContinue && !previous.canContinue) log("consequence_ready")
        if (s.saved && !previous.saved) {
            val draft = domain.draft(s, System.currentTimeMillis())
            log("save_preview_only persisted=false title=${draft?.title} selections=${draft?.selections}")
        }
    }

    private fun handle(action: FamilyAction): Boolean {
        if (art.isEmpty()) return true
        val old = state.value; val oldControls = state.controls()
        if (action == FamilyAction.Home) { narration?.stop(); finish(); return true }
        val handled = state.semantic(action)
        if (state.exitRequested) { narration?.stop(); log("exit"); finish(); return true }
        update(old, oldControls)
        if (action == FamilyAction.Repeat || action == FamilyAction.Help) speak()
        if (action == FamilyAction.Pause) narration?.stop()
        log("action=$action handled=$handled")
        return true
    }

    private fun speak() {
        val prompt = state.prompt()
        if (prompt.isBlank()) narration?.stop() else narration?.present(PresentationMessage(prompt))
    }

    private fun routeKey(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Direction.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Direction.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Direction.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Direction.DOWN
            else -> null
        }
        val select = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (direction == null && !select) return false
        if (art.isEmpty() || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        if (select) dispatcher.dispatch(InputSource.REMOTE, FamilyAction.Select) else {
            val old = state.value
            state.move(requireNotNull(direction)); controls[state.value.focus]?.requestFocus()
            if (old.focus != state.value.focus) { world?.invalidate(); log("focus") }
        }
        return true
    }

    private fun log(event: String) {
        val s = state.value
        Log.i(TAG, "$event screen=${s.screen} focus=${s.focus} hero=${s.hero} world=${s.world} object=${s.item} action=${s.action} elapsed=${s.elapsedMs} revealing=${s.revealing} canContinue=${s.canContinue} replayBeat=${s.replayBeat} paused=${s.paused} soon=${s.soon} saved=${s.saved} mood=${s.mood} controls=${state.controls().joinToString(",")} AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
    }

    override fun onResume() {
        super.onResume(); resumed = true; state.resume(); dispatcher.attach(actionHandler)
        lastTick = SystemClock.uptimeMillis(); clock.removeCallbacks(ticker); clock.post(ticker)
        if (art.isNotEmpty()) { controls[state.value.focus]?.requestFocus(); log("resume") }
    }

    override fun onPause() {
        resumed = false; clock.removeCallbacks(ticker); state.pause(); narration?.stop(); dispatcher.detach(actionHandler); log("pause")
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        state.value.encode().forEach { (k, v) -> outState.putString(KEY + k, v) }
        outState.putLong("ambient", ambientMs)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        clock.removeCallbacks(ticker); dispatcher.detach(actionHandler); narration?.stop(); super.onDestroy()
    }

    companion object {
        private const val TAG = "StitchStory"
        private const val ASSETS = "create_story_v1"
        private const val KEY = "story."
        val ART = listOf("create_home", "choose_hero", "choose_world", "forest_door", "consequence", "ending")
    }
}
