package ru.familytv.adventure.guide

import ru.familytv.adventure.GuideEmotion

/** Structured events the guide character reacts to — not a free chatbot. */
enum class GuideEvent {
    ADVENTURE_STARTED,
    CHALLENGE_PRESENTED,
    ANSWER_CORRECT,
    ANSWER_WRONG,
    HINT_REQUIRED,
    OBJECTIVE_COMPLETED,
    MOVE_BREAK,
    ADVENTURE_COMPLETED,
    DIALOGUE_TURN,
    OFFSCREEN_DONE,
}

data class GuideReaction(
    val text: String,
    val emotion: GuideEmotion,
    val audioRef: String? = null,
)

/**
 * Maps structured runtime events to authored/context-aware reactions.
 * Personality: curious, kind, calm — never scolding. Does not invent curriculum facts.
 */
class GuideDirector(
    private val characterName: String = "Луми",
) {
    fun react(event: GuideEvent, authoredLine: String? = null, emotionHint: GuideEmotion? = null): GuideReaction {
        if (!authoredLine.isNullOrBlank()) {
            return GuideReaction(
                text = authoredLine,
                emotion = emotionHint ?: defaultEmotion(event),
            )
        }
        return when (event) {
            GuideEvent.ADVENTURE_STARTED -> GuideReaction(
                "Привет! Я $characterName. Давай начнём.",
                GuideEmotion.HAPPY,
                audioRef = "common:hello",
            )
            GuideEvent.CHALLENGE_PRESENTED -> GuideReaction(
                "Посмотрим вместе.",
                GuideEmotion.CURIOUS,
            )
            GuideEvent.ANSWER_CORRECT -> GuideReaction(
                "Интересная мысль!",
                GuideEmotion.CELEBRATING,
                audioRef = "common:good_thought",
            )
            GuideEvent.ANSWER_WRONG -> GuideReaction(
                "Посмотрим ещё раз.",
                GuideEmotion.ENCOURAGING,
                audioRef = "common:try_again",
            )
            GuideEvent.HINT_REQUIRED -> GuideReaction(
                "Хочешь, помогу?",
                GuideEmotion.HELPING,
                audioRef = "common:want_hint",
            )
            GuideEvent.OBJECTIVE_COMPLETED -> GuideReaction(
                "Отлично! Мир изменился.",
                GuideEmotion.HAPPY,
            )
            GuideEvent.MOVE_BREAK -> GuideReaction(
                "Сделаем небольшой перерыв.",
                GuideEmotion.HAPPY,
                audioRef = "common:take_break",
            )
            GuideEvent.ADVENTURE_COMPLETED -> GuideReaction(
                "Какое путешествие! Можно отдохнуть.",
                GuideEmotion.CALM,
                audioRef = "common:well_done",
            )
            GuideEvent.DIALOGUE_TURN -> GuideReaction(
                "Как думаете?",
                GuideEmotion.THINKING,
            )
            GuideEvent.OFFSCREEN_DONE -> GuideReaction(
                "Класс! Вы нашли это вокруг себя.",
                GuideEmotion.HAPPY,
            )
        }
    }

    private fun defaultEmotion(event: GuideEvent): GuideEmotion = when (event) {
        GuideEvent.ANSWER_CORRECT, GuideEvent.ADVENTURE_STARTED, GuideEvent.OFFSCREEN_DONE -> GuideEmotion.HAPPY
        GuideEvent.ANSWER_WRONG, GuideEvent.HINT_REQUIRED -> GuideEmotion.ENCOURAGING
        GuideEvent.ADVENTURE_COMPLETED -> GuideEmotion.CALM
        GuideEvent.DIALOGUE_TURN, GuideEvent.CHALLENGE_PRESENTED -> GuideEmotion.THINKING
        else -> GuideEmotion.CURIOUS
    }
}
