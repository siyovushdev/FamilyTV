package ru.familytv

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "H728MicTest"
        private const val REQUEST_MIC = 1001

        private const val SAMPLE_RATE = 16000
        private const val RECORD_SECONDS = 10
    }

    private lateinit var status: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Готово к записи"
            textSize = 20f
        }

        button = Button(this).apply {
            text = "Записать 10 секунд"
            setOnClickListener {
                checkPermissionAndRecord()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            addView(status)
            addView(button)
        }

        setContentView(layout)
    }

    private fun checkPermissionAndRecord() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MIC
            )
            return
        }

        record()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == REQUEST_MIC &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            record()
        }
    }

    private fun record() {
        button.isEnabled = false
        status.text = "Запись..."

        thread {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val bufferSize = max(minBuffer, 4096)

                Log.i(TAG, "minBuffer=$minBuffer bufferSize=$bufferSize")

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("Microphone permission is no longer granted")
                }

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                Log.i(TAG, "state=${recorder.state}")

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord not initialized")
                }

                val file = File(filesDir, "mic_test.wav")

                RandomAccessFile(file, "rw").use { wav ->
                    wav.setLength(0)

                    // Место под WAV header
                    wav.write(ByteArray(44))

                    val buffer = ByteArray(bufferSize)

                    recorder.startRecording()

                    val start = System.currentTimeMillis()
                    var totalBytes = 0L

                    while (
                        System.currentTimeMillis() - start <
                        RECORD_SECONDS * 1000L
                    ) {
                        val read = recorder.read(
                            buffer,
                            0,
                            buffer.size
                        )

                        if (read > 0) {
                            wav.write(buffer, 0, read)
                            totalBytes += read
                        }
                    }

                    recorder.stop()
                    recorder.release()

                    writeWavHeader(wav, totalBytes)

                    Log.i(
                        TAG,
                        "DONE bytes=$totalBytes path=${file.absolutePath}"
                    )
                }

                runOnUiThread {
                    status.text = "Готово: mic_test.wav"
                    button.isEnabled = true
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)

                runOnUiThread {
                    status.text = "Ошибка: ${e.message}"
                    button.isEnabled = true
                }
            }
        }
    }

    private fun writeWavHeader(
        file: RandomAccessFile,
        audioBytes: Long
    ) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate =
            SAMPLE_RATE * channels * bitsPerSample / 8

        file.seek(0)

        file.writeBytes("RIFF")
        writeLE32(file, audioBytes + 36)

        file.writeBytes("WAVE")

        file.writeBytes("fmt ")
        writeLE32(file, 16)

        writeLE16(file, 1)
        writeLE16(file, channels)

        writeLE32(file, SAMPLE_RATE.toLong())
        writeLE32(file, byteRate.toLong())

        writeLE16(
            file,
            channels * bitsPerSample / 8
        )

        writeLE16(file, bitsPerSample)

        file.writeBytes("data")
        writeLE32(file, audioBytes)
    }

    private fun writeLE16(
        file: RandomAccessFile,
        value: Int
    ) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
    }

    private fun writeLE32(
        file: RandomAccessFile,
        value: Long
    ) {
        file.write((value and 0xFF).toInt())
        file.write(((value shr 8) and 0xFF).toInt())
        file.write(((value shr 16) and 0xFF).toInt())
        file.write(((value shr 24) and 0xFF).toInt())
    }
}
