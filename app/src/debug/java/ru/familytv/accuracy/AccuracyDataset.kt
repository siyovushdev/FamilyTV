package ru.familytv.accuracy

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class AudioSample(val id: String, val file: String, val truth: String,
    val category: String, val domain: AnswerDomain, val answerTruth: String?, val metadata: JSONObject) {
    fun audio(root: File): File {
        val target = File(root, file).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) { "Audio path escapes dataset" }
        return target
    }
}

internal object AccuracyDataset {
    fun parse(lines: String, root: File): List<AudioSample> {
        val ids = mutableSetOf<String>()
        return lines.lineSequence().filter { it.isNotBlank() }.mapIndexed { index, line ->
            try {
                val j = JSONObject(line)
                val id = j.getString("id")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && ids.add(id)) { "Invalid/duplicate id" }
                val category = j.getString("category").also { require(it.isNotBlank()) }
                val domain = AnswerDomain.valueOf(j.optString("domain", when(category) {
                    "number" -> "NUMBER_0_10"; "color" -> "COLOR"; "yes-no" -> "YES_NO"; else -> "OPEN"
                }))
                j.optJSONArray("allowed")?.let { a ->
                    val values = (0 until a.length()).map(a::getString)
                    require(values.size == domain.words.size && values.toSet() == domain.words.toSet()) {
                        "allowed must be the entire fixed domain vocabulary, never an answer-specific subset"
                    }
                }
                val truth = j.getString("truth")
                val answer = if (j.has("answerTruth") && !j.isNull("answerTruth")) j.getString("answerTruth") else
                    (AnswerPolicy.literal(truth, domain) as? AnswerRecognitionResult.Recognized)?.value
                require(answer == null || answer == "UNKNOWN" || answer in domain.acceptedValues()) { "Invalid answerTruth" }
                val file = j.getString("file")
                require(!File(file).isAbsolute && file.endsWith(".wav")) { "Use a relative .wav path" }
                AudioSample(id, file, truth, category, domain, answer, j).also { it.audio(root) }
            } catch (e: Exception) { throw IllegalArgumentException("Manifest line ${index + 1}: ${e.message}", e) }
        }.toList()
    }
    fun load(root: File) = parse(File(root, "manifest.jsonl").readText(), root)
    fun append(root: File, entry: JSONObject) {
        root.mkdirs()
        val file = File(root, "manifest.jsonl")
        val content = (if (file.exists()) file.readText().trimEnd() else "")
            .let { if (it.isEmpty()) "" else "$it\n" } + entry.toString() + "\n"
        parse(content, root)
        val temporary = File(root, "manifest.jsonl.tmp")
        temporary.writeText(content)
        require(temporary.renameTo(file)) { "Atomic manifest update failed" }
    }
}

internal data class Wave(val pcm: ShortArray) {
    val durationMs get() = pcm.size * 1000.0 / 16000
    val peak get() = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
    val rms get() = kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size.coerceAtLeast(1))
    val clippedFraction get() = pcm.count { kotlin.math.abs(it.toInt()) >= 32760 }.toDouble() / pcm.size.coerceAtLeast(1)
    fun floats() = FloatArray(pcm.size) { pcm[it] / 32768f }
}

internal object WaveFiles {
    fun read(file: File): Wave {
        require(file.length() in 44..2_000_000) { "WAV too large/short" }
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        fun tag() = ByteArray(4).also(b::get).toString(Charsets.US_ASCII)
        require(tag() == "RIFF"); val riffSize = b.int; require(riffSize + 8L == b.limit().toLong())
        require(tag() == "WAVE")
        var format = false; var samples: ShortArray? = null
        while (b.remaining() >= 8) {
            val key = tag(); val size = b.int
            require(size >= 0 && size <= b.remaining()) { "Invalid WAV chunk size" }
            val end = b.position() + size
            if (key == "fmt ") {
                require(size >= 16)
                require(b.short.toInt() == 1 && b.short.toInt() == 1 && b.int == 16000) { "Require PCM16 mono 16 kHz" }
                require(b.int == 32000 && b.short.toInt() == 2 && b.short.toInt() == 16)
                format = true
            } else if (key == "data") {
                require(samples == null && size % 2 == 0 && size in 2..960000) { "Require one data chunk, <=30 seconds" }
                samples = ShortArray(size / 2) { b.short }
            }
            b.position(end)
            if (size % 2 == 1) { require(b.hasRemaining()); b.get() }
        }
        require(format && b.remaining() == 0 && samples != null) { "Incomplete WAV" }
        return Wave(checkNotNull(samples))
    }
    fun write(file: File, pcm: ShortArray) {
        require(pcm.size in 1..480000)
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        b.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2); pcm.forEach(b::putShort)
        file.parentFile?.mkdirs(); file.writeBytes(b.array())
    }
}
