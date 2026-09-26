package ru.familytv.interaction

/**
 * Central dispatch for [FamilyAction] from REMOTE or VOICE.
 * Handlers are surface-specific; this object only fans out — no Android UI here.
 */
fun interface FamilyActionHandler {
    fun handle(source: InputSource, action: FamilyAction): Boolean
}

class FamilyActionDispatcher {
    @Volatile
    private var handler: FamilyActionHandler? = null

    fun attach(handler: FamilyActionHandler) {
        this.handler = handler
    }

    fun detach(handler: FamilyActionHandler) {
        if (this.handler === handler) this.handler = null
    }

    fun dispatch(source: InputSource, action: FamilyAction): Boolean =
        handler?.handle(source, action) ?: false
}

/**
 * Audio-first authoring checks (pure). Does not prove pedagogy.
 */
object AudioFirstValidator {
    data class Finding(val level: Level, val message: String)
    enum class Level { ERROR, WARNING }

    fun validateAdventureScene(
        sceneId: String,
        kind: String,
        text: String?,
        spokenPrompt: String?,
        narrationRef: String?,
        readingSupport: ReadingSupportMode,
        choiceLabelsRequireReading: Boolean = false,
        visualRepresentationOk: Boolean = true,
        textOnlyCriticalControl: Boolean = false,
        choiceCount: Int? = null,
        ageMax: Int? = null,
    ): List<Finding> {
        if (readingSupport != ReadingSupportMode.AUDIO_FIRST) return emptyList()
        val findings = mutableListOf<Finding>()
        val body = text.orEmpty()
        if (body.isBlank() && spokenPrompt.isNullOrBlank()) {
            findings += Finding(Level.ERROR, "scene '$sceneId': AUDIO_FIRST needs spoken/display instruction")
        }
        if (narrationRef.isNullOrBlank() && spokenPrompt.isNullOrBlank() && body.length > 12) {
            findings += Finding(
                Level.ERROR,
                "scene '$sceneId': critical instruction has no spoken/narration path",
            )
        }
        if (choiceLabelsRequireReading) {
            findings += Finding(
                Level.ERROR,
                "scene '$sceneId': choices require reading without voice aliases / visuals",
            )
        }
        if (!visualRepresentationOk) {
            findings += Finding(
                Level.ERROR,
                "scene '$sceneId': visual options lack understandable representation",
            )
        }
        if (textOnlyCriticalControl) {
            findings += Finding(
                Level.ERROR,
                "scene '$sceneId': required control is text-only (need icon affordance)",
            )
        }
        if (body.length > 140 && spokenPrompt.isNullOrBlank()) {
            findings += Finding(Level.WARNING, "scene '$sceneId': long text without short spokenPrompt")
        }
        val spoken = spokenPrompt.orEmpty()
        if (spoken.length > 90) {
            findings += Finding(Level.WARNING, "scene '$sceneId': long narration — prefer shorter beats")
        }
        if (choiceCount != null && ageMax != null) {
            val maxOk = when {
                ageMax <= 5 -> 3
                ageMax <= 7 -> 4
                else -> 6
            }
            if (choiceCount > maxOk) {
                findings += Finding(
                    Level.WARNING,
                    "scene '$sceneId': $choiceCount choices may be dense for ageMax=$ageMax",
                )
            }
        }
        return findings
    }
}
