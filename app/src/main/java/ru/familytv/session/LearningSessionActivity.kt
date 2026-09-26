package ru.familytv.session

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import ru.familytv.content.ContentItem
import ru.familytv.content.PlatformServices
import ru.familytv.games.GameStyle
import ru.familytv.home.ContentNavigator
import ru.familytv.home.HomeActivity
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.interaction.InputSource
import ru.familytv.interaction.PresentationMessage
import ru.familytv.interaction.PresentationRole
import ru.familytv.parent.ActivityKind

/**
 * Runs a curated finite learning session (no autoplay feed).
 * Calm ending — does not push another endless chain.
 */
class LearningSessionActivity : ComponentActivity() {
    private lateinit var session: LearningSession
    private lateinit var contentItem: ContentItem
    private var index = 0
    private var launchedChild = false
    private var sessionStartedAt = 0L
    private val actionHandler = FamilyActionHandler { _, action -> handleSessionAction(action) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    persist()
                    finish()
                }
            },
        )
        PlatformServices.actionDispatcher().attach(actionHandler)
        sessionStartedAt = System.currentTimeMillis()
        val contentId = intent.getStringExtra(EXTRA_CONTENT_ID).orEmpty()
        val repo = PlatformServices.contentRepository(this)
        val item = repo.getItem(contentId)
        if (item == null) {
            showUnavailable()
            return
        }
        contentItem = item
        val json = repo.openSessionJson(item)
        session = if (json != null) {
            LearningSessionParser.parse(json)
        } else {
            // Deterministic composer fallback when session JSON missing
            SessionComposer.compose(repo.getEnabledItems())
        }
        val progress = PlatformServices.sessionProgress(this).get(session.id)
        index = when {
            progress == null || progress.completed -> 0
            else -> progress.index.coerceIn(0, session.items.lastIndex.coerceAtLeast(0))
        }
        if (session.items.isEmpty()) {
            complete()
            return
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        PlatformServices.actionDispatcher().attach(actionHandler)
        if (launchedChild) {
            launchedChild = false
            advance()
        }
    }

    override fun onPause() {
        PlatformServices.actionDispatcher().detach(actionHandler)
        super.onPause()
    }

    private fun render() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(28))
        }
        root.addView(GameStyle.button(this, "‹ Домой") {
            PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Home)
        }, LinearLayout.LayoutParams(-2, dp(52)))
        root.addView(GameStyle.text(this, session.title, 34f, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        val minutes = session.estimatedMinutes?.let { " · около $it мин" } ?: ""
        root.addView(
            GameStyle.text(this, "Шаг ${index + 1} из ${session.items.size}$minutes", 18f).apply {
                setTextColor(GameStyle.muted)
            },
        )
        root.addView(
            GameStyle.text(this, session.description, 16f).apply {
                setTextColor(GameStyle.muted)
                setPadding(0, dp(8), 0, dp(16))
            },
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        actions.addView(
            GameStyle.button(this, "↻ Повтори") {
                PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Repeat)
            }.apply { textSize = 15f },
            LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) },
        )
        actions.addView(
            GameStyle.button(this, "? Помоги") {
                PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Help)
            }.apply { textSize = 15f },
            LinearLayout.LayoutParams(-2, dp(48)),
        )
        root.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })

        val repo = PlatformServices.contentRepository(this)
        val ref = session.items.getOrNull(index)
        if (ref == null) {
            complete()
            return
        }
        val nextItem = repo.getItem(ref.contentId)
        if (nextItem == null) {
            root.addView(GameStyle.text(this, "Это занятие сейчас недоступно.", 22f, true))
            if (ref.optional) {
                val skip = GameStyle.button(this, "Пропустить") { advance() }
                root.addView(skip, LinearLayout.LayoutParams(-2, dp(56)).apply { topMargin = dp(12) })
                skip.requestFocus()
            } else {
                root.addView(GameStyle.button(this, "Домой") {
                    PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Home)
                }, LinearLayout.LayoutParams(-2, dp(56)).apply {
                    topMargin = dp(12)
                })
            }
        } else {
            root.addView(GameStyle.text(this, nextItem.title, 28f, true).apply { gravity = Gravity.CENTER })
            root.addView(GameStyle.text(this, nextItem.description, 16f).apply {
                gravity = Gravity.CENTER
                setTextColor(GameStyle.muted)
            })
            val start = GameStyle.button(this, "Начать шаг") {
                persist()
                launchedChild = true
                ContentNavigator.open(this, nextItem)
            }
            root.addView(start, LinearLayout.LayoutParams(-2, dp(64)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20)
            })
            start.requestFocus()
            PlatformServices.narrationCoordinator(this).present(
                PresentationMessage(
                    text = "Шаг ${index + 1}: ${nextItem.title}",
                    spokenText = "Дальше: ${nextItem.title}",
                    role = PresentationRole.NAVIGATION,
                ),
            )
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(GameStyle.background)
            addView(root)
        })
    }

    private fun handleSessionAction(action: FamilyAction): Boolean {
        when (action) {
            FamilyAction.Home, FamilyAction.Back -> {
                persist(); goHome(); return true
            }
            FamilyAction.Repeat -> {
                PlatformServices.narrationCoordinator(this).repeat(); return true
            }
            FamilyAction.Help -> {
                PlatformServices.narrationCoordinator(this).present(
                    PresentationMessage(
                        text = "Выбери «Начать шаг», когда будете готовы.",
                        narrationRef = "common:go_next",
                        role = PresentationRole.HINT,
                    ),
                )
                return true
            }
            FamilyAction.Next -> {
                advance(); return true
            }
            else -> return false
        }
    }

    private fun advance() {
        if (index >= session.items.lastIndex) {
            complete()
            return
        }
        index++
        persist()
        render()
    }

    private fun complete() {
        PlatformServices.sessionProgress(this).save(session.id, index, completed = true)
        val elapsed = (System.currentTimeMillis() - sessionStartedAt).coerceAtLeast(0L)
        PlatformServices.activityHistory(this).record(
            kind = ActivityKind.SESSION,
            title = session.title,
            contentId = contentItem.id,
            topics = emptyList(),
            durationMs = elapsed,
        )
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
        }
        root.addView(GameStyle.text(this, "На сегодня всё.", 36f, true).apply { gravity = Gravity.CENTER })
        root.addView(GameStyle.text(this, "Отличная работа.", 24f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(GameStyle.text(this, "Можно отдохнуть или выбрать ещё одно занятие.", 17f).apply {
            gravity = Gravity.CENTER
            setTextColor(GameStyle.muted)
        })
        val home = GameStyle.button(this, "Вернуться домой") { goHome() }
        root.addView(home, LinearLayout.LayoutParams(-2, dp(60)).apply {
            topMargin = dp(28)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(GameStyle.button(this, "Ещё одно занятие") {
            // Return to Home so the child chooses deliberately — no auto chain
            goHome()
        }, LinearLayout.LayoutParams(-2, dp(56)).apply {
            topMargin = dp(12)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(root.apply { setBackgroundColor(GameStyle.background) })
        home.requestFocus()
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
        val back = GameStyle.button(this, "Домой") { goHome() }
        root.addView(back, LinearLayout.LayoutParams(-2, dp(56)).apply {
            topMargin = dp(20)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(root.apply { setBackgroundColor(GameStyle.background) })
        back.requestFocus()
    }

    private fun persist() {
        PlatformServices.sessionProgress(this).save(session.id, index, completed = false)
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        PlatformServices.actionDispatcher().detach(actionHandler)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONTENT_ID = "contentId"
    }
}
