package ru.familytv.knowledge

/**
 * Orchestrates Ask Lumi: intent → candidates → constrained match → fact-locked answer.
 * Supports short follow-ups, TellMeMore, WhatIf→experiment, clarification.
 */
class GroundedDialogueEngine(
    private val packs: List<KnowledgePack>,
    private val matcher: GroundedQwenMatcher = GroundedQwenMatcher(),
) {
    var context: DialogueContext = DialogueContext()
        private set

    fun bindSurface(adventureId: String?, world: String?, sceneId: String? = null) {
        if (context.adventureId != adventureId || context.world != world) {
            context = context.resetForAdventure(adventureId, world).withScene(sceneId)
        } else {
            context = context.withScene(sceneId)
        }
    }

    fun clearContext() {
        context = DialogueContext(adventureId = context.adventureId, world = context.world)
    }

    fun ask(query: KnowledgeQuery): KnowledgeResult {
        val transcript = query.transcript.lowercase().trim()
        if (transcript.isBlank()) {
            return KnowledgeResult.Unknown(KnowledgeGrounding.UNKNOWN_REPLY)
        }

        val intent = query.intent
            ?: CuriosityIntentDetector.detect(transcript, context)
            ?: if (CuriosityIntentDetector.isBareCuriosity(transcript)) {
                context.lastIntent ?: CuriosityIntent.WHY
            } else null

        // TELL_ME_MORE / «а ещё?»
        if (intent == CuriosityIntent.TELL_ME_MORE || CuriosityIntentDetector.isTellMeMore(transcript)) {
            return tellMeMore(query)
        }

        // WHAT_IF → prefer experiment when authored
        if (intent == CuriosityIntent.WHAT_IF || CuriosityIntentDetector.isWhatIf(transcript)) {
            val whatIf = resolveWhatIf(transcript, query)
            if (whatIf != null) return whatIf
        }

        val candidates = candidateEntries(query)
        if (candidates.isEmpty()) {
            return KnowledgeResult.Unknown(KnowledgeGrounding.UNKNOWN_REPLY)
        }

        val contract = matcher.match(transcript, intent, query, context, candidates)
        return when (contract) {
            MatchContract.Unknown -> {
                // Short follow-up with entity context → try last knowledge
                if (CuriosityIntentDetector.isBareCuriosity(transcript) && context.lastKnowledgeId != null) {
                    val entry = candidates.find { it.id == context.lastKnowledgeId }
                        ?: allEntries().find { it.id == context.lastKnowledgeId }
                    if (entry != null) return found(entry, intent ?: CuriosityIntent.WHY, query, ConfidenceClass.MEDIUM)
                }
                KnowledgeResult.Unknown(
                    softReply = KnowledgeGrounding.UNKNOWN_REPLY,
                    relatedContentIds = candidates.flatMap { it.relatedContentIds }.distinct().take(3),
                )
            }
            is MatchContract.Ambiguous -> {
                val opts = contract.knowledgeIds.mapNotNull { id -> candidates.find { it.id == id } }
                if (opts.size < 2) {
                    opts.firstOrNull()?.let { return found(it, intent, query, ConfidenceClass.MEDIUM) }
                    return KnowledgeResult.Unknown(KnowledgeGrounding.UNKNOWN_REPLY)
                }
                val labels = opts.map { it.clarificationLabel ?: it.aliases.firstOrNull() ?: it.topic }
                val prompt = "Ты спрашиваешь про ${labels.joinToString(" или ")}?"
                KnowledgeResult.Clarification(opts, prompt, prompt)
            }
            is MatchContract.Selected -> {
                val entry = candidates.find { it.id == contract.knowledgeId }
                    ?: return KnowledgeResult.Unknown(KnowledgeGrounding.UNKNOWN_REPLY)
                // WHAT_IF with experiment on selected entry
                if ((intent == CuriosityIntent.WHAT_IF || CuriosityIntentDetector.isWhatIf(transcript)) &&
                    !entry.experimentActionRef.isNullOrBlank()
                ) {
                    return askExperiment(entry)
                }
                found(entry, contract.intent ?: intent, query, contract.confidenceClass)
            }
        }
    }

    fun selectClarification(entryId: String, query: KnowledgeQuery): KnowledgeResult {
        val entry = allEntries().find { it.id == entryId }
            ?: return KnowledgeResult.Unknown(KnowledgeGrounding.UNKNOWN_REPLY)
        return found(entry, query.intent ?: CuriosityIntent.WHY, query, ConfidenceClass.HIGH)
    }

    private fun tellMeMore(query: KnowledgeQuery): KnowledgeResult {
        val id = context.lastKnowledgeId
            ?: return KnowledgeResult.Unknown(KnowledgeGrounding.TELL_ME_MORE_EMPTY)
        val entry = allEntries().find { it.id == id }
            ?: return KnowledgeResult.Unknown(KnowledgeGrounding.TELL_ME_MORE_EMPTY)
        val nextIndex = context.tellMeMoreIndex
        if (nextIndex >= entry.additionalFacts.size) {
            context = context.copy(lastIntent = CuriosityIntent.TELL_ME_MORE)
            return KnowledgeResult.Found(
                entry = entry,
                groundedAnswer = KnowledgeGrounding.TELL_ME_MORE_EMPTY,
                spokenAnswer = KnowledgeGrounding.TELL_ME_MORE_EMPTY,
                intent = CuriosityIntent.TELL_ME_MORE,
                factIndex = nextIndex,
            )
        }
        val (display, spoken, audio) = FactLockedComposer.compose(
            entry, CuriosityIntent.TELL_ME_MORE, query.ageMin, query.ageMax,
            factIndex = nextIndex, tellMeMore = true,
        )
        context = context.copy(
            tellMeMoreIndex = nextIndex + 1,
            lastIntent = CuriosityIntent.TELL_ME_MORE,
            lastKnowledgeId = entry.id,
        )
        return KnowledgeResult.Found(
            entry, display, spoken, audio, CuriosityIntent.TELL_ME_MORE,
            ConfidenceClass.HIGH, nextIndex,
        )
    }

    private fun resolveWhatIf(transcript: String, query: KnowledgeQuery): KnowledgeResult? {
        val candidates = candidateEntries(query)
        if (candidates.isEmpty()) return null
        val ranked = DeterministicGroundedMatcher().rank(transcript, query, context, candidates)
        val withAction = ranked.map { it.entry }.firstOrNull { !it.experimentActionRef.isNullOrBlank() }
            ?: candidates.firstOrNull { !it.experimentActionRef.isNullOrBlank() && (
                it.aliases.any { a -> transcript.contains(a.lowercase()) } ||
                    transcript.contains(it.topic.replace('_', ' '))
                )
            }
            ?: context.lastKnowledgeId?.let { lid ->
                allEntries().find { it.id == lid && !it.experimentActionRef.isNullOrBlank() }
            }
            // Weather-style: any what-if in a lab with a single experiment entry
            ?: candidates.singleOrNull { !it.experimentActionRef.isNullOrBlank() }
        if (withAction != null) return askExperiment(withAction)
        return null
    }

    private fun askExperiment(entry: KnowledgeEntry): KnowledgeResult {
        val ref = entry.experimentActionRef!!
        context = context.copy(
            lastKnowledgeId = entry.id,
            currentTopicId = entry.topic,
            currentEntityId = entry.entityId,
            lastIntent = CuriosityIntent.WHAT_IF,
            lastExperimentState = ref,
            tellMeMoreIndex = 0,
        )
        val invite = KnowledgeGrounding.TRY_EXPERIMENT
        return KnowledgeResult.AskToExperiment(entry, ref, invite, invite)
    }

    private fun found(
        entry: KnowledgeEntry,
        intent: CuriosityIntent?,
        query: KnowledgeQuery,
        confidence: ConfidenceClass,
    ): KnowledgeResult {
        val (display, spoken, audio) = FactLockedComposer.compose(
            entry, intent, query.ageMin, query.ageMax,
        )
        context = context.copy(
            lastKnowledgeId = entry.id,
            currentTopicId = entry.topic,
            currentEntityId = entry.entityId,
            lastIntent = intent,
            tellMeMoreIndex = 0,
        )
        return KnowledgeResult.Found(entry, display, spoken, audio, intent, confidence)
    }

    private fun candidateEntries(query: KnowledgeQuery): List<KnowledgeEntry> {
        val ordered = packs.sortedByDescending { pack ->
            var s = 0
            if (query.adventureId != null && pack.adventureId == query.adventureId) s += 100
            if (query.world != null && pack.world == query.world) s += 40
            if (context.adventureId != null && pack.adventureId == context.adventureId) s += 50
            s
        }
        return ordered.flatMap { it.entries }.filter { ageOk(it, query) }
    }

    private fun allEntries(): List<KnowledgeEntry> = packs.flatMap { it.entries }

    private fun ageOk(entry: KnowledgeEntry, query: KnowledgeQuery): Boolean {
        val qMax = query.ageMax ?: return true
        val eMin = entry.ageMin ?: 0
        val eMax = entry.ageMax ?: 99
        return qMax in (eMin - 1)..(eMax + 2) || (query.ageMin ?: qMax) in eMin..eMax
    }
}

object CuriosityIntentDetector {
    fun detect(normalized: String, context: DialogueContext = DialogueContext()): CuriosityIntent? {
        val n = normalized.lowercase().trim().trimEnd('?', '!', '.')
        return when {
            isTellMeMore(n) -> CuriosityIntent.TELL_ME_MORE
            isWhatIf(n) -> CuriosityIntent.WHAT_IF
            n.contains("я не понял") || n.contains("я не поняла") || n == "не понял" -> CuriosityIntent.HELP_UNDERSTAND
            n.startsWith("что такое") || n.contains("что это") || n.contains("что значит") -> CuriosityIntent.WHAT_IS
            n.startsWith("как ") || n.contains("как это") || n.contains("как работает") -> CuriosityIntent.HOW
            n.startsWith("почему") || n.contains("почему") || n.contains("зачем") ||
                n == "а почему" || n == "а это почему" || n == "а зачем" -> CuriosityIntent.WHY
            isBareCuriosity(n) && context.lastIntent != null -> context.lastIntent
            else -> null
        }
    }

    fun isTellMeMore(n: String): Boolean =
        n.contains("расскажи ещё") || n.contains("расскажи еще") || n.contains("подробнее") ||
            n == "а ещё" || n == "а еще" || n == "ещё" || n == "еще" ||
            n.contains("расскажи всё") || n.contains("расскажи все")

    fun isWhatIf(n: String): Boolean =
        n.contains("что будет если") || n.contains("а если") || n.contains("что если") ||
            n.contains("а наоборот") || n.contains("если наоборот") ||
            n.contains("если сделать") || n.contains("если убрать") || n.contains("если поставить")

    fun isBareCuriosity(n: String): Boolean {
        val bare = listOf(
            "почему", "а почему", "а это почему", "а зачем", "зачем",
            "а днём", "а днем", "а ночью", "как это", "что это",
            "а ещё", "а еще",
        )
        return bare.any { n == it || n == "$it?" }
    }

    fun looksLikeCuriosity(normalized: String, context: DialogueContext = DialogueContext()): Boolean =
        detect(normalized, context) != null || isBareCuriosity(normalized.lowercase().trim())
}
