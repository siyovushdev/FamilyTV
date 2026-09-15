package ru.familytv.voicelab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.familytv.voice.VoiceRuntime
import ru.familytv.voice.VoiceAnswerPipeline

/** Isolated lab: launched explicitly by adb, without changing the FamilyTV launcher. */
class VoiceLabActivity : ComponentActivity() {
    private lateinit var pipeline: VoiceAnswerPipeline
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var result: TextView
    private lateinit var llmResult: TextView
    private lateinit var metrics: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var visible = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && visible) pipeline.startListening()
        else if (!granted) pipeline.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
        }
        fun label(size: Float) = TextView(this).apply {
            textSize = size
            content.addView(this)
        }
        label(24f).text = "FamilyTV — Voice Lab"
        label(14f).text = "Микрофон → Silero VAD → Russian STT → Qwen3-0.6B (CPU)"
        status = label(22f)
        detail = label(16f)
        val buttons = LinearLayout(this)
        content.addView(buttons)
        fun button(title: String, action: () -> Unit) = Button(this).apply {
            text = title
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        start = button("Start listening") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) pipeline.startListening()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
        stop = button("Stop") { pipeline.stopListening() }
        button("Clear") { pipeline.clear() }
        result = label(24f).apply { setTextIsSelectable(true) }
        llmResult = label(24f).apply { setTextIsSelectable(true) }
        metrics = label(16f)
        label(14f).text = "Пример: «мне кажется пять» → 5. Пауза ≥ 0.5 с завершает фразу.\n" +
            "Stop/уход с экрана отменяет незавершённую фразу и ожидающие результаты.\n" +
            "Модели общие с играми и сохраняются в процессе приложения. Непрерывная речь ограничена 25 секундами."
        setContentView(ScrollView(this).apply { addView(content) })
        val runtime = VoiceRuntime.get(this)
        pipeline = runtime.newPipeline(singleAnswer = false, requireUsb = false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { runtime.diagnostics.collect(::render) }
        }
        start.requestFocus()
    }

    private fun render(state: VoiceLabState) {
        status.text = "STATUS: ${state.status}"
        detail.text = state.detail
        result.text = "STT: ${state.text.ifBlank { "—" }}"
        llmResult.text = "LLM: ${state.llmText.ifBlank { "—" }}"
        fun Long?.ms() = this?.let { "$it ms" } ?: "—"
        val inputLevel = if (state.peakPcm16 == 0) "Нулевой PCM — проверьте подключение/активацию микрофона"
            else "peak=${state.peakPcm16 ?: "—"}, RMS=${state.rmsPcm16 ?: "—"} (PCM16)"
        metrics.text = "MIC: ${state.microphone}\n$inputLevel\nVAD: ${state.vadStatus}\n" +
            "Utterance: ${state.utterance}\n" +
            "Speech: ${state.speechDurationMs.ms()} | STT: ${state.sttLatencyMs.ms()}\n" +
            "LLM latency: ${state.llmLatencyMs.ms()} | tokens: ${state.promptTokens ?: "—"} / ${state.generatedTokens ?: "—"}\n" +
            "TOTAL speech end → LLM: ${state.totalPostSpeechLatencyMs.ms()}\n" +
            "End → STT: ${state.endToTextMs.ms()} | VAD endpoint → STT: ${state.endpointToTextMs.ms()}\n" +
            "Timestamp: ${state.timestampSource.ifBlank { "—" }}\n" +
            "Init VAD: ${state.vadInitMs.ms()} | STT: ${state.sttInitMs.ms()} | Qwen: ${state.llmInitMs.ms()}"
        start.isEnabled = state.ready && !state.active && !state.captureBusy
        stop.isEnabled = state.active
        if (state.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        pipeline.activate()
    }

    override fun onStop() {
        visible = false
        pipeline.stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        pipeline.close()
        super.onDestroy()
    }
}
