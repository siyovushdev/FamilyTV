package ru.familytv.move.stitch

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
import java.util.Locale

/**
 * Debug-only Move V1 preview. No body tracking, no sensing, no microphone, no production stores.
 */
class StitchMovePreviewActivity : ComponentActivity() {
    private lateinit var state: MovePreviewState
    private lateinit var stage: ReferenceStage
    private var world: MoveWorldView? = null
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
                if (!state.value.paused) ambientMs += delta
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
        val restored = savedInstanceState?.let { b ->
            MoveSnapshot.decode(b.keySet().filter { it.startsWith(KEY) }.associate { it.removePrefix(KEY) to b.getString(it) })
        }
        state = MovePreviewState(restored ?: MoveSnapshot())
        ambientMs = savedInstanceState?.getLong("ambient") ?: 0L
        stage = ReferenceStage(this); setContentView(stage)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handle(FamilyAction.Back) }
        })
        lifecycleScope.launch {
            art = withContext(Dispatchers.IO) {
                ART.associateWith { name -> assets.open("$ART_DIR/$name.jpg").use { requireNotNull(BitmapFactory.decodeStream(it)) } }
            }
            if (isDestroyed) return@launch
            render()
            narration = PlatformServices.narrationCoordinator(this@StitchMovePreviewActivity)
            log("ready")
            speak()
        }
    }

    private fun render() {
        if (art.isEmpty() || isDestroyed) return
        controls.clear()
        stage = ReferenceStage(this).apply { routeKey = ::routeKey }
        world = MoveWorldView(this, state, art).also { it.ambientMs = ambientMs; stage.place(it, 0f, 0f, 1920f, 1080f) }
        fun add(key: String, view: View, rect: RectF) {
            view.setOnFocusChangeListener { _, focused -> if (focused && state.value.focus != key) { state.focus(key); world?.invalidate(); log("focus") } }
            view.setOnClickListener { state.focus(key); handle(FamilyAction.Select) }
            controls[key] = view
            stage.place(view, rect.left, rect.top, rect.width(), rect.height())
        }
        val s = state.value
        if ("back" in state.controls()) {
            val label = if (s.screen == MoveScreen.ENDING) "В мир движения" else "Назад"
            add("back", MoveButton(this, label, if (s.screen == MoveScreen.ENDING) 400f else 280f, 76f, false, MoveGlyph.BACK), RectF(88f, 72f, 88f + if (s.screen == MoveScreen.ENDING) 400f else 280f, 148f))
        }
        if ("pause" in state.controls()) add("pause", MoveButton(this, "Пауза", 260f, 76f, false, MoveGlyph.PAUSE), MoveGeometry.pause)
        if ("start" in state.controls() && s.screen == MoveScreen.READY) add("start", MoveButton(this, "Мы готовы! Начинаем", 620f, 96f, true, MoveGlyph.PLAY), MoveGeometry.readyStart)
        if ("start" in state.controls() && s.screen == MoveScreen.MOVE_HOME) add("start", MoveButton(this, "Начать «Разбудим звёзды»", 680f, 104f, true, MoveGlyph.PLAY), MoveGeometry.start)
        if (s.paused) {
            add("continue", MoveButton(this, "Продолжить", 360f, 96f, true, MoveGlyph.PLAY), MoveGeometry.continueBtn)
            add("finish", MoveButton(this, "Закончить", 360f, 96f, false, MoveGlyph.HOME), MoveGeometry.finishBtn)
        }
        if (s.actionsReady) {
            add("replay", MoveButton(this, "Повторить приключение", 400f, 96f, true, MoveGlyph.PLAY), MoveGeometry.replay)
            add("world", MoveButton(this, "В мир «Двигаться»", 420f, 96f, false, MoveGlyph.NONE), MoveGeometry.world)
            add("home", MoveButton(this, "На главный экран", 400f, 96f, false, MoveGlyph.HOME), MoveGeometry.home)
        }
        for (key in state.controls()) {
            if (key in controls) continue
            val rect = MoveGeometry.target(key) ?: continue
            add(key, MoveTarget(this, description(key)), rect)
        }
        val wanted = state.value.focus.takeIf { it in controls } ?: state.defaultFocus()
        setContentView(stage)
        stage.post { state.focus(wanted); controls[wanted]?.requestFocus(); log("render size=${stage.width}x${stage.height} scale=${stage.sceneScale}") }
    }

    private fun description(key: String): String {
        if (key.startsWith("place:")) {
            val place = MovePlace.valueOf(key.substringAfter(':'))
            return if (place.available) place.label else "${place.label}. Скоро"
        }
        return when (key) {
            "start" -> "Начать"
            "pause" -> "Пауза"
            "continue" -> "Продолжить"
            "finish" -> "Закончить"
            "replay" -> "Повторить приключение"
            "world" -> "В мир Двигаться"
            "home" -> "На главный экран"
            else -> "Приключение"
        }
    }

    private fun update(previous: MoveSnapshot, oldControls: List<String>) {
        val s = state.value
        val rebuild = previous.screen != s.screen || previous.phase != s.phase || previous.paused != s.paused || previous.actionsReady != s.actionsReady || oldControls != state.controls()
        if (rebuild) render() else if (controls[s.focus]?.isFocused == false) controls[s.focus]?.requestFocus()
        if (previous.screen != s.screen || previous.phase != s.phase || (s.soon != null && s.soon != previous.soon) || s.paused != previous.paused || s.helping && !previous.helping) speak()
        if (previous.screen != s.screen) log("enter")
        if (previous.phase != s.phase) log("phase")
    }

    private fun handle(action: FamilyAction): Boolean {
        if (art.isEmpty()) return true
        val old = state.value; val oldControls = state.controls()
        val handled = state.semantic(action)
        if (state.exitRequested) { narration?.stop(); log("exit"); finish(); return true }
        update(old, oldControls)
        if (action == FamilyAction.Repeat || action == FamilyAction.Help) speak()
        if (action == FamilyAction.Pause && state.value.paused) narration?.stop()
        log("action=$action handled=$handled")
        return true
    }

    private fun speak() {
        val prompt = state.prompt()
        if (prompt.isBlank()) narration?.stop() else narration?.present(PresentationMessage(prompt))
    }

    private fun routeKey(event: KeyEvent): Boolean {
        val pauseKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        val resumeKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
        val repeatKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || event.keyCode == KeyEvent.KEYCODE_F2
        val helpKey = event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> MoveDirection.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> MoveDirection.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> MoveDirection.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> MoveDirection.DOWN
            else -> null
        }
        val select = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (direction == null && !select && !pauseKey && !resumeKey && !repeatKey && !helpKey) return false
        if (art.isEmpty() || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        when {
            pauseKey -> dispatcher.dispatch(InputSource.REMOTE, if (state.value.paused) FamilyAction.Resume else FamilyAction.Pause)
            resumeKey -> dispatcher.dispatch(InputSource.REMOTE, FamilyAction.Resume)
            repeatKey -> dispatcher.dispatch(InputSource.REMOTE, FamilyAction.Repeat)
            helpKey -> dispatcher.dispatch(InputSource.REMOTE, FamilyAction.Help)
            select -> dispatcher.dispatch(InputSource.REMOTE, FamilyAction.Select)
            else -> {
                val old = state.value
                state.move(requireNotNull(direction)); controls[state.value.focus]?.requestFocus()
                if (old.focus != state.value.focus) { world?.invalidate(); log("focus") }
            }
        }
        return true
    }

    private fun log(event: String) {
        val s = state.value
        fun n(v: Float) = String.format(Locale.US, "%.2f", v)
        Log.i(TAG, "$event screen=${s.screen} phase=${s.phase} focus=${s.focus} elapsed=${s.elapsedMs} paused=${s.paused} soon=${s.soon} help=${s.helpMs} pose=${s.pose} stars=${n(s.starsGlow)} path=${n(s.pathOpen)} still=${n(s.worldStill)} bridge=${n(s.bridge)} actions=${s.actionsReady} controls=${state.controls().joinToString(",")} AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
    }

    override fun onResume() {
        super.onResume(); resumed = true; dispatcher.attach(actionHandler)
        lastTick = SystemClock.uptimeMillis(); clock.removeCallbacks(ticker); clock.post(ticker)
        if (art.isNotEmpty()) { controls[state.value.focus]?.requestFocus(); log("resume") }
    }

    override fun onPause() {
        resumed = false; clock.removeCallbacks(ticker); narration?.stop(); dispatcher.detach(actionHandler); log("lifecycle_pause")
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        state.value.encode().forEach { (k, v) -> outState.putString(KEY + k, v) }
        outState.putLong("ambient", ambientMs)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        clock.removeCallbacks(ticker); dispatcher.detach(actionHandler); narration?.stop()
        art.values.forEach { it.recycle() }; art = emptyMap(); world = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "StitchMove"
        private const val KEY = "move."
        const val ART_DIR = "move_v1"
        val ART = listOf("move_home", "ready", "reach", "comets", "freeze", "balance", "ending")
    }
}
