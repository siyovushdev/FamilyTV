package ru.familytv.accuracy

import android.content.Context
import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import ru.familytv.voicelab.llm.AnswerPrompt
import ru.familytv.voicelab.llm.NativeQwen
import ru.familytv.voicelab.llm.QwenAnswerEngine
import ru.familytv.voicelab.llm.QwenModelStore
import java.io.File
import java.security.MessageDigest

internal class LabAsr(context: Context, experiment: AsrExperiment) : AutoCloseable {
    private val root = "voice-lab/ru-2024-09-18"
    private val recognizer = OfflineRecognizer(context.assets, OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig("$root/encoder.int8.onnx", "$root/decoder.int8.onnx", "$root/joiner.int8.onnx"),
            tokens = "$root/tokens.txt", modelType = "transducer", numThreads = 4, provider = "cpu",
            modelingUnit = if (experiment.score != null) "bpe" else "",
            bpeVocab = if (experiment.score != null) "accuracy/bpe.vocab" else ""),
        decodingMethod = experiment.decoding, maxActivePaths = 4, hotwordsScore = experiment.score ?: 1.5f))
    private val contextual = experiment.score != null
    fun decode(samples: FloatArray, domain: AnswerDomain): OfflineRecognizerResult {
        val stream = if (contextual && domain.words.isNotEmpty()) recognizer.createStream(domain.hotwords()) else recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000); recognizer.decode(stream)
            return recognizer.getResult(stream)
        } finally { stream.release() }
    }
    override fun close() = recognizer.release()
}

internal class LabVad(context: Context) : AutoCloseable {
    private val vad = Vad(context.assets, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = "voice-lab/silero_vad.onnx", threshold = .5f,
            minSpeechDuration = .25f, minSilenceDuration = .5f, windowSize = 512, maxSpeechDuration = 30f),
        sampleRate = 16000, numThreads = 1, provider = "cpu"))
    fun segments(wave: Wave): List<FloatArray> {
        vad.reset()
        val input = wave.floats() + FloatArray(16000) // Same fixed EOF silence for every experiment; never force flush.
        val output = mutableListOf<FloatArray>()
        var position = 0
        while (position < input.size) {
            val block = FloatArray(512)
            input.copyInto(block, 0, position, minOf(input.size, position + 512))
            vad.acceptWaveform(block)
            while (!vad.empty()) { output += vad.front().samples.copyOf(); vad.pop() }
            position += 512
        }
        return output
    }
    override fun close() = vad.release()
}

/** One Qwen context, loaded after all ASR owners close. Fixed prefix per mode/domain group.
 * Production Qwen classes, prompt and cache implementation remain untouched.
 */
internal class LabQwen(context: Context) : AutoCloseable {
    private val handle = NativeQwen.create(context.applicationInfo.nativeLibraryDir, QwenModelStore.prepare(context).path)
    init { NativeQwen.configureCpu(handle, QwenAnswerEngine.defaultCpuStrategy) }
    fun prefix(domain: AnswerDomain?, domainMode: Boolean) {
        val prefix = if (domainMode) "<|im_start|>system\n${AnswerPolicy.domainInstruction(checkNotNull(domain))}<|im_end|>\n"
            else AnswerPrompt.prefix
        NativeQwen.preparePrefix(handle, prefix.toByteArray(), true)
    }
    fun normalize(transcript: String): String = AnswerPrompt.validate(NativeQwen.generate(handle,
        AnswerPrompt.suffix(transcript).toByteArray(), 16, NativeQwen.ticket(handle)).toString(Charsets.UTF_8))
    override fun close() = NativeQwen.destroy(handle)
}

internal object AccuracyRunner {
    fun sha(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun ms(start: Long) = (System.nanoTime() - start) / 1e6
    fun run(context: Context, dataset: File, output: File, qwen: Boolean = false,
        corpusKind: String = "human", cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}): List<JSONObject> {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val samples = AccuracyDataset.load(dataset)
        // Preflight every input before loading native models or scoring partial datasets.
        samples.forEach { WaveFiles.read(it.audio(dataset)) }
        val hashes = samples.associate { it.id to sha(it.audio(dataset)) }
        val modelHashes = JSONObject()
        val assetsToHash = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            .map { "voice-lab/ru-2024-09-18/$it" } + listOf("voice-lab/silero_vad.onnx", "accuracy/bpe.vocab") +
            if (qwen) listOf("voice-lab/llm/Qwen3-0.6B-Q4_K_M.gguf") else emptyList()
        for (asset in assetsToHash) {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(asset).use { input ->
                val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) }
            }
            modelHashes.put(asset, digest.digest().joinToString("") { "%02x".format(it) })
        }
        val metadata = JSONObject().put("schemaVersion", 1).put("policyVersion", "lexical-evidence-v1")
            .put("datasetSha256", sha(File(dataset, "manifest.jsonl"))).put("audioSha256", JSONObject(hashes))
            .put("modelSha256", modelHashes).put("nativeSherpaSha256", sha(File(context.applicationInfo.nativeLibraryDir, "libsherpa-onnx-jni.so")))
            .put("device", Build.MODEL).put("hardware", Build.HARDWARE).put("android", Build.VERSION.RELEASE)
            .put("sampleCount", samples.size).put("corpusKind", corpusKind).put("qwenEnabled", qwen)
            .put("vadTailPadMs", 1000).put("vadThreshold", .5).put("vadMinSpeechMs", 250).put("vadSilenceMs", 500)
            .put("asrThreads", 4).put("maxActivePaths", 4).put("confidence", "unavailable_in_checked_in_Kotlin_JNI")
            .put("startedAtEpochMs", System.currentTimeMillis()).put("configOrder", JSONArray(AsrExperiment.matrix.map { it.id }))
        val rows = mutableListOf<JSONObject>(); val initialization = JSONObject()
        metadata.put("initializationMs", initialization)
        fun checkpoint() = AccuracyMetrics.write(output, rows, metadata)
        try {
            if (samples.isNotEmpty()) LabVad(context).use { vad ->
                for (experiment in AsrExperiment.matrix) {
                    if (cancelled()) break
                    progress("Загрузка ${experiment.id}")
                    val start = System.nanoTime()
                    LabAsr(context, experiment).use { asr ->
                        initialization.put(experiment.id, ms(start))
                        for ((index, sample) in samples.withIndex()) {
                            if (cancelled()) break
                            progress("${experiment.id}: ${index + 1}/${samples.size} ${sample.id}")
                            val wave = WaveFiles.read(sample.audio(dataset)); val t = System.nanoTime()
                            val segments = vad.segments(wave); val vadMs = ms(t)
                            val sttStart = System.nanoTime()
                            var failure: String? = null
                            val tokens = JSONArray(); val timestamps = JSONArray()
                            val text = try { segments.joinToString(" ") {
                                val r = asr.decode(it, sample.domain)
                                tokens.put(JSONArray(r.tokens.toList())); timestamps.put(JSONArray(r.timestamps.toList()))
                                r.text.trim()
                            }.trim() } catch (e: Exception) { failure = e.toString(); "" }
                            val elapsed = ms(sttStart)
                            val errors = AccuracyMetrics.errors(sample.truth, text)
                            val words = AccuracyMetrics.words(sample.truth).size
                            val literal = if (segments.isEmpty()) AnswerRecognitionResult.Unknown("vad_no_speech") else AnswerPolicy.literal(text, sample.domain)
                            rows += JSONObject().put("config", experiment.id).put("decoding", experiment.decoding)
                                .put("hotwordsScore", experiment.score ?: JSONObject.NULL).put("hotwords", if (experiment.score != null) sample.domain.hotwords() else "")
                                .put("id", sample.id).put("file", sample.file).put("truth", sample.truth).put("category", sample.category)
                                .put("domain", sample.domain.name).put("metadata", sample.metadata)
                                .put("shortGroup", when {
                                    AccuracyMetrics.words(sample.truth).size == 1 && sample.domain == AnswerDomain.NUMBER_0_10 -> "single_number"
                                    AccuracyMetrics.words(sample.truth).size == 1 && sample.domain == AnswerDomain.COLOR -> "single_color"
                                    else -> "full_phrase_or_control"
                                }).put("rawStt", text).put("tokens", tokens).put("segmentRelativeTimestamps", timestamps)
                                .put("exact", failure == null && clean(sample.truth) == clean(text)).put("sttMs", elapsed)
                                .put("asrFirstAfterInit", index == 0).put("vadMs", vadMs).put("vadSegments", segments.size).put("audioDurationMs", wave.durationMs)
                                .put("rtf", elapsed / wave.durationMs).put("peakPcm16", wave.peak).put("rmsPcm16", wave.rms).put("clippedFraction", wave.clippedFraction)
                                .put("answerTruth", sample.answerTruth ?: JSONObject.NULL).put("literalAnswer", AnswerPolicy.value(literal))
                                .put("wordErrors", errors.total).put("wer", if (words > 0) errors.total.toDouble() / words else JSONObject.NULL)
                                .put("substitutions", errors.substitutions).put("deletions", errors.deletions).put("insertions", errors.insertions)
                                .put("error", failure ?: JSONObject.NULL)
                        }
                    }
                    checkpoint()
                }
            }
            if (qwen && rows.isNotEmpty() && !cancelled()) {
                progress("Загрузка Qwen; ASR уже выгружен")
                val start = System.nanoTime()
                LabQwen(context).use { normalizer ->
                    initialization.put("qwenModel", ms(start))
                    for (domainMode in listOf(false, true)) {
                        val key = if (domainMode) "domainNormalized" else "normalized"
                        val reasonKey = if (domainMode) "domainUnknownReason" else "unknownReason"
                        val timeKey = if (domainMode) "domainQwenMs" else "qwenMs"
                        val groups = if (domainMode) rows.groupBy { it.getString("domain") } else mapOf("all" to rows)
                        for ((domainName, group) in groups) {
                            if (cancelled()) break
                            val started = System.nanoTime()
                            normalizer.prefix(if (domainMode) AnswerDomain.valueOf(domainName) else null, domainMode)
                            initialization.put("prefix_${key}_$domainName", ms(started))
                            for (row in group) {
                                if (cancelled()) break
                                val text = row.getString("rawStt"); val domain = AnswerDomain.valueOf(row.getString("domain"))
                                progress("$key: ${row.getString("config")} ${row.getString("id")}")
                                var result: AnswerRecognitionResult = AnswerRecognitionResult.Unknown("no_domain_evidence")
                                if (row.isNull("error") && row.getInt("vadSegments") > 0 && AnswerPolicy.mayNormalize(text, domain)) {
                                    val t = System.nanoTime()
                                    try {
                                        val raw = normalizer.normalize(text)
                                        row.put("${key}RawOutput", raw)
                                        result = AnswerPolicy.validate(text, raw, domain)
                                    } catch (e: Exception) { result = AnswerRecognitionResult.Unknown("normalizer_error: ${e.message}") }
                                    row.put(timeKey, ms(t))
                                }
                                row.put(key, AnswerPolicy.value(result))
                                row.put(reasonKey, (result as? AnswerRecognitionResult.Unknown)?.reason ?: JSONObject.NULL)
                            }
                        }
                    }
                }
            }
            metadata.put("cancelled", cancelled()).put("finishedAtEpochMs", System.currentTimeMillis())
        } catch (error: Exception) {
            metadata.put("runError", error.toString())
            throw error
        } finally { checkpoint() }
        Log.i("FamilyAccuracyLab", "runCompleted samples=${samples.size} rows=${rows.size} output=$output")
        return rows
    }
}
