package ru.familytv.games

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.familytv.voice.VoiceProblem
import ru.familytv.voice.VoiceRuntime

abstract class BaseVoiceGameActivity : ComponentActivity() {
    protected abstract val gameKind: GameKind
    private lateinit var controller: VoiceGameSessionController
    private lateinit var title: android.widget.TextView
    private lateinit var feedback: android.widget.TextView
    private lateinit var hint: android.widget.TextView
    private lateinit var counter: android.widget.TextView
    private lateinit var retry: android.widget.Button
    private lateinit var board: View
    private var askedPermission = false
    private var seed = 0L
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) controller.retry()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seed = savedInstanceState?.getLong("gameSeed") ?: System.nanoTime()
        askedPermission = savedInstanceState?.getBoolean("askedPermission") ?: false
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(18))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(GameStyle.button(this, "‹ Все игры") {
            startActivity(Intent(this, GameHubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, LinearLayout.LayoutParams(-2, dp(52)))
        counter = GameStyle.text(this, "", 17f, true).apply { gravity = Gravity.END; setTextColor(GameStyle.muted) }
        header.addView(counter, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)
        title = GameStyle.text(this, if (gameKind == GameKind.MATH) "Сколько яблок получилось?"
            else "Какого цвета этот круг?", 32f, true).apply { gravity = Gravity.CENTER }
        root.addView(title, LinearLayout.LayoutParams(-1, dp(68)))
        board = if (gameKind == GameKind.MATH) AppleBoard(this) else ColorBoard(this)
        board.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        root.addView(board, LinearLayout.LayoutParams(-1, dp(214)))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GameStyle.rounded(this@BaseVoiceGameActivity, Color.WHITE)
        }
        feedback = GameStyle.text(this, "Подготовка игры...", 25f, true).apply { gravity = Gravity.CENTER }
        hint = GameStyle.text(this, "", 16f).apply { gravity = Gravity.CENTER; setTextColor(GameStyle.muted) }
        bottom.addView(feedback)
        bottom.addView(hint)
        retry = GameStyle.button(this, "Ещё раз") {
            if (!hasPermission()) {
                if (askedPermission && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            } else controller.retry()
        }
        bottom.addView(retry, LinearLayout.LayoutParams(-2, dp(50)).apply { topMargin = dp(6) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(ScrollView(this).apply { setBackgroundColor(GameStyle.background); addView(root) })
        val first = savedInstanceState?.let { saved ->
            val id = saved.getLong("questionId", 0)
            if (id == 0L) null else if (gameKind == GameKind.MATH)
                AppleQuestion(id, saved.getInt("left"), saved.getInt("right"))
            else ColorQuestion(id, GameColor.valueOf(checkNotNull(saved.getString("target"))),
                checkNotNull(saved.getStringArray("choices")).map(GameColor::valueOf))
        }
        controller = VoiceGameSessionController(VoiceRuntime.get(this).newPipeline(), lifecycleScope,
            GameSession(gameKind, QuestionGenerator(seed), first), telemetry = { question, answer, verdict ->
                val tag = if (gameKind == GameKind.MATH) "FamilyMathGame" else "FamilyColorGame"
                val m = answer.metrics
                Log.i(tag, "game=$gameKind questionId=${question.id} requestId=${answer.requestId} " +
                    "speechDurationMs=${m.speechDurationMs} endpointDelayMs=${m.endpointDelayMs} " +
                    "sttLatencyMs=${m.sttLatencyMs} sttText=\"${answer.transcript}\" " +
                    "llmLatencyMs=${m.llmLatencyMs} normalizedAnswer=\"${answer.normalizedText}\" " +
                    "expectedAnswer=\"${question.expectedAnswer}\" correct=${verdict == Verdict.CORRECT} " +
                    "verdict=$verdict totalPostSpeechLatencyMs=${m.totalPostSpeechLatencyMs}")
            })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { controller.state.collect(::render) }
        }
    }

    private fun render(state: GameViewState) {
        val question = state.question
        counter.text = "${if (gameKind == GameKind.MATH) "Яблоки" else "Цвета"}  ·  Задание ${question.id}"
        when (question) {
            is AppleQuestion -> (board as AppleBoard).apply {
                leftCount = question.left; rightCount = question.right
                contentDescription = "${question.left} яблок слева и ${question.right} справа"
            }
            is ColorQuestion -> (board as ColorBoard).apply {
                this.question = question
                contentDescription = "Три цветных круга. Выделен круг номер ${question.choices.indexOf(question.target) + 1}"
            }
        }
        val message = when (state.phase) {
            GamePhase.LOADING -> "Подготовка игры..."
            GamePhase.QUESTION -> "Назови ответ вслух"
            GamePhase.LISTENING -> "Я слушаю..."
            GamePhase.SPEECH -> "Слушаю твой ответ"
            GamePhase.PROCESSING -> "Проверяю ответ..."
            GamePhase.CORRECT -> "Правильно!"
            GamePhase.WRONG -> "Попробуй ещё раз"
            GamePhase.NOT_UNDERSTOOD -> "Не расслышал. Попробуй ещё раз"
            GamePhase.STOPPED -> "Продолжим игру?"
            GamePhase.ERROR -> when (state.problem) {
                VoiceProblem.MICROPHONE -> "Микрофон не подключён"
                VoiceProblem.PERMISSION -> "Нужен доступ к микрофону"
                VoiceProblem.INITIALIZATION -> "Не получилось подготовить игру"
                else -> "Не получилось услышать ответ"
            }
        }
        feedback.text = message
        feedback.setTextColor(when (state.phase) {
            GamePhase.CORRECT -> GameStyle.teal
            GamePhase.WRONG, GamePhase.NOT_UNDERSTOOD -> Color.rgb(159, 93, 36)
            else -> GameStyle.ink
        })
        hint.text = when {
            state.problem == VoiceProblem.MICROPHONE && state.phase == GamePhase.ERROR -> "Подключи микрофон и нажми «Ещё раз»"
            state.phase == GamePhase.LISTENING -> "Скажи ответ и сделай небольшую паузу"
            state.phase == GamePhase.CORRECT -> "Отлично! Дальше — новое задание"
            state.phase == GamePhase.WRONG || state.phase == GamePhase.NOT_UNDERSTOOD -> "Задание остаётся тем же"
            state.phase == GamePhase.ERROR -> "Нажми «Ещё раз», когда будешь готов"
            else -> ""
        }
        retry.visibility = if (state.phase in listOf(GamePhase.ERROR, GamePhase.STOPPED)) View.VISIBLE else View.GONE
        if (state.phase == GamePhase.ERROR) retry.requestFocus()
        if (state.phase in listOf(GamePhase.LISTENING, GamePhase.SPEECH, GamePhase.PROCESSING))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun onResume() {
        super.onResume()
        controller.resume()
        if (!hasPermission() && !askedPermission) {
            askedPermission = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    override fun onPause() { controller.pause(); super.onPause() }
    override fun onDestroy() { controller.close(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("gameSeed", seed)
        outState.putBoolean("askedPermission", askedPermission)
        when (val q = controller.state.value.question) {
            is AppleQuestion -> { outState.putLong("questionId", q.id); outState.putInt("left", q.left); outState.putInt("right", q.right) }
            is ColorQuestion -> { outState.putLong("questionId", q.id); outState.putString("target", q.target.name); outState.putStringArray("choices", q.choices.map { it.name }.toTypedArray()) }
        }
        super.onSaveInstanceState(outState)
    }
}

class MathVoiceGameActivity : BaseVoiceGameActivity() { override val gameKind = GameKind.MATH }
class ColorVoiceGameActivity : BaseVoiceGameActivity() { override val gameKind = GameKind.COLOR }
