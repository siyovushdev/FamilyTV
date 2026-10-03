package ru.familytv.adventure

import ru.familytv.adventure.mechanics.MechanicEngine
import ru.familytv.adventure.mechanics.MechanicRegistry
import ru.familytv.adventure.mechanics.MechanicSnapshot
import ru.familytv.adventure.mechanics.MechanicVerdict

enum class AdventurePhase {
    SCENE,
    CHALLENGE_ACTIVE,
    FEEDBACK,
    GUIDE_REACTION,
    COMPLETED,
}

enum class ScaffoldLevel {
    NONE,
    HINT1,
    HINT2_VISUAL,
    SIMPLIFIED,
    EXPLAIN,
}

data class AdventureViewState(
    val adventureId: String,
    val sceneId: String,
    val phase: AdventurePhase,
    val scene: AdventureScene?,
    val variables: Map<String, Int>,
    val completedObjectives: Set<String>,
    val difficulty: Int,
    val attemptCount: Int,
    val scaffoldLevel: ScaffoldLevel,
    val scaffoldMessage: String?,
    val mechanic: MechanicSnapshot?,
    val guideEmotion: GuideEmotion,
    val guideLine: String?,
    val groupPrompt: String?,
    val feedbackCorrect: Boolean? = null,
    val inventory: Set<String> = emptySet(),
)

/**
 * Deterministic adventure state machine. Testable without Android.
 * Expected answers never flow into Qwen — only normalized transcript comparison in mechanics/dialogue.
 */
class AdventureRuntime(
    val adventure: Adventure,
    startSceneId: String? = null,
    initialDifficulty: Int? = null,
) {
    private val scenesById = adventure.scenes.associateBy { it.id }
    private var sceneId: String = startSceneId
        ?: adventure.startSceneId
            .takeIf { it in scenesById }
        ?: adventure.scenes.first().id
    private var variables: MutableMap<String, Int> = adventure.variables.toMutableMap()
    private var completedObjectives: MutableSet<String> = linkedSetOf()
    private var inventory: MutableSet<String> = linkedSetOf()
    private var difficulty: Int =
        (initialDifficulty ?: adventure.difficultyMin).coerceIn(adventure.difficultyMin, adventure.difficultyMax)
    private var attemptCount: Int = 0
    private var scaffoldLevel: ScaffoldLevel = ScaffoldLevel.NONE
    private var consecutiveCorrect: Int = 0
    private var consecutiveWrong: Int = 0
    private var mechanic: MechanicSnapshot? = null
    private var activeChallenge: ChallengeDef? = null
    private var activeParams: MechanicParams? = null
    private var phase: AdventurePhase = AdventurePhase.SCENE
    private var guideEmotion: GuideEmotion = adventure.guide.defaultEmotion
    private var guideLine: String? = null
    private var feedbackCorrect: Boolean? = null
    private var lastScaffoldMessage: String? = null

    init {
        enterScene(sceneId, isInit = true)
    }

    fun snapshot(): AdventureViewState = AdventureViewState(
        adventureId = adventure.id,
        sceneId = sceneId,
        phase = phase,
        scene = scenesById[sceneId],
        variables = variables.toMap(),
        completedObjectives = completedObjectives.toSet(),
        difficulty = difficulty,
        attemptCount = attemptCount,
        scaffoldLevel = scaffoldLevel,
        scaffoldMessage = lastScaffoldMessage,
        mechanic = mechanic,
        guideEmotion = guideEmotion,
        guideLine = guideLine,
        groupPrompt = groupPromptFor(scenesById[sceneId]),
        feedbackCorrect = feedbackCorrect,
        inventory = inventory.toSet(),
    )

    fun currentScene(): AdventureScene? = scenesById[sceneId]

    fun isCompleted(): Boolean = phase == AdventurePhase.COMPLETED

    fun advanceStory() {
        val scene = scenesById[sceneId] ?: return
        when (scene) {
            is StoryScene, is MoveScene, is OffscreenScene, is ReflectionScene -> {
                val next = scene.next
                if (next == null) completeAdventure()
                else enterScene(next)
            }
            else -> Unit
        }
    }

    fun confirmOffscreen() {
        val scene = scenesById[sceneId] as? OffscreenScene ?: return
        guideEmotion = GuideEmotion.EXCITED
        guideLine = "Отлично! Вы справились."
        val next = scene.next
        if (next == null) completeAdventure() else enterScene(next)
    }

    /** Ask→Do: jump to authored challenge by id when WHAT_IF maps to experimentActionRef. */
    fun jumpToChallengeById(challengeId: String): Boolean {
        val target = adventure.scenes.firstOrNull {
            it is ChallengeScene && it.challenge.id == challengeId
        } as? ChallengeScene ?: return false
        enterScene(target.id)
        return true
    }

    fun selectMechanicTarget(objectId: String) {
        val challenge = activeChallenge ?: return
        val params = activeParams ?: return
        val engine = MechanicRegistry.get(challenge.mechanic)
        val snap = mechanic ?: return
        mechanic = engine.applySelect(snap, params, objectId)
        evaluateMechanic(engine, autoSubmit = false)
    }

    fun submitMechanic() {
        val challenge = activeChallenge ?: return
        val params = activeParams ?: return
        val engine = MechanicRegistry.get(challenge.mechanic)
        if (MechanicRegistry.isDiscovery(challenge.mechanic)) {
            val snap = mechanic ?: return
            mechanic = engine.applySelect(snap, params, "__test__")
        }
        evaluateMechanic(engine, autoSubmit = true)
    }

    fun voiceInput(normalizedTranscript: String) {
        val scene = scenesById[sceneId]
        when (scene) {
            is DialogueScene -> handleDialogueVoice(scene, normalizedTranscript)
            is OffscreenScene -> {
                val ok = scene.voiceConfirmPhrases.any {
                    normalizedTranscript.contains(it.lowercase()) || normalizedTranscript == it.lowercase()
                }
                if (ok) confirmOffscreen()
            }
            is ChallengeScene -> {
                val challenge = activeChallenge ?: return
                val params = activeParams ?: return
                val engine = MechanicRegistry.get(challenge.mechanic)
                val snap = mechanic ?: return
                val (next, verdict) = engine.applyVoice(snap, params, normalizedTranscript)
                mechanic = next
                when (verdict) {
                    MechanicVerdict.CORRECT -> onChallengeSuccess(challenge)
                    MechanicVerdict.WRONG -> onChallengeWrong(challenge)
                    MechanicVerdict.OBSERVED -> onDiscoveryObserved(challenge)
                    MechanicVerdict.UNKNOWN -> {
                        guideEmotion = GuideEmotion.THINKING
                        guideLine = "Не совсем расслышал. Попробуй ещё раз или выбери на экране."
                    }
                    MechanicVerdict.INCOMPLETE -> Unit
                }
            }
            else -> Unit
        }
    }

    /**
     * HELP — advance scaffolding one step. Does not count as a wrong answer for difficulty,
     * does not reset mechanic selection, does not treat transcript as an answer.
     */
    fun requestHelp(): Boolean {
        val challenge = activeChallenge ?: return false
        if (phase != AdventurePhase.CHALLENGE_ACTIVE && phase != AdventurePhase.FEEDBACK) return false
        val before = scaffoldLevel
        // Drive scaffolding levels via attemptCount without scoring a wrong for adaptation
        if (attemptCount < 1) attemptCount = 1
        else if (attemptCount < 3) attemptCount++
        applyScaffolding(challenge)
        phase = AdventurePhase.FEEDBACK
        feedbackCorrect = null
        guideEmotion = GuideEmotion.ENCOURAGING
        return scaffoldLevel != before || !lastScaffoldMessage.isNullOrBlank()
    }

    /** Snapshot values that REPEAT must not change. */
    fun challengeMutationToken(): Long =
        attemptCount * 1_000_000L + consecutiveWrong * 10_000L + consecutiveCorrect * 100L +
            difficulty * 10L + scaffoldLevel.ordinal

    /** Restore in-progress mechanic selection after process death. Does not re-evaluate. */
    fun restoreMechanic(snapshot: MechanicSnapshot) {
        if (phase == AdventurePhase.CHALLENGE_ACTIVE || phase == AdventurePhase.FEEDBACK) {
            mechanic = snapshot
        }
    }

    /** D-pad continue for non-challenge scenes. Does not mark a challenge correct. */
    fun continueScene() {
        when (val scene = scenesById[sceneId]) {
            is StoryScene, is MoveScene, is ReflectionScene -> advanceStory()
            is OffscreenScene -> confirmOffscreen()
            is DialogueScene -> {
                val next = scene.next
                if (next == null) completeAdventure() else enterScene(next)
            }
            else -> Unit
        }
    }

    fun finishFeedback() {
        if (phase != AdventurePhase.FEEDBACK && dialoguePendingNext == null) return
        val wasCorrect = feedbackCorrect == true
        feedbackCorrect = null
        if (dialoguePendingNext != null) {
            consumeDialogueAdvance()
            return
        }
        val challenge = activeChallenge
        if (challenge != null && wasCorrect) {
            val next = challenge.successNext ?: scenesById[sceneId]?.next
            if (next == null) completeAdventure() else enterScene(next)
            return
        }
        if (challenge != null) {
            phase = AdventurePhase.CHALLENGE_ACTIVE
            guideLine = lastScaffoldMessage
            return
        }
        val next = scenesById[sceneId]?.next
        if (next == null) completeAdventure() else enterScene(next)
    }

    private fun evaluateMechanic(engine: MechanicEngine, autoSubmit: Boolean) {
        val challenge = activeChallenge ?: return
        val params = activeParams ?: return
        val snap = mechanic ?: return
        val verdict = engine.evaluate(snap, params)
        when (verdict) {
            MechanicVerdict.CORRECT -> onChallengeSuccess(challenge)
            MechanicVerdict.WRONG -> if (autoSubmit) onChallengeWrong(challenge)
            MechanicVerdict.OBSERVED -> onDiscoveryObserved(challenge)
            MechanicVerdict.INCOMPLETE -> if (autoSubmit) {
                guideEmotion = GuideEmotion.ENCOURAGING
                guideLine = when {
                    MechanicRegistry.isDiscovery(challenge.mechanic) &&
                        mechanic?.chosenOption != null &&
                        mechanic?.phase == "PREDICT" -> "Теперь нажми «Проверить» — посмотрим, что будет."
                    MechanicRegistry.isDiscovery(challenge.mechanic) -> "Собери вариант и нажми «Проверить»."
                    else -> "Почти! Продолжай."
                }
            }
            MechanicVerdict.UNKNOWN -> Unit
        }
    }

    /** Discovery observation — no wrong score, no "you lost". */
    private fun onDiscoveryObserved(challenge: ChallengeDef) {
        val obs = mechanic?.observation
        guideEmotion = GuideEmotion.CURIOUS
        guideLine = obs ?: "Интересно! Что ты заметил?"
        when (challenge.mechanic) {
            MechanicType.PREDICT_AND_TEST -> {
                challenge.successSets.forEach { (k, v) -> variables[k] = v }
                maybeCompleteObjectives()
                feedbackCorrect = true
                phase = AdventurePhase.FEEDBACK
            }
            MechanicType.BUILD_AND_TEST -> {
                // Failed build shows why; stay active for rebuild — zero wrongs
                feedbackCorrect = null
                phase = AdventurePhase.CHALLENGE_ACTIVE
                lastScaffoldMessage = obs
            }
            else -> {
                feedbackCorrect = true
                phase = AdventurePhase.FEEDBACK
            }
        }
    }

    private fun onChallengeSuccess(challenge: ChallengeDef) {
        attemptCount = 0
        consecutiveWrong = 0
        consecutiveCorrect++
        adaptDifficultyAfterSuccess()
        challenge.successSets.forEach { (k, v) -> variables[k] = v }
        maybeCompleteObjectives()
        feedbackCorrect = true
        phase = AdventurePhase.FEEDBACK
        guideEmotion = GuideEmotion.EXCITED
        guideLine = when {
            MechanicRegistry.isDiscovery(challenge.mechanic) ->
                mechanic?.observation ?: "Интересно получилось!"
            else -> "Да! Так и есть."
        }
        scaffoldLevel = ScaffoldLevel.NONE
        lastScaffoldMessage = null
        // Auto-advance success path on finishFeedback
        consecutiveWrong = 0
    }

    private fun onChallengeWrong(challenge: ChallengeDef) {
        attemptCount++
        consecutiveCorrect = 0
        consecutiveWrong++
        adaptDifficultyAfterWrong()
        feedbackCorrect = false
        phase = AdventurePhase.FEEDBACK
        guideEmotion = GuideEmotion.ENCOURAGING
        applyScaffolding(challenge)
    }

    private fun applyScaffolding(challenge: ChallengeDef) {
        val sc = challenge.scaffolding
        when {
            attemptCount == 1 && !sc.hint1.isNullOrBlank() -> {
                scaffoldLevel = ScaffoldLevel.HINT1
                lastScaffoldMessage = sc.hint1
                guideLine = sc.hint1
            }
            attemptCount == 2 && (sc.visualHint || !sc.hint2.isNullOrBlank()) -> {
                scaffoldLevel = ScaffoldLevel.HINT2_VISUAL
                lastScaffoldMessage = sc.hint2 ?: "Посмотри внимательнее на подсветку."
                guideLine = lastScaffoldMessage
                if (sc.visualHint && challenge.mechanic == MechanicType.VISUAL_SEARCH) {
                    val params = activeParams
                    val snap = mechanic
                    if (params is VisualSearchParams && snap != null) {
                        mechanic = ru.familytv.adventure.mechanics.VisualSearchEngine
                            .withVisualHint(snap, params)
                    }
                }
            }
            attemptCount >= 3 && sc.simplifiedParams != null -> {
                scaffoldLevel = ScaffoldLevel.SIMPLIFIED
                lastScaffoldMessage = "Давай упростим задачу."
                guideLine = lastScaffoldMessage
                activeParams = sc.simplifiedParams
                mechanic = MechanicRegistry.get(challenge.mechanic)
                    .initial(sc.simplifiedParams, simplified = true)
            }
            attemptCount >= 3 && !sc.explanation.isNullOrBlank() -> {
                scaffoldLevel = ScaffoldLevel.EXPLAIN
                lastScaffoldMessage = sc.explanation
                guideLine = sc.explanation
            }
            else -> {
                scaffoldLevel = ScaffoldLevel.HINT1
                lastScaffoldMessage = sc.hint1 ?: "Попробуй ещё раз — ты справишься."
                guideLine = lastScaffoldMessage
            }
        }
    }

    private fun adaptDifficultyAfterSuccess() {
        if (consecutiveCorrect >= 2 && difficulty < adventure.difficultyMax) {
            difficulty++
            consecutiveCorrect = 0
        }
    }

    private fun adaptDifficultyAfterWrong() {
        if (consecutiveWrong >= 2 && difficulty > adventure.difficultyMin) {
            difficulty--
            consecutiveWrong = 0
        }
    }

    private fun handleDialogueVoice(scene: DialogueScene, normalized: String) {
        val hit = scene.accepted.firstOrNull { accept ->
            accept.phrases.any { phrase ->
                val p = phrase.lowercase().trim()
                normalized == p || normalized.contains(p)
            }
        }
        if (hit == null) {
            guideEmotion = GuideEmotion.THINKING
            guideLine = scene.unknownReply
            return
        }
        guideEmotion = GuideEmotion.EXCITED
        guideLine = hit.reply
        phase = AdventurePhase.FEEDBACK
        feedbackCorrect = true
        val next = scene.onMappedNext ?: scene.next
        // Stash next via success path
        activeChallenge = null
        // Use a one-shot: store next in variables marker
        if (next != null) {
            variables["__dialogue_next"] = 1
            // finishFeedback will read scene.next — override by entering
            // We'll enter on finishFeedback via custom: set scene next temporarily
        }
        // Immediately schedule transition id
        dialoguePendingNext = next
    }

    private var dialoguePendingNext: String? = null

    fun consumeDialogueAdvance() {
        val next = dialoguePendingNext
        dialoguePendingNext = null
        feedbackCorrect = null
        if (next == null) completeAdventure() else enterScene(next)
    }

    private fun enterScene(id: String, isInit: Boolean = false) {
        val scene = scenesById[id] ?: run {
            completeAdventure()
            return
        }
        sceneId = id
        attemptCount = 0
        scaffoldLevel = ScaffoldLevel.NONE
        lastScaffoldMessage = null
        feedbackCorrect = null
        activeChallenge = null
        activeParams = null
        mechanic = null
        phase = AdventurePhase.SCENE
        when (scene) {
            is StoryScene -> {
                guideEmotion = scene.guideEmotion
                guideLine = scene.guideLine ?: scene.text
            }
            is ChallengeScene -> {
                activeChallenge = scene.challenge
                activeParams = scene.challenge.params
                mechanic = MechanicRegistry.get(scene.challenge.mechanic)
                    .initial(scene.challenge.params, simplified = false)
                phase = AdventurePhase.CHALLENGE_ACTIVE
                guideEmotion = GuideEmotion.CURIOUS
                guideLine = scene.challenge.prompt
            }
            is DialogueScene -> {
                guideEmotion = scene.guideEmotion
                guideLine = scene.prompt
            }
            is OffscreenScene -> {
                guideEmotion = GuideEmotion.CURIOUS
                guideLine = scene.guideLine ?: scene.prompt
            }
            is MoveScene -> {
                guideEmotion = GuideEmotion.EXCITED
                guideLine = scene.prompt
            }
            is ReflectionScene -> {
                guideEmotion = GuideEmotion.CALM
                guideLine = scene.text
            }
        }
        if (!isInit) maybeCompleteObjectives()
    }

    private fun completeAdventure() {
        phase = AdventurePhase.COMPLETED
        guideEmotion = GuideEmotion.CALM
        guideLine = "Приключение завершено. Вы молодцы!"
        adventure.objectives.forEach { completedObjectives.add(it.id) }
    }

    private fun maybeCompleteObjectives() {
        // Simple: mark objectives when matching variable flags set
        adventure.objectives.forEach { obj ->
            if (variables["obj:${obj.id}"] == 1) completedObjectives.add(obj.id)
        }
        if (variables["shipPower"] == 1) completedObjectives.add("launch")
        if (variables["navigated"] == 1) completedObjectives.add("navigate")
        if (variables["repaired"] == 1) completedObjectives.add("repair")
        if (variables["observed"] == 1) completedObjectives.add("observe")
    }

    companion object {
        fun groupPromptFor(scene: AdventureScene?): String? = when (scene?.groupMode) {
            GroupMode.DISCUSS_TOGETHER -> "Обсудите вместе и скажите ответ."
            GroupMode.TAKE_TURNS -> "Теперь пусть отвечает кто-нибудь другой."
            GroupMode.ALL_TOGETHER -> "Выберите всей командой."
            GroupMode.SOLO_OR_GROUP, null -> null
        }
    }
}
