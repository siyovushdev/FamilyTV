package ru.familytv.voicelab

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoicePipelineInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun russianFixtureEndpointsAndDecodesTwiceWithOneRuntime() {
        RuntimeTestSupport.releaseIdle()
        val audio = readFixture()
        VoiceEngine(instrumentation.targetContext.assets).use { engine ->
            var started = false
            var processed = 0
            val texts = mutableListOf<String>()
            val vad = engine.vad
            fun feed(samples: FloatArray) {
                var offset = 0
                while (offset < samples.size) {
                    val block = FloatArray(VoiceEngine.WINDOW_SIZE)
                    samples.copyInto(block, 0, offset, minOf(offset + block.size, samples.size))
                    processed += block.size
                    vad.acceptWaveform(block)
                    started = started || vad.isSpeechDetected()
                    while (!vad.empty()) {
                        val segment = vad.front()
                        vad.pop()
                        assertTrue("Speech segment must be nonempty", segment.samples.isNotEmpty())
                        val trailingSilence = processed - (segment.start + segment.samples.size)
                        assertTrue("Endpoint should wait for >= 0.5 s of silence", trailingSilence >= 8000)
                        val start = System.nanoTime()
                        val text = engine.recognize(segment.samples)
                        Log.i(VoiceEngine.TAG, "FIXTURE speechDurationMs=${segment.samples.size * 1000L / 16000} " +
                            "sttLatencyMs=${VoiceEngine.elapsedMs(start)} recognizedText=\"$text\"")
                        assertTrue("Expected meaningful Russian text: $text",
                            Regex("[а-яА-ЯёЁ]+").findAll(text).count() >= 3)
                        assertEquals("родион потапыч высчитывал каждый новый вершок углубления " +
                            "и давно определил про себя", text)
                        texts += text
                    }
                    offset += block.size
                }
            }
            feed(FloatArray(16000))
            assertFalse(started)
            assertTrue(texts.isEmpty())
            repeat(2) {
                feed(audio)
                feed(FloatArray(16000)) // Real endpoint detection; deliberately never call flush().
                assertTrue(started)
                assertFalse(vad.isSpeechDetected())
            }
            assertEquals("One endpoint per fixture", 2, texts.size)
            assertEquals("Recognizer remains reusable", texts[0], texts[1])
            vad.reset()
            feed(FloatArray(16000))
            assertTrue(vad.empty())
        }
    }

    @Test
    fun microphoneStartStopRestartClearAndBackground() {
        // Keep an Activity in the foreground: Android forbids background microphone capture.
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            VoiceLabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VoiceLabActivity
        fun views(): List<View> {
            fun children(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
                else emptyList()
            return children(activity.window.decorView)
        }
        fun click(title: String) = instrumentation.runOnMainSync {
            val button = views().filterIsInstance<Button>().first { it.text.toString() == title }
            assertTrue("$title must be enabled", button.isEnabled)
            button.performClick()
        }
        fun waitFor(description: String, predicate: (List<View>) -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 60000
            while (SystemClock.elapsedRealtime() < end) {
                var matched = false
                instrumentation.runOnMainSync { matched = predicate(views()) }
                if (matched) return
                SystemClock.sleep(50)
            }
            fail("Timed out: $description")
        }
        fun ready() = waitFor("Start enabled") { all ->
            all.filterIsInstance<Button>().any { it.text == "Start listening" && it.isEnabled }
        }
        try {
            ready()
            repeat(2) {
                click("Start listening")
                waitFor("Live microphone") { all ->
                    all.filterIsInstance<TextView>().any { it.text == "Говорите по-русски" }
                }
                SystemClock.sleep(750)
                click("Stop")
                ready()
                waitFor("IDLE") { all -> all.filterIsInstance<TextView>().any { it.text == "STATUS: IDLE" } }
            }
            click("Clear")
            waitFor("Cleared result") { all -> all.filterIsInstance<TextView>().any { it.text.toString() == "STT: —" } }
            click("Start listening")
            waitFor("Live microphone before background") { all ->
                all.filterIsInstance<TextView>().any { it.text == "Говорите по-русски" }
            }
            // Opt-in acoustic acceptance test: speaker -> room -> actual AudioRecord.
            // No fixture samples are injected into the production pipeline.
            if (InstrumentationRegistry.getArguments().getString("acoustic") == "true") {
                val pcm = readFixture().map { (it * 32768).toInt().toShort() }.toShortArray()
                repeat(2) { index ->
                    click("Clear")
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.size * 2)
                        .build()
                    try {
                        assertEquals(pcm.size, track.write(pcm, 0, pcm.size))
                        track.play()
                        Log.i(VoiceEngine.TAG, "ACOUSTIC playbackStart repetition=${index + 1}")
                        waitFor("Live acoustic Russian STT, repetition ${index + 1}") { all ->
                            all.filterIsInstance<TextView>().any {
                                it.text.toString().startsWith("STT:") &&
                                    it.text.toString().contains("высчитывал") &&
                                    it.text.toString().contains("углубления")
                            }
                        }
                        waitFor("Listening after acoustic result") { all ->
                            all.filterIsInstance<TextView>().any { it.text == "STATUS: LISTENING" }
                        }
                    } finally {
                        track.stop()
                        track.release()
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            RuntimeTestSupport.releaseIdle()
        }
    }

    private fun readFixture(): FloatArray {
        val bytes = instrumentation.context.assets.open("voice-lab/1.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        var offset = 12
        var validFormat = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4)
            val length = buffer.getInt(offset + 4)
            require(length >= 0 && offset + 8L + length <= bytes.size)
            if (name == "fmt ") {
                require(length >= 16)
                validFormat = buffer.getShort(offset + 8).toInt() == 1 &&
                    buffer.getShort(offset + 10).toInt() == 1 &&
                    buffer.getInt(offset + 12) == 16000 && buffer.getShort(offset + 22).toInt() == 16
            }
            if (name == "data") {
                require(validFormat)
                return FloatArray(length / 2) { buffer.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + length + length % 2
        }
        error("Missing WAV data")
    }
}
