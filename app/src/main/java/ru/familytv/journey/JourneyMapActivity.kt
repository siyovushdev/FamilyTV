package ru.familytv.journey

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import ru.familytv.content.PlatformServices
import ru.familytv.games.GameStyle
import ru.familytv.home.ContentNavigator
import ru.familytv.home.HomeActivity
import ru.familytv.space.SpaceRoutes
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.InteractionSurface
import ru.familytv.interaction.PresentationImportance
import ru.familytv.interaction.PresentationMessage
import ru.familytv.interaction.PresentationRole
import ru.familytv.interaction.RouteContext
import ru.familytv.interaction.RouteResult
import ru.familytv.interaction.VoiceCommandRouter
import ru.familytv.trial.TrialEventStore
import ru.familytv.ui.FamilyTvTheme
import ru.familytv.ui.LumiView
import ru.familytv.adventure.GuideEmotion

/**
 * Child-facing Journey Map — planets / trail points, not a table.
 */
class JourneyMapActivity : ComponentActivity() {
    private lateinit var journeyId: String
    private lateinit var status: TextView
    private lateinit var lumi: LumiView
    private lateinit var coord: JourneyCoordinator
    private var lastSpoken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        journeyId = intent.getStringExtra(EXTRA_JOURNEY_ID)
            ?: PlatformServices.journeyCoordinator(this).activeJourneyId()
            ?: "space-grand-expedition"
        coord = PlatformServices.journeyCoordinator(this)
        if (SpaceRoutes.handlesJourney(journeyId)) {
            startActivity(SpaceRoutes.intent(this, journeyId))
            finish()
            return
        }
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || SpaceRoutes.handlesJourney(journeyId)) return
        rebuild()
    }

    private fun rebuild() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val pair = coord.progressFor(journeyId)
        if (pair == null) {
            setContentView(GameStyle.text(this, "Путешествие недоступно", 24f, true))
            return
        }
        val (journey, progress) = pair
        val age = PlatformServices.familySettings(this).contentFilter()
        val repo = PlatformServices.contentRepository(this)
        val views = JourneyEngine.views(
            journey,
            progress,
            contentExists = { id -> repo.getItem(id) != null },
            ageMin = age.ageMin,
            ageMax = age.ageMax,
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(32))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                when (journey.mapStyle) {
                    JourneyMapStyle.FOREST_TRAIL -> intArrayOf(Color.rgb(230, 245, 232), Color.rgb(200, 230, 210))
                    JourneyMapStyle.LAB -> intArrayOf(Color.rgb(235, 245, 255), Color.rgb(220, 235, 250))
                    else -> intArrayOf(Color.rgb(20, 24, 48), Color.rgb(40, 50, 90))
                },
            )
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lumi = LumiView(this)
        top.addView(lumi, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ink = if (journey.mapStyle == JourneyMapStyle.PLANETS) Color.WHITE else FamilyTvTheme.ink
        titleCol.addView(GameStyle.text(this, journey.title, 26f, true).apply { setTextColor(ink) })
        val cue = JourneyNarrator.whereWeAre(journey, progress) + " " + JourneyNarrator.whatNext(journey, progress)
        status = GameStyle.text(this, cue, 16f).apply { setTextColor(if (journey.mapStyle == JourneyMapStyle.PLANETS) Color.rgb(200, 210, 230) else FamilyTvTheme.muted) }
        titleCol.addView(status)
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)

        val trail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(28), dp(8), dp(16))
        }
        views.sortedBy { it.node.mapProgress ?: 0f }.forEachIndexed { index, nv ->
            if (index > 0) {
                trail.addView(View(this).apply {
                    setBackgroundColor(if (journey.mapStyle == JourneyMapStyle.PLANETS) Color.rgb(100, 120, 180) else Color.rgb(120, 160, 120))
                }, LinearLayout.LayoutParams(dp(28), dp(4)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
            trail.addView(nodePlanet(nv, journey, progress, ink), LinearLayout.LayoutParams(dp(140), dp(140)).apply {
                marginEnd = dp(4)
            })
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(trail)
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        actions.addView(GameStyle.button(this, "Продолжить") { resumeJourney(journey, progress) },
            LinearLayout.LayoutParams(-2, dp(56)).apply { marginEnd = dp(12) })
        actions.addView(GameStyle.button(this, "Открытия") {
            startActivity(
                Intent(this@JourneyMapActivity, DiscoveryJournalActivity::class.java)
                    .putExtra(DiscoveryJournalActivity.EXTRA_JOURNEY_ID, journey.id),
            )
        }, LinearLayout.LayoutParams(-2, dp(56)).apply { marginEnd = dp(12) })
        actions.addView(GameStyle.button(this, "Домой") {
            startActivity(Intent(this@JourneyMapActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, LinearLayout.LayoutParams(-2, dp(56)))
        root.addView(actions)

        if (progress.completed) {
            root.addView(
                GameStyle.text(this, journey.completionLine, 20f, true).apply {
                    setTextColor(ink)
                    setPadding(0, dp(16), 0, 0)
                },
            )
        }

        setContentView(ScrollView(this).apply { addView(root) })
        speak(cue)
        lumi.post { lumi.setEmotionWithMotion(GuideEmotion.CURIOUS) }
        PlatformServices.trialEvents(this).record("JOURNEY_MAP_OPENED", journey.id)
        consumeDebugExtras(intent, journey)
        consumeInject(intent)
    }

    private fun consumeDebugExtras(intent: Intent?, journey: Journey) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val completeContent = intent?.getStringExtra(EXTRA_DEBUG_COMPLETE_CONTENT)?.trim().orEmpty()
        if (completeContent.isNotBlank()) {
            android.util.Log.i("FamilyJourney", "debug_complete_content=$completeContent")
            coord.ensureStarted(journey.id)
            coord.onContentCompleted(completeContent, journey.id)
            intent?.removeExtra(EXTRA_DEBUG_COMPLETE_CONTENT)
            rebuild()
            return
        }
        val completeNode = intent?.getStringExtra(EXTRA_DEBUG_COMPLETE_NODE)?.trim().orEmpty()
        if (completeNode.isNotBlank()) {
            android.util.Log.i("FamilyJourney", "debug_complete_node=$completeNode")
            coord.ensureStarted(journey.id)
            coord.markManualNode(journey.id, completeNode)
            intent?.removeExtra(EXTRA_DEBUG_COMPLETE_NODE)
            rebuild()
            return
        }
        val branch = intent?.getStringExtra(EXTRA_DEBUG_BRANCH)?.trim().orEmpty()
        val from = intent?.getStringExtra(EXTRA_DEBUG_BRANCH_FROM)?.trim().orEmpty()
        if (branch.isNotBlank() && from.isNotBlank()) {
            coord.ensureStarted(journey.id)
            coord.onBranchChosen(journey.id, from, branch)
            intent?.removeExtra(EXTRA_DEBUG_BRANCH)
            intent?.removeExtra(EXTRA_DEBUG_BRANCH_FROM)
            rebuild()
        }
    }

    private fun consumeInject(intent: Intent?) {
        val t = intent?.getStringExtra(EXTRA_INJECT_TRANSCRIPT)?.trim().orEmpty()
        if (t.isBlank()) return
        intent?.removeExtra(EXTRA_INJECT_TRANSCRIPT)
        android.util.Log.i("FamilyJourney", "inject=$t")
        val routed = VoiceCommandRouter.route(
            RouteContext(surface = InteractionSurface.HOME),
            t.lowercase(),
        )
        when (routed) {
            is RouteResult.Action -> when (val a = routed.action) {
                is FamilyAction.AskWhatWeDiscovered -> {
                    val text = JourneyNarrator.whatWeDiscovered(
                        PlatformServices.discoveryJournal(this).list(),
                        a.worldHint,
                    )
                    status.text = text
                    speak(text)
                }
                is FamilyAction.ContinueJourney, is FamilyAction.OpenJourneyMap -> Unit
                FamilyAction.OpenJournal -> {
                    startActivity(Intent(this, DiscoveryJournalActivity::class.java))
                }
                else -> status.text = "action=${a::class.simpleName}"
            }
            RouteResult.Unhandled -> status.text = "unhandled: $t"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_JOURNEY_ID)?.trim()?.takeIf { it.isNotBlank() }?.let {
            journeyId = it
        }
        if (SpaceRoutes.handlesJourney(journeyId)) {
            startActivity(SpaceRoutes.intent(this, journeyId))
            finish()
            return
        }
        val pair = coord.progressFor(journeyId) ?: return
        consumeDebugExtras(intent, pair.first)
        consumeInject(intent)
        // Refresh map when journey id or progress changed via debug extras
        if (intent.hasExtra(EXTRA_JOURNEY_ID) &&
            !intent.hasExtra(EXTRA_DEBUG_COMPLETE_CONTENT) &&
            !intent.hasExtra(EXTRA_DEBUG_COMPLETE_NODE) &&
            !intent.hasExtra(EXTRA_DEBUG_BRANCH)
        ) {
            rebuild()
        }
    }

    private fun nodePlanet(
        nv: JourneyNodeView,
        journey: Journey,
        progress: JourneyProgress,
        ink: Int,
    ): View {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val bg = when (nv.availability) {
            NodeAvailability.COMPLETED -> Color.rgb(60, 160, 120)
            NodeAvailability.AVAILABLE -> Color.rgb(70, 120, 200)
            NodeAvailability.FUTURE -> Color.rgb(70, 70, 90)
            NodeAvailability.MISSING_CONTENT -> Color.rgb(120, 60, 60)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                it.setColor(bg)
            }
            isFocusable = nv.availability == NodeAvailability.AVAILABLE ||
                nv.availability == NodeAvailability.COMPLETED
            isClickable = isFocusable
            contentDescription = nv.node.spokenLabel ?: nv.node.mapLabel
            alpha = when (nv.availability) {
                NodeAvailability.FUTURE -> 0.45f
                NodeAvailability.MISSING_CONTENT -> 0.5f
                else -> 1f
            }
            foreground = GameStyle.focusBackground(this@JourneyMapActivity, Color.TRANSPARENT)
            setOnClickListener { onNodeSelected(nv, journey, progress) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onNodeSelected(nv, journey, progress)
                    true
                } else false
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.08f else 1f
                v.scaleY = if (hasFocus) 1.08f else 1f
                if (hasFocus) {
                    status.text = nv.node.title
                    speak(nv.node.spokenLabel ?: nv.node.mapLabel)
                }
            }
        }
        val mark = when (nv.availability) {
            NodeAvailability.COMPLETED -> "✓"
            NodeAvailability.AVAILABLE -> "●"
            NodeAvailability.FUTURE -> "○"
            NodeAvailability.MISSING_CONTENT -> "!"
        }
        box.addView(GameStyle.text(this, mark, 22f, true).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        box.addView(GameStyle.text(this, nv.node.mapLabel, 14f, true).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
        })
        return box
    }

    private fun onNodeSelected(nv: JourneyNodeView, journey: Journey, progress: JourneyProgress) {
        when (nv.availability) {
            NodeAvailability.FUTURE -> {
                status.text = "Это дальше по пути — сейчас можно другое место."
                speak(status.text.toString())
            }
            NodeAvailability.MISSING_CONTENT -> {
                status.text = "Это занятие сейчас недоступно."
            }
            NodeAvailability.COMPLETED, NodeAvailability.AVAILABLE -> {
                openNode(nv, journey)
            }
        }
    }

    private fun openNode(nv: JourneyNodeView, journey: Journey) {
        coord.ensureStarted(journey.id)
        val node = nv.node
        when {
            node.branches.isNotEmpty() && node.type == JourneyNodeType.HUB -> {
                showBranchPicker(journey, node)
            }
            node.type == JourneyNodeType.HUB || node.type == JourneyNodeType.CONCLUSION -> {
                coord.markManualNode(journey.id, node.id)
                rebuild()
                speak(node.spokenLabel ?: journey.completionLine)
            }
            else -> {
                val contentId = nv.resolvedContentId ?: return
                coord.onContentOpened(contentId, journey.id)
                val item = PlatformServices.contentRepository(this).getItem(contentId) ?: return
                ContentNavigator.open(this, item)
            }
        }
    }

    private fun showBranchPicker(journey: Journey, node: JourneyNode) {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
            setBackgroundColor(Color.rgb(20, 24, 48))
        }
        root.addView(GameStyle.text(this, node.title, 28f, true).apply { setTextColor(Color.WHITE) })
        root.addView(
            GameStyle.text(this, "Куда дальше?", 18f).apply {
                setTextColor(Color.rgb(200, 210, 230))
                setPadding(0, dp(8), 0, dp(16))
            },
        )
        node.branches.forEach { b ->
            root.addView(
                GameStyle.button(this, b.label) {
                    coord.onBranchChosen(journey.id, node.id, b.id)
                    // Also mark hub complete via branch
                    rebuild()
                },
                LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(12) },
            )
        }
        root.addView(GameStyle.button(this, "Назад к карте") { rebuild() }, LinearLayout.LayoutParams(-2, dp(52)))
        setContentView(root)
        speak("Куда дальше? ${node.branches.joinToString(" или ") { it.spokenLabel ?: it.label }}")
    }

    private fun resumeJourney(journey: Journey, progress: JourneyProgress) {
        PlatformServices.trialEvents(this).record(TrialEventStore.JOURNEY_RESUMED, journey.id)
        val target = coord.resumeTarget(journey.id) ?: return
        val nv = JourneyNodeView(
            target.node,
            NodeAvailability.AVAILABLE,
            target.contentId,
        )
        openNode(nv, journey)
    }

    private fun speak(text: String) {
        lastSpoken = text
        PlatformServices.narrationCoordinator(this).present(
            PresentationMessage(
                text = text,
                spokenText = text,
                role = PresentationRole.INSTRUCTION,
                importance = PresentationImportance.IMPORTANT,
                repeatable = true,
            ),
        )
    }

    companion object {
        const val EXTRA_JOURNEY_ID = "journey_id"
        const val EXTRA_INJECT_TRANSCRIPT = "inject_transcript"
        const val EXTRA_DEBUG_COMPLETE_CONTENT = "debug_complete_content"
        const val EXTRA_DEBUG_COMPLETE_NODE = "debug_complete_node"
        const val EXTRA_DEBUG_BRANCH = "debug_branch"
        const val EXTRA_DEBUG_BRANCH_FROM = "debug_branch_from"
    }
}
