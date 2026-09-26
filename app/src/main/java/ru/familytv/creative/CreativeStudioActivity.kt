package ru.familytv.creative

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import ru.familytv.adventure.GuideEmotion
import ru.familytv.content.PlatformServices
import ru.familytv.games.GameStyle
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.InteractionSurface
import ru.familytv.interaction.NarrationCoordinator
import ru.familytv.interaction.PresentationImportance
import ru.familytv.interaction.PresentationMessage
import ru.familytv.interaction.PresentationRole
import ru.familytv.interaction.RouteContext
import ru.familytv.interaction.RouteResult
import ru.familytv.interaction.VoiceCommandRouter
import ru.familytv.knowledge.KnowledgeCatalog
import ru.familytv.knowledge.KnowledgeQuery
import ru.familytv.knowledge.KnowledgeResult
import ru.familytv.parent.ActivityHistoryStore
import ru.familytv.parent.ActivityKind
import ru.familytv.trial.TrialEventStore
import ru.familytv.ui.FamilyTvTheme
import ru.familytv.ui.LumiView

/**
 * Creative Studio — Story Builder foundation.
 * Choices truly change retell / ending via authored grammar — not a free chatbot.
 */
class CreativeStudioActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var board: LinearLayout
    private lateinit var guideView: TextView
    private lateinit var lumiView: LumiView
    private lateinit var narration: NarrationCoordinator
    private lateinit var store: StoryStore
    private lateinit var trial: TrialEventStore
    private lateinit var grammar: StoryGrammar
    private var state: StoryState = StoryState(grammarId = "")
    private var mode: Mode = Mode.MENU

    private enum class Mode { MENU, BUILD, RETELL, REPLAY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = StoryStore(this)
        trial = TrialEventStore(this)
        grammar = assets.open("catalog/stories/lumi-story.json").bufferedReader().use {
            StoryGrammarParser.parse(it.readText())
        }
        narration = PlatformServices.narrationCoordinator(this)
        buildUi()
        showMenu()
        ActivityHistoryStore(this).record(ActivityKind.INTERACTIVE, grammar.title, grammar.id)
        registerInjectReceiver()
        consumeInjectExtra(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (mode) {
                    Mode.MENU -> finish()
                    else -> showMenu()
                }
            }
        })
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeInjectExtra(intent)
    }

    private fun consumeInjectExtra(intent: android.content.Intent?) {
        val t = intent?.getStringExtra(EXTRA_INJECT_TRANSCRIPT)?.trim().orEmpty()
        if (t.isBlank()) return
        intent?.removeExtra(EXTRA_INJECT_TRANSCRIPT)
        android.util.Log.i(TAG_STORY, "inject transcript=$t")
        root.postDelayed({
            if (mode == Mode.MENU) startBuild(intent?.getBooleanExtra(EXTRA_GROUP, false) == true)
            root.postDelayed({ handleVoice(t) }, 300L)
        }, 300L)
    }

    private var injectReceiverRegistered = false
    private val injectReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val t = intent?.getStringExtra(EXTRA_INJECT_TRANSCRIPT)?.trim().orEmpty()
            if (t.isBlank()) return
            android.util.Log.i(TAG_STORY, "inject transcript=$t")
            if (mode == Mode.MENU) startBuild(false)
            handleVoice(t)
        }
    }

    private fun registerInjectReceiver() {
        if (injectReceiverRegistered) return
        val filter = android.content.IntentFilter(ACTION_INJECT_TRANSCRIPT)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(injectReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(injectReceiver, filter)
        }
        injectReceiverRegistered = true
    }

    private fun buildUi() {
        val d = { v: Int -> GameStyle.dp(this, v) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FamilyTvTheme.worlds.getValue("create").accentDeep)
            setPadding(d(24), d(16), d(24), d(16))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lumiView = LumiView(this).also {
            top.addView(it, LinearLayout.LayoutParams(d(72), d(72)).apply { marginEnd = d(12) })
        }
        guideView = GameStyle.text(this, "", 20f).apply {
            setTextColor(Color.WHITE)
        }
        top.addView(guideView, LinearLayout.LayoutParams(0, -2, 1f))
        val home = GameStyle.button(this, "⌂") { finish() }.apply {
            contentDescription = "Домой"
        }
        top.addView(home, LinearLayout.LayoutParams(d(64), d(56)))
        root.addView(top)
        val scroll = ScrollView(this)
        board = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(12), 0, d(12))
        }
        scroll.addView(board)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showMenu() {
        mode = Mode.MENU
        board.removeAllViews()
        lumiView.setEmotionWithMotion(GuideEmotion.CURIOUS)
        guideView.text = "✦ Придумай историю с Луми"
        present("Давай придумаем историю вместе!", "Давай придумаем историю вместе!")
        val d = { v: Int -> GameStyle.dp(this, v) }
        board.addView(GameStyle.button(this, "Новая история") {
            startBuild(groupMode = false)
        }.apply { textSize = 22f })
        board.addView(GameStyle.button(this, "Вместе с семьёй") {
            startBuild(groupMode = true)
        }.apply {
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(-1, d(64)).apply { topMargin = d(10) }
        })
        store.list().take(5).forEach { story ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, d(8), 0, 0)
            }
            row.addView(
                GameStyle.button(this, "▶ ${story.title}") {
                    mode = Mode.REPLAY
                    guideView.text = story.retellText
                    present(story.retellText, story.retellText)
                    board.removeAllViews()
                    board.addView(GameStyle.text(this, story.retellText, 20f).apply {
                        setTextColor(Color.WHITE)
                    })
                    board.addView(GameStyle.button(this, "Удалить") {
                        store.delete(story.id)
                        showMenu()
                    })
                    board.addView(GameStyle.button(this, "В меню") { showMenu() })
                },
                LinearLayout.LayoutParams(0, d(56), 1f),
            )
            board.addView(row)
        }
    }

    private fun startBuild(groupMode: Boolean) {
        mode = Mode.BUILD
        state = StoryBuilderRuntime.start(grammar, groupMode)
        trial.record(TrialEventStore.STORY_STARTED, grammar.id, mapOf("group" to groupMode.toString()))
        renderStep()
    }

    private fun renderStep() {
        board.removeAllViews()
        if (state.completed) {
            showRetell()
            return
        }
        val step = StoryBuilderRuntime.currentStep(grammar, state) ?: return
        lumiView.setEmotionWithMotion(GuideEmotion.CURIOUS)
        val groupLine = if (state.groupMode) {
            step.groupHint ?: "Пусть кто-нибудь выберет."
        } else null
        val prompt = listOfNotNull(step.prompt, groupLine).joinToString("\n")
        guideView.text = "✦ $prompt"
        present(step.prompt, step.spokenPrompt ?: step.prompt)
        val d = { v: Int -> GameStyle.dp(this, v) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        step.options.forEach { opt ->
            val tile = GameStyle.button(this, "${glyph(opt.id)} ${opt.label}") {
                applyChoice(opt.id)
            }.apply {
                textSize = 18f
                contentDescription = opt.spokenLabel ?: opt.label
                minimumWidth = d(140)
            }
            row.addView(tile, LinearLayout.LayoutParams(-2, d(72)).apply { marginEnd = d(10) })
        }
        board.addView(row)
        board.addView(GameStyle.button(this, "Повтори") {
            present(step.prompt, step.spokenPrompt ?: step.prompt)
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, d(56)).apply { topMargin = d(16) } })
    }

    private fun applyChoice(optionId: String, adjective: String? = null) {
        val step = StoryBuilderRuntime.currentStep(grammar, state) ?: return
        state = StoryBuilderRuntime.select(grammar, state, optionId, adjective)
        trial.record(
            TrialEventStore.STORY_CHOICE,
            grammar.id,
            mapOf("step" to step.kind.name, "option" to optionId),
        )
        renderStep()
    }

    private fun showRetell() {
        mode = Mode.RETELL
        val text = StoryBuilderRuntime.retell(grammar, state)
        val title = StoryBuilderRuntime.titleFor(grammar, state)
        android.util.Log.i(TAG_STORY, "result=RETELL title=$title text=$text selections=${state.selections}")
        lumiView.setEmotionWithMotion(GuideEmotion.EXCITED)
        guideView.text = "✦ $title"
        present(text, text)
        board.removeAllViews()
        board.addView(GameStyle.text(this, text, 22f).apply {
            setTextColor(Color.WHITE)
            setPadding(0, GameStyle.dp(this@CreativeStudioActivity, 8), 0, GameStyle.dp(this@CreativeStudioActivity, 16))
        })
        val saved = SavedStory(
            id = store.createId(),
            title = title,
            createdAtMs = System.currentTimeMillis(),
            retellText = text,
            selections = state.selections,
        )
        store.save(saved)
        trial.record(TrialEventStore.STORY_COMPLETED, grammar.id)
        PlatformServices.journeyCoordinator(this).onStoryCreated(
            storyId = saved.id,
            title = saved.title,
            world = state.selections["WORLD"],
        )
        board.addView(GameStyle.button(this, "Послушать снова") { present(text, text) })
        board.addView(GameStyle.button(this, "Новая история") { startBuild(state.groupMode) })
        board.addView(GameStyle.button(this, "В меню") { showMenu() })
    }

    private fun present(display: String, spoken: String) {
        narration.present(
            PresentationMessage(
                text = display,
                spokenText = spoken,
                role = PresentationRole.INSTRUCTION,
                importance = PresentationImportance.CRITICAL,
                repeatable = true,
            ),
        )
    }

    private fun glyph(id: String): String = when {
        id.contains("robot") -> "🤖"
        id.contains("fox") -> "🦊"
        id.contains("astro") -> "🧑‍🚀"
        id.contains("forest") || id.contains("wood") -> "🌲"
        id.contains("moon") || id.contains("space") -> "🌙"
        id.contains("sea") || id.contains("ocean") -> "🌊"
        else -> "✦"
    }

    fun handleVoice(transcript: String) {
        val n = transcript.lowercase().trim()
        val ctx = RouteContext(surface = InteractionSurface.INTERACTIVE, awaitingAnswer = mode == Mode.BUILD)
        when (val routed = VoiceCommandRouter.route(ctx, n)) {
            is RouteResult.Action -> when (val a = routed.action) {
                FamilyAction.Home, FamilyAction.Back -> finish()
                FamilyAction.Repeat -> {
                    val step = StoryBuilderRuntime.currentStep(grammar, state)
                    if (step != null) present(step.prompt, step.spokenPrompt ?: step.prompt)
                }
                is FamilyAction.AskLumi -> {
                    val resolver = KnowledgeCatalog.loadForAdventure(this, "lumi-story", "create")
                    when (val r = resolver.resolve(KnowledgeQuery(a.transcript, a.intent, world = "create"))) {
                        is KnowledgeResult.Found -> {
                            guideView.text = "✦ ${r.groundedAnswer}"
                            present(r.groundedAnswer, r.spokenAnswer)
                            trial.record(TrialEventStore.KNOWLEDGE_FOUND, grammar.id)
                        }
                        is KnowledgeResult.Unknown -> {
                            guideView.text = "✦ ${r.softReply}"
                            present(r.softReply, r.softReply)
                            trial.record(TrialEventStore.KNOWLEDGE_UNKNOWN, grammar.id)
                        }
                        is KnowledgeResult.Clarification -> {
                            guideView.text = "✦ ${r.prompt}"
                            present(r.prompt, r.spokenPrompt)
                        }
                        is KnowledgeResult.AskToExperiment -> {
                            guideView.text = "✦ ${r.invite}"
                            present(r.invite, r.spokenInvite)
                        }
                    }
                    trial.record(TrialEventStore.CURIOSITY_QUESTION, grammar.id)
                }
                is FamilyAction.Answer -> {
                    if (mode != Mode.BUILD) return
                    val step = StoryBuilderRuntime.currentStep(grammar, state) ?: return
                    val mapped = StoryBuilderRuntime.mapVoice(step, a.transcript)
                        ?: StoryBuilderRuntime.mapFreeform(step, a.transcript)
                    if (mapped != null) {
                        android.util.Log.i(
                            TAG_STORY,
                            "result=MAPPED option=${mapped.first} adj=${mapped.second} state=${state.selections}",
                        )
                        trial.record(
                            TrialEventStore.STORY_FREEFORM_MAPPED,
                            grammar.id,
                            mapOf("option" to mapped.first),
                        )
                        applyChoice(mapped.first, mapped.second)
                    } else {
                        android.util.Log.i(TAG_STORY, "result=UNMAPPED transcript=${a.transcript}")
                        guideView.text = "✦ Выбери героя или место на экране — или скажи одно из слов."
                    }
                }
                else -> Unit
            }
            RouteResult.Unhandled -> {
                if (mode == Mode.BUILD) {
                    val step = StoryBuilderRuntime.currentStep(grammar, state) ?: return
                    StoryBuilderRuntime.mapVoice(step, n)?.let { applyChoice(it.first, it.second) }
                }
            }
        }
    }

    override fun onDestroy() {
        if (injectReceiverRegistered) {
            runCatching { unregisterReceiver(injectReceiver) }
            injectReceiverRegistered = false
        }
        narration.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GROUP = "group"
        const val EXTRA_INJECT_TRANSCRIPT = "inject_transcript"
        const val ACTION_INJECT_TRANSCRIPT = "ru.familytv.action.INJECT_TRANSCRIPT"
        private const val TAG_STORY = "FamilyStoryMap"
    }
}
