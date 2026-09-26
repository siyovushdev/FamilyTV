package ru.familytv.knowledge

/**
 * Builds child-facing answers strictly from approved KnowledgeEntry fields.
 * May rephrase/connect provided facts — never invents new claims.
 */
object FactLockedComposer {
    enum class AgePresentation { YOUNG, MID, OLDER }

    fun presentationFor(ageMin: Int?, ageMax: Int?): AgePresentation {
        val max = ageMax ?: ageMin ?: 7
        return when {
            max <= 5 -> AgePresentation.YOUNG
            max <= 7 -> AgePresentation.MID
            else -> AgePresentation.OLDER
        }
    }

    fun compose(
        entry: KnowledgeEntry,
        intent: CuriosityIntent?,
        ageMin: Int?,
        ageMax: Int?,
        factIndex: Int = 0,
        tellMeMore: Boolean = false,
    ): Triple<String, String, String?> {
        if (tellMeMore) {
            val extras = entry.additionalFacts
            if (factIndex < 0 || factIndex >= extras.size) {
                return Triple(
                    KnowledgeGrounding.TELL_ME_MORE_EMPTY,
                    KnowledgeGrounding.TELL_ME_MORE_EMPTY,
                    null,
                )
            }
            val fact = extras[factIndex]
            val spoken = fact
            return Triple(fact, spoken, null)
        }

        val band = presentationFor(ageMin, ageMax)
        val primary = entry.facts.getOrNull(0) ?: entry.shortAnswer
        val display = when (band) {
            AgePresentation.YOUNG -> entry.shortAnswer.take(120).ifBlank { primary }
            AgePresentation.MID -> {
                val second = entry.facts.getOrNull(1)
                listOfNotNull(entry.shortAnswer, second).joinToString(" ").trim()
            }
            AgePresentation.OLDER -> {
                val bits = (listOf(entry.shortAnswer) + entry.facts.take(2)).distinct()
                bits.joinToString(" ").trim()
            }
        }
        val spoken = entry.spokenAnswer?.takeIf { it.isNotBlank() }
            ?: when (band) {
                AgePresentation.YOUNG -> entry.shortAnswer
                else -> display
            }
        // Safety: composed text must stay grounded
        if (!KnowledgeGrounding.isGroundedInEntry(display, entry) &&
            display != entry.shortAnswer &&
            !entry.facts.any { display.contains(it.take(20)) }
        ) {
            return Triple(entry.shortAnswer, spoken, entry.spokenAnswerAudio)
        }
        return Triple(display, spoken, entry.spokenAnswerAudio)
    }

    /**
     * Optional naturalization of template story recap — tokens must remain from approved pieces.
     * Returns null if candidate invents content.
     */
    fun naturalizeIfGrounded(candidate: String, approvedPieces: Collection<String>): String? {
        val approved = approvedPieces.joinToString(" ").lowercase()
        val tokens = candidate.lowercase().split(Regex("\\W+")).filter { it.length >= 4 }
        val content = tokens.filterNot {
            it in setOf("жил", "была", "отправился", "чтобы", "потом", "тогда", "вот", "чем", "всё", "все")
        }
        if (content.any { !approved.contains(it) && approvedPieces.none { p -> p.lowercase().contains(it) } }) {
            return null
        }
        return candidate
    }
}
