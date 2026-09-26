package ru.familytv.home

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.content.PlatformServices
import ru.familytv.games.GameStyle
import ru.familytv.parent.ActivityKind

/** Details before longer content/sessions. Short games may still launch directly. */
class ContentDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun dp(v: Int) = GameStyle.dp(this, v)
        val id = intent.getStringExtra(EXTRA_CONTENT_ID).orEmpty()
        val item = PlatformServices.contentRepository(this).getItem(id)
        if (item == null) {
            showUnavailable()
            return
        }
        if (item.type == ContentType.VIDEO) {
            startActivity(Intent(this, ru.familytv.home.watch.WatchActivity::class.java)
                .putExtra("screen", "details").putExtra("contentId", item.id))
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        root.addView(GameStyle.button(this, "‹ Назад") { finish() }, LinearLayout.LayoutParams(-2, dp(52)))

        val art = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(28))
            background = GameStyle.rounded(this@ContentDetailsActivity, artColor(item), 20)
            minimumHeight = dp(220)
        }
        art.addView(GameStyle.text(this, typeLabel(item), 16f).apply {
            setTextColor(GameStyle.teal)
            gravity = Gravity.CENTER
        })
        art.addView(GameStyle.text(this, item.title, 36f, true).apply { gravity = Gravity.CENTER })
        root.addView(art, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        root.addView(GameStyle.text(this, item.description, 20f).apply {
            setPadding(0, dp(16), 0, dp(8))
        })
        val minutes = item.metadata.estimatedMinutes?.let { "~$it мин" }
            ?: item.durationMs?.let { "~${(it / 1000).coerceAtLeast(1)} сек" }
            ?: ""
        val topics = item.metadata.topics.take(4).joinToString(" · ")
        val modes = item.metadata.interactionModes.take(4).joinToString(" · ")
        val meta = listOfNotNull(
            minutes.ifBlank { null },
            topics.ifBlank { null },
            modes.ifBlank { null },
        ).joinToString("  ·  ")
        if (meta.isNotBlank()) {
            root.addView(GameStyle.text(this, meta, 16f).apply { setTextColor(GameStyle.muted) })
        }

        val startLabel = if (hasResume(item)) "Продолжить" else "Начать"
        val start = GameStyle.button(this, startLabel) {
            PlatformServices.activityHistory(this).record(
                kind = when (item.type) {
                    ContentType.VIDEO -> ActivityKind.WATCH
                    ContentType.GAME -> ActivityKind.GAME
                    ContentType.INTERACTIVE, ContentType.ADVENTURE -> ActivityKind.INTERACTIVE
                    ContentType.SESSION -> ActivityKind.SESSION
                },
                title = item.title,
                contentId = item.id,
                topics = item.metadata.topics + item.metadata.skills,
            )
            ContentNavigator.open(this, item)
            finish()
        }
        root.addView(start, LinearLayout.LayoutParams(-2, dp(64)).apply {
            topMargin = dp(24)
            gravity = Gravity.START
        })
        start.requestFocus()

        setContentView(ScrollView(this).apply {
            setBackgroundColor(GameStyle.background)
            addView(root)
        })
    }

    private fun hasResume(item: ContentItem): Boolean {
        return when (item.type) {
            ContentType.SESSION -> {
                // Best-effort: any incomplete session progress counts as continue for this card
                listOf("morning", "today-together", item.id.removePrefix("pkg-")).any { id ->
                    val p = PlatformServices.sessionProgress(this).get(id)
                    p != null && !p.completed
                }
            }
            ContentType.INTERACTIVE -> {
                val expId = item.experienceRef
                    ?.substringAfterLast('/')
                    ?.removeSuffix(".json")
                    ?: return false
                val p = PlatformServices.experienceProgress(this).get(expId)
                p != null && !p.completed
            }
            ContentType.ADVENTURE -> {
                val advId = item.adventureRef
                    ?.substringAfterLast('/')
                    ?.removeSuffix(".json")
                    ?: item.id
                PlatformServices.adventureProgress(this).hasIncomplete(advId)
            }
            else -> false
        }
    }

    private fun showUnavailable() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
        }
        root.addView(GameStyle.text(this, "Это занятие сейчас недоступно.", 28f, true).apply {
            gravity = Gravity.CENTER
        })
        val back = GameStyle.button(this, "Назад") { finish() }
        root.addView(back, LinearLayout.LayoutParams(-2, dp(56)).apply {
            topMargin = dp(20)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(root.apply { setBackgroundColor(GameStyle.background) })
        back.requestFocus()
    }

    private fun artColor(item: ContentItem) = when (item.type) {
        ContentType.VIDEO -> Color.rgb(210, 232, 245)
        ContentType.GAME -> GameStyle.peach
        ContentType.INTERACTIVE -> GameStyle.lavender
        ContentType.SESSION -> Color.rgb(220, 240, 225)
        ContentType.ADVENTURE -> Color.rgb(225, 220, 255)
    }

    private fun typeLabel(item: ContentItem) = when (item.type) {
        ContentType.VIDEO -> "Видео"
        ContentType.GAME -> "Игра"
        ContentType.INTERACTIVE -> "Занятие"
        ContentType.SESSION -> "Сессия"
        ContentType.ADVENTURE -> "Приключение"
    }

    companion object {
        const val EXTRA_CONTENT_ID = "contentId"
    }
}
