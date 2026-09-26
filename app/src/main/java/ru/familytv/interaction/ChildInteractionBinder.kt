package ru.familytv.interaction

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.familytv.content.PlatformServices
import ru.familytv.voice.VoiceAnswerPipeline
import ru.familytv.voice.VoicePhase
import ru.familytv.voice.VoiceRuntime

/**
 * Shared binder so Interactive / Games / Session / Video / Adventure
 * attach the same FamilyAction + VoiceCommandRouter path.
 */
class ChildInteractionBinder(
    private val activity: Activity,
    private val surface: InteractionSurface,
    private val handler: FamilyActionHandler,
    private val awaitingAnswer: () -> Boolean = { false },
    private val allowNext: () -> Boolean = { false },
    private val catalog: () -> List<ru.familytv.content.ContentItem> = { emptyList() },
) {
    private val wrapped = FamilyActionHandler { source, action -> handler.handle(source, action) }
    private var pipeline: VoiceAnswerPipeline? = null
    private var collectJob: Job? = null

    fun attach() {
        PlatformServices.actionDispatcher().attach(wrapped)
    }

    fun detach() {
        PlatformServices.actionDispatcher().detach(wrapped)
        pipeline?.close()
        pipeline = null
        collectJob?.cancel()
        collectJob = null
    }

    fun dispatchRemote(action: FamilyAction) {
        PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, action)
    }

    fun routeTranscript(normalized: String): Boolean {
        val ctx = RouteContext(
            surface = surface,
            awaitingAnswer = awaitingAnswer(),
            allowNext = allowNext(),
            catalog = catalog(),
        )
        return when (val routed = VoiceCommandRouter.route(ctx, normalized.lowercase().trim())) {
            is RouteResult.Action -> handler.handle(InputSource.VOICE, routed.action)
            RouteResult.Unhandled -> {
                if (awaitingAnswer()) {
                    handler.handle(InputSource.VOICE, FamilyAction.Answer(normalized.lowercase().trim()))
                } else {
                    false
                }
            }
        }
    }

    fun ensureVoiceCollect(owner: LifecycleOwner) {
        if (pipeline != null) return
        try {
            pipeline = VoiceRuntime.get(activity).newPipeline()
        } catch (_: Exception) {
            return
        }
        collectJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                pipeline?.state?.collect { voice ->
                    when (voice.phase) {
                        VoicePhase.RESULT -> voice.answer?.let { answer ->
                            pipeline?.stopListening()
                            routeTranscript(answer.normalizedText)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    fun startListening() {
        ensureVoiceCollect(activity as LifecycleOwner)
        pipeline?.activate()
        pipeline?.startListening()
    }
}
