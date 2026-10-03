package ru.familytv.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.familytv.R
import ru.familytv.adventure.GuideEmotion
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.content.PlatformServices
import ru.familytv.creative.StoryRoutes
import ru.familytv.explore.ExploreDestination
import ru.familytv.explore.ExploreRoutes
import ru.familytv.home.watch.WatchActivity
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.interaction.InputSource
import ru.familytv.interaction.InteractionSurface
import ru.familytv.interaction.PresentationImportance
import ru.familytv.interaction.PresentationMessage
import ru.familytv.interaction.PresentationRole
import ru.familytv.interaction.ReadingSupportMode
import ru.familytv.interaction.RouteContext
import ru.familytv.interaction.RouteResult
import ru.familytv.interaction.VoiceCommandRouter
import ru.familytv.journey.DiscoveryJournalActivity
import ru.familytv.journey.JourneyMapActivity
import ru.familytv.move.MoveRoutes
import ru.familytv.space.SpaceRoutes
import ru.familytv.narration.CommonVoicePack
import ru.familytv.parent.ParentModeActivity
import ru.familytv.parent.ScreenTimeReminder
import ru.familytv.trial.TrialEventStore
import ru.familytv.ui.FamilyTvTheme
import ru.familytv.ui.LumiView
import ru.familytv.voice.VoiceRuntime

/**
 * Production Home C / Visual Playground / Cosmic Hearth V2.
 * Five child worlds open the same production destinations as FamilyAction.OpenWorld.
 * Debug preview subclasses this without changing routes.
 */
open class HomeActivity : ComponentActivity() {
    private lateinit var stage: HomeStage
    private lateinit var backdrop: HomeBackdrop
    private lateinit var lumi: LumiView
    private lateinit var worlds: List<HomeWorldView>
    private lateinit var voice: HomeControl
    private lateinit var journal: HomeControl
    private lateinit var parent: HomeControl
    private lateinit var watchContinue: HomeControl
    private lateinit var mission: HomeMissionView
    private lateinit var reminderLabel: TextView
    private val cardViews = linkedMapOf<String, View>()
    private var content: HomeWorldContent? = null
    private var lastWorld = 0
    private var requestedFocus = "watch"
    private var dataJob: Job? = null
    private var idleAnimator: ObjectAnimator? = null
    private var hasRestoredFocus = false
    private var startedAt = 0L
    private var lastReminder: String? = null
    private var focusedSpeakLabel: String? = null
    private var lastSpokenFocusId: String? = null
    private var lastSpokenAt = 0L
    private val speakHandler = Handler(Looper.getMainLooper())
    private var pendingSpeak: Runnable? = null
    private val reminderHandler = Handler(Looper.getMainLooper())
    private val reminderTick = object : Runnable {
        override fun run() {
            updateReminder()
            reminderHandler.postDelayed(this, 30_000L)
        }
    }
    private val homeActionHandler = FamilyActionHandler { _, action -> handleHomeAction(action) }

    override fun onCreate(savedInstanceState: Bundle?) {
        startedAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        lastWorld = savedInstanceState?.getInt("lastWorld", 0)?.coerceIn(0, 4) ?: worldIndex(prefsFocus()) ?: 0
        requestedFocus = savedInstanceState?.getString("focus") ?: prefsFocus() ?: "watch"
        hideSystemBars()
        PlatformServices.screenTime(this).onAppForeground()
        PlatformServices.actionDispatcher().attach(homeActionHandler)
        stage = HomeStage(this)
        stage.routeKey = ::routeKey
        backdrop = HomeBackdrop(this)
        stage.place(backdrop, 0f, 0f, 1920f, 1080f)
        stage.place(LumiView(this).apply {
            emotion = GuideEmotion.CURIOUS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, 76f, 80f, 76f, 80f)
        reminderLabel = TextView(this).apply {
            setTextColor(Color.rgb(253, 224, 171))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.place(reminderLabel, 164f, 148f, 900f, 36f)
        voice = HomeControl(this, "«Луми, давай играть!»", R.drawable.stitch_c_mic, 305f).apply {
            tag = "voice"
            contentDescription = "Голос. Подсказка. Голос не включается сам"
            setOnClickListener { showVoiceHint() }
        }
        journal = HomeControl(this, "Открытия", R.drawable.ic_world_stories, 200f).apply {
            tag = "journal"
            contentDescription = "Наши открытия"
            setOnClickListener { openJournal() }
        }
        parent = HomeControl(this, "Родителям", R.drawable.stitch_c_parent, 209f).apply {
            tag = "parent"
            contentDescription = "Режим родителя. Нужен PIN"
            setOnClickListener { startActivity(Intent(this@HomeActivity, ParentModeActivity::class.java)) }
        }
        stage.place(journal, 1088f, 88f, 200f, 64f)
        stage.place(voice, 1306f, 88f, 305f, 64f)
        stage.place(parent, 1631f, 88f, 209f, 64f)
        worlds = HomeWorld.ALL.indices.map { index ->
            HomeWorldView(this, index).apply {
                val id = WORLD_FOCUS[index]
                tag = id
                onFocused = {
                    lastWorld = index
                    backdrop.selectedWorld = index
                    rememberFocus(id)
                    if (index == 2) {
                        getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "explore").apply()
                    }
                    moveLumi()
                    lumi.emotion = if (index == 2 || index == 0) GuideEmotion.CURIOUS else GuideEmotion.HAPPY
                    scheduleWorldSpeak(id, WORLD_SECTION[index], HomeWorld.ALL[index].title)
                    Log.i(TAG, "focus=$id lastWorld=$lastWorld AI=${aiFlag()}")
                }
                setOnClickListener { openWorld(index) }
                stage.place(this, 40f + 357.5f * index, 275f, 410f, 436f)
            }
        }
        val watch = worlds[0]
        val games = worlds[1]
        val explore = worlds[2]
        cardViews["watch"] = watch
        cardViews["games"] = games
        cardViews["explore"] = explore
        cardViews["create"] = worlds[3]
        cardViews[StoryRoutes.CONTENT_ID] = worlds[3]
        cardViews["move"] = worlds[4]
        cardViews[MoveRoutes.CONTENT_ID] = worlds[4]
        WORLD_FOCUS.forEachIndexed { i, id -> cardViews[id] = worlds[i] }
        lumi = LumiView(this).apply {
            emotion = GuideEmotion.HAPPY
            contentDescription = "Луми рядом с выбранным островом"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            translationZ = 16f
        }
        stage.place(lumi, 691f, 182f, 128f, 136f)
        mission = HomeMissionView(this).apply {
            tag = "mission"
            setOnClickListener { openMission() }
            setOnFocusChangeListener { _, focused -> if (focused) rememberFocus("mission") }
        }
        stage.place(mission, 64f, 798f, 1792f, 162f)
        watchContinue = HomeControl(this, "Досмотреть", R.drawable.stitch_c_watch, 256f).apply {
            tag = "continue"
            visibility = View.GONE
            setOnClickListener {
                content?.continueVideo?.let { item ->
                    rememberFocus("watch")
                    startActivity(Intent(this@HomeActivity, WatchActivity::class.java)
                        .putExtra("screen", "details").putExtra("contentId", item.id))
                }
            }
        }
        stage.place(watchContinue, 1268f, 847f, 256f, 64f)
        setContentView(stage)
        stage.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused != null && hasRestoredFocus) {
                requestedFocus = focused.tag as? String ?: requestedFocus
                Log.i(TAG, "focus=$requestedFocus lastWorld=$lastWorld AI=${aiFlag()}")
            }
        }
        stage.post {
            if (!hasRestoredFocus) {
                restoreFocus()
                hasRestoredFocus = true
            }
            moveLumi()
            Log.i(TAG, "scene=${stage.width}x${stage.height} scale=${stage.sceneScale} cold_onCreate_ms=${SystemClock.elapsedRealtime() - startedAt} AI=${aiFlag()}")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        PlatformServices.actionDispatcher().attach(homeActionHandler)
        PlatformServices.screenTime(this).onAppForeground()
        updateReminder()
        reminderHandler.removeCallbacks(reminderTick)
        reminderHandler.postDelayed(reminderTick, 30_000L)
        dataJob?.cancel()
        dataJob = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { HomeWorldContent.load(this@HomeActivity) }
                content = loaded
                worlds.forEachIndexed { i, v -> v.representative = loaded.labels[i] }
                mission.data = loaded
                watchContinue.visibility = if (loaded.continueVideo != null) View.VISIBLE else View.GONE
                watchContinue.contentDescription = loaded.continueVideo?.let { "Досмотреть: ${it.title}" }
                if (requestedFocus == "continue") {
                    if (loaded.continueVideo != null) watchContinue.requestFocus() else mission.requestFocus()
                }
                Log.i(TAG, "catalog_bound_ms=${SystemClock.elapsedRealtime() - startedAt} labels=${loaded.labels} journey=${loaded.journeyId} continue=${loaded.continueVideo?.id} emptyMission=${loaded.emptyMission} AI=${aiFlag()}")
                reportFullyDrawn()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Catalog unavailable", e)
                worlds.forEach { it.representative = "Пока нет занятий" }
            }
        }
        stage.post {
            idleAnimator?.cancel()
            idleAnimator = ObjectAnimator.ofFloat(lumi, View.TRANSLATION_Y, 0f, -6f * stage.sceneScale).apply {
                duration = 2400L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
            restoreFocus()
        }
        consumeHomeInject(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeHomeInject(intent)
    }

    private fun consumeHomeInject(intent: Intent?) {
        val t = intent?.getStringExtra("inject_transcript")?.trim().orEmpty()
        if (t.isBlank()) return
        intent?.removeExtra("inject_transcript")
        Log.i(TAG, "inject=$t")
        val catalog = PlatformServices.contentRepository(this).getEnabledItems()
        when (
            val routed = VoiceCommandRouter.route(
                RouteContext(surface = InteractionSurface.HOME, catalog = catalog),
                t.lowercase(),
            )
        ) {
            is RouteResult.Action -> handleHomeAction(routed.action)
            RouteResult.Unhandled -> Toast.makeText(this, "Не расслышал: $t", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        reminderHandler.removeCallbacks(reminderTick)
        idleAnimator?.cancel()
        lumi.animate().cancel()
        worlds.forEach { it.settle() }
        dataJob?.cancel()
        PlatformServices.actionDispatcher().detach(homeActionHandler)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("lastWorld", lastWorld)
        outState.putString("focus", requestedFocus)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun restoreFocus() {
        val target = cardViews[requestedFocus]
            ?: cardViews[prefsFocus()]
            ?: when (requestedFocus) {
                "voice" -> voice
                "journal" -> journal
                "parent" -> parent
                "mission", "continue" -> mission
                else -> worlds[lastWorld]
            }
        target.requestFocus()
    }

    private fun prefsFocus(): String? = getSharedPreferences("familytv_home", MODE_PRIVATE).getString("lastFocusId", null)

    private fun rememberFocus(id: String) {
        requestedFocus = id
        getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", id).apply()
    }

    private fun moveLumi() {
        if (!::lumi.isInitialized) return
        val edgeOffset = when (lastWorld) { 0 -> 28f; 4 -> -48f; else -> 0f }
        lumi.animate().translationX(((lastWorld - 1) * 357.5f + edgeOffset) * stage.sceneScale)
            .setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun routeKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (event.action == KeyEvent.ACTION_UP) showVoiceHint()
            return true
        }
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -2
            KeyEvent.KEYCODE_DPAD_DOWN -> 2
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val focused = currentFocus
        val world = worlds.indexOfFirst { it === focused }
        val target = when {
            world >= 0 && direction == -1 -> worlds[(world - 1).coerceAtLeast(0)]
            world >= 0 && direction == 1 -> worlds[(world + 1).coerceAtMost(4)]
            world >= 0 && direction == -2 -> when {
                world <= 1 -> voice
                world == 2 -> journal
                else -> parent
            }
            world >= 0 && direction == 2 -> mission
            focused === journal && direction == 1 -> voice
            focused === journal && direction == -1 -> journal
            focused === voice && direction == -1 -> journal
            focused === voice && direction == 1 -> parent
            focused === parent && direction == -1 -> voice
            (focused === voice || focused === parent || focused === journal) && direction == 2 -> worlds[lastWorld]
            (focused === mission || focused === watchContinue) && direction == -2 -> worlds[lastWorld]
            focused === mission && direction == -1 && watchContinue.visibility == View.VISIBLE -> watchContinue
            focused === watchContinue && direction == 1 -> mission
            focused == null -> worlds[lastWorld]
            else -> focused
        }
        target?.requestFocus()
        return true
    }

    private fun openWorld(index: Int) {
        when (index) {
            0 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld("watch"))
            1 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld("play"))
            2 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(ExploreRoutes.WORLD_ID))
            3 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(StoryRoutes.WORLD_ID))
            4 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(MoveRoutes.WORLD_ID))
            else -> return
        }
    }

    private fun openMission() {
        val d = content ?: return
        rememberFocus("mission")
        when {
            d.journeyId != null && SpaceRoutes.handlesJourney(d.journeyId) ->
                startActivity(SpaceRoutes.intent(this, d.journeyId))
            d.journeyId != null -> startActivity(Intent(this, JourneyMapActivity::class.java)
                .putExtra(JourneyMapActivity.EXTRA_JOURNEY_ID, d.journeyId))
            d.continueVideo != null -> startActivity(Intent(this, WatchActivity::class.java)
                .putExtra("screen", "details").putExtra("contentId", d.continueVideo.id))
            d.missionItem != null -> openItem(d.missionItem, preferDetails = true)
            else -> worlds[lastWorld].requestFocus()
        }
    }

    private fun openJourneyIntent(journeyId: String): Intent =
        if (SpaceRoutes.handlesJourney(journeyId)) SpaceRoutes.intent(this, journeyId)
        else Intent(this, JourneyMapActivity::class.java)
            .putExtra(JourneyMapActivity.EXTRA_JOURNEY_ID, journeyId)

    private fun openJournal(stories: Boolean = false) {
        rememberFocus("journal")
        startActivity(Intent(this, DiscoveryJournalActivity::class.java).apply {
            if (stories) putExtra("focus", "stories")
        })
    }

    private fun showVoiceHint() {
        Toast.makeText(this, "Выбери остров стрелками и нажми OK. Голос доступен в занятиях.", Toast.LENGTH_LONG).show()
        Log.i(TAG, "voice_control_only AI=${aiFlag()} runtimePresent=${VoiceRuntime.peek() != null}")
    }

    private fun updateReminder() {
        if (!::reminderLabel.isInitialized) return
        when (val r = PlatformServices.screenTime(this).reminder()) {
            is ScreenTimeReminder.Warn -> {
                reminderLabel.text = r.message
                lastReminder = r.message
            }
            is ScreenTimeReminder.Rest -> {
                reminderLabel.text = r.message
                lastReminder = r.message
            }
            null -> {
                reminderLabel.text = ""
                lastReminder = null
            }
        }
    }

    private fun scheduleWorldSpeak(focusId: String, sectionId: String, title: String) {
        val mode = PlatformServices.familySettings(this).readingSupportMode()
        if (mode != ReadingSupportMode.AUDIO_FIRST) {
            focusedSpeakLabel = title
            return
        }
        pendingSpeak?.let { speakHandler.removeCallbacks(it) }
        val key = CommonVoicePack.spokenLabelForWorld(sectionId)
        val spoken = when {
            key != null -> FamilyTvTheme.worldForSection(sectionId).title
            else -> title
        }
        focusedSpeakLabel = spoken
        val run = Runnable {
            val now = System.currentTimeMillis()
            if (focusId == lastSpokenFocusId && now - lastSpokenAt < 2500) return@Runnable
            if (now - lastSpokenAt < 700) return@Runnable
            lastSpokenFocusId = focusId
            lastSpokenAt = now
            lumi.emotion = GuideEmotion.LISTENING
            val narration = PlatformServices.narrationCoordinator(this)
            val ref = key?.let { "common:$it" }
            narration.present(
                PresentationMessage(
                    text = spoken,
                    spokenText = spoken,
                    narrationRef = ref,
                    role = PresentationRole.INSTRUCTION,
                    importance = PresentationImportance.SECONDARY,
                    repeatable = true,
                ),
            )
        }
        pendingSpeak = run
        speakHandler.postDelayed(run, 450)
    }

    private fun openItem(item: ContentItem, preferDetails: Boolean) {
        getSharedPreferences("familytv_home", MODE_PRIVATE)
            .edit().putString("lastFocusId", item.id).apply()
        PlatformServices.trialEvents(this).record(TrialEventStore.HOME_ITEM_OPEN, item.id)
        if (StoryRoutes.opens(item)) {
            Log.i(TAG, "open=create content=${item.id} AI=${aiFlag()}")
            startActivity(StoryRoutes.intent(this))
            return
        }
        if (MoveRoutes.opens(item)) {
            Log.i(TAG, "open=move content=${item.id} AI=${aiFlag()}")
            startActivity(MoveRoutes.intent(this))
            return
        }
        if (item.type == ContentType.VIDEO) {
            startActivity(Intent(this, WatchActivity::class.java)
                .putExtra("screen", "details").putExtra("contentId", item.id))
            return
        }
        val needsDetails = preferDetails ||
            item.type == ContentType.SESSION ||
            item.type == ContentType.INTERACTIVE ||
            item.type == ContentType.ADVENTURE
        if (needsDetails && item.type != ContentType.GAME) {
            startActivity(
                Intent(this, ContentDetailsActivity::class.java)
                    .putExtra(ContentDetailsActivity.EXTRA_CONTENT_ID, item.id),
            )
        } else {
            ContentNavigator.open(this, item)
        }
    }

    private fun handleHomeAction(action: FamilyAction): Boolean {
        when (action) {
            FamilyAction.Home -> {
                worlds[lastWorld].requestFocus()
                return true
            }
            FamilyAction.Repeat -> {
                val label = focusedSpeakLabel
                if (!label.isNullOrBlank()) {
                    PlatformServices.narrationCoordinator(this).present(
                        PresentationMessage(
                            text = label,
                            spokenText = label,
                            narrationRef = "common:repeat_task",
                            role = PresentationRole.INSTRUCTION,
                            importance = PresentationImportance.SECONDARY,
                            repeatable = true,
                        ),
                    )
                    PlatformServices.narrationCoordinator(this).present(
                        PresentationMessage(
                            text = label,
                            spokenText = label,
                            role = PresentationRole.INSTRUCTION,
                            repeatable = true,
                        ),
                    )
                } else {
                    PlatformServices.narrationCoordinator(this).repeat()
                }
                return true
            }
            FamilyAction.OpenParentGate -> {
                startActivity(Intent(this, ParentModeActivity::class.java))
                return true
            }
            FamilyAction.BlockedSensitive -> {
                Toast.makeText(this, "Это могут сделать только родители.", Toast.LENGTH_SHORT).show()
                return true
            }
            is FamilyAction.OpenWorld -> {
                if (action.worldId == "watch" || action.worldId == "stories") {
                    lastWorld = 0
                    requestedFocus = "watch"
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "watch").apply()
                    Log.i(TAG, "open=watch destination=watch AI=${aiFlag()}")
                    startActivity(Intent(this, WatchActivity::class.java))
                    return true
                }
                if (action.worldId == "play") {
                    lastWorld = 1
                    requestedFocus = "games"
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "games").apply()
                    Log.i(TAG, "open=games destination=play AI=${aiFlag()}")
                    ContentNavigator.gameIntent(this, "play")?.let(::startActivity)
                    return true
                }
                if (action.worldId == StoryRoutes.WORLD_ID) {
                    lastWorld = 3
                    requestedFocus = StoryRoutes.CONTENT_ID
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", StoryRoutes.CONTENT_ID).apply()
                    Log.i(TAG, "open=create destination=${StoryRoutes.WORLD_ID} AI=${aiFlag()}")
                    startActivity(StoryRoutes.intent(this))
                    return true
                }
                if (MoveRoutes.handles(action.worldId)) {
                    lastWorld = 4
                    requestedFocus = MoveRoutes.CONTENT_ID
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", MoveRoutes.CONTENT_ID).apply()
                    Log.i(TAG, "open=move destination=${MoveRoutes.WORLD_ID} AI=${aiFlag()}")
                    startActivity(MoveRoutes.intent(this))
                    return true
                }
                if (ExploreRoutes.handles(action.worldId)) {
                    val destination = ExploreDestination.fromRoute(action.worldId)
                    lastWorld = 2
                    requestedFocus = "explore"
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "explore").apply()
                    Log.i(TAG, "open=explore destination=$destination AI=${aiFlag()}")
                    startActivity(ExploreRoutes.intent(this, destination))
                    return true
                }
                worldIndex(action.worldId)?.let { worlds[it].requestFocus() }
                return true
            }
            is FamilyAction.OpenContent, is FamilyAction.StartAdventure -> {
                val id = when (action) {
                    is FamilyAction.OpenContent -> action.contentId
                    is FamilyAction.StartAdventure -> action.contentId
                    else -> return false
                }
                val item = PlatformServices.contentRepository(this).getItem(id) ?: return false
                openItem(item, preferDetails = true)
                return true
            }
            is FamilyAction.OpenGame -> {
                if (ru.familytv.games.GamesDestination.fromRoute(action.route) == ru.familytv.games.GamesDestination.HOME) {
                    return handleHomeAction(FamilyAction.OpenWorld("play"))
                }
                val item = PlatformServices.contentRepository(this).getEnabledItems()
                    .firstOrNull { it.type == ContentType.GAME && (it.route == action.route || it.id == action.route) }
                    ?: return false
                ContentNavigator.open(this, item)
                return true
            }
            FamilyAction.ContinueCurrent -> {
                return handleHomeAction(FamilyAction.ContinueJourney())
            }
            is FamilyAction.ContinueJourney -> {
                val target = PlatformServices.journeyCoordinator(this).resumeTarget(action.journeyId)
                if (target != null) {
                    startActivity(openJourneyIntent(target.journey.id))
                    return true
                }
                return false
            }
            is FamilyAction.OpenJourneyMap -> {
                val id = action.journeyId
                    ?: PlatformServices.journeyCoordinator(this).activeJourneyId()
                    ?: SpaceRoutes.JOURNEY_ID
                startActivity(openJourneyIntent(id))
                return true
            }
            FamilyAction.OpenJournal -> {
                openJournal()
                return true
            }
            is FamilyAction.AskWhatWeDiscovered -> {
                val journalStore = PlatformServices.discoveryJournal(this)
                val text = ru.familytv.journey.JourneyNarrator.whatWeDiscovered(journalStore.list(), action.worldHint)
                lumi.emotion = GuideEmotion.HAPPY
                PlatformServices.narrationCoordinator(this).present(
                    PresentationMessage(
                        text = text,
                        spokenText = text,
                        role = PresentationRole.FEEDBACK,
                        importance = PresentationImportance.IMPORTANT,
                        repeatable = true,
                    ),
                )
                return true
            }
            else -> return false
        }
    }

    private fun worldIndex(id: String?): Int? = when (id) {
        "watch", "stories", "world:0" -> 0
        "games", "play", "world:1" -> 1
        "explore", "world:2" -> 2
        "create", StoryRoutes.CONTENT_ID, "world:3" -> 3
        "move", MoveRoutes.CONTENT_ID, "world:4" -> 4
        else -> id?.removePrefix("world:")?.toIntOrNull()?.takeIf { it in 0..4 }
    }

    private fun aiFlag() = if (VoiceRuntime.peek() == null) 0 else 1

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onDestroy() {
        PlatformServices.actionDispatcher().detach(homeActionHandler)
        pendingSpeak?.let { speakHandler.removeCallbacks(it) }
        reminderHandler.removeCallbacks(reminderTick)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FamilyHome"
        val WORLD_FOCUS = listOf("watch", "games", "explore", "create", "move")
        val WORLD_SECTION = listOf("stories", "play", "explore", "create", "move")
        val CHILD_WORLDS = listOf("Смотреть", "Играть", "Исследовать", "Создавать", "Двигаться")
    }
}
