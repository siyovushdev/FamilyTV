package ru.familytv.accuracy

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.familytv.voice.VoiceRuntime
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AccuracyLabInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun exclusive(block: () -> Unit) {
        val ready = CountDownLatch(1)
        var release: (() -> Unit)? = null
        instrumentation.runOnMainSync { release = VoiceRuntime.get(context).reserveForDiagnostics { ready.countDown() } }
        try { check(ready.await(90, TimeUnit.SECONDS)); block() }
        finally { instrumentation.runOnMainSync { release?.invoke() } }
    }
    @Test fun benchmarkDataset() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("accuracyDataset")
        assumeTrue("Pass -e accuracyDataset ABSOLUTE_DATASET_DIRECTORY", path != null)
        exclusive {
            val root = File(checkNotNull(path))
            val output = args.getString("accuracyOutput")?.let(::File) ?: File(root, "runs/${System.currentTimeMillis()}")
            AccuracyRunner.run(context, root, output, args.getString("accuracyQwen", "false").toBoolean(),
                corpusKind = args.getString("accuracyCorpusKind", "human"))
            assertTrue(File(output, "summary.md").exists())
        }
    }
    @Test fun sixNativeConfigurationsDecodeFixtureAndSilence() = exclusive {
        val root = File(context.cacheDir, "accuracy-smoke").apply { mkdirs() }
        val audio = File(root, "audio").apply { mkdirs() }
        instrumentation.context.assets.open("voice-lab/1.wav").use { input -> File(audio, "fixture.wav").outputStream().use(input::copyTo) }
        WaveFiles.write(File(audio, "silence.wav"), ShortArray(16000))
        val truth = "родион потапыч высчитывал каждый новый вершок углубления и давно определил про себя"
        val entries = listOf(
            JSONObject().put("id", "fixture_number_negative").put("file", "audio/fixture.wav").put("truth", truth)
                .put("category", "control").put("domain", "NUMBER_0_10").put("answerTruth", "UNKNOWN"),
            JSONObject().put("id", "fixture_color_negative").put("file", "audio/fixture.wav").put("truth", truth)
                .put("category", "control").put("domain", "COLOR").put("answerTruth", "UNKNOWN"),
            JSONObject().put("id", "silence").put("file", "audio/silence.wav").put("truth", "")
                .put("category", "control").put("domain", "NUMBER_0_10").put("answerTruth", "UNKNOWN"))
        File(root, "manifest.jsonl").writeText(entries.joinToString("\n") + "\n")
        val output = File(context.getExternalFilesDir(null), "accuracy-smoke-results")
        val rows = AccuracyRunner.run(context, root, output, corpusKind = "fixture_smoke_not_human_accuracy")
        assertEquals(18, rows.size)
        assertTrue(rows.all { it.isNull("error") })
        assertTrue(rows.filter { it.getString("id") == "silence" }.all { it.getString("rawStt").isEmpty() && it.getInt("vadSegments") == 0 })
        assertTrue(rows.filter { it.getString("config") == "A_greedy" }.all { it.getBoolean("exact") })
        assertTrue(rows.filter { it.getString("id").startsWith("fixture") }.all { it.getInt("vadSegments") > 0 })
        assertTrue(rows.filter { it.getString("config").startsWith("C_") && it.getString("domain") == "COLOR" }
            .all { it.getString("hotwords").split('/').size == 4 })
    }
    @Test fun realQwenVariantsStayInsideTranscriptEvidence() = exclusive {
        val results = JSONArray()
        LabQwen(context).use { qwen ->
            for (domainMode in listOf(false, true)) for (domain in listOf(AnswerDomain.NUMBER_0_10, AnswerDomain.COLOR)) {
                qwen.prefix(domain, domainMode)
                val cases = if (domain == AnswerDomain.NUMBER_0_10) listOf("мне кажется пят", "не пят а шесть", "четыре", "честь", "ты")
                    else listOf("я выбираю синий", "цвет зелёный", "честь", "ты")
                for (speech in cases) {
                    val raw = if (AnswerPolicy.mayNormalize(speech, domain)) qwen.normalize(speech) else "UNKNOWN"
                    val result = AnswerPolicy.validate(speech, raw, domain)
                    val value = AnswerPolicy.value(result)
                    assertTrue(value == "UNKNOWN" || value in AnswerPolicy.evidence(speech, domain))
                    if (speech in listOf("честь", "ты")) assertEquals("UNKNOWN", value)
                    if (!domainMode && speech == "четыре") assertEquals("4", value)
                    results.put(JSONObject().put("domainMode", domainMode).put("domain", domain.name)
                        .put("transcript", speech).put("qwenInvoked", AnswerPolicy.mayNormalize(speech, domain))
                        .put("modelOutput", if (AnswerPolicy.mayNormalize(speech, domain)) raw else JSONObject.NULL).put("accepted", value))
                }
            }
        }
        File(context.getExternalFilesDir(null), "accuracy-qwen-text-smoke.json").writeText(results.toString(2))
    }
}
