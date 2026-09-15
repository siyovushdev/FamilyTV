package ru.familytv.voicelab.llm

import android.content.Context
import android.os.Looper
import android.os.Build
import android.util.Log

internal data class AnswerResult(val text: String, val latencyMs: Long,
    val promptTokens: Long, val generatedTokens: Long)

/** Construct, infer and close on the inference worker. cancel/ticket are safe from the UI thread. */
internal class QwenAnswerEngine(context: Context) : AutoCloseable {
    private var handle = 0L
    val initMs: Long
    val deploymentMs: Long
    init {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Qwen must initialize off the main thread" }
        val deployStart = System.nanoTime()
        val model = QwenModelStore.prepare(context)
        deploymentMs = (System.nanoTime() - deployStart) / 1_000_000
        val started = System.nanoTime()
        handle = NativeQwen.create(context.applicationInfo.nativeLibraryDir, model.absolutePath)
        check(handle != 0L) { "Native Qwen initialization failed" }
        try {
            NativeQwen.configureCpu(handle, defaultCpuStrategy)
            NativeQwen.preparePrefix(handle, AnswerPrompt.prefix.toByteArray(Charsets.UTF_8), true)
        } catch (error: Throwable) {
            NativeQwen.destroy(handle)
            handle = 0L
            throw error
        }
        initMs = (System.nanoTime() - started) / 1_000_000
        Log.i("FamilyVoiceLab", "llmInitMs=$initMs modelDeploymentMs=$deploymentMs thinking=false greedy=true warmCpuStrategy=$defaultCpuStrategy")
    }

    @Synchronized fun ticket(): Long = NativeQwen.ticket(checkHandle())
    @Synchronized fun cancel() { if (handle != 0L) NativeQwen.cancel(handle) }

    fun extract(sttText: String, ticket: Long = ticket()): AnswerResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Qwen inference on main thread is forbidden" }
        val prompt = AnswerPrompt.suffix(sttText)
        val pointer = checkHandle()
        val started = System.nanoTime()
        val raw = NativeQwen.generate(pointer, prompt.toByteArray(Charsets.UTF_8), 16, ticket)
            .toString(Charsets.UTF_8)
        val metrics = NativeQwen.metrics(pointer)
        val answer = AnswerPrompt.validate(raw)
        return AnswerResult(answer, (System.nanoTime() - started) / 1_000_000, metrics[0], metrics[1])
    }

    companion object {
        // H728 measurements: short suffix prefill on 8 mixed-frequency cores has long tails.
        // Preserve the generic scheduler on other SoCs; native code also checks the allowed mask.
        val defaultCpuStrategy: Int get() = if (Build.HARDWARE == "sun55iw3p1") 2 else 0
    }

    private fun checkHandle(): Long = handle.also { check(it != 0L) { "Qwen engine closed" } }
    @Synchronized override fun close() {
        if (handle != 0L) {
            NativeQwen.destroy(handle)
            handle = 0L
            Log.i("FamilyVoiceLab", "qwenReleased")
        }
    }
}
