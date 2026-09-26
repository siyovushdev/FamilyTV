package ru.familytv.narration

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Speaks prompts for children who cannot comfortably read TV text.
 * Priority: package audio file → optional offline TTS → text-only (caller shows UI).
 */
interface NarrationProvider {
    fun speakText(text: String)
    fun playFile(file: File?): Boolean
    fun stop()
    fun release()
}

class FamilyNarrationProvider(
    context: Context,
    private val enabled: () -> Boolean,
) : NarrationProvider {
    private val app = context.applicationContext
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        try {
            tts = TextToSpeech(app) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale("ru", "RU")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS unavailable: ${e.message}")
        }
    }

    override fun speakText(text: String) {
        if (!enabled() || text.isBlank()) return
        stop()
        val engine = tts
        if (ttsReady && engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ftv-narration")
        }
    }

    override fun playFile(file: File?): Boolean {
        if (!enabled() || file == null || !file.isFile) return false
        return try {
            stop()
            ru.familytv.audio.FamilySfx.setNarrationDucking(true)
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    ru.familytv.audio.FamilySfx.setNarrationDucking(false)
                    it.release()
                }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            ru.familytv.audio.FamilySfx.setNarrationDucking(false)
            Log.w(TAG, "Audio play failed: ${e.message}")
            false
        }
    }

    override fun stop() {
        ru.familytv.audio.FamilySfx.setNarrationDucking(false)
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        tts?.stop()
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    companion object {
        private const val TAG = "FamilyNarration"
    }
}

object NoOpNarration : NarrationProvider {
    override fun speakText(text: String) = Unit
    override fun playFile(file: File?): Boolean = false
    override fun stop() = Unit
    override fun release() = Unit
}
