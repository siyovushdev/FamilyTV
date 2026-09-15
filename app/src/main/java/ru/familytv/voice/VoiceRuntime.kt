package ru.familytv.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.familytv.voicelab.VoiceLabController
import ru.familytv.voicelab.VoiceLabState
import ru.familytv.voicelab.VoiceStatus

/** One stack per process, created lazily on entering the games area or VoiceLab.
 * Only application Context is retained. Main-thread commands serialize ownership/retry.
 * Activity destruction releases a lease, not the models. No background recording.
 */
internal class VoiceRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val _readiness = MutableStateFlow(AiReadiness.AI_INITIALIZING)
    val readiness: StateFlow<AiReadiness> = _readiness.asStateFlow()
    private val _diagnostics = MutableStateFlow(VoiceLabState())
    val diagnostics: StateFlow<VoiceLabState> = _diagnostics.asStateFlow()
    private var backend: VoiceLabController? = null
    private var releasing = false
    private var owner: VoiceAnswerPipeline? = null
    var initializationCount = 0
        private set

    init {
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (owner?.requireUsb == true && !hasUsbMicrophone()) {
                    backend?.microphoneRemoved()
                    owner?.inputMissing()
                }
            }
        }, main)
    }

    fun prewarm() {
        checkMain()
        if (backend != null || releasing) return
        _readiness.value = AiReadiness.AI_INITIALIZING
        initializationCount++
        Log.i("FamilyVoiceGame", "runtimeInitialization count=$initializationCount")
        backend = VoiceLabController(app) { snapshot ->
            // Drop queued snapshots from an earlier listening generation/owner.
            if (snapshot.session != backend?.currentSession) return@VoiceLabController
            _diagnostics.value = snapshot
            _readiness.value = when {
                snapshot.problem == VoiceProblem.INITIALIZATION -> AiReadiness.AI_ERROR
                snapshot.ready -> AiReadiness.AI_READY
                else -> AiReadiness.AI_INITIALIZING
            }
            owner?.accept(snapshot)
        }
    }

    fun newPipeline(singleAnswer: Boolean = true, requireUsb: Boolean =
        Build.HARDWARE == "sun55iw3p1"): VoiceAnswerPipeline {
        checkMain()
        prewarm()
        return VoiceAnswerPipeline(this, singleAnswer, requireUsb)
    }

    internal fun acquire(client: VoiceAnswerPipeline) {
        checkMain()
        prewarm()
        if (releasing) {
            owner?.takeIf { it !== client }?.revoked()
            owner = client
            client.initializing()
            return
        }
        if (owner !== client) {
            owner?.revoked()
            backend?.stop()
            backend?.clear()
            owner = client
        }
        val snapshot = backend?.currentState ?: VoiceLabState()
        client.accept(snapshot.copy(session = backend?.currentSession ?: 0,
            status = VoiceStatus.IDLE, text = "", llmText = "",
            active = false,
            problem = snapshot.problem.takeIf { it == VoiceProblem.INITIALIZATION }))
    }

    internal fun start(client: VoiceAnswerPipeline): Long {
        checkMain()
        if (owner !== client) acquire(client)
        if (client.requireUsb && !hasUsbMicrophone()) {
            client.inputMissing()
            return -1
        }
        backend?.clear()
        backend?.start(client.singleAnswer, client.requireUsb)
        return backend?.currentSession ?: -1
    }

    internal fun stop(client: VoiceAnswerPipeline, detach: Boolean) {
        checkMain()
        if (owner !== client) return
        backend?.stop()
        if (detach) owner = null
    }

    fun retryInitialization() {
        checkMain()
        if (readiness.value != AiReadiness.AI_ERROR || releasing) return
        owner?.initializing()
        releaseBackend { prewarm() }
    }

    /** Diagnostic/test teardown: no reload can start until both native owners are freed. */
    fun releaseIdle(onReleased: () -> Unit = {}) {
        checkMain()
        check(owner == null) { "A foreground voice session still owns the runtime" }
        releaseBackend(onReleased)
    }

    private fun releaseBackend(onReleased: () -> Unit) {
        if (releasing) return
        val previous = backend
        if (previous == null) { onReleased(); return }
        releasing = true
        _readiness.value = AiReadiness.AI_INITIALIZING
        previous.close {
            backend = null
            releasing = false
            _diagnostics.value = VoiceLabState()
            Log.i("FamilyVoiceGame", "runtimeReleased")
            onReleased()
        }
    }

    internal fun clear(client: VoiceAnswerPipeline) { if (owner === client) backend?.clear() }
    internal fun permissionDenied(client: VoiceAnswerPipeline) { if (owner === client) backend?.permissionDenied() }
    internal fun isOwner(client: VoiceAnswerPipeline) = owner === client
    internal fun hasUsbMicrophone() = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Voice runtime commands require main thread" }

    companion object {
        @Volatile private var instance: VoiceRuntime? = null
        fun get(context: Context): VoiceRuntime = instance ?: synchronized(this) {
            instance ?: VoiceRuntime(context.applicationContext).also { instance = it }
        }
    }
}
