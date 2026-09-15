package ru.familytv.games

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.familytv.voice.AiReadiness
import ru.familytv.voice.VoiceRuntime
import ru.familytv.voicelab.VoiceLabActivity

class GameHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = VoiceRuntime.get(this)
        runtime.prewarm()
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(20))
        }
        val brand = GameStyle.text(this, "FamilyTV", 20f, true).apply {
            setTextColor(GameStyle.teal)
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) setOnLongClickListener {
                startActivity(Intent(this@GameHubActivity, VoiceLabActivity::class.java)); true
            }
        }
        root.addView(brand)
        root.addView(GameStyle.text(this, "Давай поиграем!", 36f, true))
        root.addView(GameStyle.text(this, "Выбирай, считай и отвечай вслух", 18f).apply { setTextColor(GameStyle.muted) })
        val cards = LinearLayout(this).apply { setPadding(0, dp(20), 0, dp(16)) }
        root.addView(cards)
        fun card(title: String, subtitle: String, color: Int, art: View, target: Class<*>): View {
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(16), dp(18), dp(18))
                background = GameStyle.focusBackground(this@GameHubActivity, color)
                isFocusable = true
                isClickable = true
                contentDescription = "$title. $subtitle"
                setOnClickListener { startActivity(Intent(this@GameHubActivity, target)) }
            }
            view.addView(art, LinearLayout.LayoutParams(-1, dp(118)))
            view.addView(GameStyle.text(this, title, 28f, true))
            view.addView(GameStyle.text(this, subtitle, 16f).apply { gravity = Gravity.CENTER })
            cards.addView(view, LinearLayout.LayoutParams(0, dp(252), 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
            return view
        }
        val math = card("Математика", "Сколько яблок вместе?", GameStyle.peach, AppleBoard(this), MathVoiceGameActivity::class.java)
        val color = card("Цвета", "Назови цвет круга", GameStyle.lavender, ColorBoard(this), ColorVoiceGameActivity::class.java)
        val readiness = GameStyle.text(this, "Подготовка игр...", 21f, true).apply { gravity = Gravity.CENTER }
        root.addView(readiness, LinearLayout.LayoutParams(-1, dp(38)))
        val retry = GameStyle.button(this, "Попробовать снова") { runtime.retryInitialization() }
        root.addView(retry, LinearLayout.LayoutParams(-2, dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        setContentView(ScrollView(this).apply { setBackgroundColor(GameStyle.background); addView(root) })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.readiness.collect { status ->
                    val ready = status == AiReadiness.AI_READY
                    math.isEnabled = ready; color.isEnabled = ready
                    math.alpha = if (ready) 1f else .55f; color.alpha = math.alpha
                    readiness.text = when (status) {
                        AiReadiness.AI_INITIALIZING -> "Подготовка игр..."
                        AiReadiness.AI_READY -> "Выбери игру — я готов слушать!"
                        AiReadiness.AI_ERROR -> "Не получилось подготовить игры"
                    }
                    retry.visibility = if (status == AiReadiness.AI_ERROR) View.VISIBLE else View.GONE
                    if (ready && currentFocus == null) math.requestFocus()
                }
            }
        }
    }
}
