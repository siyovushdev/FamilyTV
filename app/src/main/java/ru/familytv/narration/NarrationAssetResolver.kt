package ru.familytv.narration

import android.content.Context
import ru.familytv.content.ContentItem
import ru.familytv.content.LocalContentRepository
import ru.familytv.content.ResolvedMedia
import java.io.File

/**
 * Resolves narration audio refs for offline playback.
 * Order: package file → bundled catalog asset → common voice pack → null.
 */
class NarrationAssetResolver(
    private val context: Context,
    private val repository: LocalContentRepository,
    private val cacheDir: File = File(context.cacheDir, "narration"),
) {
    init {
        cacheDir.mkdirs()
    }

    fun resolve(contentItem: ContentItem?, ref: String?): File? {
        if (ref.isNullOrBlank()) return null
        if (ref.startsWith("common:")) {
            return resolveCommon(ref.removePrefix("common:").trim())
        }
        contentItem?.let { item ->
            when (val media = repository.resolveMediaRef(item, ref)) {
                is ResolvedMedia.FilePath -> return media.file.takeIf { it.isFile }
                is ResolvedMedia.AssetPath -> return materializeAsset(media.assetPath)
                null -> Unit
            }
            // Try narration/ prefix
            if (!ref.startsWith("narration/") && !ref.startsWith("audio/")) {
                when (val media = repository.resolveMediaRef(item, "narration/$ref")) {
                    is ResolvedMedia.FilePath -> return media.file.takeIf { it.isFile }
                    is ResolvedMedia.AssetPath -> return materializeAsset(media.assetPath)
                    null -> Unit
                }
                when (val media = repository.resolveMediaRef(item, "audio/$ref")) {
                    is ResolvedMedia.FilePath -> return media.file.takeIf { it.isFile }
                    is ResolvedMedia.AssetPath -> return materializeAsset(media.assetPath)
                    null -> Unit
                }
            }
        }
        // Bundled adventure/catalog narration without package
        listOf(
            "catalog/narration/$ref",
            "narration/$ref",
            "catalog/audio/$ref",
            "audio/$ref",
        ).forEach { asset ->
            materializeAsset(asset)?.let { return it }
        }
        return resolveCommon(ref)
    }

    fun resolveCommon(key: String): File? {
        val normalized = key.trim().removeSuffix(".wav").removeSuffix(".ogg").removeSuffix(".mp3")
        val path = CommonVoicePack.pathFor(normalized) ?: return null
        return materializeAsset(path)
    }

    private fun materializeAsset(assetPath: String): File? {
        return try {
            val out = File(cacheDir, assetPath.replace('/', '_'))
            if (!out.exists() || out.length() == 0L) {
                context.assets.open(assetPath).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            out.takeIf { it.isFile && it.length() > 0L }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Shared Lumi phrases used across packages — avoid copying identical clips.
 * Quality: INTERIM_VOICE (semantic TTS) until FINAL_VOICE talent recordings.
 */
object CommonVoicePack {
    const val ASSET_ROOT = "narration/common"
    const val MANIFEST_ASSET = "narration/common/manifest.json"
    const val QUALITY_DEV_VOICE = "DEV_VOICE"
    const val QUALITY_INTERIM = "INTERIM_VOICE"
    const val QUALITY_PLACEHOLDER = "DEV_PLACEHOLDER"
    const val QUALITY_FINAL = "FINAL_VOICE"

    /** Logical phrase id → relative file under [ASSET_ROOT]. */
    val PHRASES: Map<String, String> = mapOf(
        "hello" to "hello.wav",
        "lets_start" to "lets_start.wav",
        "explore" to "explore.wav",
        "solve" to "solve.wav",
        "create" to "create.wav",
        "stories" to "stories.wav",
        "move" to "move.wav",
        "play" to "play.wav",
        "repeat_task" to "repeat_task.wav",
        "try_again" to "try_again.wav",
        "want_hint" to "want_hint.wav",
        "want_repeat" to "want_repeat.wav",
        "almost" to "almost.wav",
        "good_thought" to "good_thought.wav",
        "did_not_hear" to "did_not_hear.wav",
        "discuss_together" to "discuss_together.wav",
        "someone_answer" to "someone_answer.wav",
        "other_turn" to "other_turn.wav",
        "choose_together" to "choose_together.wav",
        "go_next" to "go_next.wav",
        "go_home" to "go_home.wav",
        "well_done" to "well_done.wav",
        "take_break" to "take_break.wav",
        "calm_celebrate" to "calm_celebrate.wav",
    )

    fun pathFor(key: String): String? {
        val file = PHRASES[key] ?: return null
        return "$ASSET_ROOT/$file"
    }

    fun allKeys(): Set<String> = PHRASES.keys

    fun spokenLabelForWorld(sectionId: String): String? = when (sectionId) {
        "explore", "mission" -> "explore"
        "solve", "play", "learn" -> "solve"
        "create" -> "create"
        "stories" -> "stories"
        "move" -> "move"
        else -> null
    }
}

/** Authoring / gate readiness for spoken coverage. */
enum class NarrationReadiness {
    NOT_WIRED,
    TECHNICALLY_WIRED,
    SEMANTIC_NARRATION_READY,
    PRODUCTION_READY,
}

object NarrationQuality {
    fun readinessFromMarker(quality: String?): NarrationReadiness = when (quality?.uppercase()) {
        "FINAL_VOICE", "PRODUCTION_READY" -> NarrationReadiness.PRODUCTION_READY
        "INTERIM_VOICE", "V1_CANDIDATE_VOICE", "DEV_VOICE", "SEMANTIC_NARRATION_READY" -> NarrationReadiness.SEMANTIC_NARRATION_READY
        "DEV_PLACEHOLDER", "PLACEHOLDER", "TECHNICALLY_WIRED" -> NarrationReadiness.TECHNICALLY_WIRED
        else -> NarrationReadiness.TECHNICALLY_WIRED
    }

    /** Release AUDIO_FIRST needs semantic speech, not beep wiring. */
    fun meetsAudioFirstRelease(readiness: NarrationReadiness): Boolean =
        readiness == NarrationReadiness.SEMANTIC_NARRATION_READY ||
            readiness == NarrationReadiness.PRODUCTION_READY
}
