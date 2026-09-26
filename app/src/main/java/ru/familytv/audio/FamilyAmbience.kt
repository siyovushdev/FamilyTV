package ru.familytv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional low-volume looped ambience (Space/Forest). Not required for understanding.
 * Ducks further when narration is active via [FamilySfx] ducking flag pattern.
 */
class FamilyAmbience(private val context: Context) {
    private val player = AtomicReference<MediaPlayer?>(null)

    fun play(assetName: String, volume: Float = 0.12f) {
        val prefs = context.getSharedPreferences("familytv_family_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("ambience_enabled", true)) return
        stop()
        try {
            val mp = MediaPlayer()
            context.assets.openFd("ambience/$assetName.wav").use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.isLooping = true
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            val v = if (FamilySfx.isNarrationDucked()) volume * 0.35f else volume
            mp.setVolume(v, v)
            mp.prepare()
            mp.start()
            player.set(mp)
        } catch (_: Exception) {
            // Missing ambience must never break adventures
        }
    }

    fun stop() {
        try {
            player.getAndSet(null)?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            player.set(null)
        }
    }
}
