package ru.familytv.knowledge

/**
 * Package-defined approved knowledge — the only source of educational facts for Lumi.
 * Qwen may match intent / rephrase; it must never invent facts beyond [facts]/[additionalFacts].
 */
data class KnowledgeProvenance(
    val sourceTitle: String? = null,
    val sourceOrganization: String? = null,
    val sourceReference: String? = null,
    val reviewStatus: String = "draft", // draft | reviewed | approved
    val lastReviewed: String? = null,
)

data class KnowledgeEntry(
    val id: String,
    val topic: String,
    /** Soft entity for short follow-ups (“а почему она?”). */
    val entityId: String? = null,
    val aliases: List<String> = emptyList(),
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val questionPatterns: List<String> = emptyList(),
    /** Primary approved facts (index 0 = primary explanation). */
    val facts: List<String> = emptyList(),
    /** Extra approved facts for TELL_ME_MORE (never invent beyond this). */
    val additionalFacts: List<String> = emptyList(),
    val shortAnswer: String,
    val spokenAnswer: String? = null,
    /** Optional pre-recorded asset ref under catalog narration. */
    val spokenAnswerAudio: String? = null,
    val followUps: List<String> = emptyList(),
    val relatedContentIds: List<String> = emptyList(),
    /** Authored experiment/challenge id for Ask→Do (WHAT_IF). */
    val experimentActionRef: String? = null,
    val clarificationLabel: String? = null,
    val provenance: KnowledgeProvenance = KnowledgeProvenance(),
    /** @deprecated use [provenance]; kept for older pack strings */
    val sourceMetadata: String? = null,
)

data class KnowledgePack(
    val id: String,
    val world: String? = null,
    val adventureId: String? = null,
    val entries: List<KnowledgeEntry>,
)

enum class CuriosityIntent {
    WHY,
    HOW,
    WHAT_IS,
    WHAT_IF,
    TELL_ME_MORE,
    HELP_UNDERSTAND,
}

enum class ConfidenceClass {
    HIGH,
    MEDIUM,
    LOW,
    AMBIGUOUS,
}

data class KnowledgeQuery(
    val transcript: String,
    val intent: CuriosityIntent? = null,
    val sceneId: String? = null,
    val adventureId: String? = null,
    val world: String? = null,
    val topicHints: List<String> = emptyList(),
    /** Soft household age upper bound for presentation (not a child profile). */
    val ageMax: Int? = null,
    val ageMin: Int? = null,
)

sealed class KnowledgeResult {
    data class Found(
        val entry: KnowledgeEntry,
        val groundedAnswer: String,
        val spokenAnswer: String,
        val spokenAnswerAudio: String? = null,
        val intent: CuriosityIntent? = null,
        val confidence: ConfidenceClass = ConfidenceClass.HIGH,
        val factIndex: Int = 0,
    ) : KnowledgeResult()

    data class Clarification(
        val options: List<KnowledgeEntry>,
        val prompt: String,
        val spokenPrompt: String,
    ) : KnowledgeResult()

    data class AskToExperiment(
        val entry: KnowledgeEntry?,
        val experimentActionRef: String,
        val invite: String,
        val spokenInvite: String,
    ) : KnowledgeResult()

    data class Unknown(
        val softReply: String,
        val relatedContentIds: List<String> = emptyList(),
    ) : KnowledgeResult()
}

/** Structured dialogue memory — not free chat history. */
data class DialogueContext(
    val currentTopicId: String? = null,
    val currentEntityId: String? = null,
    val lastKnowledgeId: String? = null,
    val lastIntent: CuriosityIntent? = null,
    val lastExperimentState: String? = null,
    val lastStoryChoice: String? = null,
    val tellMeMoreIndex: Int = 0,
    val adventureId: String? = null,
    val world: String? = null,
    val sceneId: String? = null,
) {
    fun resetForAdventure(adventureId: String?, world: String?): DialogueContext =
        DialogueContext(adventureId = adventureId, world = world)

    fun withScene(sceneId: String?): DialogueContext = copy(sceneId = sceneId)
}

object KnowledgeGrounding {
    const val UNKNOWN_REPLY =
        "Хороший вопрос. Здесь у меня пока нет точного ответа."
    const val UNKNOWN_REPLY_ALT =
        "Про это я пока не знаю. Давай найдём это в другом занятии."
    const val TELL_ME_MORE_EMPTY =
        "Пока это всё, что я могу рассказать здесь."
    const val CLARIFY_PROMPT =
        "Уточни, пожалуйста: о чём ты спрашиваешь?"
    const val TRY_EXPERIMENT =
        "Давай проверим!"

    fun allApprovedText(entry: KnowledgeEntry): String =
        buildList {
            add(entry.shortAnswer)
            addAll(entry.facts)
            addAll(entry.additionalFacts)
            entry.spokenAnswer?.let { add(it) }
        }.joinToString(" ")

    /** Reject any model-proposed sentence that invents unapproved content. */
    fun isGroundedInEntry(candidate: String, entry: KnowledgeEntry): Boolean {
        val c = candidate.lowercase().trim()
        if (c.isBlank()) return false
        val approved = allApprovedText(entry).lowercase()
        if (entry.shortAnswer.lowercase().let { it.isNotBlank() && (c.contains(it.take(12)) || it.contains(c.take(12))) }) {
            return true
        }
        val tokens = c.split(Regex("\\W+")).filter { it.length >= 4 }
        if (tokens.isEmpty()) return approved.contains(c)
        // Every significant content token must appear in approved text (strict)
        val content = tokens.filterNot { it in FILLER }
        if (content.isEmpty()) return true
        return content.all { approved.contains(it) }
    }

    /** Stricter: candidate must not introduce novel multi-word claims. */
    fun containsUnapprovedClaim(candidate: String, entry: KnowledgeEntry): Boolean {
        val c = candidate.lowercase().trim()
        val approved = allApprovedText(entry).lowercase()
        val novel = c.split(Regex("[.!?]+")).map { it.trim() }.filter { it.length > 12 }
        return novel.any { sentence ->
            val words = sentence.split(Regex("\\W+")).filter { it.length >= 5 && it !in FILLER }
            words.isNotEmpty() && words.none { approved.contains(it) }
        }
    }

    private val FILLER = setOf(
        "потому", "поэтому", "когда", "если", "тоже", "ещё", "еще", "очень",
        "можно", "нужно", "давай", "сейчас", "здесь", "потом", "также",
    )
}
