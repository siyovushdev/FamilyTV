package ru.familytv.ui

import android.view.View
import android.view.animation.DecelerateInterpolator

/** Small reusable transitions — not an animation framework. */
object SceneTransitions {
    fun fadeIn(view: View, durationMs: Long = 280) {
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun fadeSwap(view: View, durationMs: Long = 200, onMid: () -> Unit) {
        view.animate()
            .alpha(0.35f)
            .setDuration(durationMs / 2)
            .withEndAction {
                onMid()
                view.animate()
                    .alpha(1f)
                    .setDuration(durationMs / 2)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    fun portalEnter(view: View) {
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
