package ru.familytv.journey

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import ru.familytv.content.PlatformServices
import ru.familytv.creative.CreativeStudioActivity
import ru.familytv.creative.StoryStore
import ru.familytv.games.GameStyle
import ru.familytv.home.HomeActivity
import ru.familytv.interaction.PresentationImportance
import ru.familytv.interaction.PresentationMessage
import ru.familytv.interaction.PresentationRole
import ru.familytv.trial.TrialEventStore
import ru.familytv.ui.FamilyTvTheme

/**
 * Discovery Journal + story gallery — “мы узнали / нашли / создали”.
 */
class DiscoveryJournalActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var lastSpoken: String? = null
    private var filterJourney: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filterJourney = intent.getStringExtra(EXTRA_JOURNEY_ID)
        PlatformServices.trialEvents(this).record(TrialEventStore.JOURNAL_OPENED, filterJourney)
        PlatformServices.journeyCoordinator(this).syncStoriesIntoJournal(StoryStore(this))
        rebuild()
    }

    private fun rebuild() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val journal = PlatformServices.discoveryJournal(this)
        val entries = if (filterJourney != null) {
            journal.listForJourney(filterJourney!!)
        } else {
            journal.list()
        }
        val stories = StoryStore(this).list()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(32))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 250, 242), Color.rgb(236, 244, 238)),
            )
        }
        root.addView(GameStyle.text(this, "Наши открытия", 28f, true).apply {
            setTextColor(FamilyTvTheme.teal)
        })
        status = GameStyle.text(this, "", 16f).apply {
            setTextColor(FamilyTvTheme.muted)
            setPadding(0, dp(6), 0, dp(12))
        }
        val summary = JourneyNarrator.whatWeDiscovered(entries.ifEmpty { journal.list() })
        status.text = summary
        root.addView(status)
        speak(summary)

        root.addView(GameStyle.text(this, "Журнал", 20f, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        if (entries.isEmpty()) {
            root.addView(GameStyle.text(this, "Пока пусто — давайте исследуем вместе.", 16f))
        } else {
            entries.take(24).forEach { e ->
                root.addView(entryCard(e), LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(10)
                })
            }
        }

        root.addView(GameStyle.text(this, "Наши истории", 20f, true).apply {
            setPadding(0, dp(18), 0, dp(8))
        })
        if (stories.isEmpty()) {
            root.addView(GameStyle.text(this, "Ещё нет сохранённых историй.", 16f))
        } else {
            stories.forEach { s ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = GameStyle.rounded(this@DiscoveryJournalActivity, Color.WHITE, 16)
                    isFocusable = true
                    isClickable = true
                    contentDescription = s.title
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(GameStyle.text(this, s.title, 18f, true))
                col.addView(GameStyle.text(this, s.retellText.take(80), 14f).apply {
                    setTextColor(FamilyTvTheme.muted)
                    maxLines = 2
                })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(GameStyle.button(this, "Слушать") {
                    speak(s.retellText)
                }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
                row.addView(GameStyle.button(this, "Открыть") {
                    startActivity(Intent(this@DiscoveryJournalActivity, CreativeStudioActivity::class.java))
                }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
                row.addView(GameStyle.button(this, "Удалить") {
                    StoryStore(this@DiscoveryJournalActivity).delete(s.id)
                    rebuild()
                }, LinearLayout.LayoutParams(-2, dp(48)))
                root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        nav.addView(GameStyle.button(this, "Карта") {
            val jid = filterJourney
                ?: PlatformServices.journeyCoordinator(this@DiscoveryJournalActivity).activeJourneyId()
                ?: "space-grand-expedition"
            startActivity(
                Intent(this@DiscoveryJournalActivity, JourneyMapActivity::class.java)
                    .putExtra(JourneyMapActivity.EXTRA_JOURNEY_ID, jid),
            )
        }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginEnd = dp(12) })
        nav.addView(GameStyle.button(this, "Домой") {
            startActivity(Intent(this@DiscoveryJournalActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, LinearLayout.LayoutParams(-2, dp(52)))
        root.addView(nav)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun entryCard(e: JournalEntry): LinearLayout {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val prefix = when (e.type) {
            JournalEntryType.FACT_DISCOVERED -> "Мы узнали"
            JournalEntryType.EXPERIMENT_OBSERVED -> "Мы проверили"
            JournalEntryType.ADVENTURE_COMPLETED -> "Мы исследовали"
            JournalEntryType.STORY_CREATED -> "Мы создали"
            JournalEntryType.WORLD_VISITED -> "Мы побывали"
            JournalEntryType.OPTIONAL_MOMENT -> "Момент"
            JournalEntryType.JOURNEY_SUMMARY -> "Итог"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GameStyle.rounded(this@DiscoveryJournalActivity, Color.WHITE, 16)
            isFocusable = true
            isClickable = true
            contentDescription = "$prefix. ${e.title}"
            addView(GameStyle.text(this@DiscoveryJournalActivity, "$prefix · ${e.title}", 16f, true))
            addView(GameStyle.text(this@DiscoveryJournalActivity, e.body, 15f).apply {
                setTextColor(FamilyTvTheme.muted)
                setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener {
                speak(e.spokenBody ?: e.body)
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    speak(e.spokenBody ?: e.body)
                    true
                } else false
            }
        }
    }

    private fun speak(text: String) {
        lastSpoken = text
        status.text = text
        PlatformServices.narrationCoordinator(this).present(
            PresentationMessage(
                text = text,
                spokenText = text,
                role = PresentationRole.FEEDBACK,
                importance = PresentationImportance.IMPORTANT,
                repeatable = true,
            ),
        )
    }

    companion object {
        const val EXTRA_JOURNEY_ID = "journey_id"
    }
}
