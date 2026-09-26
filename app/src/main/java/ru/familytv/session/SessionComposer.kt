package ru.familytv.session

import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType

/**
 * Deterministic local session composition from catalog metadata.
 * No personalization / recommendation AI.
 */
object SessionComposer {
    data class Rules(
        val targetMinutes: Int = 20,
        val preferSkills: List<String> = emptyList(),
        val requireMove: Boolean = true,
        val maxItems: Int = 6,
    )

    fun compose(catalog: List<ContentItem>, rules: Rules = Rules()): LearningSession {
        val pool = catalog.filter { it.enabled && it.type != ContentType.SESSION }
        val picked = mutableListOf<ContentItem>()
        var minutes = 0

        fun take(predicate: (ContentItem) -> Boolean): ContentItem? {
            val item = pool.firstOrNull { it !in picked && predicate(it) } ?: return null
            picked += item
            minutes += item.metadata.estimatedMinutes ?: defaultMinutes(item)
            return item
        }

        // Rhythm: learn → interact → play → move → story
        take { it.type == ContentType.VIDEO && "learn" in it.homeSections }
        take {
            it.type == ContentType.INTERACTIVE &&
                (rules.preferSkills.isEmpty() || it.metadata.skills.any { s -> s in rules.preferSkills })
        }
        take { it.type == ContentType.GAME || "play" in it.homeSections }
        if (rules.requireMove) {
            take { "move" in it.homeSections || it.metadata.skills.any { s -> s.contains("MOVE", true) } }
        }
        take { "stories" in it.homeSections || it.metadata.contentPurpose == "story" }
        while (picked.size < rules.maxItems && minutes < rules.targetMinutes) {
            val more = take { true } ?: break
            if (minutes >= rules.targetMinutes) break
            @Suppress("UNUSED_VARIABLE")
            val _m = more
        }

        return LearningSession(
            id = "composed-today",
            title = "Сегодня вместе",
            description = "Короткая семейная сессия без бесконечного автоплея",
            estimatedMinutes = minutes.coerceAtLeast(10).coerceAtMost(rules.targetMinutes + 5),
            items = picked.map { SessionItemRef(it.id, optional = true) },
        )
    }

    private fun defaultMinutes(item: ContentItem): Int = when (item.type) {
        ContentType.VIDEO -> 2
        ContentType.GAME -> 4
        ContentType.INTERACTIVE -> 5
        ContentType.SESSION -> 15
        ContentType.ADVENTURE -> 12
    }
}
