package ru.familytv.interaction

import ru.familytv.narration.NarrationProvider
import java.io.File

enum class NarrationPath {
    PACKAGE_AUDIO,
    LOCAL_TTS,
    TEXT_ONLY,
}

data class NarrationResult(
    val path: NarrationPath,
    val message: PresentationMessage,
    /** True when AUDIO_FIRST expected speech but only text was available. */
    val audioFirstGap: Boolean = false,
)

/**
 * Single presentation/narration layer for child-facing instructions.
 * Priority: package audio → TTS → visible text fallback.
 * Keeps [current] for REPEAT across scene changes when callers [present] again.
 */
class NarrationCoordinator(
    private val provider: NarrationProvider,
    private val readingMode: () -> ReadingSupportMode,
    private val resolveAudio: (String) -> File? = { null },
    private val ttsUsable: () -> Boolean = { true },
) {
    @Volatile
    var current: PresentationMessage? = null
        private set

    @Volatile
    var lastResult: NarrationResult? = null
        private set

    fun present(message: PresentationMessage): NarrationResult {
        current = message
        val mode = readingMode()
        val spoken = message.effectiveSpoken()
        val ref = message.effectiveNarrationRef()
        if (!ref.isNullOrBlank()) {
            val file = resolveAudio(ref)
            if (provider.playFile(file)) {
                return NarrationResult(NarrationPath.PACKAGE_AUDIO, message).also { lastResult = it }
            }
        }
        // Prefer dedicated repeat clip when re-presenting via common pack
        if (message.assets?.repeat != null) {
            val file = resolveAudio(message.assets.repeat)
            if (provider.playFile(file)) {
                return NarrationResult(NarrationPath.PACKAGE_AUDIO, message).also { lastResult = it }
            }
        }
        if (spoken.isNotBlank() && ttsUsable()) {
            provider.speakText(spoken)
            val gap = mode == ReadingSupportMode.AUDIO_FIRST && ref.isNullOrBlank()
            return NarrationResult(NarrationPath.LOCAL_TTS, message, audioFirstGap = gap).also { lastResult = it }
        }
        val gap = mode == ReadingSupportMode.AUDIO_FIRST
        return NarrationResult(NarrationPath.TEXT_ONLY, message, audioFirstGap = gap).also { lastResult = it }
    }

    /** Re-speak current presentation; uses assets.repeat when present. */
    fun repeat(): NarrationResult? {
        val msg = current ?: return null
        if (!msg.repeatable) return lastResult
        val repeatRef = msg.assets?.repeat
        if (!repeatRef.isNullOrBlank()) {
            val file = resolveAudio(repeatRef)
            if (provider.playFile(file)) {
                return NarrationResult(NarrationPath.PACKAGE_AUDIO, msg).also { lastResult = it }
            }
            // Fall back to common phrase then full present
            val common = resolveAudio("common:repeat_task")
            if (provider.playFile(common)) {
                // Still re-speak the instruction after the cue when possible
                present(msg)
                return lastResult
            }
        }
        return present(msg)
    }

    fun clear() {
        current = null
        lastResult = null
        provider.stop()
    }

    fun stop() = provider.stop()

    fun restore(message: PresentationMessage?) {
        current = message
    }

    companion object {
        // logging omitted — keep JVM-testable
    }
}
