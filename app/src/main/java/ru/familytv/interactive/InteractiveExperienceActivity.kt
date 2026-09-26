package ru.familytv.interactive

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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.familytv.content.ContentItem
import ru.familytv.content.PlatformServices
import ru.familytv.content.ResolvedMedia
import ru.familytv.games.AppleBoard
import ru.familytv.games.GameStyle
import ru.familytv.home.HomeActivity
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
import ru.familytv.voice.VoicePhase
import ru.familytv.voice.VoiceProblem
import ru.familytv.voice.VoiceRuntime
import java.io.File

/**
 * Interactive runner v2: VideoStep (Media3), ChoiceStep (D-pad), Voice via VoiceRuntime.
 * Expected answers stay in InteractiveSession — never passed to Qwen.
 */
class InteractiveExperienceActivity : ComponentActivity() {
    private lateinit var session: InteractiveSession
    private lateinit var contentItem: ContentItem
    private lateinit var title: TextView
    private lateinit var prompt: TextView
    private lateinit var feedback: TextView
    private lateinit var boardHost: LinearLayout
    private lateinit var continueButton: android.widget.Button
    private lateinit var retryButton: android.widget.Button
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var pipeline: ru.familytv.voice.VoiceAnswerPipeline? = null
    private var voiceCollectorStarted = false
    private var request = -1L
    private var consumed = -1L
    private var feedbackJob: Job? = null
    private var awaitingReady = false
    private var askedPermission = false
    private var startedAt = 0L
    private lateinit var narration: NarrationCoordinator
    private lateinit var actionRow: LinearLayout
    private val actionHandler = FamilyActionHandler { _, action -> handleInteractiveAction(action) }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedPermission = true
        if (granted) retryVoice()
        else showVoiceUnavailable(VoiceProblem.PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    persistProgress()
                    PlatformServices.narration(this@InteractiveExperienceActivity).stop()
                    finish()
                }
            },
        )
        startedAt = System.currentTimeMillis()
        PlatformServices.screenTime(this).onAppForeground()
        val contentId = intent.getStringExtra(EXTRA_CONTENT_ID).orEmpty()
        val repo = PlatformServices.contentRepository(this)
        val item = repo.getItem(contentId)
        if (item == null) {
            showUnavailable("missing content $contentId")
            return
        }
        contentItem = item
        val json = repo.openExperienceJson(item)
        if (json == null) {
            showUnavailable("missing experience json for ${item.id}")
            return
        }
        val experience = try {
            InteractiveExperienceParser.parse(json)
        } catch (e: Exception) {
            showUnavailable("parse failed: ${e.message}")
            return
        }
        val validation = ExperienceValidator.validate(experience)
        if (!validation.ok) {
            showUnavailable("invalid: ${validation.errors}")
            return
        }
        val resume = PlatformServices.experienceProgress(this).resumeStepId(
            experience.id,
            item.packageId,
            item.packageVersion,
            experience.steps.map { it.id }.toSet(),
        )
        session = InteractiveSession(experience, startStepId = resume)
        narration = PlatformServices.narrationCoordinator(this)
        PlatformServices.narrationContentItem = contentItem
        PlatformServices.actionDispatcher().attach(actionHandler)

        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(18))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(GameStyle.button(this, "‹ Домой") {
            PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Home)
            goHome()
        }, LinearLayout.LayoutParams(-2, dp(52)))
        title = GameStyle.text(this, experience.title, 20f, true).apply {
            gravity = Gravity.END
            setTextColor(GameStyle.muted)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)

        prompt = GameStyle.text(this, "", 28f, true).apply { gravity = Gravity.CENTER }
        root.addView(prompt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        boardHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        root.addView(boardHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GameStyle.rounded(this@InteractiveExperienceActivity, Color.WHITE)
        }
        feedback = GameStyle.text(this, "", 22f, true).apply { gravity = Gravity.CENTER }
        bottom.addView(feedback)
        continueButton = GameStyle.button(this, "Дальше") { onContinue() }
        retryButton = GameStyle.button(this, "Ещё раз") { retryVoice() }
        bottom.addView(continueButton, LinearLayout.LayoutParams(-2, dp(50)).apply { topMargin = dp(6) })
        bottom.addView(retryButton, LinearLayout.LayoutParams(-2, dp(50)).apply { topMargin = dp(6) })
        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottom.addView(actionRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(GameStyle.background)
            isFillViewport = true
            addView(root)
        })
        renderStep()
    }

    private fun showUnavailable(technical: String) {
        Log.e("FamilyInteractive", "Unavailable: $technical")
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
        }
        root.addView(GameStyle.text(this, "Это занятие сейчас недоступно.", 28f, true).apply {
            gravity = Gravity.CENTER
        })
        val back = GameStyle.button(this, "Домой") { goHomeSafe() }
        root.addView(back, LinearLayout.LayoutParams(-2, dp(56)).apply {
            topMargin = dp(20)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(root.apply { setBackgroundColor(GameStyle.background) })
        back.requestFocus()
    }

    private fun goHomeSafe() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun ensureVoiceCollector() {
        if (voiceCollectorStarted) return
        voiceCollectorStarted = true
        try {
            pipeline = VoiceRuntime.get(this).newPipeline()
        } catch (e: Exception) {
            Log.e("FamilyInteractive", "Voice runtime unavailable", e)
            return
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pipeline?.state?.collect { voice ->
                    if (session.currentStep !is QuestionStep) return@collect
                    when (voice.phase) {
                        VoicePhase.INITIALIZING -> feedback.text = "Подготовка…"
                        VoicePhase.READY -> if (awaitingReady) {
                            awaitingReady = false
                            request = pipeline?.startListening() ?: -1
                        }
                        VoicePhase.LISTENING -> {
                            session.markListening()
                            feedback.text = "Я слушаю..."
                        }
                        VoicePhase.SPEECH -> feedback.text = "Слушаю твой ответ"
                        VoicePhase.STT_PROCESSING, VoicePhase.LLM_PROCESSING -> {
                            session.markProcessing()
                            feedback.text = "Проверяю ответ..."
                        }
                        VoicePhase.ERROR -> showVoiceUnavailable(voice.problem)
                        VoicePhase.RESULT -> voice.answer?.let { answer ->
                            if (answer.requestId != request || answer.requestId == consumed) return@let
                            consumed = answer.requestId
                            pipeline?.stopListening()
                            val transcript = answer.normalizedText.lowercase().trim()
                            val routed = VoiceCommandRouter.route(
                                RouteContext(InteractionSurface.INTERACTIVE, awaitingAnswer = true),
                                transcript,
                            )
                            if (routed is RouteResult.Action && routed.action !is FamilyAction.Answer) {
                                handleInteractiveAction(routed.action)
                                awaitingReady = true
                                pipeline?.activate()
                                return@let
                            }
                            val step = session.currentStep as? QuestionStep ?: return@let
                            val verdict = session.answerVoice(answer.normalizedText)
                            Log.i(
                                "FamilyInteractive",
                                "experience=${session.experience.id} step=${step.id} " +
                                    "normalized=\"${answer.normalizedText}\" expected=\"${step.expected}\" verdict=$verdict",
                            )
                            awaitingReady = false
                            showVerdictFeedback(step.feedback, verdict)
                            feedbackJob?.cancel()
                            feedbackJob = lifecycleScope.launch {
                                delay(1500)
                                session.finishFeedback()
                                renderStep()
                            }
                        }
                        VoicePhase.STOPPED -> Unit
                    }
                    if (voice.phase in listOf(
                            VoicePhase.LISTENING, VoicePhase.SPEECH,
                            VoicePhase.STT_PROCESSING, VoicePhase.LLM_PROCESSING,
                        )
                    ) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun showVoiceUnavailable(problem: VoiceProblem?) {
        awaitingReady = false
        session.markError()
        feedback.text = when (problem) {
            VoiceProblem.MICROPHONE, VoiceProblem.PERMISSION -> MicPermissionGate.CHILD_MESSAGE
            VoiceProblem.INITIALIZATION -> "Голос пока недоступен"
            else -> "Не получилось услышать ответ"
        }
        retryButton.text = if (problem == VoiceProblem.PERMISSION) MicPermissionGate.RETRY_LABEL else "Ещё раз"
        retryButton.visibility = View.VISIBLE
        retryButton.setOnClickListener { retryVoice() }
        continueButton.text = "Пропустить"
        continueButton.visibility = View.VISIBLE
        continueButton.setOnClickListener {
            session.skipQuestionUsingCorrectTransition()
            renderStep()
        }
        retryButton.requestFocus()
    }

    private fun renderStep() {
        persistProgress()
        releasePlayer()
        continueButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        continueButton.setOnClickListener { onContinue() }
        feedback.setTextColor(GameStyle.ink)
        boardHost.removeAllViews()
        awaitingReady = false
        pipeline?.stopListening()

        if (session.phase == InteractivePhase.COMPLETED || session.isCompleted) {
            prompt.text = "Готово!"
            feedback.text = "Молодец!"
            continueButton.text = "Домой"
            continueButton.visibility = View.VISIBLE
            continueButton.setOnClickListener { goHome() }
            continueButton.requestFocus()
            PlatformServices.experienceProgress(this).save(
                session.experience.id,
                session.currentStepId,
                contentItem.packageId,
                contentItem.packageVersion,
                completed = true,
            )
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            PlatformServices.activityHistory(this).record(
                kind = ActivityKind.INTERACTIVE,
                title = contentItem.title,
                contentId = contentItem.id,
                topics = contentItem.metadata.topics + contentItem.metadata.skills,
                durationMs = elapsed,
            )
            PlatformServices.narrationCoordinator(this).present(
                PresentationMessage(
                    text = "Молодец!",
                    narrationRef = "common:well_done",
                    role = PresentationRole.FEEDBACK,
                ),
            )
            return
        }

        when (val step = session.currentStep) {
            null -> goHome()
            is MessageStep -> {
                prompt.text = step.text
                feedback.text = ""
                continueButton.text = step.continueLabel
                continueButton.visibility = View.VISIBLE
                continueButton.requestFocus()
                narratePrompt(step.promptAudio, step.text)
            }
            is VideoStep -> {
                prompt.text = ""
                feedback.text = "Смотри…"
                playVideo(step)
            }
            is ChoiceStep -> {
                prompt.text = step.prompt
                feedback.text = step.feedback.hint ?: "Выбери ответ"
                renderChoices(step)
                narratePrompt(step.promptAudio, step.prompt)
            }
            is TrueFalseStep -> {
                prompt.text = step.prompt
                feedback.text = step.feedback.hint ?: "Правда или нет?"
                renderTrueFalse(step)
                narratePrompt(step.promptAudio, step.prompt)
            }
            is MoveStep -> {
                prompt.text = step.prompt
                feedback.text = "Около ${step.durationSeconds} секунд"
                continueButton.text = step.continueLabel
                continueButton.visibility = View.VISIBLE
                continueButton.requestFocus()
                boardHost.addView(
                    GameStyle.text(this, "Подвигайся и нажми «${step.continueLabel}»", 20f).apply {
                        gravity = Gravity.CENTER
                        setTextColor(GameStyle.muted)
                    },
                )
                narratePrompt(step.promptAudio, step.prompt)
            }
            is QuestionStep -> {
                prompt.text = step.prompt
                feedback.text = "Назови ответ вслух"
                when (val visual = step.visual) {
                    is QuestionVisual.Apples -> {
                        boardHost.addView(
                            AppleBoard(this).apply {
                                leftCount = visual.left
                                rightCount = visual.right
                            },
                            LinearLayout.LayoutParams(-1, GameStyle.dp(this, 200)),
                        )
                    }
                    is QuestionVisual.TextOnly -> {
                        boardHost.addView(GameStyle.text(this, visual.text, 28f, true).apply {
                            gravity = Gravity.CENTER
                        })
                    }
                    else -> Unit
                }
                narratePrompt(step.promptAudio, step.prompt)
                when (
                    MicPermissionGate.nextAction(
                        hasPermission = hasPermission(),
                        alreadyAskedThisSession = askedPermission,
                        shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
                    )
                ) {
                    MicPermissionAction.PROCEED -> {
                        ensureVoiceCollector()
                        if (pipeline == null) {
                            showVoiceUnavailable(VoiceProblem.INITIALIZATION)
                        } else {
                            awaitingReady = true
                            pipeline?.activate()
                        }
                    }
                    MicPermissionAction.EXPLAIN_THEN_REQUEST -> {
                        feedback.text = MicPermissionGate.EXPLAIN_BODY
                        retryButton.text = MicPermissionGate.RETRY_LABEL
                        retryButton.visibility = View.VISIBLE
                        retryButton.setOnClickListener {
                            askedPermission = true
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        continueButton.text = "Пропустить"
                        continueButton.visibility = View.VISIBLE
                        continueButton.setOnClickListener {
                            session.skipQuestionUsingCorrectTransition()
                            renderStep()
                        }
                        retryButton.requestFocus()
                    }
                    MicPermissionAction.SHOW_UNAVAILABLE -> showVoiceUnavailable(VoiceProblem.PERMISSION)
                }
            }
            is GameStep, is BreakStep -> {
                prompt.text = "Шаг"
                feedback.text = "Этот тип шага пока упрощён"
                continueButton.visibility = View.VISIBLE
            }
        }
        ensureActionButtons()
    }

    private fun narratePrompt(audioRef: String?, text: String, spoken: String? = null) {
        if (!::narration.isInitialized) {
            narration = PlatformServices.narrationCoordinator(this)
        }
        narration.present(
            PresentationMessage(
                text = text,
                spokenText = spoken,
                narrationRef = audioRef,
                role = PresentationRole.INSTRUCTION,
                importance = PresentationImportance.CRITICAL,
            ),
        )
    }

    private fun ensureActionButtons() {
        if (!::actionRow.isInitialized) return
        actionRow.removeAllViews()
        fun dp(v: Int) = GameStyle.dp(this, v)
        actionRow.addView(
            GameStyle.button(this, "↻ Повтори") {
                PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Repeat)
            }.apply { textSize = 15f },
            LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) },
        )
        actionRow.addView(
            GameStyle.button(this, "? Помоги") {
                PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.Help)
            }.apply { textSize = 15f },
            LinearLayout.LayoutParams(-2, dp(48)),
        )
    }

    private fun handleInteractiveAction(action: FamilyAction): Boolean {
        when (action) {
            FamilyAction.Home, FamilyAction.Back -> {
                goHome(); return true
            }
            FamilyAction.Repeat -> {
                narration.repeat(); return true
            }
            FamilyAction.Help -> {
                val step = session.currentStep
                val hintText = when (step) {
                    is ChoiceStep -> step.feedback.hint ?: "Посмотри на варианты ещё раз."
                    is TrueFalseStep -> step.feedback.hint ?: "Подумай: правда или нет?"
                    is QuestionStep -> step.feedback.hint ?: "Посчитай внимательно и скажи число."
                    else -> "Нажми «Дальше», когда будешь готов."
                }
                feedback.text = hintText
                narration.present(
                    PresentationMessage(
                        text = hintText,
                        narrationRef = "common:want_hint",
                        role = PresentationRole.HINT,
                    ),
                )
                return true
            }
            FamilyAction.Next -> {
                onContinue(); return true
            }
            FamilyAction.BlockedSensitive, FamilyAction.OpenParentGate -> {
                feedback.text = "Это могут сделать только родители."
                return true
            }
            else -> return false
        }
    }

    private fun renderChoices(step: ChoiceStep) {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        step.options.forEachIndexed { index, option ->
            val button = GameStyle.button(this, option.text) {
                if (feedbackJob?.isActive == true) return@button
                val verdict = session.answerChoice(option.id)
                showVerdictFeedback(step.feedback, verdict)
                feedbackJob = lifecycleScope.launch {
                    delay(1200)
                    session.finishFeedback()
                    renderStep()
                }
            }.apply {
                textSize = 22f
                minimumWidth = dp(360)
                minHeight = dp(64)
            }
            column.addView(button, LinearLayout.LayoutParams(-2, dp(64)).apply { bottomMargin = dp(12) })
            if (index == 0) button.post { button.requestFocus() }
        }
        boardHost.addView(column)
    }

    private fun renderTrueFalse(step: TrueFalseStep) {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun option(label: String, value: Boolean): android.widget.Button {
            return GameStyle.button(this, label) {
                if (feedbackJob?.isActive == true) return@button
                val verdict = session.answerTrueFalse(value)
                showVerdictFeedback(step.feedback, verdict)
                feedbackJob = lifecycleScope.launch {
                    delay(1200)
                    session.finishFeedback()
                    renderStep()
                }
            }.apply {
                textSize = 24f
                minimumWidth = dp(220)
                minHeight = dp(72)
            }
        }
        val truth = option("Правда", true)
        val falsity = option("Неправда", false)
        row.addView(truth, LinearLayout.LayoutParams(-2, dp(72)).apply { marginEnd = dp(16) })
        row.addView(falsity, LinearLayout.LayoutParams(-2, dp(72)))
        boardHost.addView(row)
        truth.post { truth.requestFocus() }
    }

    private fun showVerdictFeedback(fb: StepFeedback, verdict: InteractiveVerdict) {
        feedback.text = when (verdict) {
            InteractiveVerdict.CORRECT -> fb.correct
            InteractiveVerdict.WRONG -> listOfNotNull(fb.wrong, fb.hint).joinToString("\n")
            InteractiveVerdict.NOT_UNDERSTOOD -> fb.unknown
        }
        feedback.setTextColor(
            if (verdict == InteractiveVerdict.CORRECT) GameStyle.teal
            else Color.rgb(159, 93, 36),
        )
        val audioRef = when (verdict) {
            InteractiveVerdict.CORRECT -> fb.correctAudio
            InteractiveVerdict.WRONG -> fb.wrongAudio
            InteractiveVerdict.NOT_UNDERSTOOD -> fb.unknownAudio
        }
        val spoken = when (verdict) {
            InteractiveVerdict.CORRECT -> fb.correct
            InteractiveVerdict.WRONG -> fb.wrong
            InteractiveVerdict.NOT_UNDERSTOOD -> fb.unknown
        }
        narratePrompt(audioRef, spoken)
    }

    private fun playVideo(step: VideoStep) {
        fun dp(v: Int) = GameStyle.dp(this, v)
        val repo = PlatformServices.contentRepository(this)
        val media = repo.resolveMediaRef(contentItem, step.mediaRef)
        val mediaItem = when (media) {
            is ResolvedMedia.FilePath -> MediaItem.fromUri(Uri.fromFile(media.file))
            is ResolvedMedia.AssetPath -> {
                val cache = File(cacheDir, "ix_${media.assetPath.hashCode()}.bin")
                if (!cache.exists() || cache.length() == 0L) {
                    try {
                        assets.open(media.assetPath).use { input ->
                            cache.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (_: Exception) {
                        feedback.text = "Не удалось открыть видео"
                        continueButton.visibility = View.VISIBLE
                        continueButton.setOnClickListener {
                            session.onVideoCompleted()
                            renderStep()
                        }
                        return
                    }
                }
                MediaItem.fromUri(Uri.fromFile(cache))
            }
            null -> {
                feedback.text = "Видео не найдено"
                continueButton.visibility = View.VISIBLE
                continueButton.setOnClickListener {
                    session.onVideoCompleted()
                    renderStep()
                }
                return
            }
        }
        session.markPlayingVideo()
        val view = PlayerView(this).apply {
            useController = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(-1, dp(280))
        }
        playerView = view
        boardHost.addView(view)
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        view.player = exo
        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    session.onVideoCompleted()
                    renderStep()
                }
            }
        })
        view.requestFocus()
    }

    private fun onContinue() {
        if (session.isCompleted) {
            goHome(); return
        }
        when (session.currentStep) {
            is MessageStep, is GameStep, is BreakStep, is MoveStep -> {
                session.advanceFromNonQuestion()
                renderStep()
            }
            else -> Unit
        }
    }

    private fun retryVoice() {
        when (
            MicPermissionGate.nextAction(
                hasPermission = hasPermission(),
                alreadyAskedThisSession = askedPermission,
                shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            )
        ) {
            MicPermissionAction.PROCEED -> {
                session.resetToStep()
                renderStep()
            }
            MicPermissionAction.EXPLAIN_THEN_REQUEST -> {
                askedPermission = true
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            MicPermissionAction.SHOW_UNAVAILABLE -> {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                )
            }
        }
    }

    private fun persistProgress() {
        if (!::session.isInitialized) return
        PlatformServices.experienceProgress(this).save(
            session.experience.id,
            session.currentStepId,
            contentItem.packageId,
            contentItem.packageVersion,
            completed = session.isCompleted,
        )
    }

    private fun releasePlayer() {
        playerView?.player = null
        player?.release()
        player = null
        playerView = null
    }

    private fun goHome() {
        if (::session.isInitialized) persistProgress()
        if (::narration.isInitialized) narration.stop() else PlatformServices.narration(this).stop()
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        player?.playWhenReady = false
        awaitingReady = false
        pipeline?.stopListening()
        persistProgress()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (session.currentStep is VideoStep && session.phase == InteractivePhase.PLAYING_VIDEO) {
            player?.playWhenReady = true
        }
    }

    override fun onDestroy() {
        feedbackJob?.cancel()
        PlatformServices.actionDispatcher().detach(actionHandler)
        PlatformServices.narrationContentItem = null
        releasePlayer()
        pipeline?.close()
        pipeline = null
        if (::narration.isInitialized) narration.stop() else PlatformServices.narration(this).stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONTENT_ID = "contentId"
    }
}
