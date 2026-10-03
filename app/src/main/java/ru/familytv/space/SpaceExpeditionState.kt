package ru.familytv.space

import ru.familytv.adventure.Adventure
import ru.familytv.adventure.AdventurePhase
import ru.familytv.adventure.AdventureRuntime
import ru.familytv.adventure.ChallengeScene
import ru.familytv.adventure.DialogueScene
import ru.familytv.adventure.MechanicType
import ru.familytv.adventure.MoveScene
import ru.familytv.adventure.OffscreenScene
import ru.familytv.adventure.PathMazeParams
import ru.familytv.adventure.PatternSequenceParams
import ru.familytv.adventure.ReflectionScene
import ru.familytv.adventure.ScaffoldLevel
import ru.familytv.adventure.SortClassifyParams
import ru.familytv.adventure.StoryScene
import ru.familytv.adventure.VisualSearchParams
import ru.familytv.adventure.mechanics.MechanicSnapshot
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.interaction.FamilyAction
import ru.familytv.journey.Journey
import ru.familytv.journey.JourneyEngine
import ru.familytv.journey.JourneyNarrator
import ru.familytv.journey.JourneyNode
import ru.familytv.journey.JourneyNodeType
import ru.familytv.journey.JourneyNodeView
import ru.familytv.journey.JourneyProgress
import ru.familytv.journey.NodeAvailability
import kotlin.math.abs

enum class SpaceScreen { ENTRY, JOURNEY, HUB, INTRO, CHALLENGE, SUCCESS }

enum class SpaceNodeVisual { COMPLETED, CURRENT, AVAILABLE, LOCKED, MISSING }

enum class SpaceBeat { IDLE, INSTALL, REJECT, IGNITE, TRAVEL, SETTLE }

internal const val SPACE_CONSEQUENCE_MS = 3200L

data class SpaceWaypoint(
    val nodeId: String,
    val label: String,
    val title: String,
    val spoken: String,
    val visual: SpaceNodeVisual,
    val optional: Boolean,
    val x: Float,
    val y: Float,
    val type: JourneyNodeType,
    val contentId: String?,
)

data class SpaceChoice(
    val key: String,
    val label: String,
    val even: Boolean? = null,
    val selected: Boolean = false,
    val wrong: Boolean = false,
    val onPath: Boolean = false,
    val blocked: Boolean = false,
    val required: Boolean = false,
    val cursor: Boolean = false,
    val goal: Boolean = false,
    val placedIn: String? = null,
)

data class SpaceBranch(
    val id: String,
    val label: String,
    val spoken: String,
)

data class SpaceSnapshot(
    val screen: SpaceScreen = SpaceScreen.ENTRY,
    val focus: String = "continue",
    val paused: Boolean = false,
    val helping: Boolean = false,
    val journeyTitle: String = "",
    val currentLabel: String = "",
    val lastCompletedLabel: String? = null,
    val guideLine: String = "",
    val waypoints: List<SpaceWaypoint> = emptyList(),
    val resumeNodeId: String? = null,
    val started: Boolean = false,
    val completed: Boolean = false,
    val availableLabels: List<String> = emptyList(),
    val requiredDone: Int = 0,
    val requiredTotal: Int = 0,
    val sceneId: String? = null,
    val mechanicType: String? = null,
    val challengeId: String? = null,
    val choices: List<SpaceChoice> = emptyList(),
    val sequenceDisplay: List<String> = emptyList(),
    val categories: List<SpaceChoice> = emptyList(),
    val shipEnergy: Int = 0,
    val engineOn: Boolean = false,
    val flying: Boolean = false,
    val routeLit: Boolean = false,
    val worldReacted: Boolean = false,
    val wrongMs: Long = 0,
    val helpMs: Long = 0,
    val successMs: Long = 0,
    val branches: List<SpaceBranch> = emptyList(),
    val hubTitle: String = "",
    val localHint: String? = null,
    val sortHold: String? = null,
    val beat: SpaceBeat = SpaceBeat.IDLE,
    val beatMs: Long = 0,
    val flyingKey: String? = null,
    val installedKey: String? = null,
    val sequenceBlank: Int = 2,
    val repaired: Int = 0,
    val navigated: Int = 0,
    val adventureSceneId: String? = null,
    val hintTarget: String? = null,
)

data class SpaceBranchChoice(val fromNodeId: String, val branchId: String)

/**
 * Child-facing Space expedition state. JourneyEngine + AdventureRuntime stay authoritative.
 */
class SpaceExpeditionState(
    val journey: Journey,
    progress: JourneyProgress,
    val adventure: Adventure?,
    resumeSceneId: String? = null,
    views: List<JourneyNodeView> = JourneyEngine.views(journey, progress, { true }),
    resumeNodeId: String? = JourneyEngine.resumeNodeId(journey, progress),
) {
    var value = SpaceSnapshot()
        private set
    private var progress = progress
    private var views = views
    private var resumeNodeId = resumeNodeId
    private var runtime: AdventureRuntime? = adventure?.let { AdventureRuntime(it, startSceneId = resumeSceneId) }
    private val shownIntros = mutableSetOf<String>()
    private var pendingExternal: String? = null
    private var pendingBranch: SpaceBranchChoice? = null
    private var pendingManual: String? = null
    private var pendingOpened = false
    private var pendingCompleted = false
    private var pendingFinish = false
    private var pendingJournal = false
    private var pendingHome = false
    private var visitOpened = false
    private var localWrong = setOf<String>()
    private var localHint: String? = null
    private var sortHold: String? = null
    private var adventureAttached = false
    private var beat = SpaceBeat.IDLE
    private var beatMs = 0L
    private var flyingKey: String? = null
    private var installedKey: String? = null
    private val hostedResumeSceneId = resumeSceneId

    init {
        value = snapshot(SpaceScreen.ENTRY, "continue")
    }

    fun bind(
        progress: JourneyProgress,
        views: List<JourneyNodeView>,
        resumeNodeId: String?,
    ) {
        this.progress = progress
        this.views = views
        this.resumeNodeId = resumeNodeId
        value = snapshot(value.screen, value.focus)
    }

    fun attachRuntime(runtime: AdventureRuntime) {
        this.runtime = runtime
        adventureAttached = true
    }

    fun runtime(): AdventureRuntime? = runtime

    fun controls(): List<String> {
        if (value.paused) return listOf("continue", "finish")
        return when (value.screen) {
            SpaceScreen.ENTRY -> listOf("home", "continue", "journey", "journal")
            SpaceScreen.JOURNEY -> listOf("back") + value.waypoints.map { "node:${it.nodeId}" } + listOf("continue", "journal")
            SpaceScreen.HUB -> listOf("back") + value.branches.map { "branch:${it.id}" }
            SpaceScreen.INTRO -> listOf("back", "continue", "help")
            SpaceScreen.CHALLENGE -> {
                val extra = if (needsCheck()) listOf("check") else emptyList()
                val picks = if (value.mechanicType == "SORT_AND_CLASSIFY") emptyList() else value.choices.map { it.key }
                listOf("back", "pause", "help") + picks + value.categories.map { it.key } + extra
            }
            SpaceScreen.SUCCESS -> listOf("continue")
        }
    }

    fun defaultFocus(): String {
        val keys = controls()
        return when {
            value.paused -> "continue"
            value.helping && value.mechanicType == "SORT_AND_CLASSIFY" ->
                keys.firstOrNull { it.startsWith("cat:") } ?: "help"
            value.helping && "help" in keys -> "help"
            value.screen == SpaceScreen.ENTRY -> "continue"
            value.screen == SpaceScreen.JOURNEY -> value.resumeNodeId?.let { "node:$it" }?.takeIf { it in keys } ?: keys.firstOrNull { it.startsWith("node:") } ?: "continue"
            value.screen == SpaceScreen.HUB -> keys.firstOrNull { it.startsWith("branch:") } ?: "back"
            value.screen == SpaceScreen.CHALLENGE && value.mechanicType == "SORT_AND_CLASSIFY" ->
                keys.firstOrNull { it.startsWith("cat:") } ?: "help"
            value.screen == SpaceScreen.CHALLENGE -> value.choices.firstOrNull { !it.blocked }?.key ?: "help"
            else -> "continue"
        }.takeIf { it in keys } ?: keys.firstOrNull() ?: "continue"
    }

    fun focus(key: String) {
        if (key !in controls()) return
        value = value.copy(focus = key)
    }

    fun move(direction: SpacePad): Boolean {
        val keys = controls()
        if (keys.isEmpty()) return false
        val next = neighbor(keys, value.focus, direction) ?: return false
        focus(next)
        return true
    }

    fun semantic(action: FamilyAction): Boolean {
        if (value.paused && action != FamilyAction.Resume && action != FamilyAction.Select && action != FamilyAction.Back && action != FamilyAction.Home) {
            if (action == FamilyAction.Pause) return true
            return false
        }
        return when (action) {
            FamilyAction.Home -> { pendingHome = true; true }
            FamilyAction.Back -> back()
            FamilyAction.Pause -> { value = value.copy(paused = true, focus = "continue"); true }
            FamilyAction.Resume -> { value = value.copy(paused = false, focus = defaultFocus()); true }
            FamilyAction.Repeat -> true
            FamilyAction.Help -> help()
            FamilyAction.Select, FamilyAction.Next, FamilyAction.ContinueCurrent -> activate()
            is FamilyAction.ContinueJourney -> {
                if (value.screen == SpaceScreen.ENTRY) continuePlay() else activate("continue")
                true
            }
            is FamilyAction.OpenJourneyMap -> { goJourney(); true }
            FamilyAction.OpenJournal -> { pendingJournal = true; true }
            else -> false
        }
    }

    fun activate(key: String? = null): Boolean {
        if (key != null) {
            if (key !in controls() && !value.paused) return false
            focus(key)
        }
        val target = value.focus
        if (value.paused) {
            return when (target) {
                "continue" -> { value = value.copy(paused = false, focus = defaultFocus()); true }
                "finish" -> { pendingFinish = true; true }
                else -> false
            }
        }
        return when {
            target == "home" -> { pendingHome = true; true }
            target == "journal" -> { pendingJournal = true; true }
            target == "back" -> back()
            target == "pause" -> { value = value.copy(paused = true, focus = "continue"); true }
            target == "help" -> if (value.helping) {
                value = value.copy(helping = false, helpMs = 0)
                true
            } else help()
            target == "continue" && value.screen == SpaceScreen.ENTRY -> continuePlay()
            target == "journey" -> goJourney()
            target == "continue" && value.screen == SpaceScreen.JOURNEY -> continuePlay()
            target.startsWith("node:") -> openNode(target.removePrefix("node:"))
            target.startsWith("branch:") -> chooseBranch(target.removePrefix("branch:"))
            target == "continue" && value.screen == SpaceScreen.INTRO -> leaveIntro()
            target == "continue" && value.screen == SpaceScreen.SUCCESS -> leaveSuccess()
            target == "check" -> submitChallenge()
            target.startsWith("obj:") || target.startsWith("cell:") || target.startsWith("opt:") ||
                target.startsWith("item:") || target.startsWith("cat:") -> pick(target)
            else -> false
        }
    }

    fun tick(delta: Long) {
        if (value.paused || delta <= 0) return
        if (beat != SpaceBeat.IDLE && beat != SpaceBeat.SETTLE) beatMs += delta
        if (value.screen == SpaceScreen.SUCCESS) {
            val elapsed = value.successMs + delta
            if (elapsed >= SPACE_CONSEQUENCE_MS) {
                leaveSuccess()
                return
            }
            value = value.copy(
                successMs = elapsed,
                beat = when {
                    elapsed < 500L -> SpaceBeat.INSTALL
                    elapsed < 1200L -> SpaceBeat.IGNITE
                    else -> SpaceBeat.TRAVEL
                },
                beatMs = elapsed,
                flying = elapsed >= 900L,
                engineOn = true,
                routeLit = true,
            )
            return
        }
        if (value.screen == SpaceScreen.CHALLENGE && beat == SpaceBeat.INSTALL && mechanicIsSort()) {
            if (beatMs > 560L) {
                beat = SpaceBeat.IDLE
                flyingKey = null
                beatMs = 0
            }
        }
        var next = value
        if (next.wrongMs > 0) next = next.copy(wrongMs = (next.wrongMs - delta).coerceAtLeast(0))
        if (next.wrongMs == 0L && (localWrong.isNotEmpty() || beat == SpaceBeat.REJECT)) {
            localWrong = emptySet()
            if (beat == SpaceBeat.REJECT) {
                beat = SpaceBeat.IDLE
                flyingKey = null
                beatMs = 0
            }
            next = snapshot(next.screen, next.focus)
        }
        if (next.helpMs > 0) {
            val remain = (next.helpMs - delta).coerceAtLeast(0)
            next = next.copy(helpMs = remain, helping = remain > 0)
        }
        if (beat == SpaceBeat.SETTLE) {
            beatMs += delta
            if (beatMs > 1400L) {
                beat = SpaceBeat.IDLE
                beatMs = 0
            }
        }
        value = next.copy(beat = beat, beatMs = beatMs, flyingKey = flyingKey, installedKey = installedKey)
    }

    fun prompt(): String {
        if (value.paused) return "Пауза. Можно продолжить или закончить."
        value.localHint?.takeIf { value.wrongMs > 0 || value.helping }?.let { return it }
        return value.guideLine
    }

    fun takeExternal(): String? = pendingExternal.also { pendingExternal = null }
    fun takeBranch(): SpaceBranchChoice? = pendingBranch.also { pendingBranch = null }
    fun takeManual(): String? = pendingManual.also { pendingManual = null }
    fun takeOpened(): Boolean = pendingOpened.also { pendingOpened = false }
    fun takeCompleted(): Boolean = pendingCompleted.also { pendingCompleted = false }
    fun takeFinish(): Boolean = pendingFinish.also { pendingFinish = false }
    fun takeJournal(): Boolean = pendingJournal.also { pendingJournal = false }
    fun takeHome(): Boolean = pendingHome.also { pendingHome = false }
    fun visitWasOpened(): Boolean = visitOpened
    fun restoreOpenedVisit() { visitOpened = true }

    fun restoreUi(
        screen: SpaceScreen,
        focus: String,
        paused: Boolean,
        helping: Boolean,
        shown: Set<String>,
        sortHold: String?,
        mechanic: MechanicSnapshot?,
    ) {
        shownIntros.clear()
        shownIntros += shown
        this.sortHold = sortHold
        mechanic?.let { runtime?.restoreMechanic(it) }
        adventureAttached = runtime != null && screen != SpaceScreen.ENTRY && screen != SpaceScreen.JOURNEY && screen != SpaceScreen.HUB
        value = snapshot(screen, focus).copy(paused = paused, helping = helping, helpMs = if (helping) 1800 else 0)
        if (focus in controls()) value = value.copy(focus = focus)
    }

    fun shownIntros(): Set<String> = shownIntros.toSet()
    fun sortHold(): String? = sortHold

    private fun back(): Boolean {
        when (value.screen) {
            SpaceScreen.ENTRY -> pendingFinish = true
            SpaceScreen.JOURNEY -> value = snapshot(SpaceScreen.ENTRY, "continue")
            SpaceScreen.HUB -> value = snapshot(SpaceScreen.JOURNEY, defaultFor(SpaceScreen.JOURNEY))
            SpaceScreen.INTRO, SpaceScreen.CHALLENGE, SpaceScreen.SUCCESS -> {
                adventureAttached = false
                value = snapshot(SpaceScreen.JOURNEY, defaultFor(SpaceScreen.JOURNEY))
            }
        }
        return true
    }

    private fun goJourney(): Boolean {
        value = snapshot(SpaceScreen.JOURNEY, defaultFor(SpaceScreen.JOURNEY))
        return true
    }

    private fun continuePlay(): Boolean {
        val id = resumeNodeId
            ?: value.waypoints.firstOrNull { it.visual == SpaceNodeVisual.CURRENT }?.nodeId
        if (id != null && openNode(id)) return true
        return goJourney()
    }

    private fun openNode(nodeId: String): Boolean {
        val view = views.find { it.node.id == nodeId } ?: return false
        val node = view.node
        if (shouldResumeHostedAdventure(node)) {
            enterAdventure()
            return true
        }
        when (view.availability) {
            NodeAvailability.FUTURE -> {
                localHint = "Это дальше по пути — сейчас можно другое место."
                value = snapshot(value.screen, "node:$nodeId").copy(localHint = localHint, wrongMs = 900)
                return true
            }
            NodeAvailability.MISSING_CONTENT -> {
                if (!JourneyEngine.isContainerNode(node) || !view.resolvedContentId.isNullOrBlank()) {
                    localHint = "Это занятие сейчас недоступно."
                    value = snapshot(value.screen, "node:$nodeId").copy(localHint = localHint)
                    return true
                }
            }
            else -> Unit
        }
        if (JourneyEngine.isContainerNode(node)) {
            val childId = JourneyEngine.playableChildNodeId(journey, progress, views, node.id)
            if (childId != null && childId != node.id) {
                return openNode(childId)
            }
            if (node.branches.isNotEmpty()) {
                showHubPicker(node)
                return true
            }
            if (node.type == JourneyNodeType.HUB || node.type == JourneyNodeType.CONCLUSION) {
                pendingManual = node.id
                return true
            }
        }
        if (opensHostedAdventure(view)) {
            enterAdventure()
            return true
        }
        val contentId = view.resolvedContentId ?: return false
        pendingExternal = contentId
        return true
    }

    private fun showHubPicker(node: JourneyNode) {
        value = snapshot(SpaceScreen.HUB, "branch:${node.branches.first().id}").copy(
            branches = node.branches.map { SpaceBranch(it.id, it.label, it.spokenLabel ?: it.label) },
            hubTitle = node.title,
        )
    }

    private fun shouldResumeHostedAdventure(from: JourneyNode): Boolean {
        if (from.type == JourneyNodeType.CONCLUSION) return false
        val rt = runtime ?: return false
        if (rt.isCompleted()) return false
        val hosted = journey.nodes.find { n ->
            val id = n.contentId ?: return@find false
            id == SpaceRoutes.CONTENT_ID || id == adventure?.id
        } ?: return false
        if (from.id == hosted.id) return true
        if (from.id != resumeNodeId) return false
        if (hosted.id !in progress.completedNodeIds) return true
        return hostedResumeSceneId != null
    }

    private fun opensHostedAdventure(view: JourneyNodeView): Boolean {
        val id = view.resolvedContentId ?: view.node.contentId ?: return false
        return id == SpaceRoutes.CONTENT_ID || SpaceRoutes.opens(
            ContentItem(id, view.node.title, type = ContentType.ADVENTURE),
        )
    }

    private fun chooseBranch(branchId: String): Boolean {
        val hub = views.find { it.node.id == (views.find { n -> n.node.branches.any { b -> b.id == branchId } }?.node?.id) }
            ?: views.find { it.node.branches.any { b -> b.id == branchId } }
        val from = hub?.node?.id ?: return false
        pendingBranch = SpaceBranchChoice(from, branchId)
        return true
    }

    private fun enterAdventure() {
        val rt = runtime ?: adventure?.let { AdventureRuntime(it) }?.also { runtime = it } ?: return
        if (!visitOpened) {
            visitOpened = true
            pendingOpened = true
        }
        adventureAttached = true
        syncAdventureScreen(firstLanding = true)
    }

    private fun leaveIntro(): Boolean {
        val rt = runtime ?: return false
        val scene = rt.currentScene()
        shownIntros += scene?.id.orEmpty()
        when (scene) {
            is ChallengeScene -> {
                value = snapshot(SpaceScreen.CHALLENGE, defaultFor(SpaceScreen.CHALLENGE))
            }
            is StoryScene, is MoveScene, is OffscreenScene, is ReflectionScene, is DialogueScene -> {
                rt.continueScene()
                if (rt.isCompleted()) {
                    pendingCompleted = true
                    adventureAttached = false
                    value = snapshot(SpaceScreen.JOURNEY, defaultFor(SpaceScreen.JOURNEY)).copy(worldReacted = true)
                } else {
                    syncAdventureScreen(firstLanding = true)
                }
            }
            else -> value = snapshot(SpaceScreen.CHALLENGE, defaultFor(SpaceScreen.CHALLENGE))
        }
        return true
    }

    private fun leaveSuccess(): Boolean {
        val rt = runtime ?: run {
            value = snapshot(SpaceScreen.JOURNEY, defaultFor(SpaceScreen.JOURNEY))
            return true
        }
        if (rt.snapshot().phase == AdventurePhase.FEEDBACK) rt.finishFeedback()
        else if (rt.currentScene() !is ChallengeScene) rt.continueScene()
        if (rt.isCompleted()) pendingCompleted = true
        adventureAttached = false
        beat = SpaceBeat.SETTLE
        beatMs = 0
        flyingKey = null
        value = snapshot(SpaceScreen.JOURNEY, defaultFor(SpaceScreen.JOURNEY)).copy(
            worldReacted = true,
            beat = SpaceBeat.SETTLE,
            beatMs = 0,
            installedKey = installedKey,
        )
        return true
    }

    private fun help(): Boolean {
        if (value.screen == SpaceScreen.INTRO) {
            value = value.copy(helping = true, helpMs = 2600, localHint = runtime?.snapshot()?.scaffoldMessage ?: value.guideLine)
            return true
        }
        if (value.screen != SpaceScreen.CHALLENGE && value.screen != SpaceScreen.SUCCESS) return false
        if (mechanicIsSort()) {
            value = snapshot(SpaceScreen.CHALLENGE, value.focus.takeIf { it.startsWith("cat:") } ?: defaultFor(SpaceScreen.CHALLENGE)).copy(
                helping = true,
                helpMs = 4200,
                localHint = null,
            )
            return true
        }
        val beforeDone = runtime?.isCompleted() == true
        runtime?.requestHelp()
        val after = runtime?.snapshot()
        if (after?.feedbackCorrect != true) runtime?.finishFeedback()
        localHint = after?.scaffoldMessage ?: after?.guideLine ?: evenHelpLine()
        value = snapshot(value.screen, "help".takeIf { it in controls() } ?: value.focus).copy(
            helping = true,
            helpMs = 4200,
            localHint = localHint,
        )
        if (beforeDone) return true
        return runtime?.isCompleted() != true || beforeDone
    }

    private fun pick(key: String): Boolean {
        val rt = runtime ?: return false
        val snap = rt.snapshot()
        if (snap.phase == AdventurePhase.FEEDBACK && snap.feedbackCorrect == false) {
            rt.finishFeedback()
        }
        when {
            key.startsWith("cell:") -> return pickMaze(key.removePrefix("cell:"))
            key.startsWith("obj:") -> {
                rt.selectMechanicTarget(key.removePrefix("obj:"))
                return afterMechanic()
            }
            key.startsWith("opt:") -> {
                flyingKey = key
                beat = SpaceBeat.INSTALL
                beatMs = 0
                rt.selectMechanicTarget(key.removePrefix("opt:"))
                rt.submitMechanic()
                return afterMechanic()
            }
            key.startsWith("item:") -> {
                sortHold = key.removePrefix("item:")
                value = snapshot(value.screen, value.focus).copy(sortHold = sortHold)
                return true
            }
            key.startsWith("cat:") -> {
                val params = (rt.currentScene() as? ChallengeScene)?.challenge?.params as? SortClassifyParams ?: return false
                val item = currentSortItemId(params, rt.snapshot().mechanic) ?: return false
                val cat = key.removePrefix("cat:")
                val expected = params.items.find { it.id == item }?.correctCategoryId
                if (expected != null && expected != cat) {
                    flyingKey = "item:$item"
                    installedKey = "cat:$cat"
                    beat = SpaceBeat.REJECT
                    beatMs = 0
                    localHint = "Этот мир не подходит. Попробуй другой."
                    sortHold = item
                    value = snapshot(SpaceScreen.CHALLENGE, key).copy(
                        wrongMs = 1400,
                        localHint = localHint,
                        beat = SpaceBeat.REJECT,
                        flyingKey = flyingKey,
                        installedKey = installedKey,
                        sortHold = item,
                    )
                    return true
                }
                rt.selectMechanicTarget("$item:$cat")
                val done = rt.snapshot().feedbackCorrect == true || rt.isCompleted()
                if (done) {
                    sortHold = null
                    flyingKey = "item:$item"
                    installedKey = "cat:$cat"
                    return afterMechanic()
                }
                sortHold = currentSortItemId(params, rt.snapshot().mechanic)
                beat = SpaceBeat.INSTALL
                beatMs = 0
                flyingKey = "item:$item"
                installedKey = "cat:$cat"
                value = snapshot(SpaceScreen.CHALLENGE, key).copy(
                    worldReacted = true,
                    beat = SpaceBeat.INSTALL,
                    flyingKey = flyingKey,
                    installedKey = installedKey,
                    sortHold = sortHold,
                )
                return true
            }
        }
        return false
    }

    private fun pickMaze(cell: String): Boolean {
        val rt = runtime ?: return false
        val params = (rt.currentScene() as? ChallengeScene)?.challenge?.params as? PathMazeParams ?: return false
        val pos = runCatching { ru.familytv.adventure.GridPos.parse(cell) }.getOrNull() ?: return false
        val label = params.cellLabels[pos]
        val number = label?.toIntOrNull()
        val even = number == null || number % 2 == 0
        val blocked = pos in params.blocked
        val cursor = rt.snapshot().mechanic?.cursor ?: params.start
        val manhattan = abs(pos.x - cursor.x) + abs(pos.y - cursor.y)
        if (blocked || !even || manhattan != 1 && pos != cursor) {
            localWrong = setOf("cell:$cell")
            beat = SpaceBeat.REJECT
            beatMs = 0
            localHint = (rt.currentScene() as? ChallengeScene)?.challenge?.scaffolding?.hint1
                ?: "Нужна соседняя чётная звезда."
            value = snapshot(SpaceScreen.CHALLENGE, "cell:$cell").copy(
                wrongMs = 1800,
                localHint = localHint,
                beat = SpaceBeat.REJECT,
            )
            return true
        }
        if (pos == cursor) return true
        rt.selectMechanicTarget(cell)
        return afterMechanic()
    }

    private fun submitChallenge(): Boolean {
        runtime?.submitMechanic()
        return afterMechanic()
    }

    private fun afterMechanic(): Boolean {
        val rt = runtime ?: return false
        val snap = rt.snapshot()
        when {
            snap.feedbackCorrect == true || snap.phase == AdventurePhase.FEEDBACK && snap.feedbackCorrect == true -> {
                installedKey = flyingKey ?: value.focus
                beat = SpaceBeat.INSTALL
                beatMs = 0
                value = snapshot(SpaceScreen.SUCCESS, "continue").copy(
                    worldReacted = true,
                    engineOn = true,
                    flying = false,
                    routeLit = true,
                    successMs = 1,
                    beat = SpaceBeat.INSTALL,
                    flyingKey = installedKey,
                    installedKey = installedKey,
                )
            }
            snap.feedbackCorrect == false -> {
                beat = SpaceBeat.REJECT
                beatMs = 0
                localHint = snap.scaffoldMessage ?: snap.guideLine ?: "Почти. Посмотри, как растут числа."
                rt.finishFeedback()
                value = snapshot(SpaceScreen.CHALLENGE, value.focus).copy(
                    wrongMs = 1400,
                    localHint = localHint,
                    beat = SpaceBeat.REJECT,
                    flyingKey = flyingKey ?: value.focus,
                )
            }
            else -> value = snapshot(SpaceScreen.CHALLENGE, value.focus)
        }
        if (rt.isCompleted()) {
            pendingCompleted = true
        }
        return true
    }

    private fun syncAdventureScreen(firstLanding: Boolean) {
        val rt = runtime ?: return
        val snap = rt.snapshot()
        if (rt.isCompleted()) {
            pendingCompleted = true
            adventureAttached = false
            value = snapshot(SpaceScreen.JOURNEY, defaultFor(SpaceScreen.JOURNEY)).copy(worldReacted = true)
            return
        }
        val scene = rt.currentScene()
        val screen = when {
            snap.feedbackCorrect == true -> SpaceScreen.SUCCESS
            scene is ChallengeScene -> {
                shownIntros += scene.id
                SpaceScreen.CHALLENGE
            }
            scene is StoryScene && !firstLanding && (snap.variables["shipPower"] ?: 0) > 0 -> SpaceScreen.SUCCESS
            else -> SpaceScreen.INTRO
        }
        value = snapshot(screen, defaultFor(screen))
    }

    private fun needsCheck(): Boolean = false

    private fun mechanicIsSort(): Boolean =
        (runtime?.currentScene() as? ChallengeScene)?.challenge?.mechanic == MechanicType.SORT_AND_CLASSIFY

    private fun currentSortItemId(
        params: SortClassifyParams,
        mechanic: MechanicSnapshot?,
    ): String? {
        val placed = mechanic?.placements.orEmpty()
        sortHold?.takeIf { id -> id !in placed && params.items.any { it.id == id } }?.let { return it }
        return params.items.firstOrNull { it.id !in placed }?.id
    }

    private fun evenHelpLine(): String =
        "Чётное число собирается парами. Две и две — летим. Одна звезда без пары — нет."

    private fun defaultFor(screen: SpaceScreen): String = when (screen) {
        SpaceScreen.ENTRY -> "continue"
        SpaceScreen.JOURNEY -> resumeNodeId?.let { "node:$it" } ?: "continue"
        SpaceScreen.HUB -> views.firstOrNull { it.node.branches.isNotEmpty() }
            ?.node?.branches?.firstOrNull()?.id?.let { "branch:$it" } ?: "back"
        SpaceScreen.INTRO, SpaceScreen.SUCCESS -> "continue"
        SpaceScreen.CHALLENGE -> {
            val challenge = (runtime?.currentScene() as? ChallengeScene)?.challenge
            if (challenge?.mechanic == MechanicType.SORT_AND_CLASSIFY) {
                categoriesOf(challenge.params).firstOrNull()?.key ?: "help"
            } else {
                val choices = choicesOf(challenge?.params, runtime?.snapshot()?.mechanic, challenge?.mechanic)
                choices.firstOrNull { it.cursor }?.key
                    ?: choices.firstOrNull { !it.blocked }?.key
                    ?: "help"
            }
        }
    }

    private fun snapshot(screen: SpaceScreen, focus: String): SpaceSnapshot {
        val required = journey.nodes.filter { !it.optional }
        val done = required.count { it.id in progress.completedNodeIds }
        val last = progress.lastVisitedNodeId?.let { journey.node(it) }
            ?.takeIf { it.id in progress.completedNodeIds }?.mapLabel
        val current = resumeNodeId?.let { journey.node(it) }
        val rtLive = runtime?.snapshot()
        val rt = runtime?.takeIf { adventureAttached }?.snapshot()
        val scene = rt?.scene
        val challenge = (scene as? ChallengeScene)?.challenge
        val mechanic = rt?.mechanic
        val params = challenge?.params
        val choices = choicesOf(params, mechanic, challenge?.mechanic)
        val categories = categoriesOf(params)
        val energy = rtLive?.variables?.get("shipPower") ?: 0
        val navigated = rtLive?.variables?.get("navigated") ?: 0
        val repaired = rtLive?.variables?.get("repaired") ?: 0
        val guide = when (screen) {
            SpaceScreen.ENTRY -> entryGuide(current?.mapLabel)
            SpaceScreen.JOURNEY -> journeyGuide(current?.mapLabel)
            SpaceScreen.HUB -> "Куда дальше?"
            SpaceScreen.INTRO -> introGuide(scene, challenge)
            SpaceScreen.CHALLENGE -> challengeGuide(challenge, rt?.guideLine)
            SpaceScreen.SUCCESS -> successGuide(energy, navigated, repaired)
        }
        val wanted = focus.takeIf { it in controlsFor(screen, choices, categories, views.find { it.node.branches.isNotEmpty() }?.node?.branches.orEmpty()) } ?: focus
        return SpaceSnapshot(
            screen = screen,
            focus = wanted,
            paused = value.paused,
            helping = value.helping && screen == SpaceScreen.CHALLENGE,
            journeyTitle = journey.title,
            currentLabel = current?.mapLabel ?: journey.startNodeId.let { journey.node(it)?.mapLabel }.orEmpty(),
            lastCompletedLabel = last,
            guideLine = guide,
            waypoints = waypointsOf(),
            resumeNodeId = resumeNodeId,
            started = progress.started,
            completed = progress.completed,
            availableLabels = JourneyEngine.availableNextLabels(journey, progress),
            requiredDone = done,
            requiredTotal = required.size,
            sceneId = rtLive?.sceneId,
            mechanicType = challenge?.mechanic?.name,
            challengeId = challenge?.id,
            choices = choices,
            sequenceDisplay = (params as? PatternSequenceParams)?.display.orEmpty(),
            categories = categories,
            shipEnergy = energy,
            engineOn = energy > 0 || screen == SpaceScreen.SUCCESS,
            flying = navigated > 0 && screen == SpaceScreen.SUCCESS,
            routeLit = navigated > 0 || repaired > 0 || screen == SpaceScreen.SUCCESS,
            worldReacted = value.worldReacted || screen == SpaceScreen.SUCCESS,
            wrongMs = if (screen == value.screen) value.wrongMs else 0,
            helpMs = if (screen == value.screen) value.helpMs else 0,
            successMs = if (screen == SpaceScreen.SUCCESS) value.successMs.coerceAtLeast(1) else 0,
            branches = if (screen == SpaceScreen.HUB) {
                views.firstOrNull { it.node.branches.isNotEmpty() && (it.availability == NodeAvailability.AVAILABLE || it.availability == NodeAvailability.COMPLETED) }
                    ?.node?.branches?.map { SpaceBranch(it.id, it.label, it.spokenLabel ?: it.label) }
                    ?: value.branches
            } else emptyList(),
            hubTitle = views.firstOrNull { it.node.branches.isNotEmpty() }?.node?.title.orEmpty(),
            localHint = localHint,
            sortHold = if (params is SortClassifyParams) currentSortItemId(params, mechanic) else sortHold,
            beat = beat,
            beatMs = beatMs,
            flyingKey = flyingKey,
            installedKey = installedKey,
            sequenceBlank = (params as? PatternSequenceParams)?.blankIndex ?: 2,
            repaired = repaired,
            navigated = navigated,
            adventureSceneId = rtLive?.sceneId,
            hintTarget = (params as? SortClassifyParams)?.let { p ->
                currentSortItemId(p, mechanic)?.let { id ->
                    p.items.find { it.id == id }?.correctCategoryId?.let { "cat:$it" }
                }
            },
        )
    }

    private fun controlsFor(
        screen: SpaceScreen,
        choices: List<SpaceChoice>,
        categories: List<SpaceChoice>,
        branches: List<ru.familytv.journey.JourneyBranch>,
    ): List<String> = when (screen) {
        SpaceScreen.ENTRY -> listOf("home", "continue", "journey", "journal")
        SpaceScreen.JOURNEY -> listOf("back") + views.map { "node:${it.node.id}" } + listOf("continue", "journal")
        SpaceScreen.HUB -> listOf("back") + branches.map { "branch:${it.id}" }
        SpaceScreen.INTRO -> listOf("back", "continue", "help")
        SpaceScreen.CHALLENGE -> {
            val picks = if ((runtime?.currentScene() as? ChallengeScene)?.challenge?.mechanic == MechanicType.SORT_AND_CLASSIFY) {
                emptyList()
            } else choices.map { it.key }
            listOf("back", "pause", "help") + picks + categories.map { it.key }
        }
        SpaceScreen.SUCCESS -> listOf("continue")
    }

    private fun waypointsOf(): List<SpaceWaypoint> = views.map { view ->
        val visual = when (view.availability) {
            NodeAvailability.COMPLETED -> SpaceNodeVisual.COMPLETED
            NodeAvailability.MISSING_CONTENT -> when {
                view.node.type == JourneyNodeType.CONCLUSION ||
                    view.node.type == JourneyNodeType.HUB ||
                    view.resolvedContentId.isNullOrBlank() ->
                    if (view.node.id == resumeNodeId) SpaceNodeVisual.CURRENT else SpaceNodeVisual.AVAILABLE
                else -> SpaceNodeVisual.MISSING
            }
            NodeAvailability.FUTURE -> SpaceNodeVisual.LOCKED
            NodeAvailability.AVAILABLE ->
                if (view.node.id == resumeNodeId) SpaceNodeVisual.CURRENT else SpaceNodeVisual.AVAILABLE
        }
        val at = SpaceGeometry.waypoint(view.node.id, view.node.mapProgress ?: 0f, view.node.optional)
        SpaceWaypoint(
            nodeId = view.node.id,
            label = view.node.mapLabel,
            title = view.node.title,
            spoken = view.node.spokenLabel ?: view.node.mapLabel,
            visual = visual,
            optional = view.node.optional,
            x = at.first,
            y = at.second,
            type = view.node.type,
            contentId = view.resolvedContentId,
        )
    }

    private fun choicesOf(
        params: ru.familytv.adventure.MechanicParams?,
        mechanic: MechanicSnapshot?,
        type: MechanicType?,
    ): List<SpaceChoice> = when (params) {
        is VisualSearchParams -> params.objects.map { obj ->
            SpaceChoice(
                key = "obj:${obj.id}",
                label = obj.label,
                selected = obj.id in mechanic?.selectedIds.orEmpty(),
                required = obj.id in params.requiredIds,
                wrong = "obj:${obj.id}" in localWrong,
            )
        }
        is PathMazeParams -> buildList {
            for (y in 0 until params.height) for (x in 0 until params.width) {
                val pos = ru.familytv.adventure.GridPos(x, y)
                val label = params.cellLabels[pos] ?: ""
                val number = label.toIntOrNull()
                SpaceChoice(
                    key = "cell:$x,$y",
                    label = if (label == "✖") "" else label,
                    even = number?.let { it % 2 == 0 },
                    selected = pos in mechanic?.path.orEmpty(),
                    onPath = pos in mechanic?.path.orEmpty(),
                    blocked = pos in params.blocked,
                    cursor = mechanic?.cursor == pos,
                    goal = pos == params.goal,
                    wrong = "cell:$x,$y" in localWrong,
                ).let { add(it) }
            }
        }
        is PatternSequenceParams -> params.options.map { opt ->
            SpaceChoice(
                key = "opt:$opt",
                label = opt,
                even = opt.toIntOrNull()?.let { it % 2 == 0 },
                selected = mechanic?.chosenOption == opt,
                required = opt == params.correctOption,
                wrong = "opt:$opt" in localWrong || (value.wrongMs > 0 && mechanic?.chosenOption == opt && opt != params.correctOption),
            )
        }
        is SortClassifyParams -> params.items.map { item ->
            SpaceChoice(
                key = "item:${item.id}",
                label = item.label,
                selected = mechanic?.placements?.containsKey(item.id) == true || sortHold == item.id,
                placedIn = mechanic?.placements?.get(item.id),
            )
        }
        else -> emptyList()
    }

    private fun categoriesOf(params: ru.familytv.adventure.MechanicParams?): List<SpaceChoice> {
        val p = params as? SortClassifyParams ?: return emptyList()
        val mechanic = runtime?.snapshot()?.mechanic
        return p.categories.map { cat ->
            SpaceChoice(
                key = "cat:${cat.id}",
                label = cat.label,
                selected = mechanic?.placements?.values?.contains(cat.id) == true,
            )
        }
    }

    private fun entryGuide(current: String?): String {
        if (progress.completed) return journey.completionLine
        if (!progress.started) return "Корабль ждёт. Начнём большую космическую экспедицию?"
        val here = current ?: "Старт"
        val last = value.lastCompletedLabel?.let { "Мы уже были: $it. " } ?: ""
        return "${last}Сейчас путь ведёт к точке «$here». Продолжим?"
    }

    private fun journeyGuide(current: String?): String {
        val where = JourneyNarrator.whereWeAre(journey, progress)
        val next = JourneyNarrator.whatNext(journey, progress)
        val here = current?.let { " Мы сейчас у точки «$it»." } ?: ""
        return "$where$here $next".trim()
    }

    private fun introGuide(scene: ru.familytv.adventure.AdventureScene?, challenge: ru.familytv.adventure.ChallengeDef?): String {
        if (scene is ChallengeScene) {
            return when (scene.challenge.mechanic) {
                MechanicType.PATH_MAZE -> "Корабль на курсе. Нужны чётные звёзды — тогда двигатель ведёт дальше."
                MechanicType.VISUAL_SEARCH -> "Двигатель молчит. Положи синюю энергию в пустые места."
                MechanicType.PATTERN_SEQUENCE -> "На пути пропала энергия. Какое число продолжит ряд?"
                MechanicType.SORT_AND_CLASSIFY -> "Находки с планеты. Разложи их по миру: вода, суша или воздух."
                else -> scene.challenge.spokenPrompt ?: scene.challenge.prompt
            }
        }
        return when (scene) {
            is StoryScene -> scene.spokenPrompt ?: scene.guideLine ?: scene.text
            is DialogueScene -> scene.prompt
            is MoveScene -> scene.prompt
            is OffscreenScene -> scene.guideLine ?: scene.prompt
            is ReflectionScene -> scene.text
            else -> challenge?.spokenPrompt ?: "Смотри на корабль — ему нужна наша помощь."
        }
    }

    private fun challengeGuide(challenge: ru.familytv.adventure.ChallengeDef?, fallback: String?): String {
        if (value.wrongMs > 0 && !localHint.isNullOrBlank()) return localHint!!
        if (value.helping && !localHint.isNullOrBlank()) return localHint!!
        return when (challenge?.mechanic) {
            MechanicType.PATTERN_SEQUENCE -> "На пути пропала энергия. Какое число продолжит ряд?"
            MechanicType.PATH_MAZE -> "Чётные звёзды ведут корабль. Пройди соседнюю светлую звезду."
            MechanicType.VISUAL_SEARCH -> "Поставь синие ячейки энергии в пустые гнёзда корабля."
            MechanicType.SORT_AND_CLASSIFY -> "Положи находку в её мир."
            else -> challenge?.spokenPrompt ?: challenge?.prompt ?: fallback.orEmpty()
        }
    }

    private fun successGuide(@Suppress("UNUSED_PARAMETER") energy: Int, @Suppress("UNUSED_PARAMETER") navigated: Int, @Suppress("UNUSED_PARAMETER") repaired: Int): String =
        "Получилось! Летим дальше."

    private fun neighbor(keys: List<String>, focus: String, direction: SpacePad): String? {
        if (keys.isEmpty()) return null
        val idx = keys.indexOf(focus).takeIf { it >= 0 } ?: 0
        val nodes = keys.filter { it.startsWith("node:") }
        val choices = keys.filter { it.startsWith("cell:") || it.startsWith("obj:") || it.startsWith("opt:") || it.startsWith("item:") }
        return when (value.screen) {
            SpaceScreen.ENTRY -> when (direction) {
                SpacePad.LEFT -> keys.getOrNull((idx - 1).coerceAtLeast(0))
                SpacePad.RIGHT -> keys.getOrNull((idx + 1).coerceAtMost(keys.lastIndex))
                SpacePad.UP -> "home"
                SpacePad.DOWN -> "continue"
            }
            SpaceScreen.JOURNEY -> when {
                focus.startsWith("node:") && direction == SpacePad.LEFT -> nodes.getOrNull((nodes.indexOf(focus) - 1).coerceAtLeast(0))
                focus.startsWith("node:") && direction == SpacePad.RIGHT -> nodes.getOrNull((nodes.indexOf(focus) + 1).coerceAtMost(nodes.lastIndex))
                focus.startsWith("node:") && direction == SpacePad.DOWN -> "continue"
                focus.startsWith("node:") && direction == SpacePad.UP -> "back"
                focus == "back" && direction == SpacePad.DOWN -> nodes.firstOrNull() ?: "continue"
                (focus == "continue" || focus == "journal") && direction == SpacePad.UP -> nodes.firstOrNull() ?: "back"
                focus == "continue" && direction == SpacePad.RIGHT -> "journal"
                focus == "journal" && direction == SpacePad.LEFT -> "continue"
                else -> keys.getOrNull(idx + if (direction == SpacePad.RIGHT || direction == SpacePad.DOWN) 1 else -1)
            }
            SpaceScreen.CHALLENGE -> challengeNeighbor(keys, focus, direction, choices)
            else -> keys.getOrNull(
                (idx + if (direction == SpacePad.RIGHT || direction == SpacePad.DOWN) 1 else -1).coerceIn(0, keys.lastIndex),
            )
        }
    }

    private fun challengeNeighbor(keys: List<String>, focus: String, direction: SpacePad, choices: List<String>): String? {
        val cats = keys.filter { it.startsWith("cat:") }
        if (cats.isNotEmpty() && (focus.startsWith("cat:") || mechanicIsSort())) {
            if (!focus.startsWith("cat:") && direction == SpacePad.DOWN) return cats.firstOrNull()
            val i = cats.indexOf(focus).takeIf { it >= 0 } ?: 0
            return when (direction) {
                SpacePad.LEFT -> cats.getOrNull(i - 1) ?: cats.first()
                SpacePad.RIGHT -> cats.getOrNull(i + 1) ?: cats.last()
                SpacePad.UP -> "help".takeIf { it in keys }
                SpacePad.DOWN -> cats.getOrNull(i)
            }
        }
        val items = keys.filter { it.startsWith("item:") }
        if (focus.startsWith("item:") && items.isNotEmpty()) {
            val i = items.indexOf(focus)
            return when (direction) {
                SpacePad.LEFT -> items.getOrNull(i - 1)
                SpacePad.RIGHT -> items.getOrNull(i + 1)
                SpacePad.UP -> "help".takeIf { it in keys }
                SpacePad.DOWN -> cats.firstOrNull() ?: "check".takeIf { it in keys } ?: items.getOrNull(i)
            }
        }
        if (focus.startsWith("cat:") && cats.isNotEmpty()) {
            val i = cats.indexOf(focus)
            return when (direction) {
                SpacePad.LEFT -> cats.getOrNull(i - 1)
                SpacePad.RIGHT -> cats.getOrNull(i + 1)
                SpacePad.UP -> items.firstOrNull() ?: "help".takeIf { it in keys }
                SpacePad.DOWN -> "check".takeIf { it in keys } ?: cats.getOrNull(i)
            }
        }
        return mazeNeighbor(keys, focus, direction, choices)
    }

    private fun mazeNeighbor(keys: List<String>, focus: String, direction: SpacePad, choices: List<String>): String? {
        if (focus.startsWith("cell:")) {
            val parts = focus.removePrefix("cell:").split(",")
            val x = parts[0].toIntOrNull() ?: return null
            val y = parts[1].toIntOrNull() ?: return null
            val nx = x + if (direction == SpacePad.RIGHT) 1 else if (direction == SpacePad.LEFT) -1 else 0
            val ny = y + if (direction == SpacePad.DOWN) 1 else if (direction == SpacePad.UP) -1 else 0
            val next = "cell:$nx,$ny"
            if (next in keys) return next
            if (direction == SpacePad.UP) return "help"
            if (direction == SpacePad.DOWN && "check" in keys) return "check"
        }
        if (choices.isNotEmpty() && focus in choices) {
            val i = choices.indexOf(focus)
            return when (direction) {
                SpacePad.LEFT -> choices.getOrNull(i - 1)
                SpacePad.RIGHT -> choices.getOrNull(i + 1)
                SpacePad.UP -> "help"
                SpacePad.DOWN -> if ("check" in keys) "check" else choices.getOrNull(i)
            }
        }
        val idx = keys.indexOf(focus)
        return keys.getOrNull(idx + if (direction == SpacePad.RIGHT || direction == SpacePad.DOWN) 1 else -1)
    }
}

enum class SpacePad { LEFT, RIGHT, UP, DOWN }
