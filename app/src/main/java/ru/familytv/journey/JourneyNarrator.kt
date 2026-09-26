package ru.familytv.journey

/**
 * Lumi journey lines from structured state only — never invents progress.
 */
object JourneyNarrator {
    fun whereWeAre(journey: Journey, progress: JourneyProgress): String {
        if (progress.completed) return journey.completionLine
        if (!progress.started) return "Мы можем начать: ${journey.title}."
        val last = progress.lastVisitedNodeId?.let { journey.node(it) }
        return if (last != null) {
            if (last.id in progress.completedNodeIds) {
                "В прошлый раз мы закончили: ${last.mapLabel}."
            } else {
                "В прошлый раз мы добрались до: ${last.mapLabel}."
            }
        } else {
            "Мы начали: ${journey.title}."
        }
    }

    fun whatNext(journey: Journey, progress: JourneyProgress): String {
        if (progress.completed) {
            return "Экспедиция уже закончена. Можно посмотреть открытия или выбрать новое путешествие."
        }
        val labels = JourneyEngine.availableNextLabels(journey, progress)
        return when {
            labels.isEmpty() -> "Сейчас нет открытых точек — открой карту путешествия."
            labels.size == 1 -> "Теперь можно: ${labels.first()}."
            else -> "Теперь можно: ${labels.take(3).joinToString(" или ")}."
        }
    }

    fun whatUnexplored(journey: Journey, progress: JourneyProgress): String {
        val left = JourneyEngine.unexploredLabels(journey, progress)
        return when {
            left.isEmpty() -> "Мы уже исследовали все обязательные места."
            left.size == 1 -> "Мы ещё не исследовали: ${left.first()}."
            else -> "Мы ещё не исследовали: ${left.take(4).joinToString(", ")}."
        }
    }

    fun resumeCue(journey: Journey, progress: JourneyProgress): String {
        if (progress.completed) return "Мы закончили: ${journey.title}."
        if (!progress.started) return "Начать: ${journey.title}"
        val nodeId = JourneyEngine.resumeNodeId(journey, progress)
        val label = nodeId?.let { journey.node(it)?.mapLabel } ?: journey.title
        return "Продолжить: $label"
    }

    /** Journal recap — only from provided entries. */
    fun whatWeDiscovered(entries: List<JournalEntry>, worldFilter: String? = null): String {
        val filtered = entries.filter { worldFilter == null || it.world == worldFilter || it.journeyId != null }
            .take(6)
        if (filtered.isEmpty()) {
            return "Пока в журнале мало записей. Давайте что-нибудь исследуем вместе."
        }
        val bits = filtered.map { e ->
            when (e.type) {
                JournalEntryType.FACT_DISCOVERED -> "Мы узнали: ${e.body}"
                JournalEntryType.EXPERIMENT_OBSERVED -> "Мы проверили: ${e.body}"
                JournalEntryType.ADVENTURE_COMPLETED -> "Мы прошли: ${e.title}"
                JournalEntryType.STORY_CREATED -> "Мы создали историю: ${e.title}"
                JournalEntryType.WORLD_VISITED -> "Мы побывали: ${e.title}"
                JournalEntryType.OPTIONAL_MOMENT -> e.body
                JournalEntryType.JOURNEY_SUMMARY -> e.body
            }
        }
        return bits.joinToString(" ")
    }

    fun todaySummary(entries: List<JournalEntry>, sinceMs: Long): String {
        val today = entries.filter { it.createdAtMs >= sinceMs }.take(5)
        if (today.isEmpty()) {
            return "Сегодня мы ещё только начинаем. Можно продолжить путешествие."
        }
        return whatWeDiscovered(today)
    }
}
