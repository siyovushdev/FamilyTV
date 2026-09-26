package ru.familytv.games.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Transient character feedback, independent of answers/progress. No art replacement or rewards. */
internal enum class LumiReaction { IDLE, HELP, WRONG, SUCCESS, LAUNCH }

internal data class LumiReactionFrame(val kind: LumiReaction = LumiReaction.IDLE, val progress: Float = 0f) {
    private val envelope get() = sin(PI * progress.coerceIn(0f, 1f)).toFloat().coerceAtLeast(0f)
    val strength: Float get() = when (kind) {
        LumiReaction.IDLE -> 0f
        LumiReaction.HELP -> envelope * (.45f + .55f * abs(sin(progress * PI * 6).toFloat()))
        LumiReaction.WRONG -> envelope * .6f
        LumiReaction.SUCCESS, LumiReaction.LAUNCH -> envelope
    }
    val bounce: Float get() = when (kind) {
        LumiReaction.SUCCESS, LumiReaction.LAUNCH -> -12f * abs(sin(progress * PI * 2).toFloat()) * envelope
        else -> 0f
    }
    val tilt: Float get() = if (kind == LumiReaction.WRONG) sin(progress * PI * 2).toFloat() * 9f * envelope else 0f
    val warm get() = kind == LumiReaction.SUCCESS || kind == LumiReaction.LAUNCH
}
