package ru.familytv.accuracy

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

internal data class WordErrors(val substitutions: Int = 0, val deletions: Int = 0, val insertions: Int = 0) {
    val total get() = substitutions + deletions + insertions
}
internal object AccuracyMetrics {
    fun words(s: String) = clean(s).split(" ").filter { it.isNotEmpty() }
    fun errors(truth: String, actual: String): WordErrors {
        val a = words(truth); val b = words(actual)
        val dp = Array(a.size + 1) { Array(b.size + 1) { WordErrors() } }
        for (i in 1..a.size) dp[i][0] = WordErrors(deletions = i)
        for (j in 1..b.size) dp[0][j] = WordErrors(insertions = j)
        for (i in 1..a.size) for (j in 1..b.size) {
            val p = dp[i - 1][j - 1]
            dp[i][j] = if (a[i - 1] == b[j - 1]) p else listOf(
                p.copy(substitutions = p.substitutions + 1),
                dp[i - 1][j].let { it.copy(deletions = it.deletions + 1) },
                dp[i][j - 1].let { it.copy(insertions = it.insertions + 1) }
            ).minBy { it.total }
        }
        return dp[a.size][b.size]
    }
    fun median(v: List<Double>): Double? = v.sorted().takeIf { it.isNotEmpty() }?.let {
        (it[(it.size - 1) / 2] + it[it.size / 2]) / 2
    }
    fun p95(v: List<Double>): Double? = v.sorted().takeIf { it.isNotEmpty() }?.let { it[ceil(it.size * .95).toInt() - 1] }
    fun aggregate(rows: List<JSONObject>): JSONObject {
        val words = rows.sumOf { words(it.getString("truth")).size }
        val labeled = rows.filter { !it.isNull("answerTruth") }
        fun semantic(key: String): Any {
            val tested = labeled.filter { !it.isNull(key) }
            return if (tested.isEmpty()) JSONObject.NULL else JSONObject().put("n", tested.size)
                .put("correct", tested.count { it.isNull("error") && it.getString(key) == it.getString("answerTruth") })
                .put("unknown", tested.count { it.getString(key) == "UNKNOWN" })
                .put("falseAccepted", tested.count { it.getString(key) != "UNKNOWN" && it.getString(key) != it.getString("answerTruth") })
        }
        return JSONObject().put("n", rows.size).put("rawExact", rows.count { it.getBoolean("exact") })
            .put("rawAccuracy", if (rows.isEmpty()) JSONObject.NULL else rows.count { it.getBoolean("exact") }.toDouble() / rows.size)
            .put("referenceWords", words).put("wer", if (words == 0) JSONObject.NULL else rows.sumOf { it.getInt("wordErrors") }.toDouble() / words)
            .put("substitutions", rows.sumOf { it.getInt("substitutions") })
            .put("insertions", rows.sumOf { it.getInt("insertions") }).put("deletions", rows.sumOf { it.getInt("deletions") })
            .put("literal", semantic("literalAnswer")).put("guardedQwen", semantic("normalized"))
            .put("domainQwen", semantic("domainNormalized"))
            .put("sttMedianMs", median(rows.map { it.getDouble("sttMs") }) ?: JSONObject.NULL)
            .put("sttP95Ms", p95(rows.map { it.getDouble("sttMs") }) ?: JSONObject.NULL)
            .put("qwenMedianMs", median(rows.filter { !it.isNull("qwenMs") }.map { it.getDouble("qwenMs") }) ?: JSONObject.NULL)
            .put("qwenP95Ms", p95(rows.filter { !it.isNull("qwenMs") }.map { it.getDouble("qwenMs") }) ?: JSONObject.NULL)
            .put("domainQwenMedianMs", median(rows.filter { !it.isNull("domainQwenMs") }.map { it.getDouble("domainQwenMs") }) ?: JSONObject.NULL)
            .put("domainQwenP95Ms", p95(rows.filter { !it.isNull("domainQwenMs") }.map { it.getDouble("domainQwenMs") }) ?: JSONObject.NULL)
            .put("failures", rows.count { !it.isNull("error") })
    }
    fun write(output: File, rows: List<JSONObject>, metadata: JSONObject) {
        output.mkdirs()
        File(output, "results.json").writeText(JSONObject().put("run", metadata).put("samples", JSONArray(rows)).toString(2))
        val columns = listOf("config", "id", "category", "domain", "truth", "rawStt", "exact", "answerTruth", "literalAnswer",
            "normalized", "unknownReason", "domainNormalized", "domainUnknownReason", "sttMs", "qwenMs", "domainQwenMs",
            "audioDurationMs", "vadMs", "vadSegments", "rtf", "wer", "substitutions", "deletions", "insertions", "error")
        fun csv(s: String): String {
            // Spreadsheet formula injection protection; exact strings remain in results.json.
            val safe = if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) "'$s" else s
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        File(output, "results.csv").writeText(columns.joinToString(",") + "\n" + rows.joinToString("\n") { r ->
            columns.joinToString(",") { csv(if (r.isNull(it)) "" else r.get(it).toString()) }
        } + "\n")
        val summaries = JSONObject()
        fun escape(s: String) = s.replace("|", "\\|").replace("\n", " ")
        val md = StringBuilder("# Voice Accuracy Lab results\n\nDataset: `${metadata.optString("datasetSha256")}`. " +
            "Corpus kind: ${metadata.optString("corpusKind")}. Configured sample count: ${metadata.optInt("sampleCount")}.\n\n" +
            "RAW A = current greedy STT; B/C = alternative/contextual STT. " +
            "Semantic results compare answerTruth, not transcript truth. UNKNOWN is abstention. " +
            "No recordings means no accuracy estimate. No automatic winner promotion.\n\n")
        if (metadata.has("runError") || !metadata.has("finishedAtEpochMs") || metadata.optBoolean("cancelled"))
            md.append("**INCOMPLETE RUN: ${metadata.optString("runError", "in progress or cancelled")}. Do not compare as a complete corpus.**\n\n")
        for ((config, samples) in rows.groupBy { it.getString("config") }) {
            md.append("## $config\n\n| Group | N | Raw exact | WER | STT median / p95 ms | Literal correct | Guarded Qwen correct | Domain Qwen correct |\n|---|---:|---:|---:|---|---|---|---|\n")
            val groups = linkedMapOf("overall" to samples)
            samples.groupBy { "category:${it.getString("category")}" }.forEach { (k, v) -> groups[k] = v }
            samples.groupBy { it.getString("shortGroup") }.forEach { (k, v) -> groups[k] = v }
            for (key in listOf("microphone", "speaker", "speakerId", "voice", "distance", "noise"))
                samples.groupBy { "$key:${it.getJSONObject("metadata").optString(key, "unknown")}" }.forEach { (k,v) -> groups[k] = v }
            val stats = JSONObject()
            for ((group, records) in groups) {
                val a = aggregate(records); stats.put(group, a)
                fun count(key: String) = a.optJSONObject(key)?.let { "${it.getInt("correct")}/${it.getInt("n")}" } ?: "N/A"
                md.append("| ${escape(group)} | ${records.size} | ${a.getInt("rawExact")}/${records.size} | ${a.get("wer")} | ${a.get("sttMedianMs")} / ${a.get("sttP95Ms")} | ${count("literal")} | ${count("guardedQwen")} | ${count("domainQwen")} |\n")
            }
            summaries.put(config, stats)
            md.append("\nCommon whole-utterance substitutions (not phoneme alignment):\n\n")
            samples.filter { !it.getBoolean("exact") }.groupingBy { "${it.getString("truth")} → ${it.getString("rawStt")}" }
                .eachCount().entries.sortedByDescending { it.value }.take(15).forEach {
                    md.append("- ${escape(it.key)}: ${it.value}\n")
                }
            val a = aggregate(samples)
            md.append("\nS/D/I: ${a.get("substitutions")}/${a.get("deletions")}/${a.get("insertions")}. Failures: ${a.get("failures")}.\n")
            for (key in listOf("guardedQwen", "domainQwen")) md.append("\n$key: ${a.get(key)}.\n")
            md.append("\nQwen median/p95: ${a.get("qwenMedianMs")}/${a.get("qwenP95Ms")} ms; domain variant: ${a.get("domainQwenMedianMs")}/${a.get("domainQwenP95Ms")} ms. Init/prefix costs are separate in results.json.\n\n")
        }
        if (rows.isEmpty()) md.append("**No scored samples. Collect a labeled corpus before selecting ASR settings.**\n")
        File(output, "summary.json").writeText(JSONObject().put("run", metadata).put("configs", summaries).toString(2))
        File(output, "summary.md").writeText(md.toString())
    }
}
