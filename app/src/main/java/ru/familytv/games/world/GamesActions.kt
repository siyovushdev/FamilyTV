package ru.familytv.games.world

import ru.familytv.games.GameColor
import ru.familytv.interaction.FamilyAction

internal sealed interface GamesCommand {
    data object Back : GamesCommand
    data object Home : GamesCommand
    data object Help : GamesCommand
    data object Repeat : GamesCommand
    data object Select : GamesCommand
    data object Next : GamesCommand
    data object Pause : GamesCommand
    data object Resume : GamesCommand
    data class Number(val value: Int) : GamesCommand
    data class Paint(val value: GameColor) : GamesCommand
}

/** The same semantic actions serve remote and normalized voice. No voice/model startup here. */
internal object GamesActions {
    fun resolve(state: GamesState, action: FamilyAction, busy: Boolean = false): GamesCommand? {
        if (action == FamilyAction.Back) return GamesCommand.Back
        if (action == FamilyAction.Home) return GamesCommand.Home
        if (action == FamilyAction.Pause) return GamesCommand.Pause
        if (busy) return null
        return when (action) {
            FamilyAction.Help -> GamesCommand.Help
            FamilyAction.Repeat -> GamesCommand.Repeat
            FamilyAction.Select -> GamesCommand.Select
            FamilyAction.Resume, FamilyAction.ContinueCurrent -> GamesCommand.Resume
            FamilyAction.Next -> if (state.feedback == GameFeedback.CORRECT) GamesCommand.Next else null
            is FamilyAction.Answer -> {
                val answer = action.transcript.trim()
                when {
                    state.canAnswerMath && Regex("(?:[0-9]|10)").matches(answer) -> GamesCommand.Number(answer.toInt())
                    state.canPaint -> GameColor.entries.find { it.answer == answer }?.let(GamesCommand::Paint)
                    else -> null
                }
            }
            else -> null
        }
    }
}
