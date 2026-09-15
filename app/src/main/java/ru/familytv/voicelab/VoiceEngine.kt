package ru.familytv.voicelab

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Native owners: capture worker owns VAD; the single decoder worker owns STT. */
internal class VoiceEngine(assets: AssetManager) : AutoCloseable {
    val vad: Vad
    private val recognizer: OfflineRecognizer
    val vadInitMs: Long
    val sttInitMs: Long

    init {
        var start = System.nanoTime()
        vad = Vad(assets, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "voice-lab/silero_vad.onnx",
                threshold = 0.5f,
                minSpeechDuration = 0.25f,
                minSilenceDuration = 0.5f,
                windowSize = WINDOW_SIZE,
                // The controller rejects continuous speech at 25 s, before sherpa's
                // adaptive endpoint threshold starts changing at this limit.
                maxSpeechDuration = 30f
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu"
        ))
        vadInitMs = elapsedMs(start)
        Log.i(TAG, "vadInitMs=$vadInitMs")
        start = System.nanoTime()
        try {
            val root = "voice-lab/ru-2024-09-18"
            recognizer = OfflineRecognizer(assets, OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "$root/encoder.int8.onnx",
                        decoder = "$root/decoder.int8.onnx",
                        joiner = "$root/joiner.int8.onnx"
                    ),
                    tokens = "$root/tokens.txt",
                    // Avoid constructing a temporary encoder session just to inspect its type.
                    modelType = "transducer",
                    numThreads = 4,
                    provider = "cpu"
                ),
                decodingMethod = "greedy_search"
            ))
        } catch (error: Throwable) {
            vad.release()
            throw error
        }
        sttInitMs = elapsedMs(start)
        Log.i(TAG, "sttInitMs=$sttInitMs recognizerReady threads=4 decoding=greedy_search")
    }

    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            // Only the completed VAD segment is passed to STT, never the microphone history.
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Called only after both workers have finished using native resources. */
    override fun close() {
        recognizer.release()
        vad.release()
        Log.i(TAG, "modelsReleased")
    }

    companion object {
        const val TAG = "FamilyVoiceLab"
        const val SAMPLE_RATE = 16000
        const val WINDOW_SIZE = 512
        fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000
    }
}
