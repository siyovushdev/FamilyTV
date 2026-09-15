package ru.familytv.voicelab

import android.util.Log
import ru.familytv.voice.VoiceRuntime
import ru.familytv.voice.VoiceAnswerPipeline
import kotlin.math.ceil
import android.content.Intent
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.voicelab.llm.AnswerPrompt
import ru.familytv.voicelab.llm.QwenAnswerEngine
import ru.familytv.voicelab.llm.NativeQwen
import ru.familytv.voicelab.llm.QwenModelStore
import java.io.File

/** Device benchmark: real JNI, independent requests, exact semantic assertions. */
class QwenLatencyInstrumentedTest {
    @Test fun consecutiveRequests() {
        RuntimeTestSupport.releaseIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val cache = args.getString("cachePrefix", "true").toBoolean()
        val candidate = args.getString("promptCandidate", "original")
        fun omitExample(prefix: String, input: String, output: String) = prefix.replace(
            "<|im_start|>user\n$input<|im_end|>\n<|im_start|>assistant\n$output<|im_end|>\n", "")
        val fourExamples = omitExample(omitExample(AnswerPrompt.prefix, "один", "1"), "думаю это два", "2")
        val prefix = when (candidate) {
            "four" -> fourExamples
            "three" -> omitExample(fourExamples, "не три а четыре", "4")
            "short" -> "<|im_start|>system\nИзвлеки ответ ребёнка, исправь явные ошибки распознавания. Числа цифрами. Бери последний вариант после исправления. Не решай задания. Только ответ.<|im_end|>\n"
            "correction" -> "<|im_start|>system\nИзвлеки ответ ребёнка. Числа цифрами. Не решай задания. Бери ответ после последнего нет. Только ответ.<|im_end|>\n" +
                "<|im_start|>user\nсначала пять нет шесть<|im_end|>\n<|im_start|>assistant\n6<|im_end|>\n" +
                "<|im_start|>user\nпят<|im_end|>\n<|im_start|>assistant\n5<|im_end|>\n"
            else -> AnswerPrompt.prefix
        }
        val cases = listOf("пят" to "5", "мне кажется пят" to "5", "не пят а шесть" to "6",
            "сначала семь нет восемь" to "8", "не знаю" to "не знаю", "я выбираю синий" to "синий",
            "четыре" to "4", "сколько будет два плюс три мой ответ четыре" to "4",
            "думаю это пять" to "5", "не пять а шесть" to "6", "мне кажется пять" to "5",
            "сначала три нет девять" to "9", "я не знаю" to "не знаю", "я выбираю красный" to "красный",
            "я игра синий" to "синий", "синий" to "синий", "красный" to "красный",
            "это наверное зелёный" to "зелёный", "жёлтый" to "жёлтый", "зелёный" to "зелёный")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activity: VoiceLabActivity? = null
        var worker: ExecutorService? = null
        fun field(instance: Any, name: String): Any = instance.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(instance)!!
        val withCapture = args.getString("withCapture", "false").toBoolean()
        val handle = if (withCapture) {
            activity = instrumentation.startActivitySync(Intent(context, VoiceLabActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VoiceLabActivity
            val controller = field(VoiceRuntime.get(context), "backend")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync { ready = field(field(controller, "state"), "ready") as Boolean }
                if (!ready) Thread.sleep(50)
            }
            check(ready) { "VoiceLab models did not initialize" }
            instrumentation.runOnMainSync { (field(activity, "pipeline") as VoiceAnswerPipeline).startListening() }
            Thread.sleep(1000)
            worker = field(controller, "decoder") as ExecutorService
            field(field(controller, "qwen"), "handle") as Long
        } else NativeQwen.create(context.applicationInfo.nativeLibraryDir, QwenModelStore.prepare(context).path)
        fun <T> infer(block: () -> T): T = worker?.submit(Callable { block() })?.get(30, TimeUnit.SECONDS) ?: block()
        val latencies = mutableListOf<Double>()
        val failures = mutableListOf<String>()
        fun sensors(): String {
            fun read(path: String) = runCatching { File(path).readText().trim() }.getOrDefault("unavailable")
            return "freq0=${read("/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq")} " +
                "freq4=${read("/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq")} " +
                "temp0=${read("/sys/class/thermal/thermal_zone0/temp")} temp1=${read("/sys/class/thermal/thermal_zone1/temp")}"
        }
        try {
            infer { NativeQwen.configureCpu(handle, args.getString("cpuStrategy", QwenAnswerEngine.defaultCpuStrategy.toString()).toInt()) }
            val initStart = System.nanoTime()
            if (!(withCapture && cache && candidate == "original"))
                infer { NativeQwen.preparePrefix(handle, prefix.toByteArray(), cache) }
            Log.i("FamilyVoiceLab", "BENCH prefixInitMs=${(System.nanoTime()-initStart)/1e6} candidate=$candidate withCapture=$withCapture")
            repeat(args.getString("passes", "2").toInt()) { pass ->
                for ((speech, expected) in cases) {
                    val prompt = (if (cache) "" else prefix) + AnswerPrompt.suffix(speech)
                    Log.i("FamilyVoiceLab", "BENCH before pass=$pass input=\"$speech\" ${sensors()}")
                    val start = System.nanoTime()
                    val answer = runCatching { infer { AnswerPrompt.validate(NativeQwen.generate(handle,
                        prompt.toByteArray(), 16, NativeQwen.ticket(handle)).toString(Charsets.UTF_8)) } }
                    val actual = answer.getOrElse { "ERROR: ${it.message}" }
                    val latency = (System.nanoTime()-start)/1e6
                    latencies += latency
                    Log.i("FamilyVoiceLab", "BENCH result candidate=$candidate pass=$pass input=\"$speech\" output=\"$actual\" expected=\"$expected\" wallMs=$latency ${sensors()}")
                    if (actual != expected) failures += "$speech => $actual (expected $expected)"
                }
            }
        } finally {
            if (activity != null) {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
                RuntimeTestSupport.releaseIdle()
            } else NativeQwen.destroy(handle)
        }
        if (cache && candidate == "original" && android.os.Build.HARDWARE == "sun55iw3p1" &&
            args.getString("performanceGate", "true") == "true") {
            val warm = latencies.drop(1).sorted()
            val median = warm[warm.size / 2]
            val p95 = warm[ceil(warm.size * .95).toInt() - 1]
            Log.i("FamilyVoiceGame", "PERFORMANCE_GATE requests=${latencies.size} medianMs=$median p95Ms=$p95 maxMs=${warm.last()}")
            assertTrue("Warm median $median ms", median < 1200)
            assertTrue("Warm p95 $p95 ms", p95 < 1500)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
