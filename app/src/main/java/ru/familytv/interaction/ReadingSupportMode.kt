package ru.familytv.interaction

/**
 * Presentation preference for how child-facing instructions are shown.
 * Independent of household age range — age does not prove reading ability.
 */
enum class ReadingSupportMode {
    /** Important info is spoken; large visuals; text still visible but not required. */
    AUDIO_FIRST,

    /** Spoken + short readable text share equal weight. */
    AUDIO_AND_TEXT,

    /** Longer readable text allowed; voice/Repeat still available. */
    TEXT_RICH,
    ;

    companion object {
        fun parse(raw: String?): ReadingSupportMode {
            val key = raw?.trim()?.uppercase()?.replace('-', '_') ?: return AUDIO_AND_TEXT
            return entries.firstOrNull { it.name == key } ?: when (key) {
                "AUDIO", "AUDIOFIRST", "VOICE_FIRST" -> AUDIO_FIRST
                "BALANCED", "BOTH" -> AUDIO_AND_TEXT
                "TEXT", "TEXTRICH", "READING" -> TEXT_RICH
                else -> AUDIO_AND_TEXT
            }
        }
    }
}

/** Household age band for filtering/presentation — not a child identity. */
data class HouseholdAgeBand(
    val ageMin: Int? = null,
    val ageMax: Int? = null,
)
