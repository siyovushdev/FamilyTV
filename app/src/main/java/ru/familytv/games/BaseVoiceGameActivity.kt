package ru.familytv.games

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
import ru.familytv.R
import ru.familytv.content.PlatformServices
import ru.familytv.ui.ChildChrome
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.interaction.InputSource
import ru.familytv.interaction.InteractionSurface
import ru.familytv.interaction.NarrationCoordinator
import ru.familytv.interaction.PresentationImportance
import ru.familytv.interaction.PresentationMessage
import ru.familytv.interaction.PresentationRole
import ru.familytv.interaction.RouteContext
import ru.familytv.interaction.RouteResult
import ru.familytv.interaction.VoiceCommandRouter
import ru.familytv.parent.ActivityKind
import ru.familytv.voice.MicPermissionAction
import ru.familytv.voice.MicPermissionGate
import ru.familytv.voice.VoiceProblem
import ru.familytv.voice.VoiceRuntime

abstract class BaseVoiceGameActivity : ComponentActivity() {
    protected abstract val gameKind: GameKind
    private var controller: VoiceGameSessionController? = null
    private lateinit var title: android.widget.TextView
    private lateinit var feedback: android.widget.TextView
    private lateinit var hint: android.widget.TextView
    private lateinit var counter: android.widget.TextView
    private lateinit var retry: android.widget.Button
    private lateinit var board: View
    private lateinit var rootHost: ScrollView
    private lateinit var narration: NarrationCoordinator
    private var askedPermission = false
    private var seed = 0L
    private var startedAt = 0L
    private var activityRecorded = false
    private var gameUiReady = false
    private var restoredQuestion: GameQuestion? = null
    private val actionHandler = FamilyActionHandler { _, action -> handleGameAction(action) }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedPermission = true
        if (granted) startGameIfNeeded()
        else showMicUnavailable(openSettings = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedAt = System.currentTimeMillis()
        seed = savedInstanceState?.getLong("gameSeed") ?: System.nanoTime()
        askedPermission = savedInstanceState?.getBoolean("askedPermission") ?: false
        restoredQuestion = savedInstanceState?.let(::questionFromBundle)
        when (
            MicPermissionGate.nextAction(
                hasPermission = hasPermission(),
                alreadyAskedThisSession = askedPermission,
                shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            )
        ) {
            MicPermissionAction.PROCEED -> startGameIfNeeded()
            MicPermissionAction.EXPLAIN_THEN_REQUEST -> showMicExplain()
            MicPermissionAction.SHOW_UNAVAILABLE -> showMicUnavailable(openSettings = true)
        }
    }

    private fun questionFromBundle(saved: Bundle): GameQuestion? {
        val id = saved.getLong("questionId", 0)
        if (id == 0L) return null
        return if (gameKind == GameKind.MATH) {
            AppleQuestion(id, saved.getInt("left"), saved.getInt("right"))
        } else {
            ColorQuestion(
                id,
                GameColor.valueOf(checkNotNull(saved.getString("target"))),
                checkNotNull(saved.getStringArray("choices")).map(GameColor::valueOf),
            )
        }
    }

    private fun showMicExplain() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
            setBackgroundColor(GameStyle.background)
        }
        root.addView(GameStyle.text(this, MicPermissionGate.EXPLAIN_TITLE, 30f, true).apply {
            gravity = Gravity.CENTER
        })
        root.addView(GameStyle.text(this, MicPermissionGate.EXPLAIN_BODY, 18f).apply {
            gravity = Gravity.CENTER
            setTextColor(GameStyle.muted)
            setPadding(0, dp(12), 0, dp(24))
        })
        val allow = GameStyle.button(this, MicPermissionGate.RETRY_LABEL) {
            askedPermission = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        root.addView(allow, LinearLayout.LayoutParams(-2, dp(56)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val back = GameStyle.button(this, MicPermissionGate.BACK_LABEL) { leaveToHome() }
        root.addView(back, LinearLayout.LayoutParams(-2, dp(52)).apply {
            topMargin = dp(12)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(root)
        allow.requestFocus()
    }

    private fun showMicUnavailable(openSettings: Boolean) {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
            setBackgroundColor(GameStyle.background)
        }
        root.addView(GameStyle.text(this, MicPermissionGate.CHILD_MESSAGE, 30f, true).apply {
            gravity = Gravity.CENTER
        })
        root.addView(GameStyle.text(this, "Можно вернуться домой или попробовать снова.", 17f).apply {
            gravity = Gravity.CENTER
            setTextColor(GameStyle.muted)
            setPadding(0, dp(12), 0, dp(24))
        })
        val retryBtn = GameStyle.button(this, MicPermissionGate.RETRY_LABEL) {
            when (
                MicPermissionGate.nextAction(
                    hasPermission = hasPermission(),
                    alreadyAskedThisSession = askedPermission,
                    shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
                )
            ) {
                MicPermissionAction.PROCEED -> startGameIfNeeded()
                MicPermissionAction.EXPLAIN_THEN_REQUEST -> {
                    askedPermission = true
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                MicPermissionAction.SHOW_UNAVAILABLE -> {
                    if (openSettings) {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    } else {
                        askedPermission = true
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
        root.addView(retryBtn, LinearLayout.LayoutParams(-2, dp(56)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        if (openSettings) {
            root.addView(
                GameStyle.button(this, MicPermissionGate.OPEN_SETTINGS_LABEL) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                },
                LinearLayout.LayoutParams(-2, dp(52)).apply {
                    topMargin = dp(12)
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }
        val back = GameStyle.button(this, MicPermissionGate.BACK_LABEL) { leaveToHome() }
        root.addView(back, LinearLayout.LayoutParams(-2, dp(52)).apply {
            topMargin = dp(12)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(root)
        retryBtn.requestFocus()
    }

    private fun startGameIfNeeded() {
        if (!hasPermission()) {
            showMicExplain()
            return
        }
        if (gameUiReady) {
            controller?.retry()
            return
        }
        gameUiReady = true
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(18))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(GameStyle.button(this, "‹ Домой") { leaveToHome() }, LinearLayout.LayoutParams(-2, dp(52)))
        counter = GameStyle.text(this, "", 17f, true).apply { gravity = Gravity.END; setTextColor(GameStyle.muted) }
        header.addView(counter, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)
        title = GameStyle.text(
            this,
            if (gameKind == GameKind.MATH) "Сколько яблок получилось?" else "Какого цвета этот круг?",
            32f,
            true,
        ).apply { gravity = Gravity.CENTER }
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
            if (!hasPermission()) showMicUnavailable(
                openSettings = askedPermission && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            )
            else controller?.retry()
        }
        bottom.addView(retry, LinearLayout.LayoutParams(-2, dp(50)).apply { topMargin = dp(6) })
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionRow.addView(
            ChildChrome.iconButton(this, R.drawable.ic_action_repeat, "Повтори") {
                PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Repeat)
            },
            LinearLayout.LayoutParams(dp(72), dp(48)).apply { marginEnd = dp(8) },
        )
        actionRow.addView(
            ChildChrome.iconButton(this, R.drawable.ic_action_help, "Помоги") {
                PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Help)
            },
            LinearLayout.LayoutParams(dp(72), dp(48)),
        )
        bottom.addView(actionRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        rootHost = ScrollView(this).apply { setBackgroundColor(GameStyle.background); addView(root) }
        setContentView(rootHost)

        narration = PlatformServices.narrationCoordinator(this)
        PlatformServices.actionDispatcher().attach(actionHandler)
        val runtime = VoiceRuntime.get(this)
        controller = VoiceGameSessionController(
            runtime.newPipeline(),
            lifecycleScope,
            GameSession(gameKind, QuestionGenerator(seed), restoredQuestion),
            telemetry = { question, answer, verdict ->
                val tag = if (gameKind == GameKind.MATH) "FamilyMathGame" else "FamilyColorGame"
                val m = answer.metrics
                Log.i(
                    tag,
                    "game=$gameKind questionId=${question.id} requestId=${answer.requestId} " +
                        "speechDurationMs=${m.speechDurationMs} endpointDelayMs=${m.endpointDelayMs} " +
                        "sttLatencyMs=${m.sttLatencyMs} sttText=\"${answer.transcript}\" " +
                        "llmLatencyMs=${m.llmLatencyMs} normalizedAnswer=\"${answer.normalizedText}\" " +
                        "expectedAnswer=\"${question.expectedAnswer}\" correct=${verdict == Verdict.CORRECT} " +
                        "verdict=$verdict totalPostSpeechLatencyMs=${m.totalPostSpeechLatencyMs}",
                )
            },
            commandInterceptor = { transcript ->
                when (val routed = VoiceCommandRouter.route(
                    RouteContext(InteractionSurface.GAME, awaitingAnswer = true),
                    transcript,
                )) {
                    is RouteResult.Action -> handleGameAction(routed.action)
                    RouteResult.Unhandled -> false
                }
            },
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller?.state?.collect(::render)
            }
        }
        controller?.resume()
    }

    private fun render(state: GameViewState) {
        if (!gameUiReady) return
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
                VoiceProblem.MICROPHONE -> MicPermissionGate.CHILD_MESSAGE
                VoiceProblem.PERMISSION -> MicPermissionGate.CHILD_MESSAGE
                VoiceProblem.INITIALIZATION -> "Не получилось подготовить игру"
                else -> "Не получилось услышать ответ"
            }
        }
        feedback.text = message
        if (state.phase == GamePhase.QUESTION || state.phase == GamePhase.LISTENING) {
            val prompt = if (gameKind == GameKind.MATH) {
                "Сколько яблок получилось?"
            } else {
                "Какого цвета этот круг?"
            }
            title.text = prompt
            if (::narration.isInitialized && state.phase == GamePhase.QUESTION) {
                narration.present(
                    PresentationMessage(
                        text = prompt,
                        spokenText = prompt,
                        narrationRef = null,
                        role = PresentationRole.QUESTION,
                        importance = PresentationImportance.CRITICAL,
                    ),
                )
            }
        }
        feedback.setTextColor(
            when (state.phase) {
                GamePhase.CORRECT -> GameStyle.teal
                GamePhase.WRONG, GamePhase.NOT_UNDERSTOOD -> Color.rgb(159, 93, 36)
                else -> GameStyle.ink
            },
        )
        hint.text = when {
            state.problem == VoiceProblem.MICROPHONE && state.phase == GamePhase.ERROR ->
                "Подключи микрофон и нажми «Ещё раз»"
            state.phase == GamePhase.LISTENING -> "Скажи ответ и сделай небольшую паузу"
            state.phase == GamePhase.CORRECT -> "Отлично! Дальше — новое задание"
            state.phase == GamePhase.WRONG || state.phase == GamePhase.NOT_UNDERSTOOD -> "Задание остаётся тем же"
            state.phase == GamePhase.ERROR -> "Нажми «Ещё раз», когда будешь готов"
            else -> ""
        }
        retry.visibility = if (state.phase in listOf(GamePhase.ERROR, GamePhase.STOPPED)) View.VISIBLE else View.GONE
        if (state.phase == GamePhase.ERROR) retry.requestFocus()
        if (state.phase in listOf(GamePhase.LISTENING, GamePhase.SPEECH, GamePhase.PROCESSING)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun handleGameAction(action: FamilyAction): Boolean {
        when (action) {
            FamilyAction.Home, FamilyAction.Back -> {
                leaveToHome()
                return true
            }
            FamilyAction.Repeat -> {
                if (::narration.isInitialized) narration.repeat()
                return true
            }
            FamilyAction.Help -> {
                if (::narration.isInitialized) {
                    narration.present(
                        PresentationMessage(
                            text = if (gameKind == GameKind.MATH) {
                                "Посчитай яблоки слева и справа и сложи."
                            } else {
                                "Назови цвет выделенного круга."
                            },
                            role = PresentationRole.HINT,
                            importance = PresentationImportance.IMPORTANT,
                            narrationRef = "common:want_hint",
                        ),
                    )
                }
                hint.text = if (gameKind == GameKind.MATH) "Сложи два числа" else "Скажи цвет вслух"
                return true
            }
            FamilyAction.BlockedSensitive, FamilyAction.OpenParentGate -> {
                feedback.text = "Это могут сделать только родители."
                return true
            }
            is FamilyAction.Answer -> return false // answered via game session path
            else -> return false
        }
    }

    private fun leaveToHome() {
        recordActivity()
        val contentId = if (gameKind == GameKind.MATH) "game-math" else "game-color"
        PlatformServices.journeyCoordinator(this).onContentCompleted(contentId)
        finish()
    }

    private fun recordActivity() {
        if (activityRecorded || startedAt <= 0L) return
        activityRecorded = true
        val title = if (gameKind == GameKind.MATH) "Математика" else "Цвета"
        val topics = if (gameKind == GameKind.MATH) listOf("math", "counting") else listOf("colors")
        PlatformServices.activityHistory(this).record(
            kind = ActivityKind.GAME,
            title = title,
            contentId = if (gameKind == GameKind.MATH) "game-math" else "game-color",
            topics = topics,
            durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
        )
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission() && gameUiReady) {
            controller?.resume()
        } else if (hasPermission() && !gameUiReady && askedPermission) {
            // Returned from system settings with permission granted — rebuild cleanly.
            recreate()
        }
    }

    override fun onPause() {
        controller?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        PlatformServices.actionDispatcher().detach(actionHandler)
        if (::narration.isInitialized) narration.stop()
        recordActivity()
        controller?.close()
        controller = null
        super.onDestroy()
    }

    @SuppressLint("GestureBackNavigation", "RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            leaveToHome()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("gameSeed", seed)
        outState.putBoolean("askedPermission", askedPermission)
        when (val q = controller?.state?.value?.question) {
            is AppleQuestion -> {
                outState.putLong("questionId", q.id)
                outState.putInt("left", q.left)
                outState.putInt("right", q.right)
            }
            is ColorQuestion -> {
                outState.putLong("questionId", q.id)
                outState.putString("target", q.target.name)
                outState.putStringArray("choices", q.choices.map { it.name }.toTypedArray())
            }
            null -> Unit
        }
        super.onSaveInstanceState(outState)
    }
}
