package ru.familytv.space

import android.content.Context
import android.content.Intent
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType

/**
 * Production «Большая космическая экспедиция» V1.
 * Presentation only — journey id, nodes and adventure JSON stay the catalog source of truth.
 */
object SpaceRoutes {
    const val JOURNEY_ID = "space-grand-expedition"
    const val CONTENT_ID = "adv-space-expedition"
    const val ADVENTURE_ID = "space-expedition"
    const val ADVENTURE_ASSET = "space-expedition.json"
    const val ART_DIR = "space_expedition_v1"
    const val SORT_ART_DIR = "$ART_DIR/sort"
    const val FONT = "$ART_DIR/fonts/PlusJakartaSans.ttf"
    const val EXTRA_JOURNEY_ID = "journey_id"
    const val FALLBACK_TITLE = "Большая космическая экспедиция"
    val HISTORY_TOPICS = listOf("adventure", "explore")
    val BACKGROUNDS = linkedMapOf(
        "entry" to "entry_bg.jpg",
        "journey" to "journey_bg.jpg",
        "intro" to "intro_bg.jpg",
        "challenge" to "challenge_active_bg.jpg",
        "success" to "success_bg.jpg",
    )

    fun handlesJourney(journeyId: String?): Boolean = journeyId == JOURNEY_ID

    fun opens(item: ContentItem): Boolean {
        if (item.id == CONTENT_ID) return true
        val ref = item.adventureRef?.substringAfterLast('/')?.removeSuffix(".json")
        return item.type == ContentType.ADVENTURE && ref == ADVENTURE_ID
    }

    fun intent(context: Context, journeyId: String = JOURNEY_ID): Intent =
        Intent(context, SpaceExpeditionActivity::class.java)
            .putExtra(EXTRA_JOURNEY_ID, journeyId)
}
