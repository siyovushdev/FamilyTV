package ru.familytv.interaction

/** Semantic role of a child-facing presentation. */
enum class PresentationRole {
    INSTRUCTION,
    QUESTION,
    HINT,
    FEEDBACK,
    DIALOGUE,
    MOVE,
    NAVIGATION,
    GROUP,
}

enum class PresentationImportance {
    CRITICAL,
    IMPORTANT,
    SECONDARY,
}

/**
 * Single contract for child-facing instruction: audio + text together.
 * [text] is displayText; [spokenText] may be a shorter spoken variant when authors set it.
 */
data class PresentationMessage(
    /** Visible on screen (displayText). */
    val text: String,
    val spokenText: String? = null,
    val narrationRef: String? = null,
    val role: PresentationRole = PresentationRole.INSTRUCTION,
    val importance: PresentationImportance = PresentationImportance.IMPORTANT,
    val repeatable: Boolean = true,
    val assets: NarrationAssets? = null,
) {
    fun displayText(): String = text
    fun effectiveSpoken(): String = spokenText?.takeIf { it.isNotBlank() } ?: text
    fun effectiveNarrationRef(): String? = narrationRef ?: assets?.prompt
}
