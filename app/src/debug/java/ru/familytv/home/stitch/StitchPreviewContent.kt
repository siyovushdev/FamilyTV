package ru.familytv.home.stitch

import android.content.Context
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.content.ContentOverrideStore
import ru.familytv.content.PlatformServices
import ru.familytv.content.V1ContentShelf
import ru.familytv.journey.JourneyEngine

/** Read-only projection of the existing catalog and device progress; no sample progress writes. */
internal data class StitchPreviewContent(
    val worlds: List<ContentItem?>,
    val missionTitle: String,
    val missionCaption: String,
    val missionBadge: String,
    val missionAction: String,
    val journeyId: String?,
    val missionItem: ContentItem?,
    val continueVideo: ContentItem?,
    val creativeShortcut: Boolean,
) {
    companion object {
        fun load(context: Context): StitchPreviewContent {
            val repo = PlatformServices.contentRepository(context)
            val filter = PlatformServices.familySettings(context).contentFilter()
            val items = V1ContentShelf.filterForHome(repo.getEnabledItems(), showDevShelf = false).filter {
                (filter.ageMin == null || it.metadata.ageMax == null || it.metadata.ageMax >= filter.ageMin) &&
                    (filter.ageMax == null || it.metadata.ageMin == null || it.metadata.ageMin <= filter.ageMax)
            }
            val worlds = listOf(
                items.firstOrNull { it.type == ContentType.VIDEO && "move" !in it.homeSections },
                items.firstOrNull { it.type == ContentType.GAME && it.homeSections.any { s -> s in listOf("solve", "play") } },
                items.firstOrNull { "explore" in it.homeSections && "discovery" in it.tags }
                    ?: items.firstOrNull { "explore" in it.homeSections },
                items.firstOrNull { "create" in it.homeSections },
                items.firstOrNull { "move" in it.homeSections },
            )
            // Existing bundled creative route has no experienceRef and is skipped by the
            // production parser. Keep that parser unchanged; expose the existing studio only
            // for this exact missing-entry case, respecting its enable flag and 3–9 age band.
            val creativeShortcut = worlds[3] == null &&
                repo.getCatalog().validationWarnings.any {
                    it.startsWith("Item[lumi-story]: INTERACTIVE requires experienceRef")
                } && ContentOverrideStore(context).isEnabled("lumi-story", true) &&
                (filter.ageMin == null || filter.ageMin <= 9) &&
                (filter.ageMax == null || filter.ageMax >= 3)
            val videos = items.filter { it.type == ContentType.VIDEO }
            val video = if (videos.isEmpty()) null else PlatformServices.watchProgress(context)
                .continueWatching(videos.map { it.id }.toSet())
                .firstNotNullOfOrNull { p -> videos.find { it.id == p.contentId } }
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
            // A missing/filtered node is never bypassed by directly launching its content.
            if (journey != null && node != null) {
                return StitchPreviewContent(
                    worlds, journey.title, node.mapLabel,
                    if (progress?.started == true) "ТЕКУЩАЯ МИССИЯ • ПРОДОЛЖИТЬ В 1 КЛИК" else "НОВОЕ ПУТЕШЕСТВИЕ • ВМЕСТЕ С ЛУМИ",
                    if (progress?.started == true) "Продолжить" else "К карте",
                    journey.id, nodeItem, video, creativeShortcut,
                )
            }
            val fallback = video ?: items.firstOrNull { "mission" in it.homeSections }
            return StitchPreviewContent(
                worlds, fallback?.title ?: "Выбирай свой остров", "Луми рядом — можно отправляться!",
                if (video != null) "ПРОДОЛЖИТЬ ПРОСМОТР" else "СЕГОДНЯ ВМЕСТЕ",
                if (video != null) "Продолжить" else "Открыть", null, fallback, null, creativeShortcut,
            )
        }
    }
}
