package ru.familytv.games

import org.json.JSONObject
import ru.familytv.content.ContentRepository
import ru.familytv.content.ContentType

/** One addition round. The apple stage still draws these counts; it does not own them. */
data class CountCombineRound(val id: String, val left: Int, val right: Int) {
    val sum: Int get() = left + right
}

/** Child-facing lines for the existing combine → answer → help loop. */
data class CountCombineLines(
    val separate: String,
    val ask: String,
    val help: String,
    val wrong: String,
    val success: String,
    val countingLead: String,
)

/**
 * Typed COUNT_COMBINE dataset.
 * [contentId] is the catalog id. It must match the item that opened this file,
 * so a second id cannot silently play the first file.
 */
data class CountCombineDefinition(
    val schemaVersion: Int,
    val contentId: String,
    val title: String,
    val subtitle: String,
    val rounds: List<CountCombineRound>,
    val lines: CountCombineLines,
    val countingWords: List<String>,
) {
    val pairs: List<Pair<Int, Int>> get() = rounds.map { it.left to it.right }
}

sealed class CountCombineLoad {
    data class Ready(val definition: CountCombineDefinition) : CountCombineLoad()
    data class Rejected(val reason: String) : CountCombineLoad()

    fun definitionOrNull(): CountCombineDefinition? = (this as? Ready)?.definition
}

object CountCombineParser {
    const val TEMPLATE = "count_combine"
    const val SCHEMA_VERSION = 1

    fun parse(json: String, expectedContentId: String): CountCombineLoad {
        if (expectedContentId.isBlank()) return CountCombineLoad.Rejected("content id is blank")
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return CountCombineLoad.Rejected("definition is not JSON")
        }
        val version = root.optInt("schemaVersion", -1)
        if (version != SCHEMA_VERSION) {
            return CountCombineLoad.Rejected("unsupported schemaVersion=$version")
        }
        val template = root.optString("template").trim()
        if (template != TEMPLATE) return CountCombineLoad.Rejected("template is '$template'")
        val contentId = root.optString("contentId").trim()
        if (contentId != expectedContentId) {
            return CountCombineLoad.Rejected("definition contentId '$contentId' does not match '$expectedContentId'")
        }
        val title = root.optString("title").trim()
        if (title.isEmpty()) return CountCombineLoad.Rejected("title is blank")
        val subtitle = root.optString("subtitle").trim()
        val linesJson = root.optJSONObject("lines") ?: return CountCombineLoad.Rejected("lines missing")
        val lines = CountCombineLines(
            separate = linesJson.optString("separate").trim(),
            ask = linesJson.optString("ask").trim(),
            help = linesJson.optString("help").trim(),
            wrong = linesJson.optString("wrong").trim(),
            success = linesJson.optString("success").trim(),
            countingLead = linesJson.optString("countingLead").trim(),
        )
        if (listOf(lines.separate, lines.ask, lines.help, lines.wrong, lines.success, lines.countingLead).any { it.isEmpty() }) {
            return CountCombineLoad.Rejected("a child-facing line is blank")
        }
        val wordsJson = root.optJSONArray("countingWords") ?: return CountCombineLoad.Rejected("countingWords missing")
        val words = buildList {
            for (i in 0 until wordsJson.length()) {
                val word = wordsJson.optString(i).trim()
                if (word.isEmpty()) return CountCombineLoad.Rejected("countingWords[$i] is blank")
                add(word)
            }
        }
        if (words.isEmpty()) return CountCombineLoad.Rejected("countingWords is empty")
        val roundsJson = root.optJSONArray("rounds") ?: return CountCombineLoad.Rejected("rounds missing")
        if (roundsJson.length() == 0) return CountCombineLoad.Rejected("rounds is empty")
        val seen = mutableSetOf<String>()
        val rounds = buildList {
            for (i in 0 until roundsJson.length()) {
                val round = roundsJson.optJSONObject(i) ?: return CountCombineLoad.Rejected("rounds[$i] is not an object")
                val id = round.optString("id").trim()
                if (id.isEmpty()) return CountCombineLoad.Rejected("rounds[$i] missing id")
                if (!seen.add(id)) return CountCombineLoad.Rejected("duplicate round id '$id'")
                if (!round.has("left") || !round.has("right")) return CountCombineLoad.Rejected("round '$id' missing left/right")
                val left = round.optInt("left", -1)
                val right = round.optInt("right", -1)
                // Same bounds as AppleQuestion, and the spoken choice sum+1 must stay a single number the voice path accepts.
                if (left !in 1..5 || right !in 1..5 || left + right !in 2..9) {
                    return CountCombineLoad.Rejected("round '$id' counts $left+$right are outside 1..5 and sum 2..9")
                }
                if (left + right > words.size) {
                    return CountCombineLoad.Rejected("round '$id' sum ${left + right} exceeds countingWords")
                }
                add(CountCombineRound(id, left, right))
            }
        }
        return CountCombineLoad.Ready(
            CountCombineDefinition(version, contentId, title, subtitle, rounds, lines, words),
        )
    }
}

/** Which dataset the existing games hub may open. Card 0 is only the apples id. */
object CountCombineHub {
    const val APPLES_CONTENT_ID = "game-math"

    fun apples(sets: List<CountCombineDefinition>): CountCombineDefinition? =
        sets.find { it.contentId == APPLES_CONTENT_ID }

    fun extras(sets: List<CountCombineDefinition>): List<CountCombineDefinition> =
        sets.filter { it.contentId != APPLES_CONTENT_ID }

    /**
     * Hub targets. "card:0" is only the apples id.
     * "more" is the first extra; "more:1" and later are the following extras in playable order.
     */
    fun openFromHub(sets: List<CountCombineDefinition>, target: String): CountCombineDefinition? = when {
        target == "card:0" -> apples(sets)
        target == "more" -> extras(sets).firstOrNull()
        target.startsWith("more:") -> target.removePrefix("more:").toIntOrNull()?.let { extras(sets).getOrNull(it) }
        else -> null
    }
}

object CountCombineCatalog {
    fun load(repository: ContentRepository, contentId: String): CountCombineLoad {
        val item = repository.getItem(contentId)
            ?: return CountCombineLoad.Rejected("unknown content '$contentId'")
        if (!item.enabled || item.type != ContentType.GAME) {
            return CountCombineLoad.Rejected("content '$contentId' is not an enabled game")
        }
        if (item.route != "game/math" && item.route != "game-math") {
            return CountCombineLoad.Rejected("content '$contentId' route '${item.route}' is not count_combine")
        }
        val json = repository.openExperienceJson(item)
            ?: return CountCombineLoad.Rejected("missing definition for '$contentId'")
        return CountCombineParser.parse(json, contentId)
    }

    fun playable(repository: ContentRepository): List<CountCombineDefinition> =
        repository.getEnabledItems()
            .asSequence()
            .filter { it.type == ContentType.GAME && (it.route == "game/math" || it.route == "game-math") }
            .sortedBy { it.sortOrder }
            .mapNotNull { load(repository, it.id).definitionOrNull() }
            .toList()
}
