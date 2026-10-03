package ru.familytv.space

import android.content.Intent
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
import ru.familytv.adventure.AdventureParser
import ru.familytv.adventure.AdventureRuntime
import ru.familytv.adventure.mechanics.MechanicSnapshot
import ru.familytv.content.PlatformServices
import ru.familytv.home.ContentNavigator
import ru.familytv.home.HomeActivity
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.interaction.InputSource
import ru.familytv.interaction.NarrationCoordinator
import ru.familytv.interaction.PresentationMessage
import ru.familytv.journey.DiscoveryJournalActivity
import ru.familytv.journey.JourneyEngine
import ru.familytv.parent.ActivityKind
import ru.familytv.trial.TrialEventStore
import ru.familytv.ui.ReferenceStage
import ru.familytv.voice.VoiceRuntime

/**
 * Production Space Expedition V1. Hosts the real journey + launch adventure.
 * Shared offline narration only — no microphone, STT or model lease.
 */
class SpaceExpeditionActivity : ComponentActivity() {
    private lateinit var state: SpaceExpeditionState
    private lateinit var stage: ReferenceStage
    private lateinit var world: SpaceWorldView
    private var art = emptyMap<String, Bitmap>()
    private val controls = linkedMapOf<String, View>()
    private val clock = Handler(Looper.getMainLooper())
    private var resumed = false
    private var lastTick = 0L
    private var ambientMs = 0L
    private var openedAt = 0L
    private var visitCompleted = false
    private var mapOpened = false
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
            if (::world.isInitialized) {
                world.ambientMs = ambientMs
                world.invalidate()
            }
            clock.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val journeyId = intent.getStringExtra(SpaceRoutes.EXTRA_JOURNEY_ID) ?: SpaceRoutes.JOURNEY_ID
        val coord = PlatformServices.journeyCoordinator(this)
        val pair = coord.progressFor(journeyId)
        if (pair == null) {
            finish()
            return
        }
        val (journey, progress) = pair
        val repo = PlatformServices.contentRepository(this)
        val age = PlatformServices.familySettings(this).contentFilter()
        val views = JourneyEngine.views(journey, progress, { id -> repo.getItem(id) != null }, age.ageMin, age.ageMax)
        val item = repo.getItem(SpaceRoutes.CONTENT_ID)
        val adventure = item?.let { repo.openAdventureJson(it) }?.let { runCatching { AdventureParser.parse(it) }.getOrNull() }
        val resumeScene = adventure?.let {
            PlatformServices.adventureProgress(this).resumeSceneId(it.id, it.scenes.map { s -> s.id }.toSet())
        }
        state = SpaceExpeditionState(
            journey, progress, adventure, resumeScene, views,
            JourneyEngine.resumeNodeId(journey, progress),
        )
        savedInstanceState?.let { restore(it, adventure) }
        openedAt = savedInstanceState?.getLong("openedAt") ?: 0L
        visitCompleted = savedInstanceState?.getBoolean("visitCompleted") ?: false
        mapOpened = savedInstanceState?.getBoolean("mapOpened") ?: false
        if (savedInstanceState?.getBoolean("visitOpened") == true) state.restoreOpenedVisit()
        ambientMs = savedInstanceState?.getLong("ambient") ?: 0L
        stage = ReferenceStage(this).apply { routeKey = ::routeKey }
        world = SpaceWorldView(this, state)
        stage.place(world, 0f, 0f, 1920f, 1080f)
        setContentView(stage)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handle(FamilyAction.Back) }
        })
        lifecycleScope.launch {
            art = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inScaled = false
                }
                val plates = SpaceRoutes.BACKGROUNDS.mapValues { (_, file) ->
                    assets.open("${SpaceRoutes.ART_DIR}/$file").use { stream ->
                        requireNotNull(BitmapFactory.decodeStream(stream, null, opts))
                    }
                }
                val sort = linkedMapOf<String, Bitmap>()
                assets.list(SpaceRoutes.SORT_ART_DIR)?.forEach { file ->
                    if (!file.endsWith(".jpg") && !file.endsWith(".png")) return@forEach
                    val key = file.substringBeforeLast('.')
                    assets.open("${SpaceRoutes.SORT_ART_DIR}/$file").use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)?.let { sort[key] = it }
                    }
                }
                plates + sort
            }
            if (isDestroyed) return@launch
            world.art = art
            render()
            narration = PlatformServices.narrationCoordinator(this@SpaceExpeditionActivity)
            log("ready")
            speak()
        }
    }

    private fun restore(bundle: Bundle, adventure: ru.familytv.adventure.Adventure?) {
        val screen = SpaceScreen.entries.find { it.name == bundle.getString("screen") } ?: SpaceScreen.ENTRY
        val shown = bundle.getStringArrayList("shown")?.toSet().orEmpty()
        val mechanic = decodeMechanic(bundle)
        if (adventure != null && screen != SpaceScreen.ENTRY && screen != SpaceScreen.JOURNEY && screen != SpaceScreen.HUB) {
            val scene = bundle.getString("sceneId")
            state.attachRuntime(AdventureRuntime(adventure, startSceneId = scene))
        }
        state.restoreUi(
            screen,
            bundle.getString("focus") ?: "continue",
            bundle.getBoolean("paused"),
            bundle.getBoolean("helping"),
            shown,
            bundle.getString("sortHold"),
            mechanic,
        )
    }

    private fun render() {
        if (isDestroyed || !::world.isInitialized) return
        controls.clear()
        stage.clearExcept(world)
        val s = state.value
        val wanted = s.focus.takeIf { it in state.controls() } ?: state.defaultFocus()
        fun add(key: String, view: View, rect: RectF) {
            view.setOnFocusChangeListener { _, focused ->
                if (focused && state.value.focus != key) {
                    state.focus(key)
                    world.invalidate()
                    log("focus")
                }
            }
            view.setOnClickListener { state.focus(key); handle(FamilyAction.Select) }
            controls[key] = view
            stage.place(view, rect.left, rect.top, rect.width(), rect.height())
        }
        for (key in state.controls()) {
            if (s.screen == SpaceScreen.CHALLENGE && s.mechanicType == "PATTERN_SEQUENCE" && !s.helping && key == "back") continue
            val rect = SpaceGeometry.rect(key, s) ?: continue
            add(key, SpaceTarget(this, description(key)), rect)
        }
        world.ambientMs = ambientMs
        world.invalidate()
        stage.post {
            state.focus(wanted)
            controls[wanted]?.requestFocus()
            log("render size=${stage.width}x${stage.height} scale=${stage.sceneScale}")
        }
    }

    private fun description(key: String): String {
        val s = state.value
        return when {
            key == "home" || key == "back" -> "Домой"
            key == "continue" && s.screen == SpaceScreen.ENTRY -> if (s.started) "Продолжить экспедицию" else "Начать экспедицию"
            key == "continue" && s.screen == SpaceScreen.JOURNEY -> "Продолжить: ${s.currentLabel}"
            key == "continue" -> "Дальше"
            key == "journey" -> "Карта маршрута"
            key == "journal" -> "Наши открытия"
            key == "pause" -> "Пауза"
            key == "help" -> "Подсказка"
            key == "check" -> "Проверить"
            key == "finish" -> "Закончить"
            key.startsWith("node:") -> s.waypoints.find { it.nodeId == key.removePrefix("node:") }?.spoken ?: "Этап"
            key.startsWith("branch:") -> s.branches.find { it.id == key.removePrefix("branch:") }?.spoken ?: "Путь"
            else -> s.choices.find { it.key == key }?.label
                ?: s.categories.find { it.key == key }?.label
                ?: "Выбор"
        }
    }

    private fun update(previous: SpaceSnapshot, oldControls: List<String>) {
        val s = state.value
        flushHost()
        val rebuild = previous.screen != s.screen || previous.paused != s.paused || previous.helping != s.helping ||
            previous.choices.map { it.key } != s.choices.map { it.key } || oldControls != state.controls() ||
            previous.waypoints.map { it.visual } != s.waypoints.map { it.visual } ||
            previous.worldReacted != s.worldReacted
        if (rebuild) render() else {
            if (controls[s.focus]?.isFocused == false) controls[s.focus]?.requestFocus()
            world.invalidate()
        }
        if (previous.screen != s.screen || previous.sceneId != s.sceneId || s.paused != previous.paused ||
            (s.helping && !previous.helping) || (s.wrongMs > 0 && previous.wrongMs == 0L)
        ) speak()
        if (previous.screen != s.screen) log("enter")
        persistAdventure()
    }

    private fun handle(action: FamilyAction): Boolean {
        if (art.isEmpty()) return true
        if (action == FamilyAction.Repeat) {
            narration?.repeat() ?: speak()
            log("action=$action")
            return true
        }
        val old = state.value
        val oldControls = state.controls()
        if (!state.semantic(action)) return false
        update(old, oldControls)
        if (action == FamilyAction.Pause) narration?.stop()
        log("action=$action")
        return true
    }

    private fun flushHost() {
        val coord = PlatformServices.journeyCoordinator(this)
        if (!mapOpened) {
            mapOpened = true
            coord.ensureStarted(state.journey.id)
            PlatformServices.trialEvents(this).record("JOURNEY_MAP_OPENED", state.journey.id)
            PlatformServices.trialEvents(this).record(TrialEventStore.JOURNEY_RESUMED, state.journey.id)
            log("journey_opened")
        }
        state.takeOpened()?.let {
            if (it) {
                coord.onContentOpened(SpaceRoutes.CONTENT_ID, state.journey.id)
                openedAt = System.currentTimeMillis()
                log("adventure_opened")
            }
        }
        state.takeCompleted()?.let {
            if (it && !visitCompleted) {
                visitCompleted = true
                val item = PlatformServices.contentRepository(this).getItem(SpaceRoutes.CONTENT_ID)
                PlatformServices.adventureEvents(this).record(SpaceRoutes.ADVENTURE_ID, "adventure_completed")
                PlatformServices.trialEvents(this).record(TrialEventStore.ADVENTURE_COMPLETE, SpaceRoutes.CONTENT_ID)
                PlatformServices.adventureProgress(this).save(
                    SpaceRoutes.ADVENTURE_ID, state.value.sceneId ?: "finale", completed = true,
                    item?.packageId, item?.packageVersion,
                )
                coord.onContentCompleted(SpaceRoutes.CONTENT_ID, state.journey.id)
                PlatformServices.activityHistory(this).record(
                    kind = ActivityKind.INTERACTIVE,
                    title = item?.title ?: SpaceRoutes.FALLBACK_TITLE,
                    contentId = SpaceRoutes.CONTENT_ID,
                    topics = SpaceRoutes.HISTORY_TOPICS,
                    durationMs = (System.currentTimeMillis() - openedAt).coerceAtLeast(0L),
                )
                log("adventure_completed")
                rebind()
            }
        }
        state.takeBranch()?.let { choice ->
            coord.onBranchChosen(state.journey.id, choice.fromNodeId, choice.branchId)
            log("branch=${choice.branchId}")
            rebind()
            state.semantic(FamilyAction.OpenJourneyMap(state.journey.id))
        }
        state.takeManual()?.let { nodeId ->
            coord.markManualNode(state.journey.id, nodeId)
            log("manual=$nodeId")
            rebind()
        }
        state.takeExternal()?.let { contentId ->
            coord.onContentOpened(contentId, state.journey.id)
            val item = PlatformServices.contentRepository(this).getItem(contentId) ?: return@let
            log("open_external=$contentId")
            ContentNavigator.open(this, item)
        }
        if (state.takeJournal()) {
            startActivity(
                Intent(this, DiscoveryJournalActivity::class.java)
                    .putExtra(DiscoveryJournalActivity.EXTRA_JOURNEY_ID, state.journey.id),
            )
        }
        if (state.takeHome()) leaveHome()
        if (state.takeFinish()) {
            narration?.stop()
            persistAdventure()
            finish()
        }
    }

    private fun rebind() {
        val coord = PlatformServices.journeyCoordinator(this)
        val pair = coord.progressFor(state.journey.id) ?: return
        val repo = PlatformServices.contentRepository(this)
        val age = PlatformServices.familySettings(this).contentFilter()
        val views = JourneyEngine.views(pair.first, pair.second, { id -> repo.getItem(id) != null }, age.ageMin, age.ageMax)
        state.bind(pair.second, views, JourneyEngine.resumeNodeId(pair.first, pair.second))
    }

    private fun persistAdventure() {
        val rt = state.runtime() ?: return
        val snap = rt.snapshot()
        val item = PlatformServices.contentRepository(this).getItem(SpaceRoutes.CONTENT_ID)
        PlatformServices.adventureProgress(this).save(
            rt.adventure.id, snap.sceneId,
            completed = snap.phase == ru.familytv.adventure.AdventurePhase.COMPLETED,
            item?.packageId, item?.packageVersion,
        )
    }

    private fun leaveHome() {
        narration?.stop()
        persistAdventure()
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun speak() {
        val text = state.prompt()
        if (text.isBlank()) return
        narration?.present(PresentationMessage(text = text, spokenText = text))
    }

    private fun routeKey(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> SpacePad.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> SpacePad.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> SpacePad.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> SpacePad.DOWN
            else -> null
        }
        val select = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (direction == null && !select) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        if (select) dispatcher.dispatch(InputSource.REMOTE, FamilyAction.Select)
        else {
            state.move(requireNotNull(direction))
            controls[state.value.focus]?.requestFocus()
            world.invalidate()
            log("focus")
        }
        return true
    }

    private fun log(event: String) {
        val s = state.value
        Log.i(
            "FamilySpace",
            "$event screen=${s.screen} focus=${s.focus} node=${s.resumeNodeId} scene=${s.sceneId} " +
                "mechanic=${s.mechanicType} helping=${s.helping} paused=${s.paused} reacted=${s.worldReacted} " +
                "controls=${state.controls().joinToString(",")} AI=${if (VoiceRuntime.peek() == null) 0 else 1}",
        )
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        dispatcher.attach(actionHandler)
        lastTick = SystemClock.uptimeMillis()
        clock.removeCallbacks(ticker)
        clock.post(ticker)
        rebind()
        if (art.isNotEmpty()) {
            controls[state.value.focus]?.requestFocus()
            log("resume")
        }
    }

    override fun onPause() {
        resumed = false
        clock.removeCallbacks(ticker)
        persistAdventure()
        narration?.stop()
        dispatcher.detach(actionHandler)
        log("pause")
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val s = state.value
        outState.putString("screen", s.screen.name)
        outState.putString("focus", s.focus)
        outState.putBoolean("paused", s.paused)
        outState.putBoolean("helping", s.helping)
        outState.putString("sceneId", s.sceneId)
        outState.putString("sortHold", state.sortHold())
        outState.putStringArrayList("shown", ArrayList(state.shownIntros()))
        outState.putLong("ambient", ambientMs)
        outState.putLong("openedAt", openedAt)
        outState.putBoolean("visitCompleted", visitCompleted)
        outState.putBoolean("mapOpened", mapOpened)
        outState.putBoolean("visitOpened", state.visitWasOpened())
        encodeMechanic(outState, state.runtime()?.snapshot()?.mechanic)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        clock.removeCallbacks(ticker)
        dispatcher.detach(actionHandler)
        narration?.stop()
        super.onDestroy()
    }

    companion object {
        private fun encodeMechanic(bundle: Bundle, mechanic: MechanicSnapshot?) {
            if (mechanic == null) return
            bundle.putString("m.type", mechanic.type.name)
            bundle.putStringArrayList("m.selected", ArrayList(mechanic.selectedIds))
            bundle.putString("m.option", mechanic.chosenOption)
            bundle.putString("m.path", mechanic.path.joinToString(";"))
            bundle.putString("m.cursor", mechanic.cursor?.toString())
            bundle.putString("m.place", mechanic.placements.entries.joinToString(";") { "${it.key}:${it.value}" })
        }

        private fun decodeMechanic(bundle: Bundle): MechanicSnapshot? {
            val typeName = bundle.getString("m.type") ?: return null
            val type = runCatching { ru.familytv.adventure.MechanicType.valueOf(typeName) }.getOrNull() ?: return null
            val path = bundle.getString("m.path").orEmpty().split(';').filter { it.isNotBlank() }
                .mapNotNull { runCatching { ru.familytv.adventure.GridPos.parse(it) }.getOrNull() }
            val cursor = bundle.getString("m.cursor")?.let { runCatching { ru.familytv.adventure.GridPos.parse(it) }.getOrNull() }
            val placements = bundle.getString("m.place").orEmpty().split(';').filter { it.contains(':') }
                .associate { it.substringBefore(':') to it.substringAfter(':') }
            return MechanicSnapshot(
                type = type,
                selectedIds = bundle.getStringArrayList("m.selected")?.toSet().orEmpty(),
                chosenOption = bundle.getString("m.option"),
                path = path,
                cursor = cursor,
                placements = placements,
            )
        }
    }
}
