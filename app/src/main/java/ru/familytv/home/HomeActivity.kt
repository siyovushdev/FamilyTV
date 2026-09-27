package ru.familytv.home

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.content.PlatformServices
import ru.familytv.content.V1ContentShelf
import ru.familytv.trial.TrialEventStore
import ru.familytv.creative.StoryRoutes
import ru.familytv.explore.ExploreDestination
import ru.familytv.explore.ExploreRoutes
import ru.familytv.games.GameStyle
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.interaction.InputSource
import ru.familytv.interaction.InteractionSurface
import ru.familytv.interaction.NonReaderSimulation
import ru.familytv.interaction.PresentationImportance
import ru.familytv.interaction.PresentationMessage
import ru.familytv.interaction.PresentationRole
import ru.familytv.interaction.ReadingSupportMode
import ru.familytv.interaction.RouteContext
import ru.familytv.interaction.RouteResult
import ru.familytv.interaction.VoiceCommandRouter
import ru.familytv.narration.CommonVoicePack
import ru.familytv.parent.ParentModeActivity
import ru.familytv.parent.ScreenTimeReminder
import ru.familytv.ui.ChildChrome
import ru.familytv.ui.FamilyTvTheme
import ru.familytv.ui.LumiView
import ru.familytv.ui.SceneTransitions
import android.widget.ImageView
import ru.familytv.R
import ru.familytv.voice.AiReadiness
import ru.familytv.voice.MicPermissionAction
import ru.familytv.voice.MicPermissionGate
import ru.familytv.voice.VoicePhase
import ru.familytv.voice.VoiceRuntime
import ru.familytv.voicelab.VoiceLabActivity
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import ru.familytv.adventure.GuideEmotion
import ru.familytv.journey.JourneyEngine
import ru.familytv.journey.JourneyNarrator

/**
 * Child-facing TV Home 3.0 — world portals, Lumi, hero mission, D-pad first.
 */
class HomeActivity : ComponentActivity() {
    private var longPressStartedAt = 0L
    private lateinit var readinessLabel: TextView
    private lateinit var detailLabel: TextView
    private lateinit var reminderLabel: TextView
    private lateinit var sectionsHost: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var lumiView: LumiView
    private val cardViews = linkedMapOf<String, View>()
    private var lastCatalogGeneration = -1L
    private var lastWatchSignature = ""
    private var lastReminder: String? = null
    private var lastSpokenFocusId: String? = null
    private var lastSpokenAt = 0L
    private var focusedSpeakLabel: String? = null
    private var nonReaderSim = false
    private val speakHandler = Handler(Looper.getMainLooper())
    private var pendingSpeak: Runnable? = null
    private val reminderHandler = Handler(Looper.getMainLooper())
    private val reminderTick = object : Runnable {
        override fun run() {
            updateReminder()
            reminderHandler.postDelayed(this, 30_000L)
        }
    }
    private var homePipeline: ru.familytv.voice.VoiceAnswerPipeline? = null
    private var homeVoiceJob: Job? = null
    private var askedMic = false
    private val homeActionHandler = FamilyActionHandler { _, action -> handleHomeAction(action) }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedMic = true
        if (granted) startHomeListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        PlatformServices.screenTime(this).onAppForeground()
        nonReaderSim = NonReaderSimulation.isEnabled(getSharedPreferences(NonReaderSimulation.PREFS, MODE_PRIVATE))
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // TV safe overscan-ish margins
            setPadding(dp(56), dp(28), dp(56), dp(36))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 250, 242), Color.rgb(232, 244, 240), Color.rgb(236, 230, 250)),
            )
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lumiView = LumiView(this).apply {
            contentDescription = "Луми"
        }
        top.addView(lumiView, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(12) })
        lumiView.post {
            lumiView.setEmotionWithMotion(GuideEmotion.CURIOUS)
            lumiView.play(LumiView.Motion.APPEAR)
        }
        val brandCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = GameStyle.text(this, "FamilyTV", 28f, true).apply {
            setTextColor(FamilyTvTheme.teal)
            isFocusable = true
            isClickable = true
            contentDescription = "FamilyTV. Родительский режим — удерживайте OK"
            setOnClickListener { /* focus only */ }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0) longPressStartedAt = System.currentTimeMillis()
                            // Parent Mode: hold OK ~2s on brand
                            if (System.currentTimeMillis() - longPressStartedAt > 2000) {
                                startActivity(Intent(this@HomeActivity, ParentModeActivity::class.java))
                                longPressStartedAt = Long.MAX_VALUE
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                setOnLongClickListener {
                    startActivity(Intent(this@HomeActivity, VoiceLabActivity::class.java))
                    true
                }
            }
        }
        brandCol.addView(brand)
        brandCol.addView(
            GameStyle.text(this, "Миры для семьи", 15f).apply {
                setTextColor(FamilyTvTheme.muted)
            },
        )
        top.addView(brandCol, LinearLayout.LayoutParams(0, -2, 1f))
        val parentEntry = GameStyle.button(this, "Родителям") {
            startActivity(Intent(this, ParentModeActivity::class.java))
        }.apply {
            textSize = 14f
            alpha = 0.85f
            contentDescription = "Режим родителя. Нужен PIN"
        }
        top.addView(parentEntry, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(12) })
        val voiceEntry = GameStyle.button(this, "🎤") {
            when (
                MicPermissionGate.nextAction(
                    hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED,
                    alreadyAskedThisSession = askedMic,
                    shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
                )
            ) {
                MicPermissionAction.PROCEED -> startHomeListening()
                MicPermissionAction.EXPLAIN_THEN_REQUEST -> {
                    askedMic = true
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                MicPermissionAction.SHOW_UNAVAILABLE -> {
                    detailLabel.text = MicPermissionGate.CHILD_MESSAGE
                }
            }
        }.apply {
            textSize = 16f
            contentDescription = "Сказать команду. Голос не включается сам"
        }
        top.addView(voiceEntry, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(12) })
        reminderLabel = GameStyle.text(this, "", 16f).apply { setTextColor(Color.rgb(159, 93, 36)) }
        top.addView(reminderLabel)
        root.addView(top)
        PlatformServices.actionDispatcher().attach(homeActionHandler)

        root.addView(GameStyle.text(this, "Куда отправимся?", 32f, true).apply {
            setTextColor(FamilyTvTheme.ink)
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(
            GameStyle.text(this, "Луми рядом. Выбирай мир пультом или голосом.", 16f).apply {
                setTextColor(FamilyTvTheme.muted)
            },
        )
        readinessLabel = GameStyle.text(this, "", 15f).apply {
            setTextColor(GameStyle.muted)
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(readinessLabel)
        detailLabel = GameStyle.text(this, "", 18f).apply {
            setTextColor(GameStyle.ink)
            setPadding(0, dp(6), 0, dp(4))
            maxLines = 2
        }
        root.addView(detailLabel)
        sectionsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sectionsHost)
        rebuildSections(forceReload = false)
        android.util.Log.i(
            "FamilyHome",
            "cold_onCreate_ms=${android.os.SystemClock.elapsedRealtime() - t0} catalogGen=$lastCatalogGeneration",
        )

        setContentView(ScrollView(this).apply {
            homeScroll = this
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(root)
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val runtime = VoiceRuntime.peek()
                if (runtime == null) {
                    readinessLabel.text = ""
                    return@repeatOnLifecycle
                }
                runtime.readiness.collect { status ->
                    readinessLabel.text = when (status) {
                        AiReadiness.AI_INITIALIZING -> "Голосовые игры готовятся…"
                        AiReadiness.AI_READY -> "Голосовые игры готовы"
                        AiReadiness.AI_ERROR -> "Голосовые игры пока недоступны"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PlatformServices.actionDispatcher().attach(homeActionHandler)
        PlatformServices.screenTime(this).onAppForeground()
        updateReminder()
        reminderHandler.removeCallbacks(reminderTick)
        reminderHandler.postDelayed(reminderTick, 30_000L)
        if (::sectionsHost.isInitialized) {
            rebuildSections(forceReload = false)
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
        android.util.Log.i("FamilyHome", "inject=$t")
        val catalog = PlatformServices.contentRepository(this).getEnabledItems()
        when (
            val routed = VoiceCommandRouter.route(
                RouteContext(surface = InteractionSurface.HOME, catalog = catalog),
                t.lowercase(),
            )
        ) {
            is RouteResult.Action -> handleHomeAction(routed.action)
            RouteResult.Unhandled -> {
                if (::detailLabel.isInitialized) detailLabel.text = "Не расслышал: $t"
            }
        }
    }

    override fun onPause() {
        reminderHandler.removeCallbacks(reminderTick)
        PlatformServices.actionDispatcher().detach(homeActionHandler)
        super.onPause()
    }

    private fun updateReminder() {
        if (!::reminderLabel.isInitialized) return
        when (val r = PlatformServices.screenTime(this).reminder()) {
            is ScreenTimeReminder.Warn -> {
                reminderLabel.text = r.message
                if (lastReminder != r.message) {
                    lastReminder = r.message
                }
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

    private fun rebuildSections(forceReload: Boolean = false) {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val repo = PlatformServices.contentRepository(this)
        val progress = PlatformServices.watchProgress(this)
        val filter = PlatformServices.familySettings(this).contentFilter()
        val enabled = try {
            if (forceReload) repo.reload() else repo.getCatalog()
            val watchSignature = progress.continueWatching().toString() + filter.toString() +
                ru.familytv.home.watch.WatchCatalog.eligible(repo, filter).map { it.id }.toString()
            if (!forceReload && repo.catalogGeneration() == lastCatalogGeneration && lastWatchSignature == watchSignature && sectionsHost.childCount > 0) {
                android.util.Log.i("FamilyHome", "rebuild_skipped gen=$lastCatalogGeneration")
                return
            }
            lastCatalogGeneration = repo.catalogGeneration()
            lastWatchSignature = watchSignature
            val showDev = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                getSharedPreferences("familytv_debug", MODE_PRIVATE).getBoolean("show_dev_shelf", false)
            repo.getEnabledItems()
                .filter { passesAgeFilter(it, filter.ageMin, filter.ageMax) }
                .let { V1ContentShelf.filterForHome(it, showDevShelf = showDev) }
        } catch (_: Exception) {
            detailLabel.text = "Это занятие сейчас недоступно."
            return
        }
        sectionsHost.removeAllViews()
        cardViews.clear()
        if (enabled.isEmpty()) {
            sectionsHost.addView(continuityRow())
            sectionsHost.addView(GameStyle.text(this, "Пока нет доступного контента", 24f, true))
            return
        }

        // Hero — active journey resume preferred over generic content card
        val journeyHero = journeyHeroBanner()
        if (journeyHero != null) {
            sectionsHost.addView(journeyHero, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            })
        } else {
            val hero = pickHero(enabled)
            if (hero != null) {
                sectionsHost.addView(heroBanner(hero), LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(8)
                })
            }
        }

        // Continuity strip — journal + journeys (keep Home light)
        sectionsHost.addView(continuityRow(), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
        })

        val continueIds = ru.familytv.home.watch.WatchCatalog.continueWatching(
            ru.familytv.home.watch.WatchCatalog.eligible(repo, filter), progress,
        )

        val heroId = PlatformServices.journeyCoordinator(this).resumeTarget()?.contentId
            ?: pickHero(enabled)?.id

        var added = 0
        for (section in HomeSections.ALL) {
            val items = when (section.id) {
                "continue" -> continueIds
                "today" -> enabled.filter { "today" in it.homeSections && it.id != heroId }
                else -> enabled.filter { section.id in it.homeSections && it.id != heroId }
            }
            if (items.isEmpty()) continue
            added++
            sectionsHost.addView(sectionTitle(section.title, section.id), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(18)
                bottomMargin = dp(10)
            })
            sectionsHost.addView(row(items, section.id), LinearLayout.LayoutParams(-1, -2))
        }
        if (added == 0 && journeyHero == null && pickHero(enabled) == null) {
            sectionsHost.addView(GameStyle.text(this, "Нет секций для показа", 20f))
        }
        android.util.Log.i(
            "FamilyHome",
            "rebuild_ms=${android.os.SystemClock.elapsedRealtime() - t0} items=${enabled.size} gen=$lastCatalogGeneration",
        )
    }

    private fun journeyHeroBanner(): View? {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val coord = PlatformServices.journeyCoordinator(this)
        val catalog = PlatformServices.journeyCatalog(this)
        val active = PlatformServices.journeyProgress(this).listActive().firstOrNull()
        val journey = active?.let { catalog.get(it.journeyId) }
            ?: catalog.get("space-grand-expedition")
            ?: catalog.all().firstOrNull()
            ?: return null
        val progress = JourneyEngine.sanitize(journey, PlatformServices.journeyProgress(this).get(journey.id))
        val cue = JourneyNarrator.resumeCue(journey, progress)
        val accent = when {
            journey.id.contains("forest") -> FamilyTvTheme.forestFar
            else -> FamilyTvTheme.spaceFar
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accent, Color.rgb(60, 100, 130)),
            ).also { it.cornerRadius = dp(28).toFloat() }
            foreground = GameStyle.focusBackground(this@HomeActivity, Color.TRANSPARENT)
            isFocusable = true
            isClickable = true
            contentDescription = cue
            tag = "hero:journey:${journey.id}"
            setOnClickListener {
                startActivity(
                    Intent(this@HomeActivity, ru.familytv.journey.JourneyMapActivity::class.java)
                        .putExtra(ru.familytv.journey.JourneyMapActivity.EXTRA_JOURNEY_ID, journey.id),
                )
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    performClick()
                    true
                } else false
            }
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    detailLabel.text = cue
                    ensureCardVisible(v)
                }
                v.scaleX = if (hasFocus) 1.02f else 1f
                v.scaleY = if (hasFocus) 1.02f else 1f
            }
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(GameStyle.text(this, "Путешествие", 14f).apply {
            setTextColor(Color.rgb(180, 230, 255))
        })
        textCol.addView(GameStyle.text(this, journey.title, 26f, true).apply { setTextColor(Color.WHITE) })
        textCol.addView(GameStyle.text(this, cue, 15f).apply {
            setTextColor(Color.rgb(210, 220, 230))
            maxLines = 2
        })
        box.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        val ctaLabel = if (progress.started && !progress.completed) "Продолжить" else "Начать"
        box.addView(
            GameStyle.button(this, ctaLabel) {
                startActivity(
                    Intent(this@HomeActivity, ru.familytv.journey.JourneyMapActivity::class.java)
                        .putExtra(ru.familytv.journey.JourneyMapActivity.EXTRA_JOURNEY_ID, journey.id),
                )
            },
            LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(16) },
        )
        cardViews["hero:journey:${journey.id}"] = box
        SceneTransitions.portalEnter(box)
        return box
    }

    private fun continuityRow(): View {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(label: String, onClick: () -> Unit): View {
            return GameStyle.button(this, label, onClick).apply {
                textSize = 15f
            }
        }
        val watch = chip("Смотреть") {
            getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "watch").apply()
            startActivity(Intent(this, ru.familytv.home.watch.WatchActivity::class.java))
        }.apply { tag = "watch"; contentDescription = "Смотреть видео" }
        cardViews["watch"] = watch
        row.addView(watch, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(12) })
        val games = chip("Играть") {
            PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld("play"))
        }.apply {
            tag = "games"
            contentDescription = "Играть"
            isFocusableInTouchMode = true
            setOnFocusChangeListener { view, focused ->
                if (focused) {
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "games").apply()
                    ensureCardVisible(view)
                    android.util.Log.i("FamilyHome", "focus=games AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
                }
            }
        }
        cardViews["games"] = games
        row.addView(games, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(12) })
        val explore = chip("Исследовать") {
            PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(ExploreRoutes.WORLD_ID))
        }.apply {
            tag = "explore"
            contentDescription = "Исследовать"
            isFocusableInTouchMode = true
            setOnFocusChangeListener { view, focused ->
                if (focused) {
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "explore").apply()
                    ensureCardVisible(view)
                    android.util.Log.i("FamilyHome", "focus=explore AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
                }
            }
        }
        cardViews["explore"] = explore
        row.addView(explore, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(12) })
        row.addView(chip("Наши открытия") {
            startActivity(Intent(this, ru.familytv.journey.DiscoveryJournalActivity::class.java))
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(12) })
        row.addView(chip("Наши истории") {
            startActivity(
                Intent(this, ru.familytv.journey.DiscoveryJournalActivity::class.java)
                    .putExtra("focus", "stories"),
            )
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(12) })
        row.addView(chip("Карта") {
            val id = PlatformServices.journeyCoordinator(this).activeJourneyId()
                ?: "space-grand-expedition"
            startActivity(
                Intent(this, ru.familytv.journey.JourneyMapActivity::class.java)
                    .putExtra(ru.familytv.journey.JourneyMapActivity.EXTRA_JOURNEY_ID, id),
            )
        }, LinearLayout.LayoutParams(-2, dp(48)))
        return row
    }

    private fun pickHero(enabled: List<ContentItem>): ContentItem? {
        val preferred = PlatformServices.familySettings(this).heroContentId()
        preferred?.let { id -> enabled.find { it.id == id } }?.let { return it }
        return enabled
            .filter { it.type == ContentType.ADVENTURE && "mission" in it.homeSections }
            .minByOrNull { it.sortOrder }
            ?: enabled.firstOrNull { it.type == ContentType.SESSION && "today" in it.homeSections }
            ?: enabled.firstOrNull { "today" in it.homeSections }
            ?: enabled.firstOrNull { "mission" in it.homeSections }
    }

    private fun heroBanner(item: ContentItem): View {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val accent = when {
            item.id.contains("space") -> FamilyTvTheme.spaceFar
            item.id.contains("forest") -> FamilyTvTheme.forestFar
            else -> Color.rgb(40, 90, 110)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accent, Color.rgb(60, 100, 130)),
            ).also {
                it.cornerRadius = dp(28).toFloat()
            }
            foreground = GameStyle.focusBackground(this@HomeActivity, Color.TRANSPARENT)
            isFocusable = true
            isClickable = true
            contentDescription = "${item.title}. ${item.description}"
            tag = "hero:${item.id}"
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    detailLabel.text = item.title + " — " + item.description
                    getSharedPreferences("familytv_home", MODE_PRIVATE)
                        .edit().putString("lastFocusId", "hero:${item.id}").apply()
                    ensureCardVisible(v)
                    if (::lumiView.isInitialized) lumiView.setEmotionWithMotion(GuideEmotion.CURIOUS)
                }
                v.scaleX = if (hasFocus) 1.02f else 1f
                v.scaleY = if (hasFocus) 1.02f else 1f
            }
            setOnClickListener { openItem(item, preferDetails = true) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    openItem(item, preferDetails = true)
                    true
                } else false
            }
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(GameStyle.text(this, if (item.type == ContentType.ADVENTURE) "Главная миссия" else "Сегодня вместе", 14f).apply {
            setTextColor(Color.rgb(180, 230, 255))
        })
        textCol.addView(GameStyle.text(this, item.title, 28f, true).apply { setTextColor(Color.WHITE) })
        val mins = item.metadata.estimatedMinutes?.let { "~$it мин" } ?: ""
        textCol.addView(
            GameStyle.text(this, listOf(item.description, mins).filter { it.isNotBlank() }.joinToString(" · "), 15f).apply {
                setTextColor(Color.rgb(210, 220, 230))
                maxLines = 2
            },
        )
        box.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        val cta = GameStyle.button(this, "Начать") { openItem(item, preferDetails = true) }
        box.addView(cta, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(16) })
        cardViews["hero:${item.id}"] = box
        SceneTransitions.portalEnter(box)
        return box
    }

    private fun openItem(item: ContentItem, preferDetails: Boolean) {
        getSharedPreferences("familytv_home", MODE_PRIVATE)
            .edit().putString("lastFocusId", item.id).apply()
        PlatformServices.trialEvents(this).record(TrialEventStore.HOME_ITEM_OPEN, item.id)
        if (StoryRoutes.opens(item)) {
            android.util.Log.i("FamilyHome", "open=create content=${item.id} AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
            startActivity(StoryRoutes.intent(this))
            return
        }
        if (item.type == ContentType.VIDEO) {
            startActivity(Intent(this, ru.familytv.home.watch.WatchActivity::class.java)
                .putExtra("screen", "details").putExtra("contentId", item.id))
            return
        }
        val needsDetails = preferDetails ||
            item.type == ContentType.SESSION ||
            item.type == ContentType.INTERACTIVE ||
            item.type == ContentType.ADVENTURE ||
            (item.type == ContentType.VIDEO && (item.metadata.estimatedMinutes ?: 0) >= 3)
        if (needsDetails && item.type != ContentType.GAME) {
            startActivity(
                Intent(this, ContentDetailsActivity::class.java)
                    .putExtra(ContentDetailsActivity.EXTRA_CONTENT_ID, item.id),
            )
        } else {
            ContentNavigator.open(this, item)
        }
    }

    private fun restoreFocus() {
        val prefs = getSharedPreferences("familytv_home", MODE_PRIVATE)
        val lastId = prefs.getString("lastFocusId", null)
        val target = lastId?.let { cardViews[it] }
            ?: cardViews["hero:${pickHero(PlatformServices.contentRepository(this).getEnabledItems())?.id}"]
            ?: cardViews.values.firstOrNull()
        target?.post {
            target.requestFocus()
            ensureCardVisible(target)
        }
    }

    private fun ensureCardVisible(view: View) {
        if (::homeScroll.isInitialized) {
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            homeScroll.offsetDescendantRectToMyCoords(view, rect)
            // Keep a little overscan padding above the focused card
            rect.top -= GameStyle.dp(this, 24)
            rect.bottom += GameStyle.dp(this, 24)
            homeScroll.requestChildRectangleOnScreen(view, rect, false)
        }
        var parent = view.parent
        while (parent != null) {
            if (parent is HorizontalScrollView) {
                val childLeft = view.left
                val scroll = (childLeft - GameStyle.dp(this, 24)).coerceAtLeast(0)
                parent.smoothScrollTo(scroll, 0)
                return
            }
            if (parent is View && view.parent is View) {
                val rowChild = view
                val row = view.parent as? View
                if (row != null && row.parent is HorizontalScrollView) {
                    val hsv = row.parent as HorizontalScrollView
                    hsv.smoothScrollTo((rowChild.left - GameStyle.dp(this, 24)).coerceAtLeast(0), 0)
                    return
                }
            }
            parent = (parent as? View)?.parent
        }
    }

    private fun sectionTitle(title: String, sectionId: String = ""): View {
        val id = sectionId.ifBlank {
            HomeSections.ALL.firstOrNull { it.title == title }?.id.orEmpty()
        }
        return ChildChrome.sectionHeader(this, id, title, nonReaderSim)
    }

    private fun scheduleWorldSpeak(focusId: String, sectionId: String, title: String) {
        val mode = PlatformServices.familySettings(this).readingSupportMode()
        if (mode != ReadingSupportMode.AUDIO_FIRST) return
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
            if (now - lastSpokenAt < 700) return@Runnable // debounce fast D-pad
            lastSpokenFocusId = focusId
            lastSpokenAt = now
            if (::lumiView.isInitialized) lumiView.setEmotionWithMotion(GuideEmotion.LISTENING)
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

    private fun row(items: List<ContentItem>, sectionId: String = ""): View {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { item ->
            row.addView(card(item, sectionId), LinearLayout.LayoutParams(dp(300), dp(196)).apply {
                marginEnd = dp(16)
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(row)
        }
    }

    private fun card(item: ContentItem, sectionId: String = ""): View {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val world = FamilyTvTheme.worldForSection(
            when {
                sectionId.isNotBlank() -> sectionId
                "explore" in item.homeSections -> "explore"
                "solve" in item.homeSections -> "solve"
                "create" in item.homeSections -> "create"
                "stories" in item.homeSections -> "stories"
                "move" in item.homeSections -> "move"
                else -> when (item.type) {
                    ContentType.VIDEO -> "stories"
                    ContentType.GAME -> "solve"
                    ContentType.INTERACTIVE -> "solve"
                    ContentType.SESSION -> "today"
                    ContentType.ADVENTURE -> "explore"
                }
            },
        )
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GameStyle.focusBackground(this@HomeActivity, world.card)
            isFocusable = true
            isClickable = true
            tag = item.id
            contentDescription = "${item.title}. ${item.description}"
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val minutes = item.metadata.estimatedMinutes?.let { " · ~$it мин" } ?: ""
                    detailLabel.text = if (nonReaderSim) item.title else "${item.title} — ${item.description}$minutes"
                    getSharedPreferences("familytv_home", MODE_PRIVATE)
                        .edit().putString("lastFocusId", item.id).apply()
                    ensureCardVisible(v)
                    scheduleWorldSpeak(item.id, sectionId.ifBlank { world.id }, world.title)
                }
                v.scaleX = if (hasFocus) 1.04f else 1f
                v.scaleY = if (hasFocus) 1.04f else 1f
            }
            setOnClickListener { openItem(item, preferDetails = false) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    openItem(item, preferDetails = false)
                    true
                } else false
            }
        }
        view.addView(
            ImageView(this).apply {
                setImageResource(ChildChrome.worldIcon(this@HomeActivity, world.id))
                contentDescription = world.title
            },
            LinearLayout.LayoutParams(GameStyle.dp(this, 56), GameStyle.dp(this, 56)),
        )
        if (!nonReaderSim) {
            view.addView(GameStyle.text(this, typeLabel(item), 14f).apply {
                setTextColor(world.accent)
                gravity = Gravity.CENTER
            })
        }
        view.addView(GameStyle.text(this, item.title, if (nonReaderSim) 22f else 24f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(FamilyTvTheme.ink)
            alpha = if (nonReaderSim) 0.4f else 1f
        })
        if (!nonReaderSim) {
            view.addView(GameStyle.text(this, item.description, 14f).apply {
                setTextColor(FamilyTvTheme.muted)
                gravity = Gravity.CENTER
                maxLines = 2
            })
        }
        cardViews[item.id] = view
        return view
    }

    private fun typeLabel(item: ContentItem) = when (item.type) {
        ContentType.VIDEO -> "Видео"
        ContentType.GAME -> "Игра"
        ContentType.INTERACTIVE -> "Занятие"
        ContentType.SESSION -> "Сессия"
        ContentType.ADVENTURE -> "Приключение"
    }

    private fun passesAgeFilter(item: ContentItem, ageMin: Int?, ageMax: Int?): Boolean {
        if (ageMin == null && ageMax == null) return true
        val itemMin = item.metadata.ageMin
        val itemMax = item.metadata.ageMax
        if (itemMin == null && itemMax == null) return true
        val filterMin = ageMin ?: 0
        val filterMax = ageMax ?: 99
        val a = itemMin ?: 0
        val b = itemMax ?: 99
        return a <= filterMax && b >= filterMin
    }

    /** Explicit opt-in listening — does not load VoiceRuntime on cold Home. */
    private fun startHomeListening() {
        detailLabel.text = "Слушаю…"
        try {
            if (homePipeline == null) {
                homePipeline = VoiceRuntime.get(this).newPipeline()
            }
            homePipeline?.activate()
            homePipeline?.startListening()
            if (homeVoiceJob == null) {
                homeVoiceJob = lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        homePipeline?.state?.collect { voice ->
                            when (voice.phase) {
                                VoicePhase.RESULT -> voice.answer?.let { answer ->
                                    homePipeline?.stopListening()
                                    val catalog = PlatformServices.contentRepository(this@HomeActivity).getEnabledItems()
                                    val ctx = RouteContext(
                                        surface = InteractionSurface.HOME,
                                        catalog = catalog,
                                    )
                                    when (val routed = VoiceCommandRouter.route(ctx, answer.normalizedText.lowercase())) {
                                        is RouteResult.Action -> handleHomeAction(routed.action)
                                        RouteResult.Unhandled -> {
                                            detailLabel.text = "Не расслышал. Скажи «покажи игры» или название."
                                        }
                                    }
                                }
                                VoicePhase.ERROR -> detailLabel.text = "Голос пока недоступен"
                                else -> Unit
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            detailLabel.text = "Голос пока недоступен"
        }
    }

    private fun handleHomeAction(action: FamilyAction): Boolean {
        when (action) {
            FamilyAction.Home -> {
                homeScroll.smoothScrollTo(0, 0)
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
                    // Re-speak the label after cue via coordinator.repeat path — present label again
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
                detailLabel.text = "Это могут сделать только родители."
                return true
            }
            is FamilyAction.OpenWorld -> {
                if (action.worldId == "play") {
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "games").apply()
                    ContentNavigator.gameIntent(this, "play")?.let(::startActivity)
                    return true
                }
                if (action.worldId == StoryRoutes.WORLD_ID) {
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", StoryRoutes.CONTENT_ID).apply()
                    android.util.Log.i("FamilyHome", "open=create destination=${StoryRoutes.WORLD_ID} AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
                    startActivity(StoryRoutes.intent(this))
                    return true
                }
                if (ExploreRoutes.handles(action.worldId)) {
                    val destination = ExploreDestination.fromRoute(action.worldId)
                    getSharedPreferences("familytv_home", MODE_PRIVATE).edit().putString("lastFocusId", "explore").apply()
                    android.util.Log.i("FamilyHome", "open=explore destination=$destination AI=${if (VoiceRuntime.peek() == null) 0 else 1}")
                    startActivity(ExploreRoutes.intent(this, destination))
                    return true
                }
                focusWorld(action.worldId)
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
                    startActivity(
                        Intent(this, ru.familytv.journey.JourneyMapActivity::class.java)
                            .putExtra(ru.familytv.journey.JourneyMapActivity.EXTRA_JOURNEY_ID, target.journey.id),
                    )
                    return true
                }
                return false
            }
            is FamilyAction.OpenJourneyMap -> {
                val id = action.journeyId
                    ?: PlatformServices.journeyCoordinator(this).activeJourneyId()
                    ?: "space-grand-expedition"
                startActivity(
                    Intent(this, ru.familytv.journey.JourneyMapActivity::class.java)
                        .putExtra(ru.familytv.journey.JourneyMapActivity.EXTRA_JOURNEY_ID, id),
                )
                return true
            }
            FamilyAction.OpenJournal -> {
                startActivity(Intent(this, ru.familytv.journey.DiscoveryJournalActivity::class.java))
                return true
            }
            is FamilyAction.AskWhatWeDiscovered -> {
                val journal = PlatformServices.discoveryJournal(this)
                val text = JourneyNarrator.whatWeDiscovered(journal.list(), action.worldHint)
                detailLabel.text = text
                if (::lumiView.isInitialized) lumiView.setEmotionWithMotion(GuideEmotion.HAPPY)
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

    private fun focusWorld(worldId: String) {
        val section = HomeSections.ALL.firstOrNull { it.id == worldId } ?: return
        detailLabel.text = "Мир: ${section.title}"
        // Scroll toward matching section title views
        for (i in 0 until sectionsHost.childCount) {
            val child = sectionsHost.getChildAt(i)
            if (child is TextView && child.text?.toString() == section.title) {
                homeScroll.smoothScrollTo(0, child.top.coerceAtLeast(0))
                // Focus first card in following row if present
                val row = sectionsHost.getChildAt(i + 1)
                if (row is ViewGroup && row.childCount > 0) {
                    row.getChildAt(0)?.requestFocus()
                }
                return
            }
        }
    }

    override fun onDestroy() {
        PlatformServices.actionDispatcher().detach(homeActionHandler)
        homePipeline?.close()
        homePipeline = null
        homeVoiceJob = null
        super.onDestroy()
    }
}
