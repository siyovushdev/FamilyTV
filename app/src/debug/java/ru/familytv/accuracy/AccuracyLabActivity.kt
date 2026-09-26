package ru.familytv.accuracy

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import ru.familytv.voice.VoiceRuntime
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Debug source set only. No child UI or production model settings depend on this screen. */
class AccuracyLabActivity : ComponentActivity() {
    private class Session {
        val stopped = AtomicBoolean(false)
        var release: (() -> Unit)? = null
        var ready = false
        var ending = false
    }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "FamilyAccuracyLab") }
    private var session: Session? = null
    private var visible = false
    private val recordStop = AtomicBoolean(false)
    private var recording = false
    private var busy = true
    private var accepted = false
    private var candidate: Wave? = null
    private var candidateRoute = "unknown"
    private var candidateMetadata: JSONObject? = null
    private var player: MediaPlayer? = null
    private lateinit var root: LinearLayout
    private lateinit var category: Spinner
    private lateinit var microphone: Spinner
    private var devices = emptyList<AudioDeviceInfo>()
    private lateinit var phrase: TextView
    private lateinit var status: TextView
    private lateinit var record: Button
    private lateinit var replay: Button
    private lateinit var accept: Button
    private lateinit var retry: Button
    private lateinit var next: Button
    private lateinit var benchmark: Button
    private lateinit var includeQwen: CheckBox
    private val fields = linkedMapOf<String, EditText>()
    private var phrases = emptyList<JSONObject>()
    private var selection = 0
    private var categoryRows = emptyList<JSONObject>()
    private val dataset by lazy { File(checkNotNull(getExternalFilesDir(null)), "voice-benchmark") }
    private val draft by lazy { File(cacheDir, "accuracy-draft.wav") }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        status.text = if (it) "Доступ разрешён. Нажмите Record." else "Нет доступа к микрофону. Разрешите его в настройках приложения."
    }
    private fun text(value: String, size: Float = 18f) = TextView(this).apply { text = value; textSize = size; setTextColor(0xff202124.toInt()) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        root.addView(text("Voice Accuracy Lab · developer only", 26f))
        root.addView(text("16 kHz · mono · PCM16. Говорите после Record; затем Stop. Максимум 6 секунд. Qwen при записи не работает."))
        category = Spinner(this)
        val categories = listOf("number", "color", "yes-no", "phrase")
        category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        root.addView(category)
        val catalog = JSONArray(assets.open("accuracy/phrases.json").bufferedReader().use { it.readText() })
        phrases = (0 until catalog.length()).map(catalog::getJSONObject)
        phrase = text("", 32f); root.addView(phrase)
        microphone = Spinner(this); root.addView(microphone)
        val metadataRow = LinearLayout(this)
        for ((key, default) in linkedMapOf("speaker" to "adult", "speakerId" to "speaker01", "voice" to "normal", "distance" to "close", "noise" to "quiet")) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            cell.addView(text(key, 14f))
            val input = EditText(this).apply { setText(default); textSize = 16f; setSingleLine() }
            fields[key] = input; cell.addView(input)
            metadataRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(metadataRow)
        val buttons = LinearLayout(this)
        record = button("Record") {
            if (recording) recordStop.set(true) else startRecording()
        }
        replay = button("Replay") {
            player?.release()
            player = MediaPlayer().apply { setDataSource(draft.path); prepare(); start(); setOnCompletionListener { it.release(); player = null } }
        }
        accept = button("Accept") { saveCandidate() }
        retry = button("Retry") { resetCandidate(); if (session?.ready == true) startRecording() else prepare() }
        next = button("Next") { selection = (selection + 1) % categoryRows.size; resetCandidate(); showPhrase() }
        for (b in listOf(record, replay, accept, retry, next)) buttons.addView(b, LinearLayout.LayoutParams(0, 96, 1f))
        root.addView(buttons)
        includeQwen = CheckBox(this).apply { text = "Benchmark: guarded Qwen + domain-prompt variant (slower)" }
        root.addView(includeQwen)
        benchmark = button("Run A/B/C benchmark") { runBenchmark() }; root.addView(benchmark)
        status = text("Освобождение общего runtime…"); root.addView(status)
        root.addView(text("Dataset: ${dataset.path}\nAccept сохраняет запись; Next меняет фразу. Retry создаёт новую попытку, принятые записи сохраняются.\nДля 100+ записей повторяйте полный цикл. После смены USB откройте экран заново.", 15f))
        setContentView(ScrollView(this).apply { setBackgroundColor(0xfffafafa.toInt()); addView(root) })
        category.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                categoryRows = phrases.filter { it.getString("category") == categories[position] }
                selection = 0; resetCandidate(); showPhrase()
            }
        }
        dataset.mkdirs(); File(dataset, "audio").mkdirs()
        File(dataset, "manifest.jsonl").let { if (!it.exists()) it.writeText("") }
        refreshDevices(); controls()
    }
    private fun refreshDevices() {
        devices = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .sortedBy { if (it.type in listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)) 0 else 1 }
        microphone.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.productName} · type=${it.type} · ${it.address}" }.ifEmpty { listOf("Нет входного устройства") })
    }
    private fun showPhrase() { phrase.text = "${selection + 1}/${categoryRows.size}  ·  ${categoryRows.getOrNull(selection)?.optString("truth") ?: ""}" }
    private fun resetCandidate() { player?.release(); player = null; candidate = null; candidateMetadata = null; accepted = false; controls() }
    private fun controls() {
        if (!::record.isInitialized) return
        record.text = if (recording) "Stop" else "Record"
        record.isEnabled = recording || (!busy && session?.ready == true && devices.isNotEmpty())
        replay.isEnabled = !busy && candidate != null
        accept.isEnabled = !busy && candidate != null && !accepted
        retry.isEnabled = !busy; next.isEnabled = !busy
        category.isEnabled = !busy; microphone.isEnabled = !busy; includeQwen.isEnabled = !busy; benchmark.isEnabled = !busy
        fields.values.forEach { it.isEnabled = !busy }
    }
    override fun onStart() { super.onStart(); visible = true; prepare() }
    private fun prepare() {
        if (!visible || session != null) return
        val current = Session(); session = current; busy = true; controls()
        try {
            current.release = VoiceRuntime.get(this).reserveForDiagnostics {
                current.ready = true
                if (current.stopped.get()) end(current) else {
                    busy = false
                    status.text = runCatching { "Готово. Записей: ${AccuracyDataset.load(dataset).size}. Выберите вход, затем Record." }
                        .getOrElse { "Ошибка manifest: ${it.message}. Исправьте файл перед записью." }
                    controls()
                }
            }
        } catch (e: Exception) { session = null; busy = false; status.text = "Ошибка: ${e.message}. Retry."; controls() }
    }
    private fun end(current: Session) {
        if (!current.ready || current.ending) return
        current.ending = true
        worker.execute { runOnUiThread {
            current.release?.invoke()
            if (session === current) session = null
            if (visible) prepare() else if (isDestroyed) worker.shutdown()
        } }
    }
    override fun onStop() {
        visible = false; player?.release(); player = null; recordStop.set(true)
        session?.let { it.stopped.set(true); end(it) }; super.onStop()
    }
    override fun onDestroy() { if (session == null) worker.shutdown(); super.onDestroy() }
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val current = session ?: return
        val device = devices.getOrNull(microphone.selectedItemPosition) ?: return
        val entry = JSONObject(categoryRows[selection].toString())
        fields.forEach { (key, view) -> entry.put(key, view.text.toString().trim().ifBlank { "unknown" }) }
        entry.put("allowed", JSONArray(AnswerDomain.valueOf(entry.getString("domain")).words))
        player?.release(); player = null; recordStop.set(false); recording = true; busy = true; candidate = null; accepted = false
        status.text = "Запись… говорите: ${entry.getString("truth")}"; controls()
        worker.execute {
            var recorder: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 32000))
                check(recorder.state == AudioRecord.STATE_INITIALIZED && recorder.setPreferredDevice(device)) { "Input unavailable" }
                recorder.startRecording()
                val pcm = ShortArray(96000); var size = 0; val deadline = System.nanoTime() + 8_000_000_000
                var routed: AudioDeviceInfo? = null
                while (!recordStop.get() && !current.stopped.get() && size < pcm.size && System.nanoTime() < deadline) {
                    val count = recorder.read(pcm, size, minOf(512, pcm.size - size), AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "AudioRecord error $count" }
                    if (count == 0) Thread.sleep(5) else size += count
                    recorder.routedDevice?.let { routed = it }
                }
                val route = routed
                check(size >= 1600 && route?.id == device.id) { "Too little audio or selected microphone was disconnected/rerouted" }
                val wave = Wave(pcm.copyOf(size)); WaveFiles.write(draft, wave.pcm)
                entry.put("microphone", when {
                    route.productName.toString().contains("H340", true) -> "h340"
                    route.productName.toString().contains("XVF", true) -> "xvf3800"
                    else -> "other"
                }).put("routeName", route.productName).put("routeType", route.type).put("routeAddress", route.address)
                    .put("recordedAtEpochMs", System.currentTimeMillis()).put("sampleRate", 16000).put("channels", 1)
                    .put("peakPcm16", wave.peak).put("rmsPcm16", wave.rms).put("clippedFraction", wave.clippedFraction)
                runOnUiThread { if (!current.stopped.get()) {
                    candidate = wave; candidateMetadata = entry; candidateRoute = route.productName.toString()
                    status.text = "${wave.durationMs.toInt()} ms · $candidateRoute · peak=${wave.peak} · RMS=${wave.rms.toInt()}. Replay → Accept или Retry."
                } }
            } catch (e: Exception) { runOnUiThread { if (visible) status.text = "Ошибка записи: ${e.message}" } }
            finally {
                recorder?.let { try { it.stop() } catch (_: Exception) {} finally { it.release() } }
                runOnUiThread { recording = false; busy = false; controls() }
            }
        }
    }
    private fun saveCandidate() {
        val wave = candidate ?: return
        if (wave.peak == 0) { status.text = "Нулевой PCM: запись не принята. Проверьте вход и повторите."; return }
        val entry = JSONObject(checkNotNull(candidateMetadata).toString())
        val id = "sample_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        entry.put("id", id).put("file", "audio/$id.wav")
        busy = true; controls()
        worker.execute {
            val target = File(dataset, entry.getString("file"))
            try {
                WaveFiles.write(target, wave.pcm); entry.put("audioSha256", AccuracyRunner.sha(target))
                try { AccuracyDataset.append(dataset, entry) } catch (e: Exception) { target.delete(); throw e }
                runOnUiThread { accepted = true; status.text = "Принято: $id. Всего: ${AccuracyDataset.load(dataset).size}. Next." }
            } catch (e: Exception) { runOnUiThread { status.text = "Не сохранено: ${e.message}" } }
            finally { runOnUiThread { busy = false; controls() } }
        }
    }
    private fun runBenchmark() {
        val current = session ?: return
        val useQwen = includeQwen.isChecked
        player?.release(); player = null; busy = true; controls()
        val output = File(dataset, "runs/${System.currentTimeMillis()}")
        worker.execute {
            try {
                AccuracyRunner.run(applicationContext, dataset, output, useQwen, cancelled = { current.stopped.get() },
                    progress = { message -> runOnUiThread { if (visible) status.text = message } })
                runOnUiThread { status.text = "Готово: $output/summary.md" }
            } catch (e: Exception) { runOnUiThread { status.text = "Benchmark error: ${e.message}" } }
            finally { runOnUiThread { busy = false; controls() } }
        }
    }
}
