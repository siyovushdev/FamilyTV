package ru.familytv.games

import org.json.JSONObject
import ru.familytv.content.ContentRepository
import ru.familytv.content.ContentType

/** One paintable choice. [voice] is the existing color word, not a new alias language. */
internal data class AttributeApplyOption(
    val id: String,
    val label: String,
    val voice: String,
    val color: GameColor,
)

internal data class AttributeApplyLines(
    val prompt: String,
    val help: String,
    val wrong: String,
    val success: String,
    val launched: String,
    val scene: String,
)

/**
 * Typed ATTRIBUTE_APPLY dataset for the existing rocket painter.
 * The runtime only knows the rocket wing and the four existing paint colors.
 */
internal data class AttributeApplyDefinition(
    val schemaVersion: Int,
    val contentId: String,
    val title: String,
    val subtitle: String,
    val targetObject: String,
    val targetPart: String,
    val options: List<AttributeApplyOption>,
    val correctId: String,
    val lines: AttributeApplyLines,
) {
    val correct: GameColor get() = options.first { it.id == correctId }.color
    val paints: List<GameColor> get() = options.map { it.color }
}

internal sealed class AttributeApplyLoad {
    data class Ready(val definition: AttributeApplyDefinition) : AttributeApplyLoad()
    data class Rejected(val reason: String) : AttributeApplyLoad()

    fun definitionOrNull(): AttributeApplyDefinition? = (this as? Ready)?.definition
}

internal object AttributeApplyParser {
    const val TEMPLATE = "attribute_apply"
    const val SCHEMA_VERSION = 1
    const val OBJECT_ROCKET = "rocket"
    const val PART_WING = "wing"

    private val colors = mapOf(
        "red" to GameColor.RED,
        "blue" to GameColor.BLUE,
        "yellow" to GameColor.YELLOW,
        "green" to GameColor.GREEN,
    )

    fun parse(json: String, expectedContentId: String): AttributeApplyLoad {
        if (expectedContentId.isBlank()) return AttributeApplyLoad.Rejected("content id is blank")
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return AttributeApplyLoad.Rejected("definition is not JSON")
        }
        val version = root.optInt("schemaVersion", -1)
        if (version != SCHEMA_VERSION) return AttributeApplyLoad.Rejected("unsupported schemaVersion=$version")
        val template = root.optString("template").trim()
        if (template != TEMPLATE) return AttributeApplyLoad.Rejected("template is '$template'")
        val contentId = root.optString("contentId").trim()
        if (contentId != expectedContentId) {
            return AttributeApplyLoad.Rejected("definition contentId '$contentId' does not match '$expectedContentId'")
        }
        val title = root.optString("title").trim()
        if (title.isEmpty()) return AttributeApplyLoad.Rejected("title is blank")
        val subtitle = root.optString("subtitle").trim()
        if (subtitle.isEmpty()) return AttributeApplyLoad.Rejected("subtitle is blank")
        val target = root.optJSONObject("target") ?: return AttributeApplyLoad.Rejected("target missing")
        val targetObject = target.optString("object").trim()
        val targetPart = target.optString("part").trim()
        if (targetObject != OBJECT_ROCKET || targetPart != PART_WING) {
            return AttributeApplyLoad.Rejected("unsupported target $targetObject/$targetPart")
        }
        val linesJson = root.optJSONObject("lines") ?: return AttributeApplyLoad.Rejected("lines missing")
        val lines = AttributeApplyLines(
            prompt = linesJson.optString("prompt").trim(),
            help = linesJson.optString("help").trim(),
            wrong = linesJson.optString("wrong").trim(),
            success = linesJson.optString("success").trim(),
            launched = linesJson.optString("launched").trim(),
            scene = linesJson.optString("scene").trim(),
        )
        if (listOf(lines.prompt, lines.help, lines.wrong, lines.success, lines.launched, lines.scene).any { it.isEmpty() }) {
            return AttributeApplyLoad.Rejected("a child-facing line is blank")
        }
        val optionsJson = root.optJSONArray("options") ?: return AttributeApplyLoad.Rejected("options missing")
        if (optionsJson.length() != 3) return AttributeApplyLoad.Rejected("options must be the three paint jars")
        val seen = mutableSetOf<String>()
        val options = buildList {
            for (i in 0 until optionsJson.length()) {
                val option = optionsJson.optJSONObject(i) ?: return AttributeApplyLoad.Rejected("options[$i] is not an object")
                val id = option.optString("id").trim()
                val color = colors[id] ?: return AttributeApplyLoad.Rejected("options[$i] color '$id' is unknown")
                if (!seen.add(id)) return AttributeApplyLoad.Rejected("duplicate option '$id'")
                val label = option.optString("label").trim()
                if (label.isEmpty()) return AttributeApplyLoad.Rejected("options[$i] label is blank")
                val voice = option.optString("voice").trim()
                if (voice != color.answer) {
                    return AttributeApplyLoad.Rejected("options[$i] voice '$voice' is not the color word '${color.answer}'")
                }
                add(AttributeApplyOption(id, label, voice, color))
            }
        }
        val correctId = root.optString("correct").trim()
        if (correctId !in seen) return AttributeApplyLoad.Rejected("correct '$correctId' is not one of the options")
        return AttributeApplyLoad.Ready(
            AttributeApplyDefinition(version, contentId, title, subtitle, targetObject, targetPart, options, correctId, lines),
        )
    }
}

/** Card 1 is only the original rocket dataset. Further sets are extra buttons. */
internal object AttributeApplyHub {
    const val ROCKET_CONTENT_ID = "game-color"

    fun rocket(sets: List<AttributeApplyDefinition>): AttributeApplyDefinition? =
        sets.find { it.contentId == ROCKET_CONTENT_ID }

    fun extras(sets: List<AttributeApplyDefinition>): List<AttributeApplyDefinition> =
        sets.filter { it.contentId != ROCKET_CONTENT_ID }

    fun openFromHub(sets: List<AttributeApplyDefinition>, target: String): AttributeApplyDefinition? = when {
        target == "card:1" -> rocket(sets)
        target == "paint" -> extras(sets).firstOrNull()
        target.startsWith("paint:") -> target.removePrefix("paint:").toIntOrNull()?.let { extras(sets).getOrNull(it) }
        else -> null
    }
}

internal object AttributeApplyCatalog {
    fun load(repository: ContentRepository, contentId: String): AttributeApplyLoad {
        val item = repository.getItem(contentId)
            ?: return AttributeApplyLoad.Rejected("unknown content '$contentId'")
        if (!item.enabled || item.type != ContentType.GAME) {
            return AttributeApplyLoad.Rejected("content '$contentId' is not an enabled game")
        }
        if (item.route != "game/color" && item.route != "game-color") {
            return AttributeApplyLoad.Rejected("content '$contentId' route '${item.route}' is not attribute_apply")
        }
        val json = repository.openExperienceJson(item)
            ?: return AttributeApplyLoad.Rejected("missing definition for '$contentId'")
        return AttributeApplyParser.parse(json, contentId)
    }

    fun playable(repository: ContentRepository): List<AttributeApplyDefinition> =
        repository.getEnabledItems()
            .asSequence()
            .filter { it.type == ContentType.GAME && (it.route == "game/color" || it.route == "game-color") }
            .sortedBy { it.sortOrder }
            .mapNotNull { load(repository, it.id).definitionOrNull() }
            .toList()
}
