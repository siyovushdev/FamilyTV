package ru.familytv.creative

/**
 * Authored story building blocks — Qwen may map free speech to ids, never invent plot.
 */
data class StoryOption(
    val id: String,
    val label: String,
    val voiceAliases: List<String> = emptyList(),
    val adjectives: List<String> = emptyList(),
    val spokenLabel: String? = null,
)

data class StoryStepDef(
    val id: String,
    val kind: StoryStepKind,
    val prompt: String,
    val spokenPrompt: String? = null,
    val options: List<StoryOption>,
    val groupHint: String? = null,
)

enum class StoryStepKind {
    HERO,
    WORLD,
    GOAL,
    OBJECT,
    OBSTACLE,
    CHOICE,
    ENDING,
}

data class StoryGrammar(
    val id: String,
    val title: String,
    val steps: List<StoryStepDef>,
    /** Template with {hero} {world} {goal} {object} {choice} {ending} placeholders. */
    val retellTemplate: String,
    /** Optional branch: choiceOptionId → endingOptionId override. */
    val choiceToEnding: Map<String, String> = emptyMap(),
)

data class StoryState(
    val grammarId: String,
    val stepIndex: Int = 0,
    val selections: Map<String, String> = emptyMap(), // stepKind → optionId
    val adjectives: Map<String, String> = emptyMap(),
    val completed: Boolean = false,
    val groupMode: Boolean = false,
)

data class SavedStory(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val retellText: String,
    val selections: Map<String, String>,
)

object SafeStoryModifiers {
    /** Curated adjectives only — unrestricted generated descriptions forbidden. */
    val ALLOWED = listOf(
        "маленький", "большая", "большой", "смешной", "смешная",
        "смелый", "смелая", "любопытный", "любопытная", "храбрый", "храбрая",
        "хитрая", "хитрый", "юный", "юная", "рыжая", "рыжий",
    )

    fun extract(normalized: String): String? {
        val n = normalized.lowercase()
        return ALLOWED.firstOrNull { a ->
            n.contains(a) || (a.length >= 5 && n.contains(a.take(5)))
        }
    }

    fun isAllowed(adj: String): Boolean {
        val a = adj.lowercase()
        return ALLOWED.any { it == a || (it.length >= 5 && a.startsWith(it.take(5))) }
    }
}

object StoryBuilderRuntime {
    fun start(grammar: StoryGrammar, groupMode: Boolean = false): StoryState =
        StoryState(grammarId = grammar.id, groupMode = groupMode)

    fun currentStep(grammar: StoryGrammar, state: StoryState): StoryStepDef? =
        grammar.steps.getOrNull(state.stepIndex)

    fun select(grammar: StoryGrammar, state: StoryState, optionId: String, adjective: String? = null): StoryState {
        val step = currentStep(grammar, state) ?: return state
        if (step.options.none { it.id == optionId }) return state
        val safeAdj = adjective?.takeIf { SafeStoryModifiers.isAllowed(it) }
        var nextSelections = state.selections + (step.kind.name to optionId)
        if (step.kind == StoryStepKind.CHOICE) {
            grammar.choiceToEnding[optionId]?.let { endId ->
                nextSelections = nextSelections + (StoryStepKind.ENDING.name to endId)
            }
        }
        val nextAdj = if (safeAdj != null) state.adjectives + (step.kind.name to safeAdj) else state.adjectives
        var nextIndex = state.stepIndex + 1
        while (nextIndex < grammar.steps.size) {
            val next = grammar.steps[nextIndex]
            if (next.kind == StoryStepKind.ENDING && nextSelections.containsKey(StoryStepKind.ENDING.name)) {
                nextIndex++
                continue
            }
            break
        }
        val done = nextIndex >= grammar.steps.size
        return state.copy(
            selections = nextSelections,
            adjectives = nextAdj,
            stepIndex = if (done) grammar.steps.lastIndex.coerceAtLeast(0) else nextIndex,
            completed = done,
        )
    }

    fun mapVoice(step: StoryStepDef, normalized: String): Pair<String, String?>? {
        val n = normalized.lowercase().trim()
        for (opt in step.options) {
            val hit = opt.label.lowercase() == n ||
                opt.voiceAliases.any { a -> n == a.lowercase() || n.contains(a.lowercase()) } ||
                n.contains(opt.label.lowercase())
            if (hit) {
                val adj = SafeStoryModifiers.extract(n)
                    ?: opt.adjectives.firstOrNull { a ->
                        val al = a.lowercase()
                        n.contains(al) || (al.length >= 4 && n.contains(al.take(5)))
                    }?.takeIf { SafeStoryModifiers.isAllowed(it) }
                return opt.id to adj
            }
        }
        return null
    }

    /**
     * Freeform natural selection — maps only to allowlisted option ids.
     * Never creates arbitrary entities.
     */
    fun mapFreeform(step: StoryStepDef, normalized: String): Pair<String, String?>? =
        mapVoice(step, normalized)

    fun retell(grammar: StoryGrammar, state: StoryState): String {
        fun label(kind: StoryStepKind): String {
            val id = state.selections[kind.name] ?: return "…"
            val step = grammar.steps.firstOrNull { it.kind == kind } ?: return id
            val opt = step.options.firstOrNull { it.id == id } ?: return id
            val adj = state.adjectives[kind.name]
            return if (adj != null && !opt.label.lowercase().contains(adj.lowercase().take(5))) {
                "$adj ${opt.label.lowercase()}"
            } else opt.label
        }
        val endingId = state.selections[StoryStepKind.CHOICE.name]?.let { grammar.choiceToEnding[it] }
            ?: state.selections[StoryStepKind.ENDING.name]
        val endingLabel = endingId?.let { eid ->
            grammar.steps.firstOrNull { it.kind == StoryStepKind.ENDING }
                ?.options?.firstOrNull { it.id == eid }?.label
        } ?: label(StoryStepKind.ENDING)

        return grammar.retellTemplate
            .replace("{hero}", label(StoryStepKind.HERO))
            .replace("{world}", label(StoryStepKind.WORLD))
            .replace("{goal}", label(StoryStepKind.GOAL))
            .replace("{object}", label(StoryStepKind.OBJECT))
            .replace("{obstacle}", label(StoryStepKind.OBSTACLE))
            .replace("{choice}", label(StoryStepKind.CHOICE))
            .replace("{ending}", endingLabel)
    }

    /** Recap must preserve selected option labels; rejects invented characters/events. */
    fun recapPreservesState(grammar: StoryGrammar, state: StoryState, candidateRecap: String): Boolean {
        val required = state.selections.mapNotNull { (kind, id) ->
            val stepKind = runCatching { StoryStepKind.valueOf(kind) }.getOrNull() ?: return@mapNotNull null
            grammar.steps.firstOrNull { it.kind == stepKind }
                ?.options?.firstOrNull { it.id == id }?.label
        }
        val c = candidateRecap.lowercase()
        return required.all { label ->
            val token = label.lowercase().split(' ').firstOrNull { it.length >= 4 } ?: label.lowercase().take(4)
            c.contains(token)
        }
    }

    fun titleFor(grammar: StoryGrammar, state: StoryState): String {
        val hero = state.selections[StoryStepKind.HERO.name]?.let { id ->
            grammar.steps.first { it.kind == StoryStepKind.HERO }.options.firstOrNull { it.id == id }?.label
        } ?: "герой"
        val world = state.selections[StoryStepKind.WORLD.name]?.let { id ->
            grammar.steps.first { it.kind == StoryStepKind.WORLD }.options.firstOrNull { it.id == id }?.label
        } ?: "мир"
        return "Наша история про $hero и $world"
    }

    fun endingDiffersByChoice(grammar: StoryGrammar): Boolean {
        val endings = grammar.choiceToEnding.values.toSet()
        return endings.size >= 2
    }
}
