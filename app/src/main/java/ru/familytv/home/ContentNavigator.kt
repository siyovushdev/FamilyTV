package ru.familytv.home

import android.content.Intent
import android.util.Log
import ru.familytv.adventure.AdventureActivity
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.creative.CreativeStudioActivity
import ru.familytv.explore.ExploreDestination
import ru.familytv.explore.ExploreRoutes
import ru.familytv.games.GamesDestination
import ru.familytv.interactive.InteractiveExperienceActivity
import ru.familytv.player.VideoPlayerActivity
import ru.familytv.session.LearningSessionActivity

object ContentNavigator {
    private const val TAG = "FamilyNav"

    fun open(activity: android.app.Activity, item: ContentItem) {
        val intent = when (item.type) {
            ContentType.VIDEO -> Intent(activity, VideoPlayerActivity::class.java).putExtra(
                VideoPlayerActivity.EXTRA_CONTENT_ID, item.id,
            )
            ContentType.GAME -> gameIntent(activity, item.route)
            ContentType.INTERACTIVE -> {
                if (item.route == "creative/story" || item.id == "lumi-story") {
                    Intent(activity, CreativeStudioActivity::class.java)
                } else {
                    Intent(activity, InteractiveExperienceActivity::class.java)
                        .putExtra(InteractiveExperienceActivity.EXTRA_CONTENT_ID, item.id)
                }
            }
            ContentType.SESSION -> Intent(activity, LearningSessionActivity::class.java)
                .putExtra(LearningSessionActivity.EXTRA_CONTENT_ID, item.id)
            ContentType.ADVENTURE -> if (ExploreRoutes.opensWeather(item)) {
                ExploreRoutes.intent(activity, ExploreDestination.WEATHER)
            } else {
                Intent(activity, AdventureActivity::class.java)
                    .putExtra(AdventureActivity.EXTRA_CONTENT_ID, item.id)
            }
        }
        if (intent == null) {
            Log.w(TAG, "No route for content ${item.id}")
            return
        }
        activity.startActivity(intent)
    }

    fun gameIntent(activity: android.app.Activity, route: String?): Intent? {
        val destination = GamesDestination.fromRoute(route) ?: run {
            Log.w(TAG, "Unknown game route: $route")
            return null
        }
        return Intent().setClassName(activity, destination.activityName)
    }

}

data class HomeSection(
    val id: String,
    val title: String,
)

/** Home 2.0 worlds — data-driven section ids for adventures & activities. */
object HomeSections {
    val ALL = listOf(
        HomeSection("continue", "Продолжить"),
        HomeSection("mission", "Сейчас в пути"),
        HomeSection("today", "Сегодня вместе"),
        HomeSection("explore", "Исследуй"),
        HomeSection("solve", "Решай"),
        HomeSection("create", "Создавай"),
        HomeSection("stories", "Истории"),
        HomeSection("move", "Двигайся"),
        // Legacy Beta ids kept so existing packages still appear
        HomeSection("play", "Поиграть"),
        HomeSection("learn", "Узнать новое"),
    )
}
