package ru.familytv.adventure

/**
 * Data-defined Adventure — WHAT happens, not Android HOW.
 * Educational correctness lives in AdventureRuntime + mechanics, never in UI.
 */
data class Adventure(
    val id: String,
    val title: String,
    val world: String,
    val description: String = "",
    val learningGoals: List<LearningGoal> = emptyList(),
    val estimatedMinutes: Int? = null,
    val difficultyMin: Int = 1,
    val difficultyMax: Int = 3,
    val startSceneId: String,
    val variables: Map<String, Int> = emptyMap(),
    val objectives: List<AdventureObjective> = emptyList(),
    val guide: GuideCharacterDef = GuideCharacterDef(),
    val scenes: List<AdventureScene>,
    val schemaVersion: Int = 1,
)

data class AdventureObjective(
    val id: String,
    val title: String,
    val description: String = "",
)

data class GuideCharacterDef(
    val id: String = "lumi",
    val name: String = "Луми",
    val defaultEmotion: GuideEmotion = GuideEmotion.CURIOUS,
)

enum class GuideEmotion {
    CALM,
    HAPPY,
    CURIOUS,
    THINKING,
    ENCOURAGING,
    SURPRISED,
    CELEBRATING,
    LISTENING,
    HELPING,
    REST,
    /** @deprecated Prefer HAPPY / CELEBRATING — kept for existing JSON. */
    EXCITED,
}

enum class LearningGoal {
    COUNTING,
    ADDITION,
    CLASSIFICATION,
    PATTERN_RECOGNITION,
    SPATIAL_REASONING,
    SEQUENCING,
    SCIENCE_REASONING,
    LANGUAGE,
    MEMORY,
    COOPERATION,
}

enum class GroupMode {
    SOLO_OR_GROUP,
    DISCUSS_TOGETHER,
    TAKE_TURNS,
    ALL_TOGETHER,
}

enum class SceneKind {
    STORY,
    CHALLENGE,
    DIALOGUE,
    OFFSCREEN,
    MOVE,
    REFLECTION,
}

sealed interface AdventureScene {
    val id: String
    val kind: SceneKind
    val next: String?
    val narrationAudio: String?
    val groupMode: GroupMode
    val background: String?
}

data class StoryScene(
    override val id: String,
    val text: String,
    val continueLabel: String = "Дальше",
    override val next: String? = null,
    override val narrationAudio: String? = null,
    override val groupMode: GroupMode = GroupMode.SOLO_OR_GROUP,
    override val background: String? = null,
    val guideLine: String? = null,
    val guideEmotion: GuideEmotion = GuideEmotion.CURIOUS,
    /** Optional shorter spoken variant; display [text] stays visible. */
    val spokenPrompt: String? = null,
) : AdventureScene {
    override val kind = SceneKind.STORY
}

data class ChallengeScene(
    override val id: String,
    val challenge: ChallengeDef,
    override val next: String? = null,
    override val narrationAudio: String? = null,
    override val groupMode: GroupMode = GroupMode.SOLO_OR_GROUP,
    override val background: String? = null,
) : AdventureScene {
    override val kind = SceneKind.CHALLENGE
}

data class DialogueScene(
    override val id: String,
    val prompt: String,
    /** Authored semantic buckets: id → accepted normalized phrases + reply. */
    val accepted: List<DialogueAccept> = emptyList(),
    val unknownReply: String = "Расскажи ещё раз своими словами.",
    val onMappedNext: String? = null,
    override val next: String? = null,
    override val narrationAudio: String? = null,
    override val groupMode: GroupMode = GroupMode.DISCUSS_TOGETHER,
    override val background: String? = null,
    val guideEmotion: GuideEmotion = GuideEmotion.THINKING,
) : AdventureScene {
    override val kind = SceneKind.DIALOGUE
}

data class DialogueAccept(
    val id: String,
    val phrases: List<String>,
    val reply: String,
    val learningNote: String? = null,
)

data class OffscreenScene(
    override val id: String,
    val prompt: String,
    val continueLabel: String = "Готово",
    val voiceConfirmPhrases: List<String> = listOf("готово", "сделали", "нашли", "да"),
    override val next: String? = null,
    override val narrationAudio: String? = null,
    override val groupMode: GroupMode = GroupMode.ALL_TOGETHER,
    override val background: String? = null,
    val guideLine: String? = null,
) : AdventureScene {
    override val kind = SceneKind.OFFSCREEN
}

data class MoveScene(
    override val id: String,
    val prompt: String,
    val durationSeconds: Int = 20,
    val continueLabel: String = "Готово",
    override val next: String? = null,
    override val narrationAudio: String? = null,
    override val groupMode: GroupMode = GroupMode.ALL_TOGETHER,
    override val background: String? = null,
) : AdventureScene {
    override val kind = SceneKind.MOVE
}

data class ReflectionScene(
    override val id: String,
    val text: String,
    val continueLabel: String = "К приключениям",
    override val next: String? = null,
    override val narrationAudio: String? = null,
    override val groupMode: GroupMode = GroupMode.SOLO_OR_GROUP,
    override val background: String? = null,
) : AdventureScene {
    override val kind = SceneKind.REFLECTION
}

data class ChallengeDef(
    val id: String,
    val mechanic: MechanicType,
    val prompt: String,
    val learningGoals: List<LearningGoal> = emptyList(),
    val scaffolding: ScaffoldingDef = ScaffoldingDef(),
    val params: MechanicParams,
    val voiceAccept: List<String> = emptyList(),
    val successSets: Map<String, Int> = emptyMap(),
    val successNext: String? = null,
    val promptAudio: String? = null,
    /** Optional short spoken prompt; [prompt] remains on screen. */
    val spokenPrompt: String? = null,
)

enum class MechanicType {
    VISUAL_SEARCH,
    SORT_AND_CLASSIFY,
    PATTERN_SEQUENCE,
    PATH_MAZE,
    /** Discovery: predict outcome, then observe — no penalty for wrong hypothesis. */
    PREDICT_AND_TEST,
    /** Discovery: assemble parts, then simulate — failure shows why, not just WRONG. */
    BUILD_AND_TEST,
}

data class ScaffoldingDef(
    val hint1: String? = null,
    val hint2: String? = null,
    val visualHint: Boolean = false,
    val explanation: String? = null,
    /** When non-null, after enough wrongs the runtime swaps to this simpler param set. */
    val simplifiedParams: MechanicParams? = null,
)

sealed interface MechanicParams

data class VisualSearchParams(
    val objects: List<SearchObject>,
    val requiredIds: Set<String>,
    val minSelect: Int? = null,
) : MechanicParams

data class SearchObject(
    val id: String,
    val label: String,
    val voiceAliases: List<String> = emptyList(),
    val category: String? = null,
    val highlightable: Boolean = true,
)

data class SortClassifyParams(
    val categories: List<SortCategory>,
    val items: List<SortItem>,
) : MechanicParams

data class SortCategory(
    val id: String,
    val label: String,
)

data class SortItem(
    val id: String,
    val label: String,
    val correctCategoryId: String,
    val voiceAliases: List<String> = emptyList(),
)

data class PatternSequenceParams(
    val display: List<String>,
    val blankIndex: Int,
    val options: List<String>,
    val correctOption: String,
    val voiceAccept: List<String> = emptyList(),
) : MechanicParams

data class PathMazeParams(
    val width: Int,
    val height: Int,
    val start: GridPos,
    val goal: GridPos,
    val blocked: Set<GridPos> = emptySet(),
    val requiredVisit: List<GridPos> = emptyList(),
    /** Optional cell labels for educational constraints (e.g. even numbers). */
    val cellLabels: Map<GridPos, String> = emptyMap(),
    val allowedLabelRule: PathLabelRule = PathLabelRule.ANY,
) : MechanicParams

data class GridPos(val x: Int, val y: Int) {
    override fun toString() = "$x,$y"
    companion object {
        fun parse(raw: String): GridPos {
            val parts = raw.split(",")
            return GridPos(parts[0].trim().toInt(), parts[1].trim().toInt())
        }
    }
}

/** Prediction option for [PredictAndTestParams]. */
data class PredictOption(
    val id: String,
    val label: String,
    val voiceAliases: List<String> = emptyList(),
)

/**
 * Discovery predict→test. [outcomeByOptionId] maps prediction id → observation text.
 * [correctPredictionId] is optional soft truth for discussion only — never scored.
 */
data class PredictAndTestParams(
    val options: List<PredictOption>,
    val outcomeByOptionId: Map<String, String>,
    val testPhrases: List<String> = listOf("проверить", "проверим", "тест"),
    val correctPredictionId: String? = null,
    val knowledgeTopic: String? = null,
) : MechanicParams

data class BuildPart(
    val id: String,
    val label: String,
    val voiceAliases: List<String> = emptyList(),
    val slotId: String,
)

data class BuildSlot(
    val id: String,
    val label: String,
)

/**
 * Assemble parts into slots, then test. [successPlacements] itemId→slotId required for success.
 * [failObservation] explains why the build did not work.
 */
data class BuildAndTestParams(
    val slots: List<BuildSlot>,
    val parts: List<BuildPart>,
    val successPlacements: Map<String, String>,
    val successObservation: String,
    val failObservation: String,
    val testPhrases: List<String> = listOf("проверить", "проверим", "тест"),
) : MechanicParams

enum class PathLabelRule {
    ANY,
    EVEN_NUMBERS,
    ODD_NUMBERS,
}
