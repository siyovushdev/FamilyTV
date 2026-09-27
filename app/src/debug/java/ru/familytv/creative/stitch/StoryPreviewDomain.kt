package ru.familytv.creative.stitch

import org.json.JSONObject
import ru.familytv.creative.SavedStory
import ru.familytv.creative.StoryBuilderRuntime
import ru.familytv.creative.StoryGrammar
import ru.familytv.creative.StoryGrammarParser
import ru.familytv.creative.StoryState
import ru.familytv.creative.StoryStepKind

/**
 * Adapter from the preview's visual state to the production Story Builder domain.
 * Branching, retell and voice mapping all come from [StoryBuilderRuntime]; nothing is persisted here.
 */
class StoryPreviewDomain(json: String) {
    val grammar: StoryGrammar = StoryGrammarParser.parse(json)
    private val preview = JSONObject(json).getJSONObject("preview")
    private val authored: Map<StoryStepKind, Set<String>> = preview.getJSONObject("authored").let { o ->
        o.keys().asSequence().associate { k -> StoryStepKind.valueOf(k) to o.getJSONArray(k).let { a -> (0 until a.length()).map(a::getString).toSet() } }
    }
    private val endingTitles = preview.getJSONObject("endingTitles").let { o -> o.keys().asSequence().associateWith(o::getString) }
    private val consequences = preview.getJSONObject("consequence").let { o -> o.keys().asSequence().associateWith(o::getString) }

    fun prompt(kind: StoryStepKind): String = grammar.steps.first { it.kind == kind }.prompt

    /** Only options with real authored art and outcome are selectable; the rest stay honest "soon". */
    fun isAuthored(kind: StoryStepKind, id: String): Boolean {
        if (grammar.steps.first { it.kind == kind }.options.none { it.id == id }) return false
        return if (kind == StoryStepKind.CHOICE) id in grammar.choiceToEnding else id in authored[kind].orEmpty()
    }

    fun storyState(s: StorySnapshot): StoryState {
        var state = StoryBuilderRuntime.start(grammar, groupMode = true)
        for (id in listOf(s.hero?.id, s.world?.id, s.item?.id, s.action?.id)) {
            state = StoryBuilderRuntime.select(grammar, state, id ?: break)
        }
        return state
    }

    /** One retell sentence per story moment: hero, world, object, choice, ending. */
    fun lines(s: StorySnapshot): List<String> =
        StoryBuilderRuntime.retell(grammar, storyState(s)).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    fun endingId(s: StorySnapshot): String? = storyState(s).selections[StoryStepKind.ENDING.name]

    fun title(s: StorySnapshot): String {
        val state = storyState(s)
        return endingId(s)?.let(endingTitles::get) ?: StoryBuilderRuntime.titleFor(grammar, state)
    }

    fun consequence(action: StoryAction): String = consequences[action.id].orEmpty()

    /** What production would save; the preview only logs it and never touches StoryStore. */
    fun draft(s: StorySnapshot, now: Long): SavedStory? {
        val state = storyState(s)
        if (!state.completed) return null
        return SavedStory(id = "preview", title = title(s), createdAtMs = now, retellText = StoryBuilderRuntime.retell(grammar, state), selections = state.selections)
    }

    /** Voice-ready: a transcript maps through the production allowlist to the same focus key as the D-pad. */
    fun voiceTarget(screen: StoryScreen, transcript: String): String? {
        val kind = when (screen) {
            StoryScreen.HERO -> StoryStepKind.HERO
            StoryScreen.WORLD -> StoryStepKind.WORLD
            StoryScreen.OBJECT -> StoryStepKind.OBJECT
            StoryScreen.STORY_CHOICE -> StoryStepKind.CHOICE
            else -> return null
        }
        val id = StoryBuilderRuntime.mapVoice(grammar.steps.first { it.kind == kind }, transcript)?.first ?: return null
        return when (kind) {
            StoryStepKind.HERO -> StoryHero.entries.find { it.id == id }?.let { "hero:${it.name}" }
            StoryStepKind.WORLD -> StoryWorld.entries.find { it.id == id }?.let { "world:${it.name}" }
            StoryStepKind.OBJECT -> StoryObject.entries.find { it.id == id }?.let { "object:${it.name}" }
            else -> StoryAction.entries.find { it.id == id }?.let { "action:${it.name}" }
        }
    }
}
