package ru.familytv.adventure

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.familytv.adventure.guide.GuideDirector
import ru.familytv.adventure.guide.GuideEvent
import ru.familytv.audio.FamilyAmbience
import ru.familytv.audio.FamilySfx
import ru.familytv.trial.TrialEventStore
import ru.familytv.content.ContentItem
import ru.familytv.content.PlatformServices
import ru.familytv.games.GameStyle
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.interaction.InputSource
import ru.familytv.interaction.InteractionSurface
import ru.familytv.interaction.NarrationCoordinator
import ru.familytv.interaction.PresentationImportance
import ru.familytv.interaction.PresentationMessage
import ru.familytv.interaction.PresentationRole
import ru.familytv.interaction.ReadingSupportMode
import ru.familytv.interaction.RouteContext
import ru.familytv.interaction.RouteResult
import ru.familytv.interaction.VoiceCommandRouter
import ru.familytv.knowledge.CuriosityIntent
import ru.familytv.knowledge.CuriosityIntentDetector
import ru.familytv.knowledge.KnowledgeCatalog
import ru.familytv.knowledge.KnowledgeEntry
import ru.familytv.knowledge.KnowledgeQuery
import ru.familytv.knowledge.KnowledgeResolver
import ru.familytv.knowledge.KnowledgeResult
import ru.familytv.parent.ActivityKind
import ru.familytv.R
import ru.familytv.ui.AdventureWorldStage
import ru.familytv.ui.ChildChrome
import ru.familytv.ui.FamilyTvTheme
import ru.familytv.ui.LumiView
import ru.familytv.ui.SceneTransitions
import ru.familytv.ui.SpaceLaunchBoard
import ru.familytv.interaction.NonReaderSimulation
import ru.familytv.voice.MicPermissionAction
import ru.familytv.voice.MicPermissionGate
import ru.familytv.voice.VoicePhase
import ru.familytv.voice.VoiceRuntime

/**
 * Single Activity for all package-defined Adventures.
 * Educational correctness stays in AdventureRuntime.
 */
class AdventureActivity : ComponentActivity() {
    private lateinit var runtime: AdventureRuntime
    private lateinit var contentItem: ContentItem
    private lateinit var titleView: TextView
    private lateinit var guideView: TextView
    private lateinit var promptView: TextView
    private lateinit var groupView: TextView
    private lateinit var board: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var worldStage: AdventureWorldStage
    private lateinit var lumiView: LumiView
    private lateinit var sfx: FamilySfx
    private lateinit var ambience: FamilyAmbience
    private lateinit var knowledge: KnowledgeResolver
    private val guide = GuideDirector()
    private lateinit var narration: NarrationCoordinator
    private var pipeline: ru.familytv.voice.VoiceAnswerPipeline? = null
    private var voiceStarted = false
    private var askedPermission = false
    private var startedAt = 0L
    private val recordedExperiments = mutableSetOf<String>()
    private var feedbackJob: Job? = null
    private var lastSceneId: String? = null
    private var readingMode: ReadingSupportMode = ReadingSupportMode.AUDIO_AND_TEXT
    private var launchBoard: SpaceLaunchBoard? = null
    private var launchEnginePlayed = false
    private val actionHandler = FamilyActionHandler { source, action -> handleFamilyAction(source, action) }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedPermission = true
        if (granted) ensureVoice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    persist(); finish()
                }
            },
        )
        startedAt = System.currentTimeMillis()
        val contentId = intent.getStringExtra(EXTRA_CONTENT_ID).orEmpty()
        val repo = PlatformServices.contentRepository(this)
        val item = repo.getItem(contentId)
        if (item == null) {
            Log.e(TAG_ASK, "unavailable: missing contentId=$contentId")
            showUnavailable(); return
        }
        contentItem = item
        val json = repo.openAdventureJson(item)
        if (json == null) {
            Log.e(TAG_ASK, "unavailable: no adventure json for $contentId ref=${item.adventureRef}")
            showUnavailable(); return
        }
        val adventure = try {
            AdventureParser.parse(json)
        } catch (e: Exception) {
            Log.e(TAG_ASK, "unavailable: parse failed $contentId", e)
            showUnavailable(); return
        }
        val errors = AdventureParser.validate(adventure)
        if (errors.isNotEmpty()) {
            Log.e(TAG_ASK, "unavailable: validate $contentId errors=$errors")
            showUnavailable(); return
        }
        Log.i(TAG_ASK, "adventure ready id=${adventure.id} contentId=$contentId")
        val resume = PlatformServices.adventureProgress(this).resumeSceneId(
            adventure.id,
            adventure.scenes.map { it.id }.toSet(),
        )
        runtime = AdventureRuntime(adventure, startSceneId = resume)
        knowledge = KnowledgeCatalog.loadForAdventure(this, adventure.id, adventure.world)
        PlatformServices.adventureEvents(this).record(adventure.id, "adventure_started")
        PlatformServices.journeyCoordinator(this).onContentOpened(contentItem.id)
        when {
            adventure.id.contains("weather") || adventure.id.contains("lab") ->
                PlatformServices.trialEvents(this).record(TrialEventStore.EXPERIMENT_STARTED, contentItem.id)
            else -> Unit
        }
        PlatformServices.narrationContentItem = contentItem
        narration = PlatformServices.narrationCoordinator(this)
        readingMode = PlatformServices.familySettings(this).readingSupportMode()
        PlatformServices.actionDispatcher().attach(actionHandler)
        sfx = FamilySfx(this)
        ambience = FamilyAmbience(this)
        when {
            adventure.id.contains("space") -> ambience.play("space")
            adventure.id.contains("forest") -> ambience.play("forest")
        }

        fun dp(v: Int) = GameStyle.dp(this, v)
        val isSpace = adventure.id.contains("space")
        val isForest = adventure.id.contains("forest")
        val bg = when {
            isSpace -> sceneBackground(FamilyTvTheme.spaceNear, FamilyTvTheme.spaceFar)
            isForest -> sceneBackground(FamilyTvTheme.forestNear, FamilyTvTheme.forestFar)
            else -> sceneBackground(Color.rgb(18, 28, 48), Color.rgb(36, 52, 78))
        }
        // Space: tighter header + overscan-safe bottom so Repeat/Help stay on-screen at 1080p.
        val padH = if (isSpace) dp(28) else dp(40)
        val padTop = if (isSpace) dp(10) else dp(16)
        val padBottom = if (isSpace) dp(56) else dp(20) // ~TV overscan reserve
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, padBottom)
            background = bg
            clipToPadding = false
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val homeH = if (isSpace) dp(44) else dp(48)
        header.addView(GameStyle.button(this, "‹ Домой") {
            dispatchRemote(FamilyAction.Home)
        }, LinearLayout.LayoutParams(-2, homeH))
        titleView = GameStyle.text(this, adventure.title, if (isSpace) 18f else 22f, true).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, 0, 0)
            if (isSpace) maxLines = 1
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        val lumiSize = if (isSpace) dp(72) else dp(96)
        lumiView = LumiView(this).apply { contentDescription = adventure.guide.name }
        header.addView(lumiView, LinearLayout.LayoutParams(lumiSize, lumiSize))
        rootLayout.addView(header)

        worldStage = AdventureWorldStage(this)
        val stageH = if (isSpace) dp(168) else dp(140)
        rootLayout.addView(worldStage, LinearLayout.LayoutParams(-1, stageH).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })

        guideView = GameStyle.text(this, "", if (isSpace) 15f else 18f, true).apply {
            setTextColor(Color.rgb(180, 230, 255))
            setPadding(0, if (isSpace) dp(4) else dp(8), 0, dp(2))
            if (isSpace) maxLines = 2
        }
        rootLayout.addView(guideView)
        groupView = GameStyle.text(this, "", 16f).apply {
            setTextColor(Color.rgb(200, 210, 160))
        }
        rootLayout.addView(groupView)
        val promptSize = when {
            isSpace -> 18f
            readingMode == ReadingSupportMode.AUDIO_FIRST -> 24f
            readingMode == ReadingSupportMode.TEXT_RICH -> 22f
            else -> 26f
        }
        promptView = GameStyle.text(this, "", promptSize, true).apply {
            setTextColor(Color.WHITE)
            setPadding(0, if (isSpace) dp(2) else dp(6), 0, if (isSpace) dp(4) else dp(10))
            maxLines = when {
                isSpace -> 2
                readingMode == ReadingSupportMode.AUDIO_FIRST -> 3
                else -> 5
            }
        }
        rootLayout.addView(promptView)
        board = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rootLayout.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Keep controls in a stable bottom band (not clipped by overscan).
            setPadding(0, dp(4), 0, if (isSpace) dp(4) else 0)
            minimumHeight = if (isSpace) dp(60) else 0
        }
        rootLayout.addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = if (isSpace) dp(4) else dp(8)
        })
        // Space fits in one 1080p frame without scroll; ScrollView kept for other worlds.
        if (isSpace) {
            setContentView(rootLayout)
        } else {
            setContentView(ScrollView(this).apply { addView(rootLayout); isFillViewport = true })
        }
        lumiView.post {
            lumiView.setEmotionWithMotion(GuideEmotion.CURIOUS)
            lumiView.play(LumiView.Motion.APPEAR)
            sfx.lumiAppear()
        }
        render(runtime.snapshot())
        registerInjectReceiver()
        flushPendingInject()
        consumeInjectExtra(intent)
        val startLine = guide.react(GuideEvent.ADVENTURE_STARTED).text
        present(
            PresentationMessage(
                text = startLine,
                role = PresentationRole.DIALOGUE,
                importance = PresentationImportance.IMPORTANT,
                narrationRef = "common:hello",
            ),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeInjectExtra(intent)
    }

    private var pendingInject: String? = null

    private fun consumeInjectExtra(intent: Intent?) {
        val t = intent?.getStringExtra(EXTRA_INJECT_TRANSCRIPT)?.trim().orEmpty()
        if (t.isBlank()) return
        intent?.removeExtra(EXTRA_INJECT_TRANSCRIPT)
        Log.i(TAG_ASK, "inject transcript=$t")
        if (!::runtime.isInitialized || !::knowledge.isInitialized) {
            pendingInject = t
            Log.i(TAG_ASK, "defer inject until ready")
            return
        }
        // Run on next looper pass so UI is settled; do not drop.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Log.i(TAG_ASK, "handle inject now=$t")
            handleInjectedTranscript(t)
        }
    }

    private fun flushPendingInject() {
        val t = pendingInject ?: return
        pendingInject = null
        Log.i(TAG_ASK, "flush pending inject=$t")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            handleInjectedTranscript(t)
        }
    }

    private fun dispatchRemote(action: FamilyAction) {
        PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, action)
    }

    private fun handleFamilyAction(source: InputSource, action: FamilyAction): Boolean {
        when (action) {
            FamilyAction.Home, FamilyAction.Back -> {
                PlatformServices.trialEvents(this).record(
                    TrialEventStore.ADVENTURE_EXIT_POINT,
                    contentItem.id,
                    mapOf("sceneId" to runtime.snapshot().sceneId),
                )
                persist(); finish(); return true
            }
            FamilyAction.Repeat -> {
                PlatformServices.trialEvents(this).record(TrialEventStore.REPEAT_USED, contentItem.id)
                narration.repeat()
                // Preserve mechanic state; only re-show the problem area visually.
                if (isSpaceLaunchScene(runtime.snapshot()) && launchBoard != null) {
                    launchBoard?.pulseProblemArea()
                    if (::lumiView.isInitialized) lumiView.play(LumiView.Motion.POINT)
                }
                return true
            }
            FamilyAction.Help -> {
                PlatformServices.trialEvents(this).record(TrialEventStore.HELP_USED, contentItem.id)
                if (runtime.requestHelp()) {
                    val snap = runtime.snapshot()
                    sfx.hint()
                    if (::lumiView.isInitialized) {
                        lumiView.setEmotionWithMotion(GuideEmotion.HELPING)
                        lumiView.play(LumiView.Motion.HELP)
                    }
                    present(
                        PresentationMessage(
                            text = snap.guideLine ?: snap.scaffoldMessage ?: "Давай подумаем вместе.",
                            spokenText = snap.guideLine ?: snap.scaffoldMessage,
                            role = PresentationRole.HINT,
                            importance = PresentationImportance.CRITICAL,
                            narrationRef = "common:want_hint",
                        ),
                    )
                    // Space launch: Help is a visual scaffold — stay on the board (no Дальше gate).
                    if (isSpaceLaunchScene(snap)) {
                        runtime.finishFeedback()
                        render(runtime.snapshot())
                        launchBoard?.pulseProblemArea()
                        actions.post {
                            launchBoard?.wireFocusToActions(actions)
                            launchBoard?.requestFirstObjectFocus()
                        }
                    } else {
                        render(snap)
                    }
                } else {
                    narration.repeat()
                }
                return true
            }
            FamilyAction.Next -> {
                val snap = runtime.snapshot()
                when {
                    snap.phase == AdventurePhase.FEEDBACK -> {
                        runtime.finishFeedback()
                        render(runtime.snapshot())
                    }
                    snap.scene is StoryScene || snap.scene is MoveScene ||
                        snap.scene is OffscreenScene || snap.scene is ReflectionScene -> {
                        if (snap.scene is OffscreenScene) runtime.confirmOffscreen()
                        else runtime.advanceStory()
                        render(runtime.snapshot())
                    }
                    else -> return false
                }
                return true
            }
            is FamilyAction.Answer -> {
                if (trySpaceLaunchSoftRejectVoice(action.transcript)) return true
                runtime.voiceInput(action.transcript)
                val snap = runtime.snapshot()
                if (snap.scene is DialogueScene && snap.feedbackCorrect == true) {
                    lifecycleScope.launch {
                        delay(1200)
                        runtime.consumeDialogueAdvance()
                        render(runtime.snapshot())
                    }
                }
                render(runtime.snapshot())
                return true
            }
            is FamilyAction.AskLumi -> return handleAskLumi(action)
            FamilyAction.BlockedSensitive -> {
                guideView.text = "✦ Это могут сделать только родители."
                return true
            }
            FamilyAction.OpenParentGate -> {
                guideView.text = "✦ Попроси родителей открыть режим родителей с пульта."
                return true
            }
            else -> return false
        }
    }

    private var pendingClarification: List<KnowledgeEntry> = emptyList()
    private var injectReceiverRegistered = false

    private val injectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val t = intent?.getStringExtra(EXTRA_INJECT_TRANSCRIPT)?.trim().orEmpty()
            if (t.isBlank()) return
            Log.i(TAG_ASK, "inject transcript=$t")
            handleInjectedTranscript(t)
        }
    }

    private fun registerInjectReceiver() {
        if (injectReceiverRegistered) return
        val filter = IntentFilter(ACTION_INJECT_TRANSCRIPT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(injectReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(injectReceiver, filter)
        }
        injectReceiverRegistered = true
    }

    private fun handleInjectedTranscript(normalizedRaw: String) {
        val normalized = normalizedRaw.lowercase().trim()
        // Acceptance / child curiosity: never treat Q&A phrases as challenge answers.
        val curiosity = CuriosityIntentDetector.detect(normalized)
            ?: when {
                CuriosityIntentDetector.isBareCuriosity(normalized) -> CuriosityIntent.WHY
                CuriosityIntentDetector.looksLikeCuriosity(normalized) -> CuriosityIntent.WHY
                normalized.contains("расскажи") || normalized.contains("придумай") ||
                    normalized.contains("знаешь") || normalized.contains("факт") ||
                    normalized.contains("чёрн") || normalized.contains("черн") ||
                    normalized.startsWith("а ") && normalized.contains("?") -> CuriosityIntent.WHY
                else -> null
            }
        if (curiosity != null) {
            handleFamilyAction(InputSource.VOICE, FamilyAction.AskLumi(curiosity, normalized))
            render(runtime.snapshot())
            return
        }
        val snap = runtime.snapshot()
        val ctx = RouteContext(
            surface = InteractionSurface.ADVENTURE,
            awaitingAnswer = snap.phase == AdventurePhase.CHALLENGE_ACTIVE ||
                snap.phase == AdventurePhase.FEEDBACK,
            allowNext = true,
        )
        when (val routed = VoiceCommandRouter.route(ctx, normalized)) {
            is RouteResult.Action -> handleFamilyAction(InputSource.VOICE, routed.action)
            RouteResult.Unhandled -> Unit
        }
        render(runtime.snapshot())
    }

    private fun logAskResult(kind: String, detail: String) {
        Log.i(TAG_ASK, "result=$kind $detail")
    }

    private fun handleAskLumi(action: FamilyAction.AskLumi): Boolean {
        PlatformServices.trialEvents(this).record(
            TrialEventStore.ASK_LUMI,
            contentItem.id,
            mapOf("intent" to action.intent.name),
        )
        PlatformServices.trialEvents(this).record(
            TrialEventStore.CURIOSITY_QUESTION,
            contentItem.id,
            mapOf("intent" to action.intent.name),
        )
        val age = PlatformServices.familySettings(this).householdAgeBand()
        val result = knowledge.resolve(
            KnowledgeQuery(
                transcript = action.transcript,
                intent = action.intent,
                sceneId = runtime.snapshot().sceneId,
                adventureId = runtime.adventure.id,
                world = runtime.adventure.world,
                ageMin = age.ageMin,
                ageMax = age.ageMax,
            ),
        )
        when (result) {
            is KnowledgeResult.Found -> {
                pendingClarification = emptyList()
                logAskResult(
                    "FOUND",
                    "entryId=${result.entry.id} intent=${result.intent} answer=${result.groundedAnswer}",
                )
                PlatformServices.trialEvents(this).record(
                    TrialEventStore.ASK_LUMI_MATCHED,
                    contentItem.id,
                    mapOf("entryId" to result.entry.id),
                )
                PlatformServices.trialEvents(this).record(
                    TrialEventStore.KNOWLEDGE_FOUND,
                    contentItem.id,
                    mapOf("entryId" to result.entry.id),
                )
                if (action.intent == CuriosityIntent.TELL_ME_MORE ||
                    result.intent == CuriosityIntent.TELL_ME_MORE
                ) {
                    PlatformServices.trialEvents(this).record(TrialEventStore.TELL_ME_MORE, contentItem.id)
                }
                PlatformServices.journeyCoordinator(this).onKnowledgeDiscovered(
                    knowledgeId = result.entry.id,
                    world = runtime.adventure.world,
                    contentId = contentItem.id,
                )
                if (::lumiView.isInitialized) lumiView.setEmotionWithMotion(GuideEmotion.CURIOUS)
                guideView.text = "✦ ${result.groundedAnswer}"
                present(
                    PresentationMessage(
                        text = result.groundedAnswer,
                        spokenText = result.spokenAnswer,
                        narrationRef = result.spokenAnswerAudio,
                        role = PresentationRole.DIALOGUE,
                        importance = PresentationImportance.IMPORTANT,
                        repeatable = true,
                    ),
                )
            }
            is KnowledgeResult.Clarification -> {
                pendingClarification = result.options
                logAskResult(
                    "CLARIFY",
                    "options=${result.options.map { it.id }} prompt=${result.prompt}",
                )
                PlatformServices.trialEvents(this).record(TrialEventStore.ASK_LUMI_CLARIFICATION, contentItem.id)
                if (::lumiView.isInitialized) lumiView.setEmotionWithMotion(GuideEmotion.THINKING)
                guideView.text = "✦ ${result.prompt}"
                present(
                    PresentationMessage(
                        text = result.prompt,
                        spokenText = result.spokenPrompt,
                        role = PresentationRole.DIALOGUE,
                        importance = PresentationImportance.IMPORTANT,
                        repeatable = true,
                    ),
                )
                actions.removeAllViews()
                result.options.forEach { opt ->
                    val label = opt.clarificationLabel ?: opt.aliases.firstOrNull() ?: opt.topic
                    actions.addView(
                        GameStyle.button(this, label) {
                            val picked = knowledge.engine.selectClarification(
                                opt.id,
                                KnowledgeQuery(
                                    transcript = label,
                                    adventureId = runtime.adventure.id,
                                    world = runtime.adventure.world,
                                    ageMin = age.ageMin,
                                    ageMax = age.ageMax,
                                ),
                            )
                            pendingClarification = emptyList()
                            if (picked is KnowledgeResult.Found) {
                                guideView.text = "✦ ${picked.groundedAnswer}"
                                present(
                                    PresentationMessage(
                                        text = picked.groundedAnswer,
                                        spokenText = picked.spokenAnswer,
                                        narrationRef = picked.spokenAnswerAudio,
                                        role = PresentationRole.DIALOGUE,
                                        importance = PresentationImportance.IMPORTANT,
                                        repeatable = true,
                                    ),
                                )
                            }
                            render(runtime.snapshot())
                        },
                        LinearLayout.LayoutParams(-2, GameStyle.dp(this, 56)).apply {
                            marginEnd = GameStyle.dp(this@AdventureActivity, 8)
                        },
                    )
                }
            }
            is KnowledgeResult.AskToExperiment -> {
                pendingClarification = emptyList()
                logAskResult(
                    "EXPERIMENT",
                    "ref=${result.experimentActionRef} invite=${result.invite}",
                )
                PlatformServices.trialEvents(this).record(
                    TrialEventStore.ASK_TO_EXPERIMENT,
                    contentItem.id,
                    mapOf("ref" to result.experimentActionRef),
                )
                if (::lumiView.isInitialized) lumiView.setEmotionWithMotion(GuideEmotion.EXCITED)
                guideView.text = "✦ ${result.invite}"
                present(
                    PresentationMessage(
                        text = result.invite,
                        spokenText = result.spokenInvite,
                        role = PresentationRole.DIALOGUE,
                        importance = PresentationImportance.IMPORTANT,
                        repeatable = true,
                    ),
                )
                if (runtime.jumpToChallengeById(result.experimentActionRef)) {
                    render(runtime.snapshot())
                }
            }
            is KnowledgeResult.Unknown -> {
                pendingClarification = emptyList()
                logAskResult("UNKNOWN", "reply=${result.softReply}")
                PlatformServices.trialEvents(this).record(TrialEventStore.ASK_LUMI_UNKNOWN, contentItem.id)
                PlatformServices.trialEvents(this).record(TrialEventStore.KNOWLEDGE_UNKNOWN, contentItem.id)
                if (::lumiView.isInitialized) lumiView.setEmotionWithMotion(GuideEmotion.THINKING)
                guideView.text = "✦ ${result.softReply}"
                present(
                    PresentationMessage(
                        text = result.softReply,
                        spokenText = result.softReply,
                        role = PresentationRole.DIALOGUE,
                        importance = PresentationImportance.IMPORTANT,
                        repeatable = true,
                    ),
                )
            }
        }
        return true
    }

    private fun present(message: PresentationMessage) {
        // Always keep spoken text visible while narrating
        if (message.text.isNotBlank() && guideView.text.isNullOrBlank()) {
            // prompt/guide already set by render
        }
        narration.present(message)
    }

    private fun presentInstruction(
        display: String,
        spoken: String? = null,
        audioRef: String? = null,
        role: PresentationRole = PresentationRole.INSTRUCTION,
    ) {
        present(
            PresentationMessage(
                text = display,
                spokenText = spoken,
                narrationRef = audioRef,
                role = role,
                importance = PresentationImportance.CRITICAL,
                repeatable = true,
            ),
        )
    }

    private fun render(state: AdventureViewState) {
        persist()
        fun dp(v: Int) = GameStyle.dp(this, v)
        val sceneChanged = lastSceneId != null && lastSceneId != state.sceneId
        lastSceneId = state.sceneId
        if (sceneChanged) {
            sfx.sceneTransition()
            SceneTransitions.fadeSwap(board) { }
        }
        val sceneIndex = runtime.adventure.scenes.indexOfFirst { it.id == state.sceneId }.coerceAtLeast(0)
        val progressApprox = (sceneIndex + 1).toFloat() / runtime.adventure.scenes.size.coerceAtLeast(1)
        if (::worldStage.isInitialized) {
            worldStage.theme = when {
                runtime.adventure.id.contains("space") -> AdventureWorldStage.Theme.SPACE
                runtime.adventure.id.contains("forest") -> AdventureWorldStage.Theme.FOREST
                else -> AdventureWorldStage.Theme.GENERIC
            }
            worldStage.worldProgress = progressApprox
            worldStage.celebrating = state.feedbackCorrect == true || state.phase == AdventurePhase.COMPLETED
            if (runtime.adventure.id.contains("space")) {
                val powered = state.variables["shipPower"] == 1 ||
                    state.sceneId == "launch_flight" ||
                    (state.sceneId == "launch_repair" && state.feedbackCorrect == true) ||
                    state.completedObjectives.contains("launch")
                worldStage.spaceShipPowered = powered
                worldStage.spaceShipFlying = state.sceneId == "launch_flight" ||
                    (powered && state.sceneId != "intro" && state.sceneId != "launch_repair")
                // Launch board already shows the ship — collapse the mid-stage strip.
                val launchScene = state.sceneId == "intro" ||
                    state.sceneId == "launch_repair" ||
                    state.sceneId == "launch_flight"
                worldStage.visibility = if (launchScene) android.view.View.GONE else android.view.View.VISIBLE
                worldStage.layoutParams = (worldStage.layoutParams as LinearLayout.LayoutParams).apply {
                    height = if (launchScene) 0 else GameStyle.dp(this@AdventureActivity, 168)
                }
            }
        }
        if (::lumiView.isInitialized) {
            lumiView.emotion = state.guideEmotion
            when {
                state.feedbackCorrect == true -> {
                    lumiView.play(LumiView.Motion.CELEBRATE)
                    sfx.confirm()
                    sfx.objectiveComplete()
                }
                state.feedbackCorrect == false -> {
                    lumiView.play(LumiView.Motion.REACT_WRONG)
                    sfx.softWrong()
                }
                state.phase == AdventurePhase.CHALLENGE_ACTIVE -> lumiView.play(LumiView.Motion.LISTEN)
            }
        }
        board.removeAllViews()
        actions.removeAllViews()
        armIdlePrompt()
        val nonReader = NonReaderSimulation.isEnabled(
            getSharedPreferences(NonReaderSimulation.PREFS, MODE_PRIVATE),
        )
        guideView.alpha = if (nonReader) 0.35f else 1f
        promptView.alpha = if (nonReader) 0.35f else 1f
        guideView.text = "${runtime.adventure.guide.name}: ${state.guideLine.orEmpty()}"
        groupView.text = state.groupPrompt.orEmpty()
        groupView.visibility = if (state.groupPrompt.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

        if (state.phase == AdventurePhase.COMPLETED) {
            promptView.text = "Приключение завершено"
            if (::worldStage.isInitialized) {
                worldStage.worldProgress = 1f
                worldStage.celebrating = true
            }
            board.addView(GameStyle.text(this, state.guideLine ?: "Молодцы!", 28f, true).apply {
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            val home = GameStyle.button(this, "К мирам") { finish() }
            actions.addView(home, LinearLayout.LayoutParams(-2, dp(56)))
            home.requestFocus()
            PlatformServices.adventureEvents(this).record(runtime.adventure.id, "adventure_completed")
            PlatformServices.trialEvents(this).record(TrialEventStore.ADVENTURE_COMPLETE, contentItem.id)
            PlatformServices.adventureProgress(this).save(
                runtime.adventure.id, state.sceneId, completed = true,
                contentItem.packageId, contentItem.packageVersion,
            )
            PlatformServices.journeyCoordinator(this).onContentCompleted(contentItem.id)
            PlatformServices.activityHistory(this).record(
                kind = ActivityKind.INTERACTIVE,
                title = contentItem.title,
                contentId = contentItem.id,
                topics = listOf("adventure", runtime.adventure.world),
                durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            )
            presentInstruction(
                state.guideLine ?: "Молодцы!",
                audioRef = "common:well_done",
                role = PresentationRole.FEEDBACK,
            )
            return
        }

        when (val scene = state.scene) {
            is StoryScene, is ReflectionScene, is MoveScene -> {
                val text = when (scene) {
                    is StoryScene -> scene.text
                    is ReflectionScene -> scene.text
                    is MoveScene -> scene.prompt
                    else -> ""
                }
                promptView.text = when {
                    readingMode == ReadingSupportMode.AUDIO_FIRST && scene is StoryScene ->
                        scene.spokenPrompt ?: text
                    else -> text
                }
                if (scene is MoveScene) sfx.movementStart()
                // Space vertical slice: keep the ship visible during intro / flight beats.
                if (runtime.adventure.id.contains("space") && scene is StoryScene &&
                    (scene.id == "intro" || scene.id == "launch_flight")
                ) {
                    applySpaceLaunchChrome(compact = true)
                    val boardView = SpaceLaunchBoard(this)
                    launchBoard = boardView
                    val selected = if (scene.id == "launch_flight") {
                        setOf("cell_a", "cell_b", "cell_c")
                    } else emptySet()
                    val objects = if (scene.id == "launch_flight") {
                        listOf(
                            SearchObject("cell_a", "Энергия"),
                            SearchObject("cell_b", "Энергия"),
                            SearchObject("cell_c", "Энергия"),
                        )
                    } else emptyList()
                    boardView.bind(
                        objects = objects,
                        requiredIds = setOf("cell_a", "cell_b", "cell_c"),
                        selectedIds = selected,
                        highlightIds = emptySet(),
                        scaffold = ScaffoldLevel.NONE,
                        celebrating = scene.id == "launch_flight",
                        interactive = false,
                    )
                    if (scene.id == "intro") {
                        boardView.pulseProblemArea()
                        if (::lumiView.isInitialized) lumiView.play(LumiView.Motion.POINT)
                    }
                    if (scene.id == "launch_flight") {
                        worldStage.spaceShipPowered = true
                        worldStage.spaceShipFlying = true
                        boardView.playEngineStart()
                    }
                    this.board.addView(boardView)
                }
                val label = when (scene) {
                    is StoryScene -> scene.continueLabel
                    is ReflectionScene -> scene.continueLabel
                    is MoveScene -> scene.continueLabel
                    else -> "Дальше"
                }
                val go = GameStyle.button(this, label) {
                    if (scene is MoveScene) sfx.movementEnd()
                    dispatchRemote(FamilyAction.Next)
                }
                actions.addView(go, LinearLayout.LayoutParams(-2, dp(56)))
                addRepeatHelpButtons()
                go.requestFocus()
                val spoken = (scene as? StoryScene)?.spokenPrompt
                val audio = when (scene) {
                    is StoryScene -> scene.narrationAudio
                    is ReflectionScene -> scene.narrationAudio
                    is MoveScene -> scene.narrationAudio
                    else -> null
                }
                presentInstruction(text, spoken = spoken, audioRef = audio, role = when (scene) {
                    is MoveScene -> PresentationRole.MOVE
                    else -> PresentationRole.INSTRUCTION
                })
            }
            is OffscreenScene -> {
                promptView.text = scene.prompt
                board.addView(GameStyle.text(this, "Сделайте это в комнате, потом нажмите «${scene.continueLabel}».", 18f).apply {
                    setTextColor(Color.rgb(200, 210, 230))
                })
                val go = GameStyle.button(this, scene.continueLabel) {
                    PlatformServices.trialEvents(this).record(TrialEventStore.OFFSCREEN_COMPLETE, contentItem.id)
                    dispatchRemote(FamilyAction.Next)
                }
                actions.addView(go, LinearLayout.LayoutParams(-2, dp(56)))
                maybeVoiceButton()
                addRepeatHelpButtons()
                go.requestFocus()
                presentInstruction(
                    scene.prompt,
                    audioRef = scene.narrationAudio,
                    role = PresentationRole.MOVE,
                )
            }
            is DialogueScene -> {
                promptView.text = scene.prompt
                board.addView(GameStyle.text(this, "Скажите ответ вслух.", 18f).apply {
                    setTextColor(Color.rgb(200, 210, 230))
                })
                // Opt-in mic only — do not auto-load VoiceRuntime (H728 AI=0 until child taps).
                maybeVoiceButton(auto = false)
                addRepeatHelpButtons()
                presentInstruction(
                    scene.prompt,
                    audioRef = scene.narrationAudio,
                    role = PresentationRole.QUESTION,
                )
            }
            is ChallengeScene -> {
                promptView.text = if (readingMode == ReadingSupportMode.AUDIO_FIRST) {
                    scene.challenge.spokenPrompt ?: scene.challenge.prompt
                } else scene.challenge.prompt
                val spaceLaunch = SpaceLaunchBoard.isSpaceLaunchChallenge(
                    scene.challenge.id,
                    state.sceneId,
                )
                if (state.phase == AdventurePhase.FEEDBACK) {
                    val warm = state.feedbackCorrect == true
                    if (spaceLaunch) {
                        applySpaceLaunchChrome(compact = true)
                        renderSpaceLaunchBoard(scene, state, interactive = false)
                        if (warm && !launchEnginePlayed) {
                            launchEnginePlayed = true
                            worldStage.spaceShipPowered = true
                            if (::lumiView.isInitialized) {
                                lumiView.setEmotionWithMotion(GuideEmotion.CELEBRATING)
                                lumiView.play(LumiView.Motion.CELEBRATE)
                            }
                            launchBoard?.playEngineStart {
                                worldStage.spaceShipFlying = true
                                worldStage.celebrating = true
                                if (::lumiView.isInitialized) {
                                    lumiView.setEmotionWithMotion(GuideEmotion.HAPPY)
                                }
                            }
                        }
                        guideView.text = "${runtime.adventure.guide.name}: ${
                            if (warm) "Энергия на месте! Двигатель запускается!"
                            else state.guideLine.orEmpty()
                        }"
                    } else {
                        board.addView(GameStyle.text(this, state.guideLine ?: "", 22f, true).apply {
                            setTextColor(
                                when {
                                    warm -> Color.rgb(140, 230, 180)
                                    else -> Color.rgb(255, 220, 160) // gentle, not punitive red
                                },
                            )
                        })
                        if (warm) {
                            board.addView(GameStyle.text(this, worldReactionLine(), 16f).apply {
                                setTextColor(Color.rgb(180, 210, 255))
                                setPadding(0, dp(8), 0, 0)
                            })
                        }
                    }
                    val cont = GameStyle.button(this, "Дальше") {
                        dispatchRemote(FamilyAction.Next)
                    }
                    actions.addView(cont, LinearLayout.LayoutParams(-2, dp(56)))
                    addRepeatHelpButtons()
                    if (spaceLaunch && warm) {
                        // Let the ~1.5–2s engine beat play before focus jumps to Дальше.
                        cont.isFocusable = false
                        cont.postDelayed({
                            cont.isFocusable = true
                            cont.isFocusableInTouchMode = false
                            cont.requestFocus()
                        }, 1700L)
                    } else {
                        cont.requestFocus()
                    }
                    feedbackJob?.cancel()
                    feedbackJob = lifecycleScope.launch {
                        delay(
                            when {
                                spaceLaunch && warm -> 1900L
                                warm -> 900L
                                else -> 1200L
                            },
                        )
                    }
                    presentInstruction(
                        if (spaceLaunch && warm) {
                            "Энергия на месте! Двигатель запускается!"
                        } else {
                            state.guideLine ?: ""
                        },
                        audioRef = if (warm) "common:good_thought" else "common:try_again",
                        role = if (warm) PresentationRole.FEEDBACK else PresentationRole.HINT,
                    )
                    return
                }
                launchEnginePlayed = false
                if (spaceLaunch) applySpaceLaunchChrome(compact = true)
                renderMechanic(scene, state)
                if (!spaceLaunch) {
                    val check = GameStyle.button(this, "Проверить") {
                        sfx.select()
                        runtime.submitMechanic()
                        render(runtime.snapshot())
                    }
                    actions.addView(check, LinearLayout.LayoutParams(-2, dp(56)).apply { marginEnd = dp(12) })
                }
                // Space launch: skip mic to keep bottom controls uncrowded and on-screen.
                if (!spaceLaunch) maybeVoiceButton()
                addRepeatHelpButtons()
                if (spaceLaunch) {
                    actions.post {
                        launchBoard?.wireFocusToActions(actions)
                        launchBoard?.requestFirstObjectFocus()
                    }
                }
                presentInstruction(
                    scene.challenge.prompt,
                    spoken = scene.challenge.spokenPrompt,
                    audioRef = scene.challenge.promptAudio ?: scene.narrationAudio,
                    role = PresentationRole.QUESTION,
                )
            }
            null -> finish()
        }
    }

    private fun worldReactionLine(): String = when {
        runtime.adventure.id.contains("space") -> when (runtime.snapshot().sceneId) {
            "launch_repair", "launch_search", "launch_flight" -> "Энергия горит. Двигатель ревёт — корабль уходит вверх!"
            "nav_maze" -> "Курс открывается между звёздами!"
            "repair_pattern" -> "Панель починена. Сигналы ровные."
            "planet_sort" -> "Планета ближе. Карта обновилась."
            else -> "Корабль отвечает. Идём дальше!"
        }
        runtime.adventure.id.contains("forest") -> "На тропе новые следы. Карта яснее."
        else -> "Мир откликнулся на ваш выбор."
    }

    private fun addRepeatHelpButtons() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val repeat = ChildChrome.iconButton(this, R.drawable.ic_action_repeat, "Повтори") {
            dispatchRemote(FamilyAction.Repeat)
            armIdlePrompt()
        }
        val help = ChildChrome.iconButton(this, R.drawable.ic_action_help, "Помоги") {
            dispatchRemote(FamilyAction.Help)
            armIdlePrompt()
        }
        actions.addView(repeat, LinearLayout.LayoutParams(dp(72), dp(56)).apply {
            marginStart = dp(12)
            marginEnd = dp(8)
        })
        actions.addView(help, LinearLayout.LayoutParams(dp(72), dp(56)))
    }

    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var idleRunnable: Runnable? = null

    private fun armIdlePrompt() {
        idleRunnable?.let { idleHandler.removeCallbacks(it) }
        val run = Runnable {
            if (::lumiView.isInitialized) lumiView.setEmotionWithMotion(GuideEmotion.HELPING)
            present(
                PresentationMessage(
                    text = "Хочешь, повторю?",
                    spokenText = "Хочешь, повторю?",
                    narrationRef = "common:want_repeat",
                    role = PresentationRole.HINT,
                    importance = PresentationImportance.SECONDARY,
                    repeatable = false,
                ),
            )
        }
        idleRunnable = run
        idleHandler.postDelayed(run, 28_000L)
    }

    private fun renderMechanic(scene: ChallengeScene, state: AdventureViewState) {
        val d = { v: Int -> GameStyle.dp(this, v) }
        val snap = state.mechanic ?: return
        when (val params = scene.challenge.params) {
            is VisualSearchParams -> {
                val spaceLaunch = SpaceLaunchBoard.isSpaceLaunchChallenge(
                    scene.challenge.id,
                    runtime.snapshot().sceneId,
                )
                if (spaceLaunch) {
                    renderSpaceLaunchBoard(scene, state, interactive = true)
                    return
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                params.objects.forEach { obj ->
                    val selected = obj.id in snap.selectedIds
                    val highlighted = obj.id in snap.highlightIds
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(d(12), d(10), d(12), d(10))
                        background = GradientDrawable().apply {
                            cornerRadius = d(20).toFloat()
                            setColor(
                                when {
                                    selected -> Color.rgb(40, 110, 90)
                                    highlighted -> Color.rgb(70, 90, 50)
                                    else -> Color.rgb(40, 55, 85)
                                },
                            )
                            if (highlighted || selected) {
                                setStroke(d(4), Color.rgb(255, 220, 120))
                            }
                        }
                        isFocusable = true
                        isClickable = true
                        setOnClickListener {
                            sfx.select()
                            runtime.selectMechanicTarget(obj.id)
                            render(runtime.snapshot())
                        }
                        setOnFocusChangeListener { v, hasFocus ->
                            v.scaleX = if (hasFocus) 1.06f else 1f
                            v.scaleY = if (hasFocus) 1.06f else 1f
                        }
                    }
                    tile.addView(GameStyle.text(this, mechanicGlyph(obj.id, obj.label), 32f, true).apply {
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        contentDescription = obj.label
                    })
                    val nonReader = NonReaderSimulation.isEnabled(
                        getSharedPreferences(NonReaderSimulation.PREFS, MODE_PRIVATE),
                    )
                    if (!nonReader) {
                        tile.addView(GameStyle.text(this, obj.label, 16f).apply {
                            setTextColor(Color.rgb(220, 230, 240))
                            gravity = Gravity.CENTER
                        })
                    }
                    row.addView(tile, LinearLayout.LayoutParams(d(110), d(100)).apply {
                        marginEnd = d(10)
                    })
                }
                board.addView(row)
            }
            is SortClassifyParams -> {
                // Category bins first — items visually “ride” into a bin when placed
                val bins = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                params.categories.forEach { cat ->
                    val bin = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(d(10), d(8), d(10), d(8))
                        background = GradientDrawable().apply {
                            cornerRadius = d(16).toFloat()
                            setColor(Color.rgb(35, 55, 75))
                            setStroke(d(2), Color.rgb(140, 180, 210))
                        }
                    }
                    bin.addView(GameStyle.text(this, cat.label.take(1), 22f, true).apply {
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        contentDescription = cat.label
                    })
                    val inBin = params.items.filter { snap.placements[it.id] == cat.id }
                    inBin.forEach { item ->
                        bin.addView(GameStyle.text(this, mechanicGlyph(item.id, item.label), 20f).apply {
                            setTextColor(Color.rgb(200, 230, 180))
                            gravity = Gravity.CENTER
                            contentDescription = item.label
                        })
                    }
                    bins.addView(bin, LinearLayout.LayoutParams(d(120), -2).apply { marginEnd = d(10) })
                }
                board.addView(bins)
                params.items.filter { snap.placements[it.id] == null }.forEach { item ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, d(6), 0, 0)
                    }
                    row.addView(GameStyle.text(this, mechanicGlyph(item.id, item.label) + " " + item.label, 18f).apply {
                        setTextColor(Color.WHITE)
                    })
                    params.categories.forEach { cat ->
                        val b = GameStyle.button(this, cat.label.take(1)) {
                            sfx.select()
                            runtime.selectMechanicTarget("${item.id}:${cat.id}")
                            render(runtime.snapshot())
                        }.apply {
                            textSize = 16f
                            contentDescription = "${item.label} → ${cat.label}"
                        }
                        row.addView(b, LinearLayout.LayoutParams(d(64), d(52)).apply { marginStart = d(8) })
                    }
                    board.addView(row)
                }
            }
            is PatternSequenceParams -> {
                val slots = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                params.display.forEachIndexed { idx, cell ->
                    val blank = idx == params.blankIndex
                    val shown = if (blank) (snap.chosenOption ?: "？") else cell
                    val slot = GameStyle.text(this, shown, 28f, true).apply {
                        setTextColor(if (blank) Color.rgb(255, 230, 120) else Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(d(14), d(10), d(14), d(10))
                        background = GradientDrawable().apply {
                            cornerRadius = d(14).toFloat()
                            setColor(if (blank) Color.rgb(60, 80, 40) else Color.rgb(40, 55, 85))
                            if (blank) setStroke(d(4), Color.rgb(255, 220, 100))
                        }
                    }
                    slots.addView(slot, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = d(8) })
                }
                board.addView(slots)
                val opts = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                params.options.forEach { opt ->
                    val b = GameStyle.button(this, opt) {
                        sfx.select()
                        runtime.selectMechanicTarget(opt)
                        render(runtime.snapshot())
                    }.apply {
                        textSize = 22f
                        if (snap.chosenOption == opt) setBackgroundColor(Color.rgb(70, 110, 160))
                    }
                    opts.addView(b, LinearLayout.LayoutParams(-2, d(64)).apply { marginEnd = d(10) })
                }
                board.addView(opts)
            }
            is PathMazeParams -> {
                val grid = GridLayout(this).apply {
                    columnCount = params.width
                    rowCount = params.height
                }
                val shipGlyph = if (runtime.adventure.id.contains("space")) "🚀" else "●"
                for (y in 0 until params.height) {
                    for (x in 0 until params.width) {
                        val pos = GridPos(x, y)
                        val label = when {
                            pos == snap.cursor -> shipGlyph
                            pos in params.blocked -> "■"
                            pos == params.goal -> "★"
                            else -> params.cellLabels[pos] ?: "·"
                        }
                        val onPath = pos in snap.path
                        val cell = GameStyle.button(this, label) {
                            val cur = snap.cursor ?: return@button
                            val man = kotlin.math.abs(pos.x - cur.x) + kotlin.math.abs(pos.y - cur.y)
                            if (man == 1) {
                                val dir = when {
                                    pos.y < cur.y -> "UP"
                                    pos.y > cur.y -> "DOWN"
                                    pos.x < cur.x -> "LEFT"
                                    else -> "RIGHT"
                                }
                                sfx.select()
                                runtime.selectMechanicTarget(dir)
                                render(runtime.snapshot())
                            }
                        }.apply {
                            textSize = 16f
                            minimumWidth = d(72)
                            contentDescription = label
                            if (onPath) setBackgroundColor(Color.rgb(50, 100, 140))
                            if (pos == snap.cursor) setBackgroundColor(Color.rgb(80, 160, 120))
                        }
                        grid.addView(cell, GridLayout.LayoutParams().apply {
                            width = d(88)
                            height = d(72)
                            setMargins(d(4), d(4), d(4), d(4))
                        })
                    }
                }
                board.addView(grid)
                val dirs = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                listOf("UP" to "↑", "LEFT" to "←", "DOWN" to "↓", "RIGHT" to "→").forEach { (id, label) ->
                    val b = GameStyle.button(this, label) {
                        sfx.select()
                        runtime.selectMechanicTarget(id)
                        render(runtime.snapshot())
                    }.apply { contentDescription = id }
                    dirs.addView(b, LinearLayout.LayoutParams(d(72), d(64)).apply { marginEnd = d(8) })
                }
                board.addView(dirs)
            }
            is PredictAndTestParams -> {
                if (snap.phase == "TESTED" && snap.observation != null) {
                    board.addView(GameStyle.text(this, snap.observation!!, 20f).apply {
                        setTextColor(Color.rgb(220, 240, 200))
                        gravity = Gravity.CENTER
                    })
                    val expKey = "${scene.challenge.id}:${snap.chosenOption}:${snap.observation}"
                    if (recordedExperiments.add(expKey)) {
                        PlatformServices.journeyCoordinator(this).onExperimentObserved(
                            experimentId = scene.challenge.id,
                            contentId = contentItem.id,
                            variants = listOfNotNull(snap.chosenOption),
                            outcome = snap.observation!!,
                            spoken = snap.observation,
                        )
                        PlatformServices.trialEvents(this).record(
                            TrialEventStore.EXPERIMENT_VARIANT_TESTED,
                            contentItem.id,
                            mapOf("experimentId" to scene.challenge.id),
                        )
                    }
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                params.options.forEach { opt ->
                    val selected = snap.chosenOption == opt.id
                    val b = GameStyle.button(this, "${mechanicGlyph(opt.id, opt.label)} ${opt.label}") {
                        sfx.select()
                        runtime.selectMechanicTarget(opt.id)
                        PlatformServices.trialEvents(this@AdventureActivity).record(
                            TrialEventStore.PREDICTION_MADE,
                            contentItem.id,
                            mapOf("option" to opt.id),
                        )
                        render(runtime.snapshot())
                    }.apply {
                        textSize = 18f
                        contentDescription = opt.label
                        if (selected) setBackgroundColor(Color.rgb(70, 120, 90))
                    }
                    row.addView(b, LinearLayout.LayoutParams(-2, d(72)).apply { marginEnd = d(10) })
                }
                board.addView(row)
            }
            is BuildAndTestParams -> {
                if (snap.observation != null) {
                    board.addView(GameStyle.text(this, snap.observation!!, 18f).apply {
                        setTextColor(Color.rgb(230, 230, 180))
                        gravity = Gravity.CENTER
                    })
                }
                val slotsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                params.slots.forEach { slot ->
                    val filled = params.parts.firstOrNull { snap.placements[it.id] == slot.id }
                    val box = GameStyle.text(
                        this,
                        "${slot.label}: ${filled?.let { mechanicGlyph(it.id, it.label) } ?: "·"}",
                        18f,
                    ).apply {
                        setTextColor(Color.WHITE)
                        setPadding(d(12), d(10), d(12), d(10))
                        background = GradientDrawable().apply {
                            cornerRadius = d(12).toFloat()
                            setColor(Color.rgb(40, 60, 80))
                            setStroke(d(2), Color.rgb(140, 180, 200))
                        }
                        contentDescription = slot.label
                    }
                    slotsRow.addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = d(10) })
                }
                board.addView(slotsRow)
                val partsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                params.parts.forEach { part ->
                    val used = snap.placements.containsKey(part.id)
                    val b = GameStyle.button(this, "${mechanicGlyph(part.id, part.label)} ${part.label}") {
                        sfx.select()
                        runtime.selectMechanicTarget(part.id)
                        render(runtime.snapshot())
                    }.apply {
                        textSize = 16f
                        contentDescription = part.label
                        if (used) setBackgroundColor(Color.rgb(60, 100, 80))
                    }
                    partsRow.addView(b, LinearLayout.LayoutParams(-2, d(64)).apply { marginEnd = d(8) })
                }
                board.addView(partsRow)
            }
        }
    }

    private fun mechanicGlyph(id: String, label: String): String = when (id) {
        "cell_a", "cell_b", "cell_c" -> "🔋"
        "cork", "boat", "ball" -> "○"
        "sun" -> "☀"
        "shade" -> "☁"
        "cold" -> "❄"
        "umbrella" -> "☂"
        "lamp" -> "💡"
        "icepack" -> "🧊"
        "heater" -> "♨"
        "rock", "key", "wrench" -> "◆"
        "star" -> "★"
        "squirrel", "fox", "bird" -> "✶"
        "cone", "fir", "moss" -> "♣"
        "whale", "fish" -> "≋"
        "tree" -> "♣"
        "cloud" -> "○"
        else -> label.take(1).ifBlank { "·" }
    }

    private fun applySpaceLaunchChrome(compact: Boolean) {
        fun dp(v: Int) = GameStyle.dp(this, v)
        if (!compact) return
        // Shrink text chrome so scene + bottom controls fit with overscan margin.
        groupView.visibility = android.view.View.GONE
        guideView.maxLines = 2
        promptView.maxLines = 2
        promptView.textSize = 18f
        guideView.textSize = 15f
        // Ensure bottom padding survives re-renders.
        rootLayout.setPadding(
            rootLayout.paddingLeft,
            rootLayout.paddingTop,
            rootLayout.paddingRight,
            dp(56).coerceAtLeast(rootLayout.paddingBottom),
        )
    }

    private fun isSpaceLaunchScene(state: AdventureViewState): Boolean {
        val scene = state.scene as? ChallengeScene ?: return state.sceneId == "intro" ||
            state.sceneId == "launch_flight" ||
            state.sceneId == "launch_repair"
        return SpaceLaunchBoard.isSpaceLaunchChallenge(scene.challenge.id, state.sceneId)
    }

    private fun renderSpaceLaunchBoard(
        scene: ChallengeScene,
        state: AdventureViewState,
        interactive: Boolean,
    ) {
        val params = scene.challenge.params as? VisualSearchParams ?: return
        val snap = state.mechanic
        val boardView = SpaceLaunchBoard(this)
        launchBoard = boardView
        val selected = snap?.selectedIds ?: emptySet()
        val highlight = when {
            state.scaffoldLevel == ScaffoldLevel.HINT2_VISUAL ->
                params.requiredIds.toList().take(2).toSet()
            else -> snap?.highlightIds ?: emptySet()
        }
        boardView.bind(
            objects = params.objects,
            requiredIds = params.requiredIds,
            selectedIds = selected,
            highlightIds = highlight,
            scaffold = state.scaffoldLevel,
            celebrating = state.feedbackCorrect == true,
            interactive = interactive && state.phase == AdventurePhase.CHALLENGE_ACTIVE,
        )
        if (interactive) {
            boardView.onPick = SpaceLaunchBoard.ObjectPickListener { objectId ->
                onSpaceLaunchPick(objectId, params)
            }
        }
        board.addView(boardView)
        if (state.scaffoldLevel == ScaffoldLevel.HINT1 || state.sceneId == "launch_repair" && selected.isEmpty()) {
            boardView.pulseProblemArea()
        }
    }

    private fun onSpaceLaunchPick(objectId: String, params: VisualSearchParams) {
        if (objectId !in params.requiredIds) {
            softRejectSpaceObject(objectId)
            return
        }
        val before = runtime.snapshot().mechanic?.selectedIds ?: emptySet()
        if (objectId in before) return // already installed — do not toggle off
        val beforeCount = before.size
        sfx.select()
        // Defer hierarchy rebuild — never removeAllViews inside the click callback.
        board.post {
            runtime.selectMechanicTarget(objectId)
            val after = runtime.snapshot()
            render(after)
            launchBoard?.let { boardView ->
                // Slot pop after bind already shows the cell; brief pulse for feedback.
                val slotIndex = beforeCount.coerceAtMost(2)
                boardView.playInstall(objectId, slotIndex)
            }
            if (after.feedbackCorrect == true && ::lumiView.isInitialized) {
                lumiView.setEmotionWithMotion(GuideEmotion.SURPRISED)
                lumiView.play(LumiView.Motion.CELEBRATE)
            }
        }
    }

    private fun softRejectSpaceObject(objectId: String) {
        sfx.softWrong()
        if (::lumiView.isInitialized) {
            lumiView.setEmotionWithMotion(GuideEmotion.ENCOURAGING)
            lumiView.play(LumiView.Motion.REACT_WRONG)
        }
        launchBoard?.playSoftReject(objectId)
        guideView.text = "✦ Этот не подходит. Посмотрим ещё."
        present(
            PresentationMessage(
                text = "Этот не подходит. Посмотрим ещё.",
                spokenText = "Этот не подходит. Посмотрим ещё.",
                narrationRef = "common:try_again",
                role = PresentationRole.HINT,
                importance = PresentationImportance.IMPORTANT,
                repeatable = false,
            ),
        )
    }

    private fun trySpaceLaunchSoftRejectVoice(transcript: String): Boolean {
        val state = runtime.snapshot()
        val scene = state.scene as? ChallengeScene ?: return false
        if (!SpaceLaunchBoard.isSpaceLaunchChallenge(scene.challenge.id, state.sceneId)) return false
        if (state.phase != AdventurePhase.CHALLENGE_ACTIVE) return false
        val params = scene.challenge.params as? VisualSearchParams ?: return false
        val selected = state.mechanic?.selectedIds ?: emptySet()
        val norm = transcript.lowercase().trim()
        val match = params.objects.firstOrNull { obj ->
            obj.id !in selected && (
                obj.label.lowercase() == norm ||
                    obj.voiceAliases.any { a -> norm == a.lowercase() || norm.contains(a.lowercase()) } ||
                    norm.contains(obj.label.lowercase())
                )
        } ?: return false
        if (match.id in params.requiredIds) {
            onSpaceLaunchPick(match.id, params)
            return true
        }
        softRejectSpaceObject(match.id)
        return true
    }

    private fun maybeVoiceButton(auto: Boolean = false) {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val mic = GameStyle.button(this, "🎤 Сказать") {
            when (
                MicPermissionGate.nextAction(
                    hasPermission = hasMic(),
                    alreadyAskedThisSession = askedPermission,
                    shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
                )
            ) {
                MicPermissionAction.PROCEED -> {
                    ensureVoice()
                    pipeline?.activate()
                    pipeline?.startListening()
                }
                MicPermissionAction.EXPLAIN_THEN_REQUEST -> {
                    askedPermission = true
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                MicPermissionAction.SHOW_UNAVAILABLE -> {
                    guideView.text = "✦ ${MicPermissionGate.CHILD_MESSAGE}"
                }
            }
        }
        actions.addView(mic, LinearLayout.LayoutParams(-2, dp(56)))
        if (auto && hasMic()) {
            ensureVoice()
            pipeline?.activate()
            pipeline?.startListening()
        }
    }

    private fun ensureVoice() {
        if (voiceStarted) return
        if (!hasMic()) return
        voiceStarted = true
        try {
            pipeline = VoiceRuntime.get(this).newPipeline()
        } catch (_: Exception) {
            return
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pipeline?.state?.collect { voice ->
                    when (voice.phase) {
                        VoicePhase.RESULT -> voice.answer?.let { answer ->
                            pipeline?.stopListening()
                            val transcript = answer.normalizedText.lowercase()
                            val snap = runtime.snapshot()
                            val ctx = RouteContext(
                                surface = InteractionSurface.ADVENTURE,
                                awaitingAnswer = snap.phase == AdventurePhase.CHALLENGE_ACTIVE ||
                                    snap.scene is DialogueScene ||
                                    snap.scene is OffscreenScene,
                                allowNext = snap.phase == AdventurePhase.FEEDBACK ||
                                    snap.scene is StoryScene ||
                                    snap.scene is MoveScene ||
                                    snap.scene is ReflectionScene,
                            )
                            when (val routed = VoiceCommandRouter.route(ctx, transcript)) {
                                is RouteResult.Action -> handleFamilyAction(InputSource.VOICE, routed.action)
                                RouteResult.Unhandled -> {
                                    if (ctx.awaitingAnswer) {
                                        handleFamilyAction(InputSource.VOICE, FamilyAction.Answer(transcript))
                                    }
                                }
                            }
                        }
                        VoicePhase.ERROR -> guideView.text = "✦ Голос пока недоступен"
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun persist() {
        if (!::runtime.isInitialized) return
        val snap = runtime.snapshot()
        PlatformServices.adventureProgress(this).save(
            runtime.adventure.id,
            snap.sceneId,
            completed = snap.phase == AdventurePhase.COMPLETED,
            contentItem.packageId,
            contentItem.packageVersion,
        )
    }

    private fun showUnavailable() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
            setBackgroundColor(GameStyle.background)
        }
        root.addView(GameStyle.text(this, "Это занятие сейчас недоступно.", 28f, true).apply {
            gravity = Gravity.CENTER
        })
        val back = GameStyle.button(this, "Домой") { finish() }
        root.addView(back, LinearLayout.LayoutParams(-2, dp(56)).apply { topMargin = dp(20) })
        setContentView(root)
        back.requestFocus()
    }

    private fun sceneBackground(c1: Int, c2: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(c1, c2),
    )

    override fun onDestroy() {
        if (injectReceiverRegistered) {
            runCatching { unregisterReceiver(injectReceiver) }
            injectReceiverRegistered = false
        }
        PlatformServices.actionDispatcher().detach(actionHandler)
        PlatformServices.narrationContentItem = null
        persist()
        if (::narration.isInitialized) narration.stop()
        if (::sfx.isInitialized) sfx.release()
        if (::ambience.isInitialized) ambience.stop()
        idleRunnable?.let { idleHandler.removeCallbacks(it) }
        pipeline?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONTENT_ID = "contentId"
        /** Deterministic acceptance harness — not acoustic ASR. */
        const val EXTRA_INJECT_TRANSCRIPT = "inject_transcript"
        const val ACTION_INJECT_TRANSCRIPT = "ru.familytv.action.INJECT_TRANSCRIPT"
        private const val TAG_ASK = "FamilyAskLumi"
    }
}
