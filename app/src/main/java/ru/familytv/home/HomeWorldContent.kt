package ru.familytv.home

import android.content.Context
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.content.PlatformServices
import ru.familytv.content.V1ContentShelf
import ru.familytv.home.watch.WatchCatalog
import ru.familytv.journey.JourneyEngine

/**
 * Real production projection for Home C. No demo titles, counters or saved-time stamps.
 * Worlds keep catalog flavour labels; OK still opens the five production destinations.
 */
data class HomeWorldContent(
    val labels: List<String>,
    val missionTitle: String,
    val missionCaption: String,
    val missionBadge: String,
    val missionAction: String,
    val journeyId: String?,
    val missionItem: ContentItem?,
    val continueVideo: ContentItem?,
    val emptyMission: Boolean,
) {
    companion object {
        val EMPTY_TITLE = "Сегодня вместе"
        val EMPTY_CAPTION = "Выбери остров стрелками. Луми рядом."
        val EMPTY_BADGE = "ОСТРОВА FAMILYTV"
        val EMPTY_ACTION = "К островам"

        fun load(context: Context): HomeWorldContent {
            val repo = PlatformServices.contentRepository(context)
            val filter = PlatformServices.familySettings(context).contentFilter()
            val items = V1ContentShelf.filterForHome(repo.getEnabledItems(), showDevShelf = false).filter {
                (filter.ageMin == null || it.metadata.ageMax == null || it.metadata.ageMax >= filter.ageMin) &&
                    (filter.ageMax == null || it.metadata.ageMin == null || it.metadata.ageMin <= filter.ageMax)
            }
            val labels = listOf(
                items.firstOrNull { it.type == ContentType.VIDEO && "move" !in it.homeSections }?.title ?: "Сказки и мультфильмы",
                items.firstOrNull { it.type == ContentType.GAME && it.homeSections.any { s -> s in listOf("solve", "play") } }?.title ?: "Игры вместе",
                items.firstOrNull { "explore" in it.homeSections && "discovery" in it.tags }?.title
                    ?: items.firstOrNull { "explore" in it.homeSections }?.title
                    ?: "Исследования",
                items.firstOrNull { "create" in it.homeSections }?.title ?: "Придумать историю",
                items.firstOrNull { "move" in it.homeSections }?.title ?: "Разбудим звёзды",
            )
            val videos = WatchCatalog.eligible(repo, filter)
            val continueVideo = WatchCatalog.continueWatching(videos, PlatformServices.watchProgress(context)).firstOrNull()
            val catalog = PlatformServices.journeyCatalog(context)
            val store = PlatformServices.journeyProgress(context)
            val journeys = catalog.all().filter { journey ->
                (filter.ageMin == null || journey.ageMax == null || journey.ageMax >= filter.ageMin) &&
                    (filter.ageMax == null || journey.ageMin == null || journey.ageMin <= filter.ageMax)
            }
            val active = store.listActive().firstNotNullOfOrNull { p -> journeys.find { it.id == p.journeyId } }
            val journey = active ?: journeys.firstOrNull { store.get(it.id)?.completed != true }
            val progress = journey?.let { JourneyEngine.sanitize(it, store.get(it.id)) }
            val node = if (journey != null && progress != null) {
                JourneyEngine.resumeNodeId(journey, progress)?.let(journey::node)
            } else null
            val nodeContentId = node?.let { JourneyEngine.resolveContentId(it, filter.ageMin, filter.ageMax) }
            val nodeItem = items.find { it.id == nodeContentId }
            if (journey != null && node != null) {
                return HomeWorldContent(
                    labels, journey.title, node.mapLabel,
                    if (progress?.started == true) "ТЕКУЩАЯ МИССИЯ • ПРОДОЛЖИТЬ В 1 КЛИК" else "НОВОЕ ПУТЕШЕСТВИЕ • ВМЕСТЕ С ЛУМИ",
                    if (progress?.started == true) "Продолжить" else "К карте",
                    journey.id, nodeItem, continueVideo, emptyMission = false,
                )
            }
            if (continueVideo != null) {
                return HomeWorldContent(
                    labels, continueVideo.title, "Можно досмотреть вместе",
                    "ПРОДОЛЖИТЬ ПРОСМОТР", "Продолжить",
                    null, continueVideo, continueVideo, emptyMission = false,
                )
            }
            return HomeWorldContent(
                labels, EMPTY_TITLE, EMPTY_CAPTION, EMPTY_BADGE, EMPTY_ACTION,
                null, null, null, emptyMission = true,
            )
        }
    }
}
