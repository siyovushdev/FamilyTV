package ru.familytv.voicelab

import androidx.test.platform.app.InstrumentationRegistry
import ru.familytv.voice.VoiceRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object RuntimeTestSupport {
    fun releaseIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runtime = VoiceRuntime.get(instrumentation.targetContext)
        // finish() and waitForIdleSync() do not guarantee that onStop has run yet.
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        var idle = false
        while (!idle && android.os.SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                idle = VoiceRuntime::class.java.getDeclaredField("owner").apply {
                    isAccessible = true
                }.get(runtime) == null
            }
            if (!idle) android.os.SystemClock.sleep(50)
        }
        check(idle) { "Activity did not release its foreground voice lease" }
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            runtime.releaseIdle { done.countDown() }
        }
        check(done.await(60, TimeUnit.SECONDS)) { "Native teardown did not complete" }
    }
}
