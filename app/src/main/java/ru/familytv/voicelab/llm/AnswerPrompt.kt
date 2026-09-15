package ru.familytv.voicelab.llm

/** The extractor receives speech only, never a correct answer or a game's answer key. */
internal object AnswerPrompt {
    private const val SYSTEM = "Извлеки окончательный ответ из последней реплики. " +
        "Числа цифрами, без пояснений. Не решай задания. " +
        "Слово нет отменяет предыдущий вариант. Бери ответ после последнего нет."
    // Six short format demonstrations, not a lookup table or a set of game answers.
    // The tested numbers/colors differ from these examples; Qwen generates every answer.
    private val demonstrations = listOf(
        "один" to "1", "думаю это два" to "2", "не три а четыре" to "4",
        "сначала пять нет шесть" to "6", "не знаю" to "не знаю", "я выбираю зелёный" to "зелёный"
    ).joinToString("") { (input, output) ->
        "<|im_start|>user\n$input<|im_end|>\n<|im_start|>assistant\n$output<|im_end|>\n"
    }

    val prefix = "<|im_start|>system\n$SYSTEM<|im_end|>\n" + demonstrations

    fun build(sttText: String): String = prefix + suffix(sttText)

    fun suffix(sttText: String): String {
        require(sttText.isNotBlank()) { "STT returned empty text" }
        require(sttText.length <= 500) { "STT text is too long for answer extraction" }
        // Escape template delimiters from transcript data before tokenizing with special tokens.
        val speech = sttText.replace('<', '‹').replace('>', '›')
        // Qwen3's enable_thinking=false template pre-fills an already CLOSED empty think block.
        return "<|im_start|>user\n$speech<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    fun validate(raw: String): String {
        val answer = raw.trim().trim('"', '«', '»').trim()
        check(answer.isNotEmpty() && answer.length <= 80 &&
            !answer.contains('\n') && !answer.contains('\r') &&
            !answer.contains('<') && !answer.contains('>')) {
            "Qwen returned an invalid answer (reasoning/explanation/empty output)"
        }
        // Formatting only: no rule-based answer extraction, arithmetic, or fallback correction.
        return answer
    }
}
