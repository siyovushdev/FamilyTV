package ru.familytv.creative.stitch

import ru.familytv.creative.StoryStepKind
import ru.familytv.interaction.FamilyAction

enum class StoryScreen { CREATE_HOME, HERO, WORLD, OBJECT, STORY_CHOICE, CONSEQUENCE, ENDING, REPLAY }

/** Left-to-right order is the D-pad order in the approved scene. */
enum class HomePlace(val label: String, val available: Boolean) { THEATER("Театр", false), BOOK("Придумать историю", true), DRAWING("Рисунки", false) }
enum class StoryHero(val id: String, val label: String) { TIM("tim", "Тим"), ROBI("robi", "Роби"), PUSHISTIK("pushistik", "Пушистик") }
enum class StoryWorld(val id: String, val label: String) { SPACE("space", "Звёздная станция"), FOREST("forest", "Волшебный лес"), ISLAND("island", "Таинственный остров") }
enum class StoryObject(val id: String, val label: String) { KEY("key", "Золотой ключ"), MAP("map", "Светящаяся карта"), LANTERN("lantern", "Звёздный фонарик") }
enum class StoryAction(val id: String, val label: String) { DOOR("door", "Открыть дверь"), PATH("path", "Пойти по тропинке") }
enum class LumiMood { CURIOSITY, SELECTION, DISCOVERY, WARM }
enum class Direction { LEFT, RIGHT, UP, DOWN }

object StoryRules {
    const val KEY_REVEAL_MS = 2600L
    const val KEY_TURN_MS = 900L
    const val DOOR_OPEN_END_MS = 2200L
    const val ROOM_FADE_END_MS = 3000L
    const val NARRATE_AT_MS = 3000L
    const val CONTINUE_AT_MS = 3600L
    const val SELECTION_MOOD_MS = 900L
    const val REPLAY_BEAT_MS = 3400L
    const val REPLAY_BEATS = 5
}

data class StorySnapshot(
    val screen: StoryScreen = StoryScreen.CREATE_HOME,
    val focus: String = "place:BOOK",
    val hero: StoryHero? = null,
    val world: StoryWorld? = null,
    val item: StoryObject? = null,
    val action: StoryAction? = null,
    val homeFocus: HomePlace = HomePlace.BOOK,
    val heroFocus: StoryHero = StoryHero.ROBI,
    val worldFocus: StoryWorld = StoryWorld.FOREST,
    val itemFocus: StoryObject = StoryObject.KEY,
    val actionFocus: StoryAction = StoryAction.DOOR,
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    /** Focus key of a not-yet-authored target the family just picked; drives an honest "soon" line. */
    val soon: String? = null,
    val saved: Boolean = false,
) {
    /** The chosen object is travelling to the hero; the scene owns input until it lands. */
    val revealing get() = screen == StoryScreen.OBJECT && item != null
    val canContinue get() = screen == StoryScreen.CONSEQUENCE && elapsedMs >= StoryRules.CONTINUE_AT_MS
    val replayBeat get() = if (screen == StoryScreen.REPLAY) (elapsedMs / StoryRules.REPLAY_BEAT_MS).toInt().coerceAtMost(StoryRules.REPLAY_BEATS - 1) else 0
    val mood get() = when {
        soon != null -> LumiMood.CURIOSITY
        revealing -> LumiMood.SELECTION
        screen == StoryScreen.CONSEQUENCE -> LumiMood.DISCOVERY
        screen == StoryScreen.ENDING || screen == StoryScreen.REPLAY -> LumiMood.WARM
        screen in listOf(StoryScreen.WORLD, StoryScreen.OBJECT, StoryScreen.STORY_CHOICE) && elapsedMs < StoryRules.SELECTION_MOOD_MS -> LumiMood.SELECTION
        else -> LumiMood.CURIOSITY
    }

    fun encode(): Map<String, String> = buildMap {
        put("screen", screen.name); put("focus", focus)
        hero?.let { put("hero", it.name) }; world?.let { put("world", it.name) }
        item?.let { put("item", it.name) }; action?.let { put("action", it.name) }
        put("homeFocus", homeFocus.name); put("heroFocus", heroFocus.name); put("worldFocus", worldFocus.name)
        put("itemFocus", itemFocus.name); put("actionFocus", actionFocus.name)
        put("elapsed", elapsedMs.toString()); put("paused", paused.toString()); put("saved", saved.toString())
        soon?.let { put("soon", it) }
    }

    companion object {
        fun decode(m: Map<String, String?>): StorySnapshot {
            fun <T : Enum<T>> read(key: String, values: List<T>): T? = values.find { it.name == m[key] }
            val d = StorySnapshot()
            return StorySnapshot(
                screen = read("screen", StoryScreen.entries) ?: d.screen, focus = m["focus"] ?: d.focus,
                hero = read("hero", StoryHero.entries), world = read("world", StoryWorld.entries),
                item = read("item", StoryObject.entries), action = read("action", StoryAction.entries),
                homeFocus = read("homeFocus", HomePlace.entries) ?: d.homeFocus, heroFocus = read("heroFocus", StoryHero.entries) ?: d.heroFocus,
                worldFocus = read("worldFocus", StoryWorld.entries) ?: d.worldFocus, itemFocus = read("itemFocus", StoryObject.entries) ?: d.itemFocus,
                actionFocus = read("actionFocus", StoryAction.entries) ?: d.actionFocus,
                elapsedMs = m["elapsed"]?.toLongOrNull() ?: 0, paused = m["paused"] == "true", saved = m["saved"] == "true", soon = m["soon"],
            )
        }
    }
}

/** Create / Story Builder V1 preview state. Pure Kotlin: semantics never depend on View state. */
class StoryPreviewState(private val domain: StoryPreviewDomain, initial: StorySnapshot = StorySnapshot()) {
    var value = initial
        private set
    /** Back from the workshop itself leaves the preview. */
    var exitRequested = false
        private set

    init { if (value.focus !in controls()) value = value.copy(focus = defaultFocus()) }

    fun controls(): List<String> = listOf("back") + when (value.screen) {
        StoryScreen.CREATE_HOME -> HomePlace.entries.map { "place:${it.name}" }
        StoryScreen.HERO -> StoryHero.entries.map { "hero:${it.name}" }
        StoryScreen.WORLD -> StoryWorld.entries.map { "world:${it.name}" }
        StoryScreen.OBJECT -> StoryObject.entries.map { "object:${it.name}" }
        StoryScreen.STORY_CHOICE -> StoryAction.entries.map { "action:${it.name}" }
        StoryScreen.CONSEQUENCE -> listOf(if (value.canContinue) "inside" else "scene")
        StoryScreen.ENDING -> listOf("replay", "save", "new")
        StoryScreen.REPLAY -> listOf("scene")
    }

    fun defaultFocus(): String = when (value.screen) {
        StoryScreen.CREATE_HOME -> "place:${value.homeFocus.name}"
        StoryScreen.HERO -> "hero:${value.heroFocus.name}"
        StoryScreen.WORLD -> "world:${value.worldFocus.name}"
        StoryScreen.OBJECT -> "object:${value.itemFocus.name}"
        StoryScreen.STORY_CHOICE -> "action:${value.actionFocus.name}"
        StoryScreen.CONSEQUENCE -> if (value.canContinue) "inside" else "scene"
        StoryScreen.ENDING -> "replay"
        StoryScreen.REPLAY -> "scene"
    }

    fun isAvailable(key: String): Boolean {
        val name = key.substringAfter(':')
        return when {
            key.startsWith("place:") -> HomePlace.valueOf(name).available
            key.startsWith("hero:") -> domain.isAuthored(StoryStepKind.HERO, StoryHero.valueOf(name).id)
            key.startsWith("world:") -> domain.isAuthored(StoryStepKind.WORLD, StoryWorld.valueOf(name).id)
            key.startsWith("object:") -> domain.isAuthored(StoryStepKind.OBJECT, StoryObject.valueOf(name).id)
            key.startsWith("action:") -> domain.isAuthored(StoryStepKind.CHOICE, StoryAction.valueOf(name).id)
            else -> true
        }
    }

    fun prompt(): String {
        val s = value
        s.soon?.let { return soonLine(it) }
        return when (s.screen) {
            StoryScreen.CREATE_HOME -> "Давай придумаем историю!"
            StoryScreen.HERO -> domain.prompt(StoryStepKind.HERO)
            StoryScreen.WORLD -> domain.prompt(StoryStepKind.WORLD)
            StoryScreen.OBJECT -> if (s.item == null) domain.prompt(StoryStepKind.OBJECT) else domain.lines(s)[2]
            StoryScreen.STORY_CHOICE -> domain.prompt(StoryStepKind.CHOICE)
            // The world changes first; Lumi speaks only once the door is open.
            StoryScreen.CONSEQUENCE -> if (s.elapsedMs < StoryRules.NARRATE_AT_MS) "" else domain.consequence(requireNotNull(s.action))
            StoryScreen.ENDING -> if (s.saved) "Эта история теперь в наших историях." else "Вот какую историю мы придумали!"
            StoryScreen.REPLAY -> domain.lines(s)[s.replayBeat]
        }
    }

    private fun soonLine(key: String): String {
        val name = key.substringAfter(':')
        return when {
            key == "place:THEATER" -> "Театр скоро откроется."
            key == "place:DRAWING" -> "Рисунки скоро появятся."
            key.startsWith("hero:") -> "${StoryHero.valueOf(name).label} пока готовится к своей сказке."
            key.startsWith("world:") -> "${StoryWorld.valueOf(name).label} скоро откроется."
            key == "object:MAP" -> "Карта пригодится в другой сказке."
            key == "object:LANTERN" -> "Фонарик пригодится в другой сказке."
            else -> "Тропинка откроется в другой сказке."
        }
    }

    fun focus(key: String) {
        if (key !in controls() || value.revealing) return
        val name = key.substringAfter(':')
        var s = value.copy(focus = key, soon = value.soon.takeIf { it == key })
        when {
            key.startsWith("place:") -> s = s.copy(homeFocus = HomePlace.valueOf(name))
            key.startsWith("hero:") -> s = s.copy(heroFocus = StoryHero.valueOf(name))
            key.startsWith("world:") -> s = s.copy(worldFocus = StoryWorld.valueOf(name))
            key.startsWith("object:") -> s = s.copy(itemFocus = StoryObject.valueOf(name))
            key.startsWith("action:") -> s = s.copy(actionFocus = StoryAction.valueOf(name))
        }
        value = s
    }

    fun move(direction: Direction) {
        if (value.revealing) return
        val row = controls().filter { it != "back" }
        val target = when {
            value.focus == "back" -> if (direction == Direction.DOWN) defaultFocus() else "back"
            direction == Direction.UP -> "back"
            direction == Direction.DOWN -> value.focus
            else -> row[(row.indexOf(value.focus).coerceAtLeast(0) + if (direction == Direction.LEFT) -1 else 1).coerceIn(row.indices)]
        }
        focus(target)
    }

    fun activate(key: String): Boolean { focus(key); return select() }

    /** Returns whether the selection changed the story; locked moments ignore it. */
    fun select(): Boolean {
        val s = value
        if (s.paused) { resume(); return true }
        val key = s.focus
        val name = key.substringAfter(':')
        when {
            s.revealing -> return false
            key == "back" -> return back()
            key == "scene" -> return false
            !isAvailable(key) -> value = s.copy(soon = key)
            key == "place:BOOK" -> enter(StoryScreen.HERO)
            key.startsWith("hero:") -> { value = s.copy(hero = StoryHero.valueOf(name)); enter(StoryScreen.WORLD) }
            key.startsWith("world:") -> { value = s.copy(world = StoryWorld.valueOf(name)); enter(StoryScreen.OBJECT) }
            key.startsWith("object:") -> value = s.copy(item = StoryObject.valueOf(name), elapsedMs = 0, soon = null)
            key.startsWith("action:") -> { value = s.copy(action = StoryAction.valueOf(name)); enter(StoryScreen.CONSEQUENCE) }
            key == "inside" -> if (s.canContinue) enter(StoryScreen.ENDING) else return false
            key == "replay" -> enter(StoryScreen.REPLAY)
            key == "save" -> value = s.copy(saved = true)
            key == "new" -> newStory()
            else -> return false
        }
        return true
    }

    fun back(): Boolean {
        val s = value
        when (s.screen) {
            StoryScreen.CREATE_HOME -> exitRequested = true
            StoryScreen.HERO -> enter(StoryScreen.CREATE_HOME)
            StoryScreen.WORLD -> { value = s.copy(hero = null); enter(StoryScreen.HERO) }
            StoryScreen.OBJECT -> if (s.revealing) value = s.copy(item = null, elapsedMs = 0) else { value = s.copy(world = null); enter(StoryScreen.WORLD) }
            StoryScreen.STORY_CHOICE -> { value = s.copy(item = null); enter(StoryScreen.OBJECT) }
            StoryScreen.CONSEQUENCE -> { value = s.copy(action = null); enter(StoryScreen.STORY_CHOICE) }
            // The finished story closes; the workshop book is ready for the next one.
            StoryScreen.ENDING -> value = StorySnapshot()
            StoryScreen.REPLAY -> enter(StoryScreen.ENDING)
        }
        return true
    }

    fun newStory() { value = StorySnapshot(screen = StoryScreen.HERO, focus = "hero:${StoryHero.ROBI.name}") }

    private fun enter(screen: StoryScreen) {
        value = value.copy(screen = screen, elapsedMs = 0, soon = null, saved = value.saved && screen in listOf(StoryScreen.ENDING, StoryScreen.REPLAY))
        value = value.copy(focus = defaultFocus())
    }

    fun tick(deltaMs: Long) {
        val s = value
        if (s.paused) return
        value = s.copy(elapsedMs = s.elapsedMs + deltaMs)
        when {
            value.revealing && value.elapsedMs >= StoryRules.KEY_REVEAL_MS -> enter(StoryScreen.STORY_CHOICE)
            s.screen == StoryScreen.CONSEQUENCE && !s.canContinue && value.canContinue -> value = value.copy(focus = "inside")
            s.screen == StoryScreen.REPLAY && value.elapsedMs >= StoryRules.REPLAY_BEAT_MS * StoryRules.REPLAY_BEATS -> enter(StoryScreen.ENDING)
        }
    }

    fun pause() { value = value.copy(paused = true) }
    fun resume() { value = value.copy(paused = false) }

    fun semantic(action: FamilyAction): Boolean = when (action) {
        FamilyAction.Select -> select()
        FamilyAction.Back -> back()
        // Next never skips the visible consequence or the reveal.
        FamilyAction.Next -> value.canContinue && activate("inside")
        FamilyAction.Repeat, FamilyAction.Help -> true
        FamilyAction.Pause -> { pause(); true }
        FamilyAction.Resume, FamilyAction.ContinueCurrent -> { resume(); true }
        is FamilyAction.Answer -> domain.voiceTarget(value.screen, action.transcript)?.let { activate(it) } ?: false
        else -> false
    }
}
