package ru.familytv.explore.stitch

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
import ru.familytv.interaction.*
import ru.familytv.ui.ReferenceStage
import ru.familytv.voice.VoiceRuntime

/** Isolated debug entry. Deliberately has no content launch, journey, history or voice lease. */
class StitchExploreWeatherPreviewActivity : ComponentActivity() {
    private var state = WeatherPreviewState()
    private lateinit var stage: ReferenceStage
    private var world: WeatherWorldView? = null
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
            if (!state.value.paused && art.isNotEmpty()) {
                val previous = state.value
                val oldControls = state.controls()
                state.tick(delta)
                ambientMs += delta
                update(previous, oldControls)
            }
            world?.ambientMs = ambientMs
            world?.invalidate()
            clock.postDelayed(this, 50)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        savedInstanceState?.let { bundle ->
            fun <T : Enum<T>> read(key: String, values: List<T>, fallback: T): T = values.find { it.name == bundle.getString(key) } ?: fallback
            state = WeatherPreviewState(WeatherSnapshot(
                screen = read("screen", WeatherScreen.entries, WeatherScreen.HOME), elapsedMs = bundle.getLong("elapsed"),
                hypothesis = IcePlace.entries.find { it.name == bundle.getString("hypothesis") },
                shelter = Shelter.entries.find { it.name == bundle.getString("shelter") },
                focus = bundle.getString("focus") ?: "destination:WEATHER",
                worldFocus = read("worldFocus", Destination.entries, Destination.WEATHER),
                placeFocus = read("placeFocus", IcePlace.entries, IcePlace.SUN),
                shelterFocus = read("shelterFocus", Shelter.entries, Shelter.CANOPY),
                paused = bundle.getBoolean("paused"), unavailable = bundle.getBoolean("unavailable"),
            ))
            ambientMs = bundle.getLong("ambient")
        }
        stage = ReferenceStage(this); setContentView(stage)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handle(FamilyAction.Back) }
        })
        lifecycleScope.launch {
            art = withContext(Dispatchers.IO) {
                listOf("explore_world", "weather_intro", "weather_comparison", "weather_build").associateWith { name ->
                    assets.open("explore_weather/$name.webp").use { requireNotNull(BitmapFactory.decodeStream(it)) }
                }
            }
            if (isDestroyed) return@launch
            render()
            // Offline narration is permitted; this does not acquire any microphone/model lease.
            narration = NarrationCoordinator(PlatformServices.narration(this@StitchExploreWeatherPreviewActivity), { ReadingSupportMode.AUDIO_FIRST })
            log("ready")
        }
    }
    private fun render() {
        if (art.isEmpty() || isDestroyed) return
        // A fresh stage avoids retaining placements from previous world states.
        controls.clear()
        stage = ReferenceStage(this).apply { routeKey = ::routeKey }
        world = WeatherWorldView(this, state, art).also { stage.place(it, 0f, 0f, 1920f, 1080f) }
        fun add(key: String, view: View, rect: RectF) {
            view.tag = key
            view.setOnFocusChangeListener { _, focused -> if (focused) { state.focus(key); world?.invalidate(); log("focus=$key") } }
            view.setOnClickListener { activate(key) }
            controls[key] = view
            stage.place(view, rect.left, rect.top, rect.width(), rect.height())
        }
        fun button(key: String, label: String, x: Float, y: Float, width: Float, primary: Boolean = false, height: Float = 84f) {
            if (key in state.controls()) add(key, WeatherButton(this, label, width, primary, height), RectF(x, y, x + width, y + height))
        }
        button("back", if (state.value.screen == WeatherScreen.HOME) "‹  Выйти из preview" else "‹  Назад", 88f, 72f, 400f, height = 72f)
        for (key in state.controls()) {
            val r: RectF? = when {
                key.startsWith("destination:") -> WeatherGeometry.home[Destination.valueOf(key.substringAfter(':')).ordinal]
                key == "place:SUN" -> WeatherGeometry.sun
                key == "place:SHADE" -> WeatherGeometry.shade
                key == "shelter:CANOPY" -> WeatherGeometry.canopy
                key == "shelter:LEAVES" -> WeatherGeometry.leaves
                key == EXPLANATION_FOCUS -> WeatherGeometry.explanation
                else -> null
            }
            if (r != null) {
                val description = when {
                    key.startsWith("destination:") -> Destination.valueOf(key.substringAfter(':')).let { "${it.label}. ${if (it.available) "Исследовать" else "Скоро, пока недоступно"}" }
                    key.startsWith("place:") -> if (key.endsWith("SUN")) "Положить лёд на солнце. Предположение" else "Положить лёд в тень. Предположение"
                    key == EXPLANATION_FOCUS -> "Луми объясняет: солнце, тепло, лёд, вода"
                    else -> "Поставить: ${Shelter.valueOf(key.substringAfter(':')).label}"
                }
                add(key, WeatherWorldTarget(this, description), r)
            }
        }
        val footerY = 920f
        button("start", "Начать опыт", 690f, footerY, 540f, true)
        button("speed", "Ускорить время", 680f, footerY, 560f, true)
        button("why", "Почему?", 630f, footerY, 520f, true)
        button("again", "Ещё один опыт", 1200f, footerY, 360f)
        button("build", "Спрятать лёд от солнца", 570f, footerY, 780f, true)
        button("check", "Проверить", 650f, footerY, 600f, true)
        button("change", if (state.value.screen == WeatherScreen.BUILD_RESULT) "Другое укрытие" else "Выбрать другое", if (state.value.screen == WeatherScreen.BUILD_RESULT) 585f else 1300f, footerY, 420f, state.value.screen == WeatherScreen.BUILD_RESULT)
        button("home", "В мир открытий", 1060f, footerY, 460f)
        button("repeat", "↻  Повторить", 112f, footerY + 7, 300f, height = 70f)
        setContentView(stage)
        val wanted = state.value.focus.takeIf { it in controls } ?: state.defaultFocus()
        stage.post { controls[wanted]?.requestFocus(); log("render size=${stage.width}x${stage.height} scale=${stage.sceneScale}") }
    }
    private fun update(previous: WeatherSnapshot, oldControls: List<String>) {
        val s = state.value
        if (previous.screen != s.screen || oldControls != state.controls()) render()
        else { controls[s.focus]?.requestFocus(); world?.invalidate() }
        if (previous.screen != s.screen) { speak(); log("enter") }
        if (s.screen == WeatherScreen.ASK && previous.cause != s.cause) {
            if (s.cause == Cause.ICE) speak()
            log("cause=${s.cause}")
        }
        if (s.screen == WeatherScreen.EXPERIMENT && previous.beat != s.beat) log("beat=${s.beat}")
    }
    private fun activate(key: String) {
        state.focus(key)
        handle(FamilyAction.Select)
    }
    private fun handle(action: FamilyAction): Boolean {
        if (art.isEmpty()) return true
        val old = state.value; val oldControls = state.controls()
        if (action == FamilyAction.Back && old.screen == WeatherScreen.HOME) { finish(); return true }
        if (old.paused && action == FamilyAction.Select) state.resume() else if (!state.semantic(action)) return false
        update(old, oldControls)
        if (action == FamilyAction.Repeat || action == FamilyAction.Help || action == FamilyAction.Select && old.focus == "repeat" || state.value.unavailable) speak()
        if (action == FamilyAction.Pause) narration?.stop()
        log("action=$action")
        return true
    }
    private fun speak() {
        narration?.present(PresentationMessage(state.value.prompt()))
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
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        if (select) dispatcher.dispatch(InputSource.REMOTE, FamilyAction.Select) else {
            state.move(requireNotNull(direction)); controls[state.value.focus]?.requestFocus()
        }
        return true
    }
    private fun log(event: String) {
        val s = state.value
        Log.i("StitchWeather", "$event screen=${s.screen} elapsed=${s.elapsedMs} hypothesis=${s.hypothesis} cause=${s.cause} beat=${s.beat} shelter=${s.shelter} canCheck=${s.canCheck} canBuild=${s.canBuild} focus=${s.focus} paused=${s.paused} mood=${s.mood} controls=${state.controls().joinToString(",")} AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
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
        val s = state.checkpoint()
        outState.putString("screen", s.screen.name); outState.putLong("elapsed", s.elapsedMs)
        outState.putString("hypothesis", s.hypothesis?.name); outState.putString("shelter", s.shelter?.name)
        outState.putString("focus", s.focus); outState.putString("worldFocus", s.worldFocus.name)
        outState.putString("placeFocus", s.placeFocus.name); outState.putString("shelterFocus", s.shelterFocus.name)
        outState.putBoolean("paused", s.paused); outState.putBoolean("unavailable", s.unavailable); outState.putLong("ambient", ambientMs)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { clock.removeCallbacks(ticker); dispatcher.detach(actionHandler); narration?.stop(); super.onDestroy() }
}
