package ru.familytv.parent

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import ru.familytv.admin.ContentAdminActivity
import ru.familytv.content.PlatformServices
import ru.familytv.games.GameStyle
import ru.familytv.home.HomeActivity
import ru.familytv.interaction.ReadingSupportMode
import ru.familytv.voicelab.VoiceLabActivity

/**
 * Parent Mode — PIN gated, clearly separate from child Home.
 */
class ParentModeActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    leaveToHome()
                }
            },
        )
        if (intent.getBooleanExtra(EXTRA_UNLOCKED, false)) {
            unlocked = true
            showDashboard()
        } else {
            showPinGate()
        }
    }

    private fun leaveToHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /** Leanback remotes: focused EditText registers a later OnBackPressedCallback that only clears focus. */
    @SuppressLint("GestureBackNavigation", "RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            leaveToHome()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showPinGate() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val pinStore = PlatformServices.parentPin(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
        }
        root.addView(GameStyle.text(this, "Режим родителя", 32f, true).apply { gravity = Gravity.CENTER })
        root.addView(
            GameStyle.text(this, "Введите PIN", 18f).apply {
                gravity = Gravity.CENTER
                setTextColor(GameStyle.muted)
                setPadding(0, dp(8), 0, dp(16))
            },
        )
        val input = EditText(this).apply {
            hint = if (pinStore.isSet()) "PIN" else "Создайте PIN (4–6 цифр)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GameStyle.rounded(this@ParentModeActivity, Color.WHITE, 12)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            // TV remotes: enter digits with number keys; avoid full QWERTY overlay
            showSoftInputOnFocus = false
        }
        root.addView(input, LinearLayout.LayoutParams(dp(280), -2))
        status = GameStyle.text(this, "", 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(status)
        val actionLabel = if (pinStore.isSet()) "Войти" else "Сохранить PIN"
        val go = GameStyle.button(this, actionLabel) {
            val pin = input.text?.toString().orEmpty()
            if (!pinStore.isSet()) {
                if (pinStore.setPin(pin)) {
                    unlocked = true
                    showDashboard()
                } else {
                    status.text = "PIN: 4–6 цифр"
                }
            } else if (pinStore.verify(pin)) {
                unlocked = true
                showDashboard()
            } else {
                status.text = "Неверный PIN"
            }
        }
        root.addView(go, LinearLayout.LayoutParams(-2, dp(56)).apply {
            topMargin = dp(16)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(GameStyle.button(this, "‹ Назад к детям") {
            leaveToHome()
        }, LinearLayout.LayoutParams(-2, dp(48)).apply {
            topMargin = dp(12)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 250))
            addView(root)
        })
        input.requestFocus()
        // Prefer primary action for D-pad; digits still reach focused EditText when selected
        go.post { go.requestFocus() }
    }

    private fun showDashboard() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val settings = PlatformServices.familySettings(this)
        val history = PlatformServices.activityHistory(this)
        val screen = PlatformServices.screenTime(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(28))
        }
        root.addView(GameStyle.text(this, "Режим родителя", 30f, true))
        root.addView(
            GameStyle.text(this, "Настройки семьи · локально на устройстве", 16f).apply {
                setTextColor(GameStyle.muted)
                setPadding(0, dp(4), 0, dp(12))
            },
        )

        // Today summary
        val day = history.todaySummary()
        val mins = (day.totalWatchMs / 60_000L).toInt()
        val topics = if (day.topics.isEmpty()) "—" else day.topics.take(8).joinToString(", ")
        root.addView(section("Сегодня"))
        root.addView(GameStyle.text(this, "Примерно $mins мин у экрана (оценка)", 18f))
        root.addView(GameStyle.text(this, "Темы: $topics", 16f).apply { setTextColor(GameStyle.muted) })
        if (day.events.isNotEmpty()) {
            day.events.takeLast(8).reversed().forEach { e ->
                root.addView(
                    GameStyle.text(this, "· ${e.title}", 15f).apply {
                        setTextColor(GameStyle.ink)
                        setPadding(0, dp(2), 0, 0)
                    },
                )
            }
        }

        // Screen time
        root.addView(section("Экранное время"))
        val st = settings.screenTime()
        val stEnabled = Switch(this).apply {
            text = "Напоминания включены"
            isChecked = st.enabled
        }
        root.addView(stEnabled)
        root.addView(GameStyle.text(this, "Лимит дня (мин, 0=выкл)", 14f).apply { setTextColor(GameStyle.muted) })
        val daily = EditText(this).apply {
            setText((st.dailyLimitMinutes ?: 0).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GameStyle.rounded(this@ParentModeActivity, Color.WHITE, 10)
        }
        root.addView(daily, LinearLayout.LayoutParams(dp(160), -2))
        root.addView(GameStyle.text(this, "Напоминание сессии (мин)", 14f).apply { setTextColor(GameStyle.muted) })
        val session = EditText(this).apply {
            setText((st.sessionReminderMinutes ?: 20).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GameStyle.rounded(this@ParentModeActivity, Color.WHITE, 10)
        }
        root.addView(session, LinearLayout.LayoutParams(dp(160), -2))
        root.addView(GameStyle.button(this, "Сохранить экранное время") {
            settings.saveScreenTime(
                ScreenTimeSettings(
                    enabled = stEnabled.isChecked,
                    dailyLimitMinutes = daily.text.toString().toIntOrNull()?.takeIf { it > 0 },
                    sessionReminderMinutes = session.text.toString().toIntOrNull()?.takeIf { it > 0 },
                    restReminderMinutes = st.restReminderMinutes,
                    bedtimeStartHour = st.bedtimeStartHour,
                    bedtimeEndHour = st.bedtimeEndHour,
                    warnMinutesBefore = st.warnMinutesBefore,
                ),
            )
            statusHint(root, "Сохранено")
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(8) })
        root.addView(
            GameStyle.text(this, "Сейчас сегодня: ${(screen.todayMs() / 60_000L).toInt()} мин", 15f).apply {
                setPadding(0, dp(6), 0, 0)
            },
        )

        // Age filter
        root.addView(section("Диапазон контента (не профиль ребёнка)"))
        val filter = settings.contentFilter()
        val ageMin = EditText(this).apply {
            hint = "от"
            setText(filter.ageMin?.toString().orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GameStyle.rounded(this@ParentModeActivity, Color.WHITE, 10)
        }
        val ageMax = EditText(this).apply {
            hint = "до"
            setText(filter.ageMax?.toString().orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GameStyle.rounded(this@ParentModeActivity, Color.WHITE, 10)
        }
        val ageRow = LinearLayout(this)
        ageRow.addView(ageMin, LinearLayout.LayoutParams(dp(100), -2).apply { marginEnd = dp(8) })
        ageRow.addView(ageMax, LinearLayout.LayoutParams(dp(100), -2))
        root.addView(ageRow)
        root.addView(GameStyle.button(this, "Сохранить диапазон") {
            settings.saveContentFilter(
                FamilyContentFilter(
                    ageMin = ageMin.text.toString().toIntOrNull(),
                    ageMax = ageMax.text.toString().toIntOrNull(),
                ),
            )
            PlatformServices.contentRepository(this).reload()
            statusHint(root, "Фильтр сохранён")
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(8) })

        // Accessibility
        root.addView(section("Доступность и чтение"))
        val a11y = settings.accessibility()
        val narr = Switch(this).apply {
            text = "Озвучка подсказок (если есть)"
            isChecked = a11y.narrationEnabled
            isFocusable = true
        }
        val large = Switch(this).apply {
            text = "Крупный текст"
            isChecked = a11y.largeText
            isFocusable = true
        }
        val captions = Switch(this).apply {
            text = "Субтитры (если есть в пакете)"
            isChecked = a11y.captionsEnabled
            isFocusable = true
        }
        root.addView(narr)
        root.addView(large)
        root.addView(captions)
        root.addView(
            GameStyle.text(
                this,
                "Режим чтения (не возраст): как подавать инструкции. Возраст и чтение — разные настройки.",
                14f,
            ).apply { setTextColor(GameStyle.muted) },
        )
        val readingModes = listOf(
            ReadingSupportMode.AUDIO_FIRST to "Сначала голос",
            ReadingSupportMode.AUDIO_AND_TEXT to "Голос и текст",
            ReadingSupportMode.TEXT_RICH to "Больше текста",
        )
        var selectedReading = a11y.readingSupportMode
        val readingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        readingModes.forEach { (mode, label) ->
            val b = GameStyle.button(this, label) {
                selectedReading = mode
                statusHint(root, "Выбрано: $label")
            }.apply {
                textSize = 14f
                if (mode == selectedReading) alpha = 1f else alpha = 0.75f
            }
            readingRow.addView(b, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        }
        root.addView(readingRow)
        root.addView(GameStyle.button(this, "Сохранить доступность") {
            settings.saveAccessibility(
                AccessibilitySettings(
                    narrationEnabled = narr.isChecked,
                    largeText = large.isChecked,
                    highContrastFocus = true,
                    captionsEnabled = captions.isChecked,
                    readingSupportMode = selectedReading,
                ),
            )
            statusHint(root, "Сохранено")
        }, LinearLayout.LayoutParams(-2, dp(48)))

        // Content / updates
        root.addView(section("Контент"))
        val pkgs = PlatformServices.packageStore(this).listInstalled()
        root.addView(
            GameStyle.text(
                this,
                if (pkgs.isEmpty()) "Нет установленных пакетов"
                else pkgs.joinToString("\n") { "${it.packageId} active=${it.activeVersion}" },
                15f,
            ),
        )
        val sync = PlatformServices.contentSync(this).state()
        if (sync.lastIndexVersion != null) {
            root.addView(
                GameStyle.text(this, "Индекс: v${sync.lastIndexVersion} · ${sync.connectionState}", 14f).apply {
                    setTextColor(GameStyle.muted)
                },
            )
        }
        root.addView(GameStyle.button(this, "Управление контентом") {
            startActivity(Intent(this, ContentAdminActivity::class.java))
        }, LinearLayout.LayoutParams(-2, dp(52)).apply { topMargin = dp(8) })

        root.addView(section("Путешествия (без оценок)"))
        val journeys = PlatformServices.journeyCatalog(this).all()
        val jp = PlatformServices.journeyProgress(this)
        val active = jp.listActive()
        val done = jp.listRecentlyCompleted()
        val journalCount = PlatformServices.discoveryJournal(this).list().size
        root.addView(
            GameStyle.text(
                this,
                buildString {
                    append("Активные: ")
                    if (active.isEmpty()) append("нет")
                    else append(
                        active.joinToString {
                            journeys.find { j -> j.id == it.journeyId }?.title ?: it.journeyId
                        },
                    )
                    append("\nНедавно завершённые: ")
                    if (done.isEmpty()) append("нет")
                    else append(
                        done.joinToString {
                            journeys.find { j -> j.id == it.journeyId }?.title ?: it.journeyId
                        },
                    )
                    append("\nЗаписей в журнале: $journalCount")
                },
                15f,
            ),
        )

        root.addView(section("О приложении"))
        root.addView(GameStyle.text(this, "FamilyTV Beta · полезное время у экрана", 15f))
        root.addView(GameStyle.text(this, "Прогресс — семейный (устройство), без обязательных профилей", 14f).apply {
            setTextColor(GameStyle.muted)
        })

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            root.addView(section("Разработка"))
            val nonReaderPrefs = getSharedPreferences(ru.familytv.interaction.NonReaderSimulation.PREFS, MODE_PRIVATE)
            var nonReaderOn = ru.familytv.interaction.NonReaderSimulation.isEnabled(nonReaderPrefs)
            root.addView(GameStyle.button(this, if (nonReaderOn) "NON_READER_SIM: ON" else "NON_READER_SIM: OFF") {
                nonReaderOn = !nonReaderOn
                ru.familytv.interaction.NonReaderSimulation.setEnabled(nonReaderPrefs, nonReaderOn)
                statusHint(root, if (nonReaderOn) "Non-reader simulation ON" else "Non-reader simulation OFF")
                recreate()
            }, LinearLayout.LayoutParams(-2, dp(48)))
            val trial = PlatformServices.trialEvents(this)
            var trialOn = trial.isTrialMode()
            root.addView(GameStyle.button(this, if (trialOn) "TRIAL_MODE: ON" else "TRIAL_MODE: OFF") {
                trialOn = !trialOn
                trial.setTrialMode(trialOn)
                statusHint(root, if (trialOn) "Trial mode ON" else "Trial mode OFF")
                recreate()
            }, LinearLayout.LayoutParams(-2, dp(48)))
            root.addView(GameStyle.button(this, "Trial summary") {
                val summary = trial.summaryJson()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Trial summary (local)")
                    .setMessage(summary.take(3500))
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Clear") { _, _ -> trial.clear() }
                    .show()
            }, LinearLayout.LayoutParams(-2, dp(48)))
            var showDev = getSharedPreferences("familytv_debug", MODE_PRIVATE).getBoolean("show_dev_shelf", false)
            root.addView(GameStyle.button(this, if (showDev) "DEV shelf: ON" else "DEV shelf: OFF") {
                showDev = !showDev
                getSharedPreferences("familytv_debug", MODE_PRIVATE).edit().putBoolean("show_dev_shelf", showDev).apply()
                statusHint(root, "Restart Home to refresh shelf")
            }, LinearLayout.LayoutParams(-2, dp(48)))
            root.addView(GameStyle.button(this, "VoiceLab") {
                startActivity(Intent(this, VoiceLabActivity::class.java))
            }, LinearLayout.LayoutParams(-2, dp(48)))
            root.addView(GameStyle.button(this, "Content Admin") {
                startActivity(Intent(this, ContentAdminActivity::class.java))
            }, LinearLayout.LayoutParams(-2, dp(48)))
        }

        root.addView(GameStyle.button(this, "‹ К детям") {
            leaveToHome()
        }, LinearLayout.LayoutParams(-2, dp(56)).apply { topMargin = dp(20) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 250))
            addView(root)
        })
    }

    private fun section(title: String): TextView {
        fun dp(v: Int) = GameStyle.dp(this, v)
        return GameStyle.text(this, title, 22f, true).apply {
            setPadding(0, dp(18), 0, dp(8))
        }
    }

    private fun statusHint(root: LinearLayout, msg: String) {
        // lightweight toast-like line at bottom
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_UNLOCKED = "unlocked"
    }
}
