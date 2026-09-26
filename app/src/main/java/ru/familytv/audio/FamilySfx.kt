package ru.familytv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tiny offline SFX player — lazy, one-shot, no bulk preload.
 * Assets under `assets/sfx/`. Parent can disable via shared prefs later.
 */
class FamilySfx(private val context: Context) {
    private val player = AtomicReference<MediaPlayer?>(null)

    fun play(name: String) {
        val prefs = context.getSharedPreferences("familytv_family_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sfx_enabled", true)) return
        try {
            player.getAndSet(null)?.release()
            val mp = MediaPlayer()
            context.assets.openFd("sfx/$name.wav").use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            val vol = if (ducked.get()) 0.25f else 1f
            mp.setVolume(vol, vol)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
            player.set(mp)
        } catch (_: Exception) {
            // Missing SFX must never crash child flows
        }
    }

    fun select() = play("select")
    fun focus() = play("focus")
    fun confirm() = play("confirm")
    fun softWrong() = play("soft_wrong")
    fun hint() = play("hint")
    fun objectiveComplete() = play("objective_complete")
    fun sceneTransition() = play("scene_transition")
    fun lumiAppear() = play("lumi_appear")
    fun movementStart() = play("movement_start")
    fun movementEnd() = play("movement_end")

    fun release() {
        player.getAndSet(null)?.release()
    }

    companion object {
        /** While narration speaks, duck SFX so speech stays intelligible. */
        private val ducked = AtomicBoolean(false)
        fun setNarrationDucking(active: Boolean) {
            ducked.set(active)
        }
        fun isNarrationDucked(): Boolean = ducked.get()
    }
}
