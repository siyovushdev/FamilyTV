package ru.familytv.move

import android.content.Context
import android.content.Intent
import ru.familytv.content.ContentItem

/** Production Move V1 entry. Catalog id stays [CONTENT_ID] so Journey REST nodes keep working. */
object MoveRoutes {
    const val CONTENT_ID = "move-stretch"
    const val WORLD_ID = "move"
    const val EXPERIENCE_ASSET = "catalog/move/wake-stars.json"
    const val ART_DIR = "move_v1"
    const val FALLBACK_TITLE = "Разбудим звёзды"
    val HISTORY_TOPICS = listOf("move")
    val ART = listOf("move_home", "ready", "reach", "comets", "freeze", "balance", "ending")

    fun opens(item: ContentItem): Boolean = item.route == "move/stars" || item.id == CONTENT_ID

    fun handles(worldId: String): Boolean = worldId == WORLD_ID

    fun intent(context: Context): Intent = Intent(context, MoveActivity::class.java)
}
