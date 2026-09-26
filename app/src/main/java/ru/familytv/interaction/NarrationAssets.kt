package ru.familytv.interaction

/**
 * Optional authored narration refs for a single presentation beat.
 * Prefer package audio; omit unused slots.
 */
data class NarrationAssets(
    val prompt: String? = null,
    val repeat: String? = null,
    val correct: String? = null,
    val wrong: String? = null,
    val hint1: String? = null,
    val hint2: String? = null,
)

/**
 * displayText is always shown; spokenText is what we try to play
 * (defaults to displayText when omitted).
 */
fun PresentationMessage.withAssets(assets: NarrationAssets?): PresentationMessage {
    if (assets == null) return this
    val ref = narrationRef ?: assets.prompt
    return copy(narrationRef = ref)
}
