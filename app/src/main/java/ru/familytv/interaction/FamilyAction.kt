package ru.familytv.interaction

/** Shared child-facing actions — remote and voice must map to the same set. */
sealed class FamilyAction {
    data object Home : FamilyAction()
    data object Back : FamilyAction()
    data object Repeat : FamilyAction()
    data object Help : FamilyAction()
    data object Pause : FamilyAction()
    data object Resume : FamilyAction()
    data object Next : FamilyAction()
    data object Select : FamilyAction()
    data object ContinueCurrent : FamilyAction()
    data class OpenWorld(val worldId: String) : FamilyAction()
    data class OpenContent(val contentId: String) : FamilyAction()
    data class OpenGame(val route: String) : FamilyAction()
    data class StartAdventure(val contentId: String) : FamilyAction()
    data class Answer(val transcript: String) : FamilyAction()
    /** Controlled curiosity — resolved via approved KnowledgeEntry only. */
    data class AskLumi(
        val intent: ru.familytv.knowledge.CuriosityIntent,
        val transcript: String,
    ) : FamilyAction()
    /** Journey map — resume / branch / explore nodes. */
    data class OpenJourneyMap(val journeyId: String? = null) : FamilyAction()
    data object OpenJournal : FamilyAction()
    data class ContinueJourney(val journeyId: String? = null) : FamilyAction()
    /** Structured journal recap — not Qwen memory. */
    data class AskWhatWeDiscovered(val worldHint: String? = null) : FamilyAction()
    /** Opens Parent Mode entry only — never changes PIN/settings via voice. */
    data object OpenParentGate : FamilyAction()
    data object BlockedSensitive : FamilyAction()
}

enum class InputSource {
    REMOTE,
    VOICE,
    /** Reserved — not implemented in 6.1. */
    VISION,
    GESTURE,
}
