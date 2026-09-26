package ru.familytv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.familytv.content.PlatformServices
import ru.familytv.content.ResolvedMedia
import ru.familytv.ui.ReferenceStage as StitchStage
import ru.familytv.home.watch.*
import ru.familytv.content.ContentItem
import kotlinx.coroutines.Job
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.interaction.InputSource
import ru.familytv.voice.VoiceRuntime

/** Approved Watch HUD on the existing production Media3 player and WatchProgressStore. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class VideoPlayerActivity : ComponentActivity() {
    data class PlaybackContent(val item: ContentItem, val media: ResolvedMedia, val caption: ResolvedMedia?)

    /** Kept overridable only so the debug fixture entry can reuse this single engine. Runs off main. */
    protected open fun loadVideo(contentId: String): PlaybackContent {
        val repo = PlatformServices.contentRepository(this)
        val item = requireNotNull(WatchCatalog.eligible(repo, PlatformServices.familySettings(this).contentFilter())
            .find { it.id == contentId }) { "Video unavailable" }
        val media = requireNotNull(repo.resolveMedia(item)) { "Media unavailable" }
        val caption = item.metadata.captionRef?.let { repo.resolveMediaRef(item, it) }?.takeIf { source ->
            runCatching {
                when (source) {
                    is ResolvedMedia.AssetPath -> assets.open(source.assetPath).close()
                    is ResolvedMedia.FilePath -> source.file.inputStream().close()
                }
            }.isSuccess
        }
        return PlaybackContent(item, media, caption)
    }

    protected open fun recordCompletion(content: PlaybackContent, durationMs: Long) {
        PlatformServices.activityHistory(this).record(kind = ru.familytv.parent.ActivityKind.WATCH,
            title = content.item.title, contentId = content.item.id,
            topics = content.item.metadata.topics, durationMs = durationMs)
        PlatformServices.journeyCoordinator(this).onContentCompleted(content.item.id)
    }

    private var loadedContent: PlaybackContent? = null
    private var loading: Job? = null
    private var captionLanguage = "ru"
    private val focusObserver = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, focus ->
        (focus?.tag as? String)?.let { Log.i(TAG, "focus=$it") }
    }
    private lateinit var root: WatchPlayerRoot
    private lateinit var video: PlayerView
    private lateinit var captions: SubtitleView
    private lateinit var hud: StitchStage
    private lateinit var shade: WatchPlayerShade
    private lateinit var timeline: WatchTimeline
    private val buttons = linkedMapOf<String, WatchButton>()
    private val controls: List<String>
        get() = listOf("rewind", "play", "forward", "captions", "audio", "voice")
            .filter { buttons[it]?.visibility == View.VISIBLE }
    private var player: ExoPlayer? = null
    private var media: MediaItem? = null
    private var contentId = ""
    private var lastPosition = 0L
    private var lastControl = "play"
    private var hudVisible = true
    private var captionsOn = false
    private var muted = false
    private var completed = false
    private var resumed = false
    private var desiredPlaying = true
    private var hasCaptions = false
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable {
        if (player?.isPlaying == true) {
            lastControl = (currentFocus?.tag as? String)?.takeIf { it in controls } ?: lastControl
            hudVisible = false; hud.visibility = View.GONE; root.requestFocus()
            positionCaptions()
            Log.i(TAG, "controls_hidden positionMs=${player?.currentPosition}")
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            timeline.update(p.currentPosition, p.duration.coerceAtLeast(0))
            handler.postDelayed(this, 300)
        }
    }
    private val actions = FamilyActionHandler { _, action ->
        when (action) {
            FamilyAction.Pause -> { desiredPlaying = false; player?.pause(); showControls(); true }
            FamilyAction.Resume -> { desiredPlaying = true; player?.play(); showControls(); true }
            FamilyAction.Back -> { saveProgress(); finish(); true }
            FamilyAction.Home -> {
                saveProgress()
                startActivity(android.content.Intent(this, ru.familytv.home.HomeActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish(); true
            }
            FamilyAction.Help, FamilyAction.Repeat -> { voiceHint(); true }
            else -> false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        contentId = intent.getStringExtra("contentId").orEmpty()
        lastPosition = savedInstanceState?.getLong("position") ?: PlatformServices.watchProgress(this).resumePositionMs(contentId)
        desiredPlaying = savedInstanceState?.getBoolean("playing") ?: true
        captionsOn = savedInstanceState?.getBoolean("captions") ?: PlatformServices.familySettings(this).accessibility().captionsEnabled
        muted = savedInstanceState?.getBoolean("muted") ?: false
        lastControl = savedInstanceState?.getString("control") ?: "play"
        root = WatchPlayerRoot(this).apply {
            setBackgroundColor(Color.BLACK); isFocusable = true; isFocusableInTouchMode = true
            routeKey = ::routeKey
        }
        video = PlayerView(this).apply {
            useController = false; isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setBackgroundColor(Color.BLACK)
            // One subtitle renderer is placed above the HUD shade, in an inset region.
            subtitleView?.visibility = View.GONE
        }
        captions = SubtitleView(this).apply {
            isFocusable = false
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setStyle(CaptionStyleCompat(Color.WHITE, 0xC0000000.toInt(), Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, android.graphics.Typeface.DEFAULT))
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionCaptions() }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(focusObserver)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { saveProgress(); finish() }
        })
    }

    private fun refreshMedia() {
        loading?.cancel()
        loading = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    loadVideo(contentId)
                }
                loadedContent = loaded
                captionLanguage = loaded.item.metadata.locale ?: "ru"
                val builder = MediaItem.Builder().setMediaId(contentId).setUri(requireNotNull(toUri(loaded.media)))
                toUri(loaded.caption)?.let { caption ->
                    val mime = if (loaded.item.metadata.captionRef?.endsWith(".srt", true) == true)
                        MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                    builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(caption)
                        .setMimeType(mime).setLanguage(captionLanguage).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
                }
                media = builder.build()
                buildHud(loaded.item.title)
                if (resumed) preparePlayer()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "load_error", e)
                media = null
                buildHud("Видео недоступно")
                buttons["back"]?.requestFocus()
                shade.status = "Нажми BACK, чтобы вернуться к списку."
            }
        }
    }

    private fun buildHud(title: String) {
        if (::hud.isInitialized) root.removeView(hud)
        root.removeView(captions)
        buttons.clear()
        hudVisible = true
        hasCaptions = false
        hud = StitchStage(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        shade = WatchPlayerShade(this, title)
        hud.place(shade, 0f, 0f, 1920f, 1080f)
        timeline = WatchTimeline(this).apply { tag = "timeline" }
        hud.place(timeline, 88f, 830f, 1720f, 80f)
        button("back", "Назад", "back", 100f, 76f, 180f, 56f) { saveProgress(); finish() }
        button("rewind", "−10 с", "", 100f, 926f, 120f, 64f) { seek(-10_000) }
        button("play", if (desiredPlaying) "Пауза" else "Смотреть", if (desiredPlaying) "pause" else "play", 248f, 926f, 220f, 64f, true) { toggle() }
        button("forward", "+10 с", "", 496f, 926f, 120f, 64f) { seek(10_000) }
        button("captions", if (captionsOn) "Вкл." else "Выкл.", "cc", 1220f, 926f, 178f, 64f) {
            if (hasCaptions) {
                captionsOn = !captionsOn; applyTracks()
                buttons["captions"]?.update(if (captionsOn) "Вкл." else "Выкл.")
                Log.i(TAG, "captions=$captionsOn")
            } else Toast.makeText(this, "В этом видео нет субтитров", Toast.LENGTH_SHORT).show()
        }
        button("audio", if (muted) "Выкл." else "Звук", "audio", 1430f, 926f, 150f, 64f) {
            muted = !muted; player?.volume = if (muted) 0f else 1f
            buttons["audio"]?.update(if (muted) "Выкл." else "Звук")
            Log.i(TAG, "muted=$muted")
        }
        button("voice", "Луми", "lumi", 1612f, 926f, 200f, 64f) { voiceHint() }
        buttons["captions"]?.visibility = View.GONE
        buttons["audio"]?.visibility = View.GONE
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        root.addView(captions, FrameLayout.LayoutParams(-1, -1))
        positionCaptions()
        hud.post { buttons[if (media == null) "back" else lastControl]?.takeIf { it.visibility == View.VISIBLE }?.requestFocus()
            ?: buttons["play"]?.requestFocus() }
    }

    private fun button(key: String, label: String, icon: String, x: Float, y: Float, w: Float, h: Float, primary: Boolean = false, action: () -> Unit) {
        val button = WatchButton(this, label, icon, w, h, primary).apply {
            tag = key
            setOnClickListener { action(); showControls() }
        }
        buttons[key] = button; hud.place(button, x - 12, y - 12, w + 24, h + 24)
    }

    private fun preparePlayer() {
        val item = media ?: return
        if (player != null) return
        completed = false
        val p = ExoPlayer.Builder(this).build()
        player = p; video.player = p
        p.volume = if (muted) 0f else 1f
        applyTracks()
        p.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                captions.setCues(cueGroup.cues)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                shade.status = if (playbackState == Player.STATE_BUFFERING) "Загружаем видео…" else ""
                if (playbackState == Player.STATE_READY) {
                    shade.paused = !p.playWhenReady
                    buttons["play"]?.update(if (p.playWhenReady) "Пауза" else "Смотреть", if (p.playWhenReady) "pause" else "play")
                    Log.i(TAG, "ready id=$contentId positionMs=${p.currentPosition} durationMs=${p.duration}")
                    showControls()
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (completed) return
                    completed = true
                    PlatformServices.watchProgress(this@VideoPlayerActivity).markCompleted(contentId, p.duration.coerceAtLeast(0))
                    loadedContent?.let { recordCompletion(it, p.duration.coerceAtLeast(0)) }
                    Log.i(TAG, "completed id=$contentId")
                    finish()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                buttons["play"]?.update(if (isPlaying) "Пауза" else "Смотреть", if (isPlaying) "pause" else "play")
                shade.paused = !isPlaying && p.playbackState == Player.STATE_READY
                Log.i(TAG, "playing=$isPlaying positionMs=${p.currentPosition}")
                if (isPlaying) armHide() else handler.removeCallbacks(hide)
            }
            override fun onTracksChanged(tracks: Tracks) {
                hasCaptions = tracks.isTypeSupported(C.TRACK_TYPE_TEXT)
                setTrackControlVisible("captions", hasCaptions)
                setTrackControlVisible("audio", tracks.isTypeSupported(C.TRACK_TYPE_AUDIO))
                Log.i(TAG, "tracks captions=$hasCaptions audio=${tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)}")
            }
            override fun onPlayerError(error: PlaybackException) {
                shade.status = "Не удалось открыть видео. Нажми BACK."
                Log.e(TAG, "playback_error ${error.errorCodeName}", error)
                showControls(); handler.removeCallbacks(hide)
            }
        })
        p.setMediaItem(item, lastPosition)
        Log.i(TAG, "resume_request id=$contentId positionMs=$lastPosition AI=${VoiceRuntime.peek()?.initializationCount ?: 0}")
        p.prepare(); p.playWhenReady = desiredPlaying
        handler.post(tick)
    }

    private fun setTrackControlVisible(key: String, visible: Boolean) {
        val button = buttons[key] ?: return
        if (!visible && button.hasFocus()) buttons["play"]?.requestFocus()
        button.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible && lastControl == key) lastControl = "play"
    }
    private fun applyTracks() {
        player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(captionLanguage).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsOn).build() }
    }
    private fun toggle() {
        PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE,
            if (player?.playWhenReady == true) FamilyAction.Pause else FamilyAction.Resume)
    }
    private fun seek(delta: Long) {
        val p = player ?: return
        if (p.duration <= 0) return
        val from = p.currentPosition
        val target = seekWatchPosition(from, delta, p.duration)
        p.seekTo(target)
        timeline.update(target, p.duration)
        Log.i(TAG, "seek fromMs=$from deltaMs=$delta targetMs=$target")
        showControls()
    }
    private fun showControls() {
        if (!::hud.isInitialized) return
        val wasHidden = !hudVisible
        hudVisible = true; hud.visibility = View.VISIBLE
        positionCaptions()
        if (wasHidden) buttons[lastControl]?.requestFocus()
        armHide()
    }
    private fun positionCaptions() {
        if (!::captions.isInitialized) return
        val params = captions.layoutParams as? FrameLayout.LayoutParams ?: return
        // VTT cues can carry an explicit bottom line, bypassing SubtitleView's default
        // bottom-padding fraction. Restrict the whole cue region instead of covering text.
        val scale = (root.height / 1080f).takeIf { it > 0 } ?: 1f
        captions.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 34f * scale)
        val bottom = ((if (hudVisible) 280 else 88) * scale).toInt()
        val side = (88 * scale).toInt()
        if (params.bottomMargin != bottom || params.leftMargin != side) {
            params.bottomMargin = bottom; params.leftMargin = side; params.rightMargin = side
            captions.layoutParams = params
        }
    }
    private fun armHide() {
        handler.removeCallbacks(hide)
        if (player?.isPlaying == true) handler.postDelayed(hide, 5_000)
    }
    private fun routeKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> actions.handle(InputSource.REMOTE, FamilyAction.Resume)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> actions.handle(InputSource.REMOTE, FamilyAction.Pause)
                    else -> toggle()
                }
            }
            return true
        }
        if (key in listOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST)) {
            if (event.action == KeyEvent.ACTION_UP) voiceHint()
            return true
        }
        if (key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) return false
        if (!::hud.isInitialized) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (!hudVisible) {
            showControls()
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) toggle()
            return true
        }
        armHide()
        val focused = currentFocus?.tag as? String ?: "play"
        val index = controls.indexOf(focused)
        when (key) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) {
                if (focused == "timeline") toggle() else buttons[focused]?.performClick()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val delta = if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (focused == "timeline") seek(delta * 10_000L)
                else if (index >= 0) buttons[controls[(index + delta).coerceIn(0, controls.lastIndex)]]?.requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (focused == "timeline") buttons["back"]?.requestFocus() else {
                if (index >= 0) lastControl = focused
                timeline.requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (focused == "back") timeline.requestFocus() else buttons[lastControl]?.requestFocus()
        }
        return true
    }

    private fun saveProgress() {
        val p = player ?: return
        if (p.duration <= 0 || p.playbackState == Player.STATE_IDLE) return
        lastPosition = p.currentPosition.coerceAtLeast(0)
        if (!completed) {
            PlatformServices.watchProgress(this).save(contentId, lastPosition, p.duration)
            Log.i(TAG, "progress_saved id=$contentId positionMs=$lastPosition durationMs=${p.duration}")
        }
    }
    override fun onResume() {
        super.onResume(); resumed = true; hideBars()
        PlatformServices.actionDispatcher().attach(actions)
        refreshMedia()
    }
    override fun onPause() {
        resumed = false
        loading?.cancel()
        lastControl = (currentFocus?.tag as? String)?.takeIf { it in controls } ?: lastControl
        desiredPlaying = player?.playWhenReady ?: desiredPlaying
        saveProgress()
        PlatformServices.actionDispatcher().detach(actions)
        handler.removeCallbacksAndMessages(null)
        video.player = null; player?.release(); player = null
        Log.i(TAG, "released id=$contentId positionMs=$lastPosition playing=$desiredPlaying")
        captions.setCues(emptyList())
        super.onPause()
    }
    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: lastPosition)
        outState.putBoolean("playing", player?.playWhenReady ?: desiredPlaying)
        outState.putString("control", lastControl)
        outState.putBoolean("captions", captionsOn); outState.putBoolean("muted", muted)
        super.onSaveInstanceState(outState)
    }
    private fun voiceHint() {
        showControls()
        Toast.makeText(this, "OK — пауза. Стрелки — управление. BACK — к описанию.", Toast.LENGTH_LONG).show()
        Log.i(TAG, "voice_control_only AI=${VoiceRuntime.peek()?.initializationCount ?: 0}")
    }
    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    private fun toUri(media: ResolvedMedia?): Uri? = when (media) {
        is ResolvedMedia.AssetPath -> Uri.parse("asset:///${media.assetPath}")
        is ResolvedMedia.FilePath -> Uri.fromFile(media.file)
        null -> null
    }
    companion object {
        const val EXTRA_CONTENT_ID = "contentId"
        private const val TAG = "FamilyPlayer"
    }
}
