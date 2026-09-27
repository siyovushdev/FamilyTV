package ru.familytv.creative

import android.content.Context
import android.content.Intent
import ru.familytv.content.ContentItem

/** Production Create / Story Builder V1 entry. Catalog id stays [CONTENT_ID] so Journey CREATE nodes keep working. */
object StoryRoutes {
    const val CONTENT_ID = "lumi-story"
    const val WORLD_ID = "create"
    const val GRAMMAR_ASSET = "catalog/stories/create-story-v1.json"
    const val ART_DIR = "create_story_v1"
    const val FALLBACK_TITLE = "Придумать историю"
    val HISTORY_TOPICS = listOf("story", "create")
    val ART = listOf("create_home", "choose_hero", "choose_world", "forest_door", "consequence", "ending")

    fun opens(item: ContentItem): Boolean = item.route == "creative/story" || item.id == CONTENT_ID

    fun intent(context: Context): Intent = Intent(context, CreativeStudioActivity::class.java)
}
