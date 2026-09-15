package ru.familytv.games

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.voice.*
import ru.familytv.voicelab.*
import java.io.File

class VoiceGamesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun field(instance: Any, name: String): Any = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(instance)!!
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $message")
    }
    private fun launch(type: Class<out Activity>): Activity = instrumentation.startActivitySync(
        Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun texts(activity: Activity): String {
        fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        return all(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120)
        val directory = File(context.filesDir, "voice-games-screenshots").apply { mkdirs() }
        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).useBitmap { image ->
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
    private fun showControlledState(activity: BaseVoiceGameActivity, state: GameViewState) {
        // Presentation-only capture; explicitly not live audio or a game answer injected into production.
        instrumentation.runOnMainSync {
            (BaseVoiceGameActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
                .get(activity) as VoiceGameSessionController).pause()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            BaseVoiceGameActivity::class.java.getDeclaredMethod("render", GameViewState::class.java)
                .apply { isAccessible = true }.invoke(activity, state)
        }
    }

    @Test fun hubGamesAndLabShareOneRuntimeAndShowChildSafeScreens() {
        RuntimeTestSupport.releaseIdle()
        val activities = mutableListOf<Activity>()
        val runtime = VoiceRuntime.get(context)
        try {
            val hub = launch(GameHubActivity::class.java).also { activities += it }
            waitFor("hub ready") { runtime.readiness.value == AiReadiness.AI_READY }
            val count = runtime.initializationCount
            screenshot("hub")
            val math = launch(MathVoiceGameActivity::class.java) as BaseVoiceGameActivity
            activities += math
            waitFor("math listening or microphone recovery") {
                texts(math).contains("Я слушаю") || texts(math).contains("Микрофон не подключён")
            }
            assertEquals(count, runtime.initializationCount)
            screenshot("math-actual")
            val apples = AppleQuestion(1, 2, 3)
            showControlledState(math, GameViewState(apples, GamePhase.LISTENING)); screenshot("math-question")
            showControlledState(math, GameViewState(apples, GamePhase.CORRECT)); screenshot("math-correct")
            showControlledState(math, GameViewState(apples, GamePhase.WRONG)); screenshot("math-wrong")
            instrumentation.runOnMainSync { math.finish() }; instrumentation.waitForIdleSync()
            val colors = launch(ColorVoiceGameActivity::class.java) as BaseVoiceGameActivity
            activities += colors
            waitFor("color listening or microphone recovery") {
                texts(colors).contains("Я слушаю") || texts(colors).contains("Микрофон не подключён")
            }
            assertEquals(count, runtime.initializationCount)
            screenshot("color-actual")
            val color = ColorQuestion(1, GameColor.BLUE, listOf(GameColor.RED, GameColor.BLUE, GameColor.GREEN))
            showControlledState(colors, GameViewState(color, GamePhase.LISTENING)); screenshot("color-question")
            showControlledState(colors, GameViewState(color, GamePhase.CORRECT)); screenshot("color-correct")
            instrumentation.runOnMainSync {
                for (word in listOf("Qwen", "STT", "VAD", "JNI", "tokens", "latency")) {
                    assertFalse(word, texts(colors).contains(word)); assertFalse(word, texts(math).contains(word))
                }
                colors.finish()
            }
            instrumentation.waitForIdleSync()
            val lab = launch(VoiceLabActivity::class.java).also { activities += it }
            waitFor("VoiceLab uses ready models") { texts(lab).contains("Готово") || texts(lab).contains("Остановлено") }
            assertEquals("Hub → Math → Hub → Color → VoiceLab must reuse models", count, runtime.initializationCount)
            screenshot("voice-lab")
        } finally {
            instrumentation.runOnMainSync { activities.reversed().filterNot { it.isDestroyed }.forEach { it.finish() } }
            instrumentation.waitForIdleSync()
            RuntimeTestSupport.releaseIdle()
        }
    }

    @Test fun ownershipRejectsStaleResultsAndInitializationRetryReleasesBeforeReload() {
        RuntimeTestSupport.releaseIdle()
        val hub = launch(GameHubActivity::class.java)
        val runtime = VoiceRuntime.get(context)
        waitFor("ready") { runtime.readiness.value == AiReadiness.AI_READY }
        var first: VoiceAnswerPipeline? = null
        var second: VoiceAnswerPipeline? = null
        try {
            instrumentation.runOnMainSync {
                first = runtime.newPipeline(requireUsb = false)
                second = runtime.newPipeline(requireUsb = false)
                first!!.activate()
                val oldSession = first!!.startListening()
                second!!.activate()
                val core = field(runtime, "backend")
                @Suppress("UNCHECKED_CAST")
                val deliver = field(core, "render") as (VoiceLabState) -> Unit
                deliver(VoiceLabState(session = oldSession, ready = true, status = VoiceStatus.RESULT,
                    text = "четыре", llmText = "4"))
                assertNull("Old question result must not reach next owner", second!!.state.value.answer)
                first!!.close()
            }
            waitFor("capture stopped and new owner ready") { second!!.state.value.phase == VoicePhase.READY }
            instrumentation.runOnMainSync {
                second!!.startListening()
                (field(runtime, "backend") as VoiceLabController).microphoneRemoved()
            }
            waitFor("microphone removal is an explicit error") { second!!.state.value.problem == VoiceProblem.MICROPHONE }
            var oldQwen: Any? = null
            val before = runtime.initializationCount
            instrumentation.runOnMainSync {
                second!!.close()
                val core = field(runtime, "backend")
                oldQwen = field(core, "qwen")
                core.javaClass.getDeclaredMethod("failInitialization", Throwable::class.java).apply { isAccessible = true }
                    .invoke(core, IllegalStateException("Controlled initialization failure test"))
            }
            waitFor("initialization error exposed") { runtime.readiness.value == AiReadiness.AI_ERROR }
            instrumentation.runOnMainSync { runtime.retryInitialization() }
            waitFor("retry ready") { runtime.readiness.value == AiReadiness.AI_READY }
            assertEquals(before + 1, runtime.initializationCount)
            assertEquals("Old native handle freed before replacement", 0L, field(checkNotNull(oldQwen), "handle"))
        } finally {
            instrumentation.runOnMainSync { first?.close(); second?.close(); hub.finish() }
            instrumentation.waitForIdleSync()
            RuntimeTestSupport.releaseIdle()
        }
    }
}
