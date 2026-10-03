package ru.familytv.explore

import ru.familytv.adventure.mechanics.MechanicVerdict
import ru.familytv.adventure.mechanics.MechanicSnapshot
import ru.familytv.adventure.mechanics.PredictAndTestEngine
import ru.familytv.interaction.FamilyAction

internal enum class WeatherScreen { HOME, INTRO, PREDICTION, PLACING_ICE, EXPERIMENT, RESULT, ASK, BUILD_CHOOSE, BUILD_PLACED, BUILD_RUNNING, BUILD_RESULT }
/**
 * Explore Home locations, in world-geometry order. One semantics for D-pad and voice:
 * [route] is the `FamilyAction.OpenWorld` id, [contentId] the existing catalog experience.
 */
enum class ExploreDestination(val label: String, val contentId: String?) {
    SPACE("Космос", "adv-space-expedition"),
    FOREST("Лес", "adv-forest-trail"),
    WEATHER("Погода", ExploreRoutes.CONTENT_ID),
    HOW("Как устроено", null),
    EXPERIMENTS("Опыты", null);
    val available get() = contentId != null
    val route get() = "${ExploreRoutes.WORLD_ID}/${name.lowercase()}"
    companion object {
        fun fromRoute(route: String?): ExploreDestination? = entries.find { it.route == route }
    }
}
internal enum class IcePlace { SUN, SHADE }
internal enum class Shelter { CANOPY, LEAVES }
internal enum class Cause { SUN, HEAT, ICE, WATER, DONE }
internal enum class ExperimentBeat { START, MID, END }
internal enum class LumiMood { IDLE, CURIOSITY, OBSERVATION, EXPLANATION, DISCOVERY }
internal enum class Direction { LEFT, RIGHT, UP, DOWN }
internal data class IceSample(val place: IcePlace, val ice: Float, val water: Float)
/** Non-advancing focus holder on the explanation chain; OK here never skips ahead. */
internal const val EXPLANATION_FOCUS = "explanation:CHAIN"

internal data class WeatherSnapshot(
    val screen: WeatherScreen = WeatherScreen.HOME,
    val elapsedMs: Long = 0,
    val hypothesis: IcePlace? = null,
    val shelter: Shelter? = null,
    val focus: String = "destination:WEATHER",
    val worldFocus: ExploreDestination = ExploreDestination.WEATHER,
    val placeFocus: IcePlace = IcePlace.SUN,
    val shelterFocus: Shelter = Shelter.CANOPY,
    val paused: Boolean = false,
    val unavailable: Boolean = false,
    val experiment: WeatherDefinition,
) {
    val cause: Cause get() = Cause.entries[(elapsedMs / experiment.timing.causeMs).toInt().coerceIn(0, 4)]
    val beat: ExperimentBeat get() = when {
        elapsedMs < 4000 -> ExperimentBeat.START
        elapsedMs < 12000 -> ExperimentBeat.MID
        else -> ExperimentBeat.END
    }
    val experimentFraction: Float get() = when (screen) {
        WeatherScreen.EXPERIMENT -> ((elapsedMs - 2000).coerceAtLeast(0) / 10000f).coerceIn(0f, 1f)
        WeatherScreen.RESULT, WeatherScreen.ASK -> 1f
        else -> 0f
    }
    val buildFraction: Float get() = when (screen) {
        WeatherScreen.BUILD_RUNNING -> ((elapsedMs - 1500).coerceAtLeast(0) / 8000f).coerceIn(0f, 1f)
        WeatherScreen.BUILD_RESULT -> 1f
        else -> 0f
    }
    val samples get() = WeatherMotion.samples(experimentFraction)
    val canCheck get() = screen == WeatherScreen.BUILD_PLACED && shelter != null && elapsedMs >= experiment.timing.placeMs
    /** The next experiment exists only after the whole explanation chain has been shown. */
    val canBuild get() = screen == WeatherScreen.ASK && cause == Cause.DONE
    val isBuild get() = screen in setOf(WeatherScreen.BUILD_CHOOSE, WeatherScreen.BUILD_PLACED, WeatherScreen.BUILD_RUNNING, WeatherScreen.BUILD_RESULT)
    /** Illustrated background asset under `assets/explore_weather/`; each contains exactly one Lumi. */
    val background: String get() = when {
        screen == WeatherScreen.HOME -> "explore_world"
        isBuild -> "weather_build"
        screen in listOf(WeatherScreen.INTRO, WeatherScreen.PREDICTION, WeatherScreen.PLACING_ICE) -> "weather_intro"
        else -> "weather_comparison"
    }
    val isTimed get() = screen in setOf(WeatherScreen.PLACING_ICE, WeatherScreen.EXPERIMENT, WeatherScreen.BUILD_RUNNING) ||
        screen == WeatherScreen.ASK && cause != Cause.DONE || screen == WeatherScreen.BUILD_PLACED && !canCheck
    val mood: LumiMood get() = when (screen) {
        WeatherScreen.HOME -> LumiMood.IDLE
        WeatherScreen.INTRO, WeatherScreen.PREDICTION, WeatherScreen.PLACING_ICE, WeatherScreen.BUILD_CHOOSE, WeatherScreen.BUILD_PLACED -> LumiMood.CURIOSITY
        WeatherScreen.EXPERIMENT, WeatherScreen.BUILD_RUNNING -> LumiMood.OBSERVATION
        WeatherScreen.ASK -> LumiMood.EXPLANATION
        WeatherScreen.RESULT, WeatherScreen.BUILD_RESULT -> LumiMood.DISCOVERY
    }
    fun prompt(): String = when (screen) {
        WeatherScreen.HOME -> if (unavailable) experiment.lines.homeSoon else experiment.lines.home
        WeatherScreen.INTRO -> experiment.lines.intro
        WeatherScreen.PREDICTION -> experiment.lines.prediction
        WeatherScreen.PLACING_ICE -> experiment.lines.placing
        WeatherScreen.EXPERIMENT -> experiment.lines.watching
        WeatherScreen.RESULT -> experiment.observe(null)
        WeatherScreen.ASK -> if (cause in listOf(Cause.SUN, Cause.HEAT)) experiment.lines.askWarm else experiment.lines.askMelt
        WeatherScreen.BUILD_CHOOSE -> experiment.lines.buildChoose
        WeatherScreen.BUILD_PLACED -> experiment.lines.buildPlaced
        WeatherScreen.BUILD_RUNNING -> experiment.lines.buildWatching
        WeatherScreen.BUILD_RESULT -> experiment.observe(shelter)
    }
}

/** Explicit, checkpointable clock. Animation duration settings cannot skip observation beats. */
internal class WeatherExploreState(initial: WeatherSnapshot) {
    var value: WeatherSnapshot = initial
        private set
    /** An existing catalog experience the host should open; Explore Home keeps its focus underneath. */
    private var launch: ExploreDestination? = null
    init { if (value.focus !in controls()) value = value.copy(focus = defaultFocus()) }
    fun takeLaunch(): ExploreDestination? = launch.also { launch = null }
    /** The host could not find an enabled catalog item for the destination. */
    fun markUnavailable() { value = value.copy(unavailable = true) }
    fun checkpoint(): WeatherSnapshot = value
    fun predictionSnapshot(): MechanicSnapshot {
        val prediction = value.experiment.toPrediction()
        var result = PredictAndTestEngine.initial(prediction)
        value.hypothesis?.let { result = PredictAndTestEngine.applySelect(result, prediction, it.name) }
        if (value.screen in listOf(WeatherScreen.RESULT, WeatherScreen.ASK)) result = PredictAndTestEngine.applySelect(result, prediction, "__test__")
        return result
    }
    fun controls(): List<String> = listOf("back") + when (value.screen) {
        WeatherScreen.HOME -> ExploreDestination.entries.map { "destination:${it.name}" }
        WeatherScreen.INTRO -> listOf("start", "repeat")
        WeatherScreen.PREDICTION -> listOf("place:SUN", "place:SHADE", "repeat")
        WeatherScreen.PLACING_ICE -> listOf("repeat")
        WeatherScreen.EXPERIMENT -> listOf("speed", "repeat")
        WeatherScreen.RESULT -> listOf("why", "again", "repeat")
        WeatherScreen.ASK -> listOf(if (value.canBuild) "build" else EXPLANATION_FOCUS, "repeat")
        WeatherScreen.BUILD_CHOOSE -> Shelter.entries.map { "shelter:${it.name}" } + "repeat"
        WeatherScreen.BUILD_PLACED -> (if (value.canCheck) listOf("check", "change") else emptyList()) + "repeat"
        WeatherScreen.BUILD_RUNNING -> listOf("speed", "repeat")
        WeatherScreen.BUILD_RESULT -> listOf("change", "home", "repeat")
    }
    fun defaultFocus(): String = when (value.screen) {
        WeatherScreen.HOME -> "destination:${value.worldFocus}"
        WeatherScreen.INTRO -> "start"
        WeatherScreen.PREDICTION -> "place:${value.placeFocus}"
        WeatherScreen.PLACING_ICE -> "back"
        WeatherScreen.EXPERIMENT, WeatherScreen.BUILD_RUNNING -> "speed"
        WeatherScreen.RESULT -> "why"
        WeatherScreen.ASK -> if (value.canBuild) "build" else EXPLANATION_FOCUS
        WeatherScreen.BUILD_CHOOSE -> "shelter:${value.shelterFocus}"
        WeatherScreen.BUILD_PLACED -> if (value.canCheck) "check" else "back"
        WeatherScreen.BUILD_RESULT -> "change"
    }
    fun focus(key: String) {
        if (key !in controls()) return
        value = value.copy(focus = key,
            worldFocus = if (key.startsWith("destination:")) ExploreDestination.valueOf(key.substringAfter(':')) else value.worldFocus,
            placeFocus = if (key.startsWith("place:")) IcePlace.valueOf(key.substringAfter(':')) else value.placeFocus,
            shelterFocus = if (key.startsWith("shelter:")) Shelter.valueOf(key.substringAfter(':')) else value.shelterFocus,
        )
    }
    fun move(direction: Direction) {
        val s = value
        val world = controls().filter { ':' in it }
        val row = controls().filter { it != "back" }
        val target = when {
            s.focus == "back" -> if (direction == Direction.DOWN) {
                defaultFocus().takeUnless { it == "back" } ?: row.firstOrNull() ?: "back"
            } else "back"
            direction == Direction.UP -> if (s.focus == "repeat" && world.isNotEmpty()) defaultFocus() else "back"
            direction == Direction.DOWN -> if (world.isNotEmpty() && "repeat" in row) "repeat" else defaultFocus()
            else -> {
                val route = if (s.focus in world) world else row
                val i = route.indexOf(s.focus).coerceAtLeast(0)
                route[(i + if (direction == Direction.LEFT) -1 else 1).coerceIn(route.indices)]
            }
        }
        focus(target)
    }
    private fun enter(screen: WeatherScreen, elapsed: Long = 0) {
        value = value.copy(screen = screen, elapsedMs = elapsed, unavailable = false)
        focus(defaultFocus())
    }
    fun activate(key: String = value.focus): Boolean {
        if (key !in controls() || value.paused) return false
        when {
            key == "back" -> return back()
            key.startsWith("destination:") -> {
                val destination = ExploreDestination.valueOf(key.substringAfter(':'))
                when {
                    destination == ExploreDestination.WEATHER -> enter(WeatherScreen.INTRO)
                    destination.available -> { value = value.copy(unavailable = false); launch = destination }
                    else -> value = value.copy(unavailable = true)
                }
            }
            key == "start" || key == "again" -> { value = value.copy(hypothesis = null); enter(WeatherScreen.PREDICTION) }
            key.startsWith("place:") -> choosePlace(IcePlace.valueOf(key.substringAfter(':')))
            key == "speed" -> tick(2500)
            key == "why" -> enter(WeatherScreen.ASK)
            key == "repeat" -> repeat()
            key == EXPLANATION_FOCUS -> Unit
            key == "build" && value.canBuild -> { value = value.copy(shelter = null); enter(WeatherScreen.BUILD_CHOOSE) }
            key.startsWith("shelter:") -> {
                value = value.copy(shelter = Shelter.valueOf(key.substringAfter(':')))
                enter(WeatherScreen.BUILD_PLACED)
            }
            key == "check" && value.canCheck -> enter(WeatherScreen.BUILD_RUNNING)
            key == "change" -> { value = value.copy(shelter = null); enter(WeatherScreen.BUILD_CHOOSE) }
            key == "home" -> enter(WeatherScreen.HOME)
        }
        return true
    }
    private fun choosePlace(place: IcePlace) {
        if (value.screen != WeatherScreen.PREDICTION) return
        val selected = PredictAndTestEngine.applySelect(predictionSnapshot(), value.experiment.toPrediction(), place.name)
        value = value.copy(hypothesis = IcePlace.valueOf(requireNotNull(selected.chosenOption)), placeFocus = place)
        enter(WeatherScreen.PLACING_ICE)
    }
    fun semantic(action: FamilyAction): Boolean = when (action) {
        FamilyAction.Back -> back()
        FamilyAction.Home -> { enter(WeatherScreen.HOME); true }
        FamilyAction.Select -> activate()
        FamilyAction.Repeat, FamilyAction.Help -> { repeat(); true }
        FamilyAction.Pause -> { pause(); true }
        FamilyAction.Resume, FamilyAction.ContinueCurrent -> { resume(); true }
        // Voice / semantic routes select the same world object the D-pad would.
        is FamilyAction.OpenWorld -> ExploreDestination.fromRoute(action.worldId)
            ?.takeIf { value.screen == WeatherScreen.HOME }
            ?.let { focus("destination:${it.name}"); activate() } ?: false
        is FamilyAction.Answer -> {
            val transcript = action.transcript.trim().lowercase().replace('ё', 'е')
            when {
                value.screen == WeatherScreen.PREDICTION -> {
                    val prediction = value.experiment.toPrediction()
                    val (selected, verdict) = PredictAndTestEngine.applyVoice(predictionSnapshot(), prediction, transcript)
                    if (verdict == MechanicVerdict.INCOMPLETE && selected.chosenOption != null && transcript !in prediction.testPhrases) {
                        choosePlace(IcePlace.valueOf(selected.chosenOption)); true
                    } else false
                }
                value.canCheck && transcript in listOf("проверить", "проверим") -> activate("check")
                value.screen == WeatherScreen.BUILD_CHOOSE -> {
                    val shelter = value.experiment.shelters.find { transcript in it.voice.map { alias -> alias.lowercase().replace('ё', 'е') } }
                    if (shelter != null) activate("shelter:${shelter.id.name}") else false
                }
                else -> false
            }
        }
        else -> false
    }
    fun repeat() {
        if (value.screen != WeatherScreen.ASK) return
        value = value.copy(elapsedMs = 0)
        if (value.focus !in controls()) focus(defaultFocus())
    }
    fun tick(deltaMs: Long) {
        if (value.paused || !value.isTimed || deltaMs <= 0) return
        val s = value
        val total = s.elapsedMs + deltaMs.coerceAtMost(60000)
        when (s.screen) {
            WeatherScreen.PLACING_ICE -> if (total >= s.experiment.timing.placeMs) enter(WeatherScreen.EXPERIMENT) else value = s.copy(elapsedMs = total)
            WeatherScreen.EXPERIMENT -> if (total >= s.experiment.timing.experimentMs) enter(WeatherScreen.RESULT) else value = s.copy(elapsedMs = total)
            WeatherScreen.BUILD_RUNNING -> if (total >= s.experiment.timing.buildMs) enter(WeatherScreen.BUILD_RESULT) else value = s.copy(elapsedMs = total)
            WeatherScreen.BUILD_PLACED -> {
                value = s.copy(elapsedMs = total.coerceAtMost(s.experiment.timing.placeMs))
                if (value.canCheck) focus("check")
            }
            WeatherScreen.ASK -> {
                value = s.copy(elapsedMs = total.coerceAtMost(4 * s.experiment.timing.causeMs))
                if (value.canBuild) focus("build")
            }
            else -> Unit
        }
    }
    fun pause() { value = value.copy(paused = true) }
    fun resume() { value = value.copy(paused = false) }
    /** False only at the Explore Home root: the host Activity decides where to return. */
    fun back(): Boolean {
        when (value.screen) {
            WeatherScreen.HOME -> return false
            WeatherScreen.INTRO -> enter(WeatherScreen.HOME)
            WeatherScreen.PREDICTION -> enter(WeatherScreen.INTRO)
            WeatherScreen.PLACING_ICE, WeatherScreen.EXPERIMENT -> enter(WeatherScreen.PREDICTION)
            WeatherScreen.RESULT -> enter(WeatherScreen.INTRO)
            WeatherScreen.ASK -> enter(WeatherScreen.RESULT)
            WeatherScreen.BUILD_CHOOSE -> enter(WeatherScreen.ASK, 4 * value.experiment.timing.causeMs)
            WeatherScreen.BUILD_PLACED, WeatherScreen.BUILD_RUNNING, WeatherScreen.BUILD_RESULT -> {
                value = value.copy(shelter = null)
                enter(WeatherScreen.BUILD_CHOOSE)
            }
        }
        return true
    }
}
