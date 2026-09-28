package ru.familytv.interaction

import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.explore.ExploreDestination

enum class InteractionSurface {
    HOME,
    ADVENTURE,
    INTERACTIVE,
    GAME,
    DETAILS,
    OTHER,
}

data class RouteContext(
    val surface: InteractionSurface,
    /** True when a challenge/question is waiting for an answer. */
    val awaitingAnswer: Boolean = false,
    /** True when NEXT/continue is a valid story advance. */
    val allowNext: Boolean = false,
    val catalog: List<ContentItem> = emptyList(),
)

sealed class RouteResult {
    data class Action(val action: FamilyAction) : RouteResult()
    data object Unhandled : RouteResult()
}

/**
 * Maps normalized transcripts to allowlisted [FamilyAction]s.
 * Sensitive parent/admin intents become [FamilyAction.BlockedSensitive] or OpenParentGate.
 * Does not call Android APIs — pure mapping for tests.
 */
object VoiceCommandRouter {
    private val repeatPhrases = listOf(
        "повтори", "ещё раз", "еще раз", "повтори задание", "что нужно сделать",
        "что делать", "скажи ещё", "скажи еще",
    )
    private val helpPhrases = listOf(
        "помоги", "я не понимаю", "подскажи", "дай подсказку", "не понял", "не поняла",
    )
    private val homePhrases = listOf("домой", "на главную", "к мирам", "домой пожалуйста")
    private val backPhrases = listOf("назад", "вернись", "выйти")
    private val pausePhrases = listOf("пауза", "поставь на паузу", "останови")
    private val resumePhrases = listOf("продолжай", "продолжить", "дальше играй")
    private val nextPhrases = listOf("дальше", "готово", "сделали", "следующий")
    private val parentPhrases = listOf("родителям", "родительский режим", "для родителей")
    private val blockedPhrases = listOf(
        "смени пин", "измени пин", "поменять пин", "экранное время",
        "обнови пакеты", "открой админ", "developer", "content admin",
    )

    fun route(context: RouteContext, normalizedRaw: String): RouteResult {
        val normalized = normalizedRaw.lowercase().trim()
        if (normalized.isBlank()) return RouteResult.Unhandled

        if (blockedPhrases.any { normalized.contains(it) }) {
            return RouteResult.Action(FamilyAction.BlockedSensitive)
        }
        if (matchesAny(normalized, parentPhrases)) {
            return RouteResult.Action(FamilyAction.OpenParentGate)
        }
        if (matchesAny(normalized, homePhrases)) {
            return RouteResult.Action(FamilyAction.Home)
        }
        if (matchesAny(normalized, backPhrases)) {
            return RouteResult.Action(FamilyAction.Back)
        }
        if (matchesAny(normalized, repeatPhrases)) {
            return RouteResult.Action(FamilyAction.Repeat)
        }
        if (matchesAny(normalized, helpPhrases)) {
            return RouteResult.Action(FamilyAction.Help)
        }
        if (matchesAny(normalized, pausePhrases)) {
            return RouteResult.Action(FamilyAction.Pause)
        }
        if (matchesAny(normalized, resumePhrases) && !context.awaitingAnswer) {
            return RouteResult.Action(FamilyAction.Resume)
        }

        // Journal / journey continuity — before free curiosity where possible
        journeyAction(normalized)?.let { return RouteResult.Action(it) }

        // Curiosity questions — before challenge answers (adventure / interactive)
        if (context.surface == InteractionSurface.ADVENTURE ||
            context.surface == InteractionSurface.INTERACTIVE ||
            context.surface == InteractionSurface.HOME
        ) {
            val intent = ru.familytv.knowledge.CuriosityIntentDetector.detect(normalized)
                ?: if (ru.familytv.knowledge.CuriosityIntentDetector.isBareCuriosity(normalized) ||
                    ru.familytv.knowledge.CuriosityIntentDetector.looksLikeCuriosity(normalized) ||
                    normalized.contains("расскажи") ||
                    normalized.contains("придумай") ||
                    normalized.contains("знаешь про") ||
                    normalized.contains("какой-нибудь факт") ||
                    normalized.contains("какой нибудь факт")
                ) {
                    when {
                        ru.familytv.knowledge.CuriosityIntentDetector.isTellMeMore(normalized) ->
                            ru.familytv.knowledge.CuriosityIntent.TELL_ME_MORE
                        ru.familytv.knowledge.CuriosityIntentDetector.isWhatIf(normalized) ->
                            ru.familytv.knowledge.CuriosityIntent.WHAT_IF
                        else -> ru.familytv.knowledge.CuriosityIntent.WHY
                    }
                } else null
            if (intent != null) {
                return RouteResult.Action(FamilyAction.AskLumi(intent, normalized))
            }
        }

        // Home discovery / world / content — before free-form answer
        if (context.surface == InteractionSurface.HOME || context.surface == InteractionSurface.DETAILS) {
            // Prefer specific content title match over world shortcuts
            ContentDiscovery.find(context.catalog, normalized)?.let { item ->
                val looksLikeOpen = listOf("открой", "начать", "запусти", "хочу", "продолжить").any { normalized.contains(it) } ||
                    normalized.contains(item.title.lowercase().take(8))
                if (looksLikeOpen || item.type == ContentType.ADVENTURE && normalized.contains("экспедиц")) {
                    return RouteResult.Action(
                        when (item.type) {
                            ContentType.ADVENTURE -> FamilyAction.StartAdventure(item.id)
                            ContentType.GAME -> FamilyAction.OpenGame(item.route ?: item.id)
                            else -> FamilyAction.OpenContent(item.id)
                        },
                    )
                }
            }
            worldIntent(normalized)?.let { return RouteResult.Action(it) }
            ContentDiscovery.find(context.catalog, normalized)?.let { item ->
                return RouteResult.Action(
                    when (item.type) {
                        ContentType.ADVENTURE -> FamilyAction.StartAdventure(item.id)
                        ContentType.GAME -> FamilyAction.OpenGame(item.route ?: item.id)
                        else -> FamilyAction.OpenContent(item.id)
                    },
                )
            }
            if (normalized.contains("продолжить") || normalized == "продолжай") {
                return RouteResult.Action(FamilyAction.ContinueJourney())
            }
        }

        if (context.allowNext && matchesAny(normalized, nextPhrases)) {
            return RouteResult.Action(FamilyAction.Next)
        }

        // Challenge answer path — only when awaiting and not a global command
        if (context.awaitingAnswer) {
            return RouteResult.Action(FamilyAction.Answer(normalized))
        }

        // Adventure story advance
        if (context.surface == InteractionSurface.ADVENTURE && matchesAny(normalized, nextPhrases)) {
            return RouteResult.Action(FamilyAction.Next)
        }

        return RouteResult.Unhandled
    }

    private fun journeyAction(normalized: String): FamilyAction? {
        if (normalized.contains("что мы узнали") ||
            normalized.contains("что мы сегодня делали") ||
            normalized.contains("что мы нашли") ||
            normalized.contains("что мы открыли") ||
            normalized.contains("наши открытия")
        ) {
            val world = when {
                normalized.contains("лесу") || normalized.contains("лес") -> "explore"
                normalized.contains("космос") || normalized.contains("лун") -> "explore"
                else -> null
            }
            return FamilyAction.AskWhatWeDiscovered(world)
        }
        if (normalized.contains("покажи карту") ||
            normalized.contains("открой карту") ||
            normalized.contains("карта путешеств") ||
            normalized == "карта"
        ) {
            return FamilyAction.OpenJourneyMap()
        }
        if (normalized.contains("журнал") || normalized.contains("наши истории")) {
            return FamilyAction.OpenJournal
        }
        if (normalized.contains("куда дальше") ||
            normalized.contains("что мы ещё не исследовали") ||
            normalized.contains("что мы еще не исследовали") ||
            normalized.contains("продолжить путешествие") ||
            normalized.contains("давай продолжим") ||
            normalized.contains("вернёмся") ||
            normalized.contains("вернемся")
        ) {
            val journeyId = when {
                normalized.contains("космос") || normalized.contains("лун") ||
                    normalized.contains("космическ") -> "space-grand-expedition"
                normalized.contains("лес") -> "forest-secrets"
                else -> null
            }
            return if (normalized.contains("карту")) {
                FamilyAction.OpenJourneyMap(journeyId)
            } else {
                FamilyAction.ContinueJourney(journeyId)
            }
        }
        if (normalized.contains("давай продолжим космос") ||
            (normalized.contains("продолжим") && normalized.contains("космос"))
        ) {
            return FamilyAction.ContinueJourney("space-grand-expedition")
        }
        return null
    }

    private fun worldIntent(normalized: String): FamilyAction? {
        val worlds = listOf(
            "игры" to "play",
            "игра" to "play",
            "поиграть" to "play",
            "истории" to "stories",
            "история" to "stories",
            "узнать" to "learn",
            "исследуй" to "explore",
            "исследовать" to "explore",
            "космос" to ExploreDestination.SPACE.route,
            "решай" to "solve",
            "двигайся" to "move",
            "двигаться" to "move",
            "движение" to "move",
            "создавай" to "create",
        )
        for ((phrase, world) in worlds) {
            if (normalized.contains("покажи $phrase") ||
                normalized.contains("открой $phrase") ||
                normalized.contains("хочу $phrase") ||
                normalized == phrase ||
                normalized.contains("мир $phrase")
            ) {
                return FamilyAction.OpenWorld(world)
            }
        }
        if (normalized.contains("космос") || normalized.contains("космическ")) {
            return FamilyAction.OpenWorld(ExploreDestination.SPACE.route)
        }
        return null
    }

    private fun matchesAny(normalized: String, phrases: List<String>): Boolean =
        phrases.any { p -> normalized == p || normalized.startsWith("$p ") || normalized.contains(p) }
}

/**
 * Catalog search for voice content discovery — uses metadata, not hardcoded package IDs.
 */
object ContentDiscovery {
    fun find(catalog: List<ContentItem>, query: String): ContentItem? {
        val q = query.lowercase().trim()
        if (q.isBlank()) return null
        val enabled = catalog.filter { it.enabled }
        val needle = stripNoise(q)
        enabled.firstOrNull { it.title.lowercase() == q || it.title.lowercase() == needle }?.let { return it }
        enabled.firstOrNull {
            val title = it.title.lowercase()
            title.contains(needle) || needle.contains(title) || tokensOverlap(needle, title)
        }?.let { return it }
        fun score(item: ContentItem): Int {
            var s = 0
            val hay = buildList {
                add(item.title.lowercase())
                addAll(item.tags.map { it.lowercase() })
                addAll(item.categories.map { it.lowercase() })
                addAll(item.homeSections.map { it.lowercase() })
                addAll(item.metadata.topics.map { it.lowercase() })
                addAll(item.metadata.skills.map { it.lowercase() })
                item.metadata.contentPurpose?.let { add(it.lowercase()) }
            }
            for (h in hay) {
                if (h.isNotBlank() && (q.contains(h) || needle.contains(h) || h.contains(needle) || tokensOverlap(needle, h))) {
                    s += 2
                }
            }
            if (item.type == ContentType.ADVENTURE && (q.contains("приключен") || q.contains("экспедиц"))) s += 3
            if (q.contains("космос") && hay.any { it.contains("space") || it.contains("космос") }) s += 3
            return s
        }
        return enabled.map { it to score(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    private fun stripNoise(q: String): String =
        q.replace("открой", "")
            .replace("начать", "")
            .replace("покажи", "")
            .replace("хочу", "")
            .replace("продолжить", "")
            .trim()

    private fun tokensOverlap(a: String, b: String): Boolean {
        val ta = a.split(Regex("\\s+|[:：]")).map { it.trim() }.filter { it.length >= 4 }
        val tb = b.split(Regex("\\s+|[:：]")).map { it.trim() }.filter { it.length >= 4 }
        return ta.any { t -> tb.any { u -> u.startsWith(t.take(5)) || t.startsWith(u.take(5)) } }
    }
}
