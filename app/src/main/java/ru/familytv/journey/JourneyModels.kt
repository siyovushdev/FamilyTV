package ru.familytv.journey

import ru.familytv.adventure.LearningGoal

/**
 * Data-defined Learning Journey — orchestrates existing content, does not replace runtimes.
 * Progress means experienced/discovered/created — never skill mastery.
 */
data class Journey(
    val id: String,
    val title: String,
    val world: String,
    val description: String = "",
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val estimatedMinutes: Int? = null,
    val learningGoals: List<LearningGoal> = emptyList(),
    val startNodeId: String,
    val nodes: List<JourneyNode>,
    val mapStyle: JourneyMapStyle = JourneyMapStyle.PLANETS,
    val completionLine: String = "Мы закончили экспедицию.",
    val version: Int = 1,
) {
    fun node(id: String): JourneyNode? = nodes.find { it.id == id }
    fun nodeIds(): Set<String> = nodes.map { it.id }.toSet()
}

enum class JourneyMapStyle {
    PLANETS,
    FOREST_TRAIL,
    LAB,
    ISLANDS,
}

enum class JourneyNodeType {
    ADVENTURE,
    DISCOVERY,
    CREATE,
    GAME,
    VIDEO,
    SESSION,
    REST,
    /** Choice / narration hub without its own content package. */
    HUB,
    CONCLUSION,
}

enum class CompletionPolicy {
    /** Whole content finished (adventure complete, story saved, video ended, etc.). */
    CONTENT_COMPLETED,
    /** Opening the content is enough (soft side activity). */
    CONTENT_OPENED,
    /** Manual / journey-engine mark (calm conclusion screens). */
    MANUAL,
}

data class JourneyBranch(
    val id: String,
    val label: String,
    val spokenLabel: String? = null,
    val nextNodeId: String,
)

data class AgeVariant(
    val ageMin: Int,
    val ageMax: Int,
    val contentId: String? = null,
    val labelHint: String? = null,
)

data class JourneyNode(
    val id: String,
    val title: String,
    val mapLabel: String = title,
    val spokenLabel: String? = null,
    val contentId: String?,
    val type: JourneyNodeType,
    val prerequisites: List<String> = emptyList(),
    /** Soft “any of” alternative to full prerequisites (branch OR). Empty = use [prerequisites]. */
    val prerequisitesAnyOf: List<String> = emptyList(),
    val next: String? = null,
    val branches: List<JourneyBranch> = emptyList(),
    val optional: Boolean = false,
    val journalUnlocks: List<String> = emptyList(),
    val completionPolicy: CompletionPolicy = CompletionPolicy.CONTENT_COMPLETED,
    val learningGoals: List<LearningGoal> = emptyList(),
    val ageVariants: List<AgeVariant> = emptyList(),
    /** Visual weight on map 0..1 along path. */
    val mapProgress: Float? = null,
)

/** Device/family scoped progress — not a child profile. */
data class JourneyProgress(
    val journeyId: String,
    val openNodeIds: Set<String>,
    val completedNodeIds: Set<String>,
    val lastVisitedNodeId: String?,
    val started: Boolean,
    val completed: Boolean,
    val journeyVersion: Int,
    val updatedAt: Long,
    val chosenBranches: Map<String, String> = emptyMap(),
)

enum class NodeAvailability {
    COMPLETED,
    AVAILABLE,
    FUTURE,
    MISSING_CONTENT,
}

data class JourneyNodeView(
    val node: JourneyNode,
    val availability: NodeAvailability,
    val resolvedContentId: String?,
)

enum class JournalEntryType {
    FACT_DISCOVERED,
    EXPERIMENT_OBSERVED,
    ADVENTURE_COMPLETED,
    STORY_CREATED,
    WORLD_VISITED,
    OPTIONAL_MOMENT,
    JOURNEY_SUMMARY,
}

data class JournalEntry(
    val id: String,
    val type: JournalEntryType,
    val title: String,
    val body: String,
    val spokenBody: String? = null,
    val world: String? = null,
    val journeyId: String? = null,
    val contentId: String? = null,
    val knowledgeId: String? = null,
    val experimentId: String? = null,
    val storyId: String? = null,
    val createdAtMs: Long,
    val authoredUnlockId: String? = null,
)

data class ExperimentObservation(
    val experimentId: String,
    val contentId: String?,
    val variants: List<String>,
    val observedOutcome: String,
    val spokenOutcome: String? = null,
    val journeyId: String? = null,
    val createdAtMs: Long,
)
