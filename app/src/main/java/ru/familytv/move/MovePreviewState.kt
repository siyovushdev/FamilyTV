package ru.familytv.move

import ru.familytv.interaction.FamilyAction

enum class MoveScreen { MOVE_HOME, READY, REACH, COMETS, FREEZE, BALANCE, ENDING }

enum class MovePhase {
    NONE,
    INTRO, ACTIVE, CONSEQUENCE, DONE,
    LEFT, RIGHT,
    MOVE, FREEZE, COUNTDOWN,
    STORY_RESULT, BREATH_IN, BREATH_OUT, ACTIONS,
}

enum class MovePlace(val label: String, val available: Boolean) {
    MUSIC("Музыкальная полянка", false),
    CLOUD("Облако тишины", false),
    STARS("Разбудим звёзды", true),
    TRAIL("Лесная тропа", false),
}

enum class LumiPose { IDLE, WAVE, STRETCH, STEP_LEFT, STEP_RIGHT, DANCE, FREEZE, BALANCE, BREATHE_IN, BREATHE_OUT, CALM }

enum class MoveDirection { LEFT, RIGHT, UP, DOWN }

object MoveRules {
    const val REACH_INTRO_MS = 1600L
    const val REACH_ACTIVE_MS = 4200L
    const val REACH_CONSEQUENCE_MS = 2800L
    const val COMETS_INTRO_MS = 1200L
    const val COMETS_LEFT_MS = 3600L
    const val COMETS_RIGHT_MS = 3600L
    const val COMETS_CONSEQUENCE_MS = 2200L
    const val FREEZE_MOVE_MS = 3600L
    const val FREEZE_HOLD_MS = 2000L
    const val FREEZE_COUNT_MS = 3000L
    const val BALANCE_INTRO_MS = 1600L
    const val BALANCE_ACTIVE_MS = 5000L
    const val BALANCE_CONSEQUENCE_MS = 2400L
    const val ENDING_STORY_MS = 2400L
    const val ENDING_BREATH_MS = 2400L
    const val HELP_MS = 2800L
    const val COUNT_STEP_MS = 1000L
}

data class MoveSnapshot(
    val screen: MoveScreen = MoveScreen.MOVE_HOME,
    val phase: MovePhase = MovePhase.NONE,
    val focus: String = "place:STARS",
    val placeFocus: MovePlace = MovePlace.STARS,
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    val soon: String? = null,
    val helpMs: Long = 0,
    val visited: Set<MoveScreen> = emptySet(),
) {
    val helping get() = helpMs > 0
    val movement get() = screen in MOVEMENT
    val actionsReady get() = screen == MoveScreen.ENDING && phase == MovePhase.ACTIONS
    val countdown get() = if (screen == MoveScreen.FREEZE && phase == MovePhase.COUNTDOWN) {
        (3 - (elapsedMs / MoveRules.COUNT_STEP_MS).toInt()).coerceIn(1, 3)
    } else 0
    val starsGlow get() = when (screen) {
        MoveScreen.MOVE_HOME, MoveScreen.READY -> 0f
        MoveScreen.REACH -> when (phase) {
            MovePhase.INTRO -> progress(MoveRules.REACH_INTRO_MS) * 0.22f
            MovePhase.ACTIVE -> 0.22f + progress(MoveRules.REACH_ACTIVE_MS) * 0.53f
            else -> 0.88f + progress(MoveRules.REACH_CONSEQUENCE_MS) * 0.12f
        }
        else -> 1f
    }
    val pathOpen get() = when {
        screen.ordinal > MoveScreen.COMETS.ordinal -> 1f
        screen != MoveScreen.COMETS -> 0f
        phase == MovePhase.INTRO -> 0.08f
        phase == MovePhase.LEFT -> 0.18f + progress(MoveRules.COMETS_LEFT_MS) * 0.22f
        phase == MovePhase.RIGHT -> 0.40f + progress(MoveRules.COMETS_RIGHT_MS) * 0.28f
        else -> 0.78f + progress(MoveRules.COMETS_CONSEQUENCE_MS) * 0.22f
    }
    val worldStill get() = when {
        screen.ordinal > MoveScreen.FREEZE.ordinal -> 1f
        screen != MoveScreen.FREEZE -> 0f
        phase == MovePhase.MOVE -> 0f
        phase == MovePhase.FREEZE -> progress(MoveRules.FREEZE_HOLD_MS)
        else -> 1f
    }
    val bridge get() = when {
        screen.ordinal > MoveScreen.BALANCE.ordinal -> 1f
        screen != MoveScreen.BALANCE -> 0f
        phase == MovePhase.INTRO -> progress(MoveRules.BALANCE_INTRO_MS) * 0.2f
        phase == MovePhase.ACTIVE -> 0.2f + progress(MoveRules.BALANCE_ACTIVE_MS) * 0.6f
        else -> 0.8f + progress(MoveRules.BALANCE_CONSEQUENCE_MS) * 0.2f
    }
    val pose: LumiPose get() {
        if (helping) return helpPose()
        return when (screen) {
            MoveScreen.MOVE_HOME -> LumiPose.WAVE
            MoveScreen.READY -> LumiPose.IDLE
            MoveScreen.REACH -> if (phase == MovePhase.INTRO) LumiPose.IDLE else LumiPose.STRETCH
            MoveScreen.COMETS -> when (phase) {
                MovePhase.LEFT -> LumiPose.STEP_LEFT
                MovePhase.RIGHT -> LumiPose.STEP_RIGHT
                else -> LumiPose.IDLE
            }
            MoveScreen.FREEZE -> if (phase == MovePhase.MOVE) LumiPose.DANCE else LumiPose.FREEZE
            MoveScreen.BALANCE -> LumiPose.BALANCE
            MoveScreen.ENDING -> when (phase) {
                MovePhase.BREATH_IN -> LumiPose.BREATHE_IN
                MovePhase.BREATH_OUT -> LumiPose.BREATHE_OUT
                else -> LumiPose.CALM
            }
        }
    }

    private fun helpPose(): LumiPose = when (screen) {
        MoveScreen.REACH -> LumiPose.STRETCH
        MoveScreen.COMETS -> if (helpMs < MoveRules.HELP_MS / 2) LumiPose.STEP_LEFT else LumiPose.STEP_RIGHT
        MoveScreen.FREEZE -> if (helpMs < MoveRules.HELP_MS / 2) LumiPose.DANCE else LumiPose.FREEZE
        MoveScreen.BALANCE -> LumiPose.BALANCE
        else -> poseForScreen()
    }

    private fun poseForScreen() = LumiPose.IDLE

    fun progress(limit: Long) = if (limit <= 0) 1f else (elapsedMs.toFloat() / limit).coerceIn(0f, 1f)

    fun encode(): Map<String, String> = buildMap {
        put("screen", screen.name); put("phase", phase.name); put("focus", focus)
        put("placeFocus", placeFocus.name); put("elapsed", elapsedMs.toString())
        put("paused", paused.toString()); put("help", helpMs.toString())
        soon?.let { put("soon", it) }
        put("visited", visited.joinToString(",") { it.name })
    }

    companion object {
        val MOVEMENT = setOf(MoveScreen.REACH, MoveScreen.COMETS, MoveScreen.FREEZE, MoveScreen.BALANCE)
        fun decode(m: Map<String, String?>): MoveSnapshot {
            fun <T : Enum<T>> read(key: String, values: List<T>): T? = values.find { it.name == m[key] }
            val d = MoveSnapshot()
            return MoveSnapshot(
                screen = read("screen", MoveScreen.entries) ?: d.screen,
                phase = read("phase", MovePhase.entries) ?: d.phase,
                focus = m["focus"] ?: d.focus,
                placeFocus = read("placeFocus", MovePlace.entries) ?: d.placeFocus,
                elapsedMs = m["elapsed"]?.toLongOrNull() ?: 0,
                paused = m["paused"] == "true",
                soon = m["soon"],
                helpMs = m["help"]?.toLongOrNull() ?: 0,
                visited = m["visited"].orEmpty().split(',').mapNotNull { n -> MoveScreen.entries.find { it.name == n } }.toSet(),
            )
        }
    }
}

/** Production Move V1 semantics. Timers live here — never only inside View animation. */
class MovePreviewState(initial: MoveSnapshot = MoveSnapshot()) {
    var value = initial
        private set
    var exitRequested = false
        private set

    init { if (value.focus !in controls()) value = value.copy(focus = defaultFocus()) }

    fun controls(): List<String> = when {
        value.paused -> listOf("continue", "finish")
        value.screen == MoveScreen.MOVE_HOME -> listOf("back") + MovePlace.entries.map { "place:${it.name}" } + "start"
        value.screen == MoveScreen.READY -> listOf("back", "start")
        value.screen == MoveScreen.ENDING && value.actionsReady -> listOf("back", "replay", "world", "home")
        value.screen == MoveScreen.ENDING -> listOf("back", "scene")
        else -> listOf("pause")
    }

    fun defaultFocus(): String = when {
        value.paused -> "continue"
        value.screen == MoveScreen.MOVE_HOME -> "place:${value.placeFocus.name}"
        value.screen == MoveScreen.READY -> "start"
        value.screen == MoveScreen.ENDING && value.actionsReady -> "replay"
        value.screen == MoveScreen.ENDING -> "scene"
        else -> "pause"
    }

    fun isAvailable(key: String) = when {
        key.startsWith("place:") -> MovePlace.valueOf(key.substringAfter(':')).available
        else -> true
    }

    fun focus(key: String) {
        if (key !in controls()) return
        value = value.copy(focus = key, placeFocus = placeOf(key) ?: value.placeFocus, soon = if (key == value.soon) value.soon else null)
    }

    fun move(direction: MoveDirection) {
        val keys = controls()
        val index = keys.indexOf(value.focus).takeIf { it >= 0 } ?: keys.indexOf(defaultFocus())
        val next = when (direction) {
            MoveDirection.LEFT, MoveDirection.UP -> keys.getOrElse(index - 1) { keys.first() }
            MoveDirection.RIGHT, MoveDirection.DOWN -> keys.getOrElse(index + 1) { keys.last() }
        }
        focus(next)
    }

    fun tick(deltaMs: Long) {
        if (deltaMs <= 0 || value.paused) return
        if (value.helping) {
            val help = value.helpMs + deltaMs
            value = value.copy(helpMs = if (help > MoveRules.HELP_MS) 0 else help)
            return
        }
        if (!value.movement && value.screen != MoveScreen.ENDING) return
        if (value.actionsReady) return
        val elapsed = value.elapsedMs + deltaMs
        value = value.copy(elapsedMs = elapsed, visited = value.visited + value.screen)
        advanceIfReady()
    }

    fun semantic(action: FamilyAction): Boolean = when (action) {
        FamilyAction.Select -> { select(); true }
        FamilyAction.Back -> { back(); true }
        FamilyAction.Pause -> { if (value.movement && !value.paused) pauseOverlay() else if (value.paused) continuePlay(); true }
        FamilyAction.Resume, FamilyAction.ContinueCurrent -> { if (value.paused) continuePlay(); true }
        FamilyAction.Repeat -> true
        FamilyAction.Help -> { help(); true }
        FamilyAction.Home -> { exitRequested = true; true }
        FamilyAction.Next -> false
        else -> false
    }

    fun select() {
        val key = value.focus
        when {
            value.paused && key == "continue" -> continuePlay()
            value.paused && key == "finish" -> goHome()
            key == "back" -> back()
            key == "start" || key == "place:STARS" -> startFrom(key)
            key.startsWith("place:") -> value = value.copy(soon = key, placeFocus = MovePlace.valueOf(key.substringAfter(':')))
            key == "pause" -> pauseOverlay()
            key == "replay" -> replay()
            key == "world" -> goHome()
            key == "home" -> exitRequested = true
        }
    }

    fun back() {
        when {
            value.paused -> goHome()
            value.screen == MoveScreen.MOVE_HOME -> exitRequested = true
            value.screen == MoveScreen.READY -> goHome()
            value.screen == MoveScreen.ENDING -> goHome()
            value.movement -> pauseOverlay()
        }
    }

    fun pauseOverlay() {
        if (!value.movement) return
        value = value.copy(paused = true, focus = "continue", helpMs = 0)
    }

    fun continuePlay() {
        value = value.copy(paused = false, focus = "pause")
    }

    fun help() {
        if (!value.movement || value.paused) return
        value = value.copy(helpMs = 1)
    }

    fun prompt(): String = when {
        value.soon != null -> soonLine(value.soon!!)
        value.paused -> "Пауза. Можно продолжить или закончить."
        value.helping -> helpLine()
        else -> when (value.screen to value.phase) {
            MoveScreen.MOVE_HOME to MovePhase.NONE -> "Подвигаемся вместе?"
            MoveScreen.READY to MovePhase.NONE -> "Проверь, чтобы рядом было свободно. Пульт можно положить рядом."
            MoveScreen.REACH to MovePhase.INTRO, MoveScreen.REACH to MovePhase.ACTIVE -> "Тянемся высоко-высоко!"
            MoveScreen.REACH to MovePhase.CONSEQUENCE, MoveScreen.REACH to MovePhase.DONE -> "Звёздочки просыпаются!"
            MoveScreen.COMETS to MovePhase.LEFT -> "Шаг в сторону!"
            MoveScreen.COMETS to MovePhase.RIGHT -> "А теперь в другую сторону!"
            MoveScreen.COMETS to MovePhase.CONSEQUENCE, MoveScreen.COMETS to MovePhase.DONE -> "Кометы прошли — путь светится дальше."
            MoveScreen.COMETS to MovePhase.INTRO -> "Шаг в сторону!"
            MoveScreen.FREEZE to MovePhase.MOVE -> "Все вместе двигаемся, как искорки!"
            MoveScreen.FREEZE to MovePhase.FREEZE, MoveScreen.FREEZE to MovePhase.COUNTDOWN, MoveScreen.FREEZE to MovePhase.DONE -> "А теперь — замри как льдинка!"
            MoveScreen.BALANCE to MovePhase.INTRO, MoveScreen.BALANCE to MovePhase.ACTIVE -> "Руки в стороны. Идём медленно."
            MoveScreen.BALANCE to MovePhase.CONSEQUENCE, MoveScreen.BALANCE to MovePhase.DONE -> "Мостик светится. Мы на полу — путь готов."
            MoveScreen.ENDING to MovePhase.STORY_RESULT -> "Получилось! Звёзды проснулись."
            MoveScreen.ENDING to MovePhase.BREATH_IN -> "Вдох…"
            MoveScreen.ENDING to MovePhase.BREATH_OUT -> "…и медленный выдох."
            MoveScreen.ENDING to MovePhase.ACTIONS -> "Можно повторить или вернуться."
            else -> "Подвигаемся вместе?"
        }
    }

    fun mapVoice(transcript: String): String? {
        val t = transcript.lowercase().trim()
        return when {
            t in listOf("начать", "старт", "готовы", "поехали", "разбудим", "звёзды", "звезды") ->
                if (value.screen == MoveScreen.MOVE_HOME) "place:STARS" else if (value.screen == MoveScreen.READY) "start" else null
            t in listOf("пауза", "стоп") -> "pause"
            t in listOf("дальше", "продолжить") -> "continue"
            t in listOf("повторить", "ещё раз") -> "replay"
            t in listOf("домой", "выход") -> "home"
            else -> null
        }
    }

    private fun startFrom(key: String) {
        if (key.startsWith("place:") && !isAvailable(key)) {
            value = value.copy(soon = key); return
        }
        if (value.screen == MoveScreen.MOVE_HOME) enter(MoveScreen.READY, MovePhase.NONE, "start")
        else enter(MoveScreen.REACH, MovePhase.INTRO, "pause")
    }

    private fun replay() {
        enter(MoveScreen.READY, MovePhase.NONE, "start")
    }

    private fun goHome() {
        value = MoveSnapshot(focus = "place:STARS", placeFocus = MovePlace.STARS)
    }

    private fun helpLine() = when (value.screen) {
        MoveScreen.REACH -> "Смотри: ручки вверх, тянемся к звёздам."
        MoveScreen.COMETS -> "Смотри: мягкий шаг в сторону."
        MoveScreen.FREEZE -> "Сначала двигаемся — потом замираем вместе."
        MoveScreen.BALANCE -> "Руки в стороны и идём совсем медленно."
        else -> prompt()
    }

    private fun soonLine(key: String): String {
        val place = key.substringAfter(':').let { runCatching { MovePlace.valueOf(it) }.getOrNull() }
        return "Скоро пойдём на ${place?.label?.lowercase() ?: "другую полянку"}."
    }

    private fun placeOf(key: String) = if (key.startsWith("place:")) runCatching { MovePlace.valueOf(key.substringAfter(':')) }.getOrNull() else null

    private fun enter(screen: MoveScreen, phase: MovePhase, focus: String) {
        value = value.copy(
            screen = screen, phase = phase, focus = focus, elapsedMs = 0, paused = false, soon = null, helpMs = 0,
            visited = value.visited + screen,
        )
    }

    private fun advanceIfReady() {
        val limit = limitOf(value.screen, value.phase) ?: return
        if (value.elapsedMs < limit) return
        val (screen, phase) = nextBeat(value.screen, value.phase) ?: return
        val focus = if (screen == MoveScreen.ENDING && phase == MovePhase.ACTIONS) "replay"
        else if (screen == MoveScreen.ENDING) "scene"
        else "pause"
        enter(screen, phase, focus)
    }

    private fun limitOf(screen: MoveScreen, phase: MovePhase): Long? = when (screen to phase) {
        MoveScreen.REACH to MovePhase.INTRO -> MoveRules.REACH_INTRO_MS
        MoveScreen.REACH to MovePhase.ACTIVE -> MoveRules.REACH_ACTIVE_MS
        MoveScreen.REACH to MovePhase.CONSEQUENCE -> MoveRules.REACH_CONSEQUENCE_MS
        MoveScreen.COMETS to MovePhase.INTRO -> MoveRules.COMETS_INTRO_MS
        MoveScreen.COMETS to MovePhase.LEFT -> MoveRules.COMETS_LEFT_MS
        MoveScreen.COMETS to MovePhase.RIGHT -> MoveRules.COMETS_RIGHT_MS
        MoveScreen.COMETS to MovePhase.CONSEQUENCE -> MoveRules.COMETS_CONSEQUENCE_MS
        MoveScreen.FREEZE to MovePhase.MOVE -> MoveRules.FREEZE_MOVE_MS
        MoveScreen.FREEZE to MovePhase.FREEZE -> MoveRules.FREEZE_HOLD_MS
        MoveScreen.FREEZE to MovePhase.COUNTDOWN -> MoveRules.FREEZE_COUNT_MS
        MoveScreen.BALANCE to MovePhase.INTRO -> MoveRules.BALANCE_INTRO_MS
        MoveScreen.BALANCE to MovePhase.ACTIVE -> MoveRules.BALANCE_ACTIVE_MS
        MoveScreen.BALANCE to MovePhase.CONSEQUENCE -> MoveRules.BALANCE_CONSEQUENCE_MS
        MoveScreen.ENDING to MovePhase.STORY_RESULT -> MoveRules.ENDING_STORY_MS
        MoveScreen.ENDING to MovePhase.BREATH_IN -> MoveRules.ENDING_BREATH_MS
        MoveScreen.ENDING to MovePhase.BREATH_OUT -> MoveRules.ENDING_BREATH_MS
        else -> null
    }

    private fun nextBeat(screen: MoveScreen, phase: MovePhase): Pair<MoveScreen, MovePhase>? = when (screen to phase) {
        MoveScreen.REACH to MovePhase.INTRO -> MoveScreen.REACH to MovePhase.ACTIVE
        MoveScreen.REACH to MovePhase.ACTIVE -> MoveScreen.REACH to MovePhase.CONSEQUENCE
        MoveScreen.REACH to MovePhase.CONSEQUENCE -> MoveScreen.COMETS to MovePhase.INTRO
        MoveScreen.COMETS to MovePhase.INTRO -> MoveScreen.COMETS to MovePhase.LEFT
        MoveScreen.COMETS to MovePhase.LEFT -> MoveScreen.COMETS to MovePhase.RIGHT
        MoveScreen.COMETS to MovePhase.RIGHT -> MoveScreen.COMETS to MovePhase.CONSEQUENCE
        MoveScreen.COMETS to MovePhase.CONSEQUENCE -> MoveScreen.FREEZE to MovePhase.MOVE
        MoveScreen.FREEZE to MovePhase.MOVE -> MoveScreen.FREEZE to MovePhase.FREEZE
        MoveScreen.FREEZE to MovePhase.FREEZE -> MoveScreen.FREEZE to MovePhase.COUNTDOWN
        MoveScreen.FREEZE to MovePhase.COUNTDOWN -> MoveScreen.BALANCE to MovePhase.INTRO
        MoveScreen.BALANCE to MovePhase.INTRO -> MoveScreen.BALANCE to MovePhase.ACTIVE
        MoveScreen.BALANCE to MovePhase.ACTIVE -> MoveScreen.BALANCE to MovePhase.CONSEQUENCE
        MoveScreen.BALANCE to MovePhase.CONSEQUENCE -> MoveScreen.ENDING to MovePhase.STORY_RESULT
        MoveScreen.ENDING to MovePhase.STORY_RESULT -> MoveScreen.ENDING to MovePhase.BREATH_IN
        MoveScreen.ENDING to MovePhase.BREATH_IN -> MoveScreen.ENDING to MovePhase.BREATH_OUT
        MoveScreen.ENDING to MovePhase.BREATH_OUT -> MoveScreen.ENDING to MovePhase.ACTIONS
        else -> null
    }
}
