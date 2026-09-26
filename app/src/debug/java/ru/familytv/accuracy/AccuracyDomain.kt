package ru.familytv.accuracy

import java.util.Locale

internal enum class AnswerDomain(val words: List<String>) {
    NUMBER_0_10("ноль один два три четыре пять шесть семь восемь девять десять".split(" ")),
    COLOR(listOf("красный", "синий", "зелёный", "жёлтый")),
    YES_NO(listOf("да", "нет")), OPEN(emptyList());
    fun hotwords(): String = words.joinToString("/")
    fun acceptedValues(): List<String> = if (this == NUMBER_0_10) (0..10).map(Int::toString) else words
}

internal fun clean(text: String) = text.lowercase(Locale.ROOT).trim()
    .replace(Regex("[.,!?;:«»\"“”]"), " ").replace(Regex("\\s+"), " ").trim()

internal sealed interface AnswerRecognitionResult {
    data class Recognized(val value: String, val info: String,
        val confidence: Double? = null) : AnswerRecognitionResult
    data class Unknown(val reason: String) : AnswerRecognitionResult
}

/** No acoustic confidence is exposed by the checked-in Kotlin JNI result.
 * This lexical evidence gate is intentionally conservative, not a probability.
 */
internal object AnswerPolicy {
    fun tokenValue(word: String, domain: AnswerDomain): String? {
        val w = clean(word)
        if (domain == AnswerDomain.NUMBER_0_10) {
            if (w in domain.acceptedValues()) return w
            if (w == "пят") return "5" // Explicit, narrow truncation experiment; measured separately.
            val i = domain.words.indexOf(w)
            return if (i >= 0) i.toString() else null
        }
        return w.takeIf { it in domain.words }
    }
    fun literal(text: String, domain: AnswerDomain): AnswerRecognitionResult {
        val value = tokenValue(text, domain)
        return if (value != null) AnswerRecognitionResult.Recognized(value, "literal_domain_token; no acoustic confidence")
        else AnswerRecognitionResult.Unknown("not_a_literal_domain_answer")
    }
    fun evidence(text: String, domain: AnswerDomain): Set<String> = clean(text).split(" ")
        .mapNotNull { tokenValue(it, domain) }.toSet()
    fun mayNormalize(text: String, domain: AnswerDomain): Boolean =
        domain != AnswerDomain.OPEN && clean(text) !in listOf("не знаю", "я не знаю") && evidence(text, domain).isNotEmpty()
    fun validate(text: String, output: String, domain: AnswerDomain): AnswerRecognitionResult {
        val value = clean(output)
        if (value == "unknown" || value == "не знаю") return AnswerRecognitionResult.Unknown("normalizer_unknown")
        if (!mayNormalize(text, domain)) return AnswerRecognitionResult.Unknown("no_domain_evidence")
        if (value !in domain.acceptedValues()) return AnswerRecognitionResult.Unknown("output_outside_domain")
        if (value !in evidence(text, domain)) return AnswerRecognitionResult.Unknown("output_not_supported_by_transcript")
        return AnswerRecognitionResult.Recognized(value, "domain_and_transcript_supported; no acoustic confidence")
    }
    // Optional domain prompt is benchmark-only, never the production prefix. No truth parameter.
    fun domainInstruction(domain: AnswerDomain): String =
        "Тип ответа: ${domain.name}. Допустимые значения: ${domain.acceptedValues().joinToString(", ")}. " +
            "Извлеки ответ из текста. Не восстанавливай неизвестные слова по звучанию. " +
            "Если ответ неясен, верни UNKNOWN. Не решай задания. Только ответ."
    fun value(result: AnswerRecognitionResult): String = when (result) {
        is AnswerRecognitionResult.Recognized -> result.value
        is AnswerRecognitionResult.Unknown -> "UNKNOWN"
    }
}

internal data class AsrExperiment(val id: String, val decoding: String, val score: Float? = null) {
    companion object {
        val matrix = listOf(AsrExperiment("A_greedy", "greedy_search"),
            AsrExperiment("B_beam", "modified_beam_search")) + listOf(.5f, 1f, 1.5f, 2f).map {
            AsrExperiment("C_hotwords_$it", "modified_beam_search", it)
        }
    }
}
