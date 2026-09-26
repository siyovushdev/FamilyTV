package ru.familytv.interactive

import org.json.JSONObject

object InteractiveExperienceParser {
    fun parse(jsonText: String): InteractiveExperience {
        val root = JSONObject(jsonText)
        val id = root.getString("id")
        val title = root.getString("title")
        val schemaVersion = root.optInt("schemaVersion", 1)
        val stepsJson = root.getJSONArray("steps")
        val steps = buildList {
            for (i in 0 until stepsJson.length()) {
                add(parseStep(stepsJson.getJSONObject(i)))
            }
        }
        require(steps.isNotEmpty()) { "Experience $id has no steps" }
        val withDefaults = if (schemaVersion < 2) applyLegacyLinearNext(steps) else steps
        return InteractiveExperience(
            id = id,
            title = title,
            schemaVersion = schemaVersion,
            steps = withDefaults,
            startStepId = root.optString("startStepId").ifBlank { null },
        )
    }

    private fun applyLegacyLinearNext(steps: List<InteractiveStep>): List<InteractiveStep> {
        return steps.mapIndexed { index, step ->
            if (step.transitions.next != null) return@mapIndexed step
            val nextId = steps.getOrNull(index + 1)?.id ?: ExperienceValidator.END
            when (step) {
                is MessageStep -> step.copy(transitions = step.transitions.copy(next = nextId))
                is VideoStep -> step.copy(transitions = step.transitions.copy(next = nextId))
                is QuestionStep -> step.copy(
                    transitions = step.transitions.copy(
                        onCorrect = nextId,
                        onWrong = step.id,
                        onUnknown = step.id,
                    ),
                )
                is ChoiceStep -> step.copy(
                    transitions = step.transitions.copy(
                        onCorrect = nextId,
                        onWrong = step.id,
                        onUnknown = step.id,
                    ),
                )
                is TrueFalseStep -> step.copy(
                    transitions = step.transitions.copy(
                        onCorrect = nextId,
                        onWrong = step.id,
                        onUnknown = step.id,
                    ),
                )
                is MoveStep -> step.copy(transitions = step.transitions.copy(next = nextId))
                is GameStep -> step.copy(transitions = step.transitions.copy(next = nextId))
                is BreakStep -> step.copy(transitions = step.transitions.copy(next = nextId))
            }
        }
    }

    private fun parseStep(json: JSONObject): InteractiveStep {
        val id = json.getString("id")
        val transitions = parseTransitions(json.optJSONObject("transitions") ?: json)
        val feedback = parseFeedback(json)
        return when (json.getString("type").lowercase()) {
            "video" -> VideoStep(
                id, json.getString("mediaRef"),
                transitions.copy(next = transitions.next ?: json.optString("next").ifBlank { null }),
                feedback,
            )
            "message" -> MessageStep(
                id = id,
                text = json.getString("text"),
                continueLabel = json.optString("continueLabel", "Дальше"),
                promptAudio = json.optString("promptAudio").ifBlank { null },
                transitions = transitions.copy(next = transitions.next ?: json.optString("next").ifBlank { null }),
                feedback = feedback,
            )
            "choice" -> {
                val optionsJson = json.getJSONArray("options")
                val options = buildList {
                    for (i in 0 until optionsJson.length()) {
                        val o = optionsJson.getJSONObject(i)
                        add(ChoiceOption(o.getString("id"), o.getString("text")))
                    }
                }
                ChoiceStep(
                    id = id,
                    prompt = json.getString("prompt"),
                    options = options,
                    correctOptionId = json.getString("correctOptionId"),
                    promptAudio = json.optString("promptAudio").ifBlank { null },
                    feedback = feedback,
                    transitions = transitions,
                )
            }
            "true_false", "truefalse" -> TrueFalseStep(
                id = id,
                prompt = json.getString("prompt"),
                correct = json.getBoolean("correct"),
                promptAudio = json.optString("promptAudio").ifBlank { null },
                feedback = feedback,
                transitions = transitions,
            )
            "move", "break_or_move" -> MoveStep(
                id = id,
                prompt = json.getString("prompt"),
                durationSeconds = json.optInt("durationSeconds", 20),
                continueMode = json.optString("continueMode", "button"),
                continueLabel = json.optString("continueLabel", "Готово"),
                promptAudio = json.optString("promptAudio").ifBlank { null },
                transitions = transitions.copy(next = transitions.next ?: json.optString("next").ifBlank { null }),
                feedback = feedback,
            )
            "question" -> QuestionStep(
                id = id,
                prompt = json.getString("prompt"),
                questionType = QuestionType.valueOf(json.getString("questionType").uppercase()),
                expected = json.getString("expected"),
                choices = buildList {
                    val arr = json.optJSONArray("choices") ?: return@buildList
                    for (i in 0 until arr.length()) add(arr.getString(i))
                },
                visual = json.optJSONObject("visual")?.let(::parseVisual),
                promptAudio = json.optString("promptAudio").ifBlank { null },
                feedback = feedback,
                transitions = transitions,
            )
            "game" -> GameStep(id, json.getString("gameId"), transitions, feedback)
            "break" -> BreakStep(
                id = id,
                text = json.optString("text"),
                durationMs = json.optLong("durationMs", 0L),
                transitions = transitions,
                feedback = feedback,
            )
            else -> error("Unknown step type in $id")
        }
    }

    private fun parseFeedback(json: JSONObject): StepFeedback {
        val block = json.optJSONObject("feedback")
        val sharedAudio = block?.optString("feedbackAudio")?.ifBlank { null }
            ?: json.optString("feedbackAudio").ifBlank { null }
        return StepFeedback(
            correct = block?.optString("correct")?.ifBlank { null }
                ?: json.optString("correctFeedback").ifBlank { "Молодец!" },
            wrong = block?.optString("wrong")?.ifBlank { null }
                ?: json.optString("wrongFeedback").ifBlank { "Попробуй ещё раз" },
            unknown = block?.optString("unknown")?.ifBlank { null }
                ?: "Не расслышал. Попробуй ещё раз.",
            hint = block?.optString("hint")?.ifBlank { null },
            retryLimit = block?.optInt("retryLimit")?.takeIf { block.has("retryLimit") && it > 0 },
            correctAudio = block?.optString("correctAudio")?.ifBlank { null } ?: sharedAudio,
            wrongAudio = block?.optString("wrongAudio")?.ifBlank { null },
            unknownAudio = block?.optString("unknownAudio")?.ifBlank { null },
        )
    }

    private fun parseTransitions(json: JSONObject): StepTransitions = StepTransitions(
        next = json.optString("next").ifBlank { null },
        onCorrect = json.optString("onCorrect").ifBlank { null },
        onWrong = json.optString("onWrong").ifBlank { null },
        onUnknown = json.optString("onUnknown").ifBlank { null },
    )

    private fun parseVisual(json: JSONObject): QuestionVisual = when (json.getString("kind").uppercase()) {
        "APPLES" -> QuestionVisual.Apples(json.getInt("left"), json.getInt("right"))
        "COLOR_CIRCLES" -> {
            val choices = buildList {
                val arr = json.getJSONArray("choices")
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
            QuestionVisual.ColorCircles(json.getString("target"), choices)
        }
        "TEXT" -> QuestionVisual.TextOnly(json.getString("text"))
        else -> error("Unknown visual kind")
    }
}
