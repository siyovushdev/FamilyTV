package ru.familytv.interactive

/** Data-driven interactive experience — WHAT happens, not Android HOW. */
data class InteractiveExperience(
    val id: String,
    val title: String,
    val schemaVersion: Int = 3,
    val steps: List<InteractiveStep>,
    val startStepId: String? = null,
)

sealed interface InteractiveStep {
    val id: String
    val transitions: StepTransitions
    val feedback: StepFeedback
}

data class StepTransitions(
    val next: String? = null,
    val onCorrect: String? = null,
    val onWrong: String? = null,
    val onUnknown: String? = null,
)

/** Feedback lives in experience data — not hardcoded in Activities. */
data class StepFeedback(
    val correct: String = "Молодец!",
    val wrong: String = "Попробуй ещё раз",
    val unknown: String = "Не расслышал. Попробуй ещё раз.",
    val hint: String? = null,
    val retryLimit: Int? = null,
    val correctAudio: String? = null,
    val wrongAudio: String? = null,
    val unknownAudio: String? = null,
)

data class VideoStep(
    override val id: String,
    val mediaRef: String,
    override val transitions: StepTransitions = StepTransitions(),
    override val feedback: StepFeedback = StepFeedback(),
) : InteractiveStep

data class MessageStep(
    override val id: String,
    val text: String,
    val continueLabel: String = "Дальше",
    val promptAudio: String? = null,
    override val transitions: StepTransitions = StepTransitions(),
    override val feedback: StepFeedback = StepFeedback(),
) : InteractiveStep

data class ChoiceOption(
    val id: String,
    val text: String,
)

data class ChoiceStep(
    override val id: String,
    val prompt: String,
    val options: List<ChoiceOption>,
    val correctOptionId: String,
    val promptAudio: String? = null,
    override val feedback: StepFeedback = StepFeedback(),
    override val transitions: StepTransitions = StepTransitions(),
) : InteractiveStep

data class TrueFalseStep(
    override val id: String,
    val prompt: String,
    val correct: Boolean,
    val promptAudio: String? = null,
    override val feedback: StepFeedback = StepFeedback(),
    override val transitions: StepTransitions = StepTransitions(),
) : InteractiveStep

data class MoveStep(
    override val id: String,
    val prompt: String,
    val durationSeconds: Int = 20,
    val continueMode: String = "button",
    val continueLabel: String = "Готово",
    val promptAudio: String? = null,
    override val transitions: StepTransitions = StepTransitions(),
    override val feedback: StepFeedback = StepFeedback(),
) : InteractiveStep

data class QuestionStep(
    override val id: String,
    val prompt: String,
    val questionType: QuestionType,
    val expected: String,
    val choices: List<String> = emptyList(),
    val visual: QuestionVisual? = null,
    val promptAudio: String? = null,
    override val feedback: StepFeedback = StepFeedback(),
    override val transitions: StepTransitions = StepTransitions(),
) : InteractiveStep

data class GameStep(
    override val id: String,
    val gameId: String,
    override val transitions: StepTransitions = StepTransitions(),
    override val feedback: StepFeedback = StepFeedback(),
) : InteractiveStep

data class BreakStep(
    override val id: String,
    val text: String,
    val durationMs: Long = 0,
    override val transitions: StepTransitions = StepTransitions(),
    override val feedback: StepFeedback = StepFeedback(),
) : InteractiveStep

enum class QuestionType {
    CHOICE,
    VOICE_NUMBER,
    VOICE_TEXT,
}

sealed interface QuestionVisual {
    data class Apples(val left: Int, val right: Int) : QuestionVisual
    data class ColorCircles(val target: String, val choices: List<String>) : QuestionVisual
    data class TextOnly(val text: String) : QuestionVisual
}

enum class InteractivePhase {
    STEP,
    PLAYING_VIDEO,
    LISTENING,
    PROCESSING,
    CORRECT,
    WRONG,
    NOT_UNDERSTOOD,
    COMPLETED,
    ERROR,
}

enum class InteractiveVerdict {
    CORRECT,
    WRONG,
    NOT_UNDERSTOOD,
}

data class ExperienceValidationResult(
    val ok: Boolean,
    val errors: List<String> = emptyList(),
)

object ExperienceValidator {
    fun validate(experience: InteractiveExperience): ExperienceValidationResult {
        val errors = mutableListOf<String>()
        if (experience.steps.isEmpty()) errors += "No steps"
        val ids = experience.steps.map { it.id }
        if (ids.size != ids.toSet().size) errors += "Duplicate step ids"
        val idSet = ids.toSet()
        val start = experience.startStepId ?: experience.steps.firstOrNull()?.id
        if (start != null && start !in idSet) errors += "startStepId '$start' missing"
        for (step in experience.steps) {
            listOf(
                step.transitions.next,
                step.transitions.onCorrect,
                step.transitions.onWrong,
                step.transitions.onUnknown,
            ).forEach { target ->
                if (target != null && target !in idSet && target != END) {
                    errors += "Step '${step.id}' references missing target '$target'"
                }
            }
            if (step is ChoiceStep) {
                if (step.options.isEmpty()) errors += "Choice '${step.id}' has no options"
                if (step.options.none { it.id == step.correctOptionId }) {
                    errors += "Choice '${step.id}' correctOptionId not in options"
                }
            }
        }
        return ExperienceValidationResult(errors.isEmpty(), errors)
    }

    const val END = "__end__"
}

/**
 * Step machine with explicit transitions + retry limits.
 * Expected answers stay here — never sent to Qwen.
 */
class InteractiveSession(
    val experience: InteractiveExperience,
    startStepId: String? = null,
) {
    private val byId = experience.steps.associateBy { it.id }
    private val wrongAttempts = mutableMapOf<String, Int>()
    var currentStepId: String = startStepId
        ?: experience.startStepId
        ?: experience.steps.first().id
        private set
    var phase: InteractivePhase = InteractivePhase.STEP
        private set
    val currentStep: InteractiveStep? get() = byId[currentStepId]
    val isCompleted: Boolean get() = phase == InteractivePhase.COMPLETED
    val lastHint: String? get() = currentStep?.feedback?.hint

    init {
        val validation = ExperienceValidator.validate(experience)
        require(validation.ok) { validation.errors.joinToString("; ") }
        require(currentStepId in byId) { "Unknown start step $currentStepId" }
    }

    fun answerVoice(normalized: String): InteractiveVerdict {
        val step = currentStep as? QuestionStep ?: error("Not a voice question")
        val verdict = InteractiveJudge.evaluateVoice(step, normalized)
        applyVerdict(verdict)
        return verdict
    }

    fun answerChoice(optionId: String): InteractiveVerdict {
        val step = currentStep as? ChoiceStep ?: error("Not a choice step")
        val verdict = when {
            optionId == step.correctOptionId -> InteractiveVerdict.CORRECT
            step.options.any { it.id == optionId } -> InteractiveVerdict.WRONG
            else -> InteractiveVerdict.NOT_UNDERSTOOD
        }
        applyVerdict(verdict)
        return verdict
    }

    fun answerTrueFalse(value: Boolean): InteractiveVerdict {
        val step = currentStep as? TrueFalseStep ?: error("Not a true/false step")
        val verdict = if (value == step.correct) InteractiveVerdict.CORRECT else InteractiveVerdict.WRONG
        applyVerdict(verdict)
        return verdict
    }

    fun finishFeedback() {
        val step = currentStep ?: return
        when (phase) {
            InteractivePhase.CORRECT -> {
                wrongAttempts.remove(currentStepId)
                go(step.transitions.onCorrect ?: step.transitions.next)
            }
            InteractivePhase.WRONG -> {
                val limit = step.feedback.retryLimit
                val attempts = wrongAttempts[currentStepId] ?: 0
                if (limit != null && attempts >= limit) {
                    // Soft advance after gentle retries — not punitive
                    go(step.transitions.onCorrect ?: step.transitions.next)
                } else {
                    go(step.transitions.onWrong ?: currentStepId)
                }
            }
            InteractivePhase.NOT_UNDERSTOOD -> go(step.transitions.onUnknown ?: currentStepId)
            else -> Unit
        }
        if (phase != InteractivePhase.COMPLETED) phase = InteractivePhase.STEP
    }

    fun advanceFromNonQuestion() {
        val step = currentStep
        require(step != null && step !is QuestionStep && step !is ChoiceStep && step !is TrueFalseStep) {
            "Use answer APIs for questions"
        }
        go(step.transitions.next)
        if (phase != InteractivePhase.COMPLETED) phase = InteractivePhase.STEP
    }

    fun onVideoCompleted() {
        val step = currentStep as? VideoStep ?: return
        go(step.transitions.next)
        if (phase != InteractivePhase.COMPLETED) phase = InteractivePhase.STEP
    }

    fun markPlayingVideo() { phase = InteractivePhase.PLAYING_VIDEO }
    fun markListening() { phase = InteractivePhase.LISTENING }
    fun markProcessing() { phase = InteractivePhase.PROCESSING }
    fun markError() { phase = InteractivePhase.ERROR }
    fun resetToStep() { phase = InteractivePhase.STEP }

    fun skipQuestionUsingCorrectTransition() {
        val step = currentStep
        val target = when (step) {
            is QuestionStep, is ChoiceStep, is TrueFalseStep ->
                step.transitions.onCorrect ?: step.transitions.next
            else -> null
        }
        go(target)
        if (phase != InteractivePhase.COMPLETED) phase = InteractivePhase.STEP
    }

    private fun applyVerdict(verdict: InteractiveVerdict) {
        phase = phaseFor(verdict)
        if (verdict == InteractiveVerdict.WRONG || verdict == InteractiveVerdict.NOT_UNDERSTOOD) {
            wrongAttempts[currentStepId] = (wrongAttempts[currentStepId] ?: 0) + 1
        }
    }

    private fun go(target: String?) {
        when {
            target == null || target == ExperienceValidator.END -> phase = InteractivePhase.COMPLETED
            target in byId -> {
                currentStepId = target
                phase = InteractivePhase.STEP
            }
            else -> error("Invalid transition target '$target'")
        }
    }

    private fun phaseFor(verdict: InteractiveVerdict) = when (verdict) {
        InteractiveVerdict.CORRECT -> InteractivePhase.CORRECT
        InteractiveVerdict.WRONG -> InteractivePhase.WRONG
        InteractiveVerdict.NOT_UNDERSTOOD -> InteractivePhase.NOT_UNDERSTOOD
    }
}

object InteractiveJudge {
    fun evaluateVoice(step: QuestionStep, normalized: String): InteractiveVerdict {
        val answer = normalized.trim().lowercase()
        if (answer.isEmpty()) return InteractiveVerdict.NOT_UNDERSTOOD
        val expected = step.expected.trim().lowercase()
        return when (step.questionType) {
            QuestionType.VOICE_NUMBER -> {
                val digit = Regex("(?:[0-9]|10)").find(answer)?.value
                when {
                    digit == null -> InteractiveVerdict.NOT_UNDERSTOOD
                    digit == expected -> InteractiveVerdict.CORRECT
                    else -> InteractiveVerdict.WRONG
                }
            }
            QuestionType.VOICE_TEXT, QuestionType.CHOICE -> when {
                answer == expected -> InteractiveVerdict.CORRECT
                step.choices.isNotEmpty() && step.choices.none { it.equals(answer, ignoreCase = true) } ->
                    InteractiveVerdict.NOT_UNDERSTOOD
                else -> InteractiveVerdict.WRONG
            }
        }
    }
}
