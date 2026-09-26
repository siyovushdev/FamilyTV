package ru.familytv.home.watch

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.familytv.content.ContentItem
import ru.familytv.content.PlatformServices
import ru.familytv.ui.ReferenceStage as StitchStage
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.FamilyActionHandler
import ru.familytv.player.VideoPlayerActivity
import kotlinx.coroutines.Job
import ru.familytv.parent.ParentModeActivity
import ru.familytv.voice.VoiceRuntime

/** Approved native Watch presentation, backed by the existing production catalog and progress store. */
open class WatchActivity : ComponentActivity() {
    private var library: WatchCatalogSnapshot? = null
    private lateinit var stage: StitchStage
    private var screen = "home"
    private var categoryId = ""
    private var videoId = ""
    private var page = 0
    private var categoryPage = 0
    private var seriesId: String? = null
    private var catalogJob: Job? = null
    private var renderJob: Job? = null
    protected open fun loadCatalog(): WatchCatalogSnapshot = WatchCatalog.load(this)
    protected open fun playerIntent(contentId: String): Intent =
        Intent(this, VideoPlayerActivity::class.java).putExtra(VideoPlayerActivity.EXTRA_CONTENT_ID, contentId)
    private val actions = FamilyActionHandler { _, action ->
        when (action) {
            FamilyAction.Back -> { back(); true }
            FamilyAction.Home -> { finish(); true }
            FamilyAction.Help, FamilyAction.Repeat -> { voiceHint(); true }
            FamilyAction.Select -> currentFocus?.performClick() == true
            FamilyAction.Resume, FamilyAction.ContinueCurrent -> {
                val item = library?.let { WatchCatalog.continueWatching(it.visibleVideos, PlatformServices.watchProgress(this)).firstOrNull() }
                if (item != null) { openDetails(item.id); true } else false
            }
            is FamilyAction.OpenContent -> {
                if (library?.getItem(action.contentId) != null) { openDetails(action.contentId); true } else false
            }
            is FamilyAction.OpenWorld -> {
                if (library?.categories?.any { it.id == action.worldId } == true) {
                    categoryId = action.worldId; screen = "browse"; seriesId = null; page = 0; savedFocus = ""; render(); true
                } else false
            }
            else -> false
        }
    }
    private var lastCategoryFocus = ""
    private var lastVideoFocus = ""
    private var savedFocus = ""
    private var restoring = false
    private val nodes = linkedMapOf<String, View>()
    private var cardKeys = emptyList<String>()
    private var startedAt = 0L
    private val focusObserver = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, focus ->
        val key = focus?.tag as? String
        if (key != null) {
            if (!restoring) savedFocus = key
            Log.i(TAG, "screen=$screen focus=$key")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedAt = SystemClock.elapsedRealtime()
        hideWatchBars()
        screen = (savedInstanceState?.getString("screen") ?: intent.getStringExtra("screen"))
            ?.takeIf { it in listOf("home", "browse", "details") } ?: "home"
        categoryId = savedInstanceState?.getString("category") ?: intent.getStringExtra("category").orEmpty()
        videoId = savedInstanceState?.getString("video") ?: intent.getStringExtra("contentId").orEmpty()
        page = savedInstanceState?.getInt("page") ?: 0
        categoryPage = savedInstanceState?.getInt("categoryPage") ?: 0
        seriesId = savedInstanceState?.getString("series")
        savedFocus = savedInstanceState?.getString("focus").orEmpty()
        lastCategoryFocus = savedInstanceState?.getString("categoryFocus").orEmpty()
        lastVideoFocus = savedInstanceState?.getString("videoFocus").orEmpty()
        stage = StitchStage(this).apply { place(WatchBackdrop(this@WatchActivity), 0f, 0f, 1920f, 1080f) }
        setContentView(stage)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(focusObserver)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
    }

    private fun refreshCatalog() {
        catalogJob?.cancel()
        renderJob?.cancel()
        catalogJob = lifecycleScope.launch {
            try {
                library = withContext(Dispatchers.IO) { loadCatalog() }
                render()
                reportFullyDrawn()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "catalog_error", e)
                stage = StitchStage(this@WatchActivity)
                stage.place(WatchBackdrop(this@WatchActivity), 0f, 0f, 1920f, 1080f)
                button("back", "Назад", "back", 820f, 500f, 244f, 88f) { finish() }
                setContentView(stage)
                nodes["back"]?.requestFocus()
                Toast.makeText(this@WatchActivity, "Каталог пока недоступен", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideWatchBars()
        PlatformServices.actionDispatcher().attach(actions)
        refreshCatalog()
        aiLog("resume")
    }

    override fun onPause() {
        savedFocus = currentFocus?.tag as? String ?: savedFocus
        nodes.values.forEach { it.animate().cancel() }
        PlatformServices.actionDispatcher().detach(actions)
        catalogJob?.cancel(); renderJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen); outState.putString("category", categoryId)
        outState.putString("video", videoId); outState.putInt("page", page)
        outState.putString("focus", currentFocus?.tag as? String ?: savedFocus)
        outState.putInt("categoryPage", categoryPage); outState.putString("series", seriesId)
        outState.putString("categoryFocus", lastCategoryFocus); outState.putString("videoFocus", lastVideoFocus)
        super.onSaveInstanceState(outState)
    }

    private fun openDetails(id: String) {
        val lib = library ?: return
        val item = lib.getItem(id) ?: return
        videoId = item.id
        categoryId = lib.categories.firstOrNull { item in lib.videos(it) }?.id.orEmpty()
        seriesId = null
        page = lib.categories.find { it.id == categoryId }?.let { WatchCatalog.pageFor(lib.videos(it), id) } ?: 0
        screen = "details"; savedFocus = "play"; render()
    }

    private fun browseVideos(lib: WatchCatalogSnapshot, category: WatchCategory): List<ContentItem> =
        seriesId?.let { WatchCatalog.series(lib.visibleVideos, it) }
            ?: lib.videos(category)

    private fun render() {
        val lib = library ?: return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            if (screen == "details") lib.getItem(videoId)?.let { item ->
                categoryId = lib.categories.firstOrNull { item in lib.videos(it) }?.id.orEmpty()
            }
            val category = lib.categories.find { it.id == categoryId } ?: lib.categories.firstOrNull()
            categoryPage = categoryPage.coerceIn(0, (lib.categories.size - 1).coerceAtLeast(0) / 5)
            if (category != null) page = page.coerceIn(0, (browseVideos(lib, category).size - 1).coerceAtLeast(0) / 5)
            val wanted = when {
                screen == "home" -> lib.categories.drop(categoryPage * 5).take(5).mapNotNull { lib.videos(it).firstOrNull() }
                screen == "details" -> listOfNotNull(lib.getItem(videoId))
                category != null -> browseVideos(lib, category).drop(page * 5).take(5)
                else -> emptyList()
            }
            withContext(Dispatchers.IO) { lib.loadArtwork(wanted) }
            renderScene()
        }
    }

    private fun renderScene() {
        val lib = library ?: return
        val category = lib.categories.find { it.id == categoryId }
            ?: lib.getItem(videoId)?.let { item -> lib.categories.firstOrNull { item in lib.videos(it) } }
            ?: lib.categories.firstOrNull()
        categoryId = category?.id.orEmpty()
        restoring = true
        nodes.clear()
        cardKeys = emptyList()
        stage = StitchStage(this).apply { routeKey = ::routeKey }
        stage.place(WatchBackdrop(this), 0f, 0f, 1920f, 1080f)
        if (category == null) {
            screen = "home"
            stage.place(WatchDecoration(this, "home"), 0f, 0f, 1920f, 1080f)
            guide("home", "Пока нет доступных видео. Нажми BACK, чтобы вернуться домой.")
            button("back", "Домой", "back", 820f, 500f, 244f, 88f) { finish() }
        } else when (screen) {
            "browse" -> buildBrowse(lib, category)
            "details" -> {
                val item = lib.getItem(videoId)
                if (item == null) { screen = "browse"; buildBrowse(lib, category) }
                else { videoId = item.id; buildDetails(lib, category, item) }
            }
            else -> buildHome(lib)
        }
        val initial = savedFocus.takeIf { it in nodes } ?: when (screen) {
            "home" -> lastCategoryFocus.takeIf { it in nodes } ?: cardKeys.firstOrNull() ?: "back"
            "browse" -> lastVideoFocus.takeIf { it in nodes } ?: cardKeys.firstOrNull() ?: "back"
            else -> "play"
        }
        setContentView(stage)
        stage.post {
            nodes[initial]?.requestFocus()
            restoring = false
            savedFocus = initial
            Log.i(TAG, "render=$screen scene=${stage.width}x${stage.height} scale=${stage.sceneScale} ready_ms=${SystemClock.elapsedRealtime()-startedAt}")
            aiLog(screen)
        }
    }

    private fun buildHome(lib: WatchCatalogSnapshot) {
        stage.place(WatchDecoration(this, "home"), 0f, 0f, 1920f, 1080f)
        val guide = guide("home", "Выбери мир стрелками и нажми OK.")
        categoryPage = categoryPage.coerceIn(0, (lib.categories.size - 1).coerceAtLeast(0) / 5)
        cardKeys = lib.categories.drop(categoryPage * 5).take(5).mapIndexed { index, category ->
            val key = "category:${category.id}"
            val card = WatchCard(this, category, lib.categoryArt(category), null, null, null,
                when (index) { 0 -> 1; 4 -> -1; else -> 0 }).apply {
                focused = {
                    categoryId = category.id; lastCategoryFocus = key
                    guide.message = "${category.title}. Нажми OK, чтобы выбрать видео."
                }
                setOnClickListener { categoryId = category.id; seriesId = null; page = 0; screen = "browse"; savedFocus = ""; lastVideoFocus = ""; render() }
            }
            add(key, card, 72f + 356f * index, 314f, 352f, 420f)
            key
        }
        button("parent", "Родителям (PIN)", "parent", 1600f, 76f, 220f, 56f) {
            startActivity(Intent(this, ParentModeActivity::class.java))
        }
        if (categoryPage > 0) button("previous", "Предыдущие", "back", 768f, 942f, 210f, 56f) {
            categoryPage--; savedFocus = ""; render()
        }
        if ((categoryPage + 1) * 5 < lib.categories.size) button("next", "Ещё миры", "play", 1030f, 942f, 210f, 56f) {
            categoryPage++; savedFocus = ""; render()
        }
        val continued = WatchCatalog.continueWatching(lib.visibleVideos, PlatformServices.watchProgress(this)).firstOrNull()
        if (continued != null) {
            val position = PlatformServices.watchProgress(this).resumePositionMs(continued.id)
            button("continue", "Продолжить · ${watchTime(position)}", "play", 1260f, 76f, 302f, 56f) {
                openDetails(continued.id)
            }.contentDescription = "Продолжить: ${continued.title}, ${watchTime(position)}"
            Log.i(TAG, "continue_visible id=${continued.id} positionMs=$position")
        }
    }

    private fun buildBrowse(lib: WatchCatalogSnapshot, category: WatchCategory) {
        val videos = browseVideos(lib, category)
        val maxPage = ((videos.size - 1).coerceAtLeast(0) / 5)
        page = page.coerceIn(0, maxPage)
        stage.place(WatchDecoration(this, "browse", if (seriesId == null) category.title else "Серии", videos.size, category.id), 0f, 0f, 1920f, 1080f)
        button("back", "К мирам", "back", 100f, 76f, 178f, 48f) { back() }
        button("parent", "Родителям (PIN)", "parent", 1600f, 76f, 220f, 56f) {
            startActivity(Intent(this, ParentModeActivity::class.java))
        }
        val guide = guide("browse", if (videos.isEmpty()) "В этом мире пока нет видео." else "Выбери видео стрелками и нажми OK.")
        cardKeys = videos.drop(page * 5).take(5).mapIndexed { index, item ->
            val key = "video:${item.id}"
            val card = WatchCard(this, category, lib.art(item, category), item.title, item.durationMs, item.metadata.episodeOrder.takeIf { !item.metadata.seriesId.isNullOrBlank() },
                when (index) { 0 -> 1; 4 -> -1; else -> 0 }).apply {
                focused = { videoId = item.id; lastVideoFocus = key; guide.message = "${item.title}. Нажми OK, чтобы открыть." }
                setOnClickListener { videoId = item.id; screen = "details"; savedFocus = "play"; render() }
            }
            add(key, card, 72f + 356f * index, 320f, 352f, 420f)
            key
        }
        if (page > 0) button("previous", "Предыдущие", "back", 768f, 942f, 210f, 56f) {
            page--; savedFocus = ""; lastVideoFocus = ""; render()
        }
        if (page < maxPage) button("next", "Ещё видео", "play", 1030f, 942f, 210f, 56f) {
            page++; savedFocus = ""; lastVideoFocus = ""; render()
        }
    }

    private fun buildDetails(lib: WatchCatalogSnapshot, category: WatchCategory, item: ContentItem) {
        stage.place(WatchDecoration(this, "details", category.title), 0f, 0f, 1920f, 1080f)
        button("back", "К видео", "back", 100f, 76f, 196f, 48f) { back() }
        stage.place(WatchHero(this, lib.art(item, category), item.durationMs, item.metadata.episodeOrder.takeIf { !item.metadata.seriesId.isNullOrBlank() }), 88f, 283f, 1000f, 530f)
        stage.place(WatchDetailsText(this, item.title, item.description), 1128f, 350f, 704f, 205f)
        button("guide", "Луми рядом · Подсказка", "lumi", 1128f, 542f, 684f, 64f) { voiceHint() }
        val resume = PlatformServices.watchProgress(this).resumePositionMs(item.id)
        val series = WatchCatalog.series(lib.visibleVideos, item.metadata.seriesId)
        val label = if (resume > 0) "Продолжить · ${watchTime(resume)}" else "Смотреть"
        button("play", label, "play", 1128f, 638f, if (series.size > 1) 430f else 684f, 76f, true) {
            PlatformServices.activityHistory(this).record(ru.familytv.parent.ActivityKind.WATCH,
                item.title, item.id, topics = item.metadata.topics + item.metadata.skills)
            startActivity(playerIntent(item.id))
        }
        if (series.size > 1) button("series", "Серии (${series.size})", "stories", 1592f, 638f, 220f, 76f) {
            seriesId = item.metadata.seriesId
            page = WatchCatalog.pageFor(browseVideos(lib, category), item.id)
            screen = "browse"; savedFocus = "video:${item.id}"; render()
        }
        Log.i(TAG, "details id=${item.id} resumeMs=$resume")
    }

    private fun guide(mode: String, message: String): WatchGuide = WatchGuide(this).apply {
        this.message = message
        setOnClickListener { voiceHint() }
        add("guide", this, 84f, if (mode == "home") 910f else 906f, 600f, 110f)
    }

    private fun add(key: String, view: View, x: Float, y: Float, w: Float, h: Float) {
        view.tag = key; nodes[key] = view; stage.place(view, x, y, w, h)
    }

    private fun button(key: String, label: String, glyph: String, x: Float, y: Float, w: Float, h: Float, primary: Boolean = false, click: () -> Unit): WatchButton =
        WatchButton(this, label, glyph, w, h, primary).also {
            it.setOnClickListener { click() }
            add(key, it, x - 12, y - 12, w + 24, h + 24)
        }

    private fun back() {
        when (screen) {
            "details" -> {
                screen = "browse"
                library?.let { lib -> lib.categories.find { it.id == categoryId }?.let {
                    page = WatchCatalog.pageFor(browseVideos(lib, it), videoId)
                } }
                savedFocus = "video:$videoId"; render()
            }
            "browse" -> {
                if (seriesId != null) { seriesId = null; screen = "details"; savedFocus = "series" }
                else { screen = "home"; savedFocus = "category:$categoryId"
                    categoryPage = library?.categories?.indexOfFirst { it.id == categoryId }?.coerceAtLeast(0)?.div(5) ?: 0 }
                render()
            }
            else -> finish()
        }
    }

    private fun routeKey(event: KeyEvent): Boolean {
        if (event.keyCode in listOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST)) {
            if (event.action == KeyEvent.ACTION_UP) voiceHint()
            return true
        }
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -2; KeyEvent.KEYCODE_DPAD_DOWN -> 2
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val key = currentFocus?.tag as? String ?: savedFocus
        val index = cardKeys.indexOf(key)
        val target = when {
            index >= 0 && dir == -1 -> cardKeys[(index - 1).coerceAtLeast(0)]
            index >= 0 && dir == 1 -> cardKeys[(index + 1).coerceAtMost(cardKeys.lastIndex)]
            index >= 0 && dir == -2 -> if (screen == "home") { if ("continue" in nodes) "continue" else "parent" } else "back"
            index >= 0 && dir == 2 -> "guide"
            screen == "details" && key == "play" && dir == 1 -> "series"
            screen == "details" && key == "series" && dir == -1 -> "play"
            screen == "details" && key in listOf("play", "series") && dir == -2 -> "guide"
            screen == "details" && key == "guide" && dir == -2 -> "back"
            screen == "details" && dir == 2 -> "play"
            screen == "details" && dir == -2 -> "back"
            key == "guide" && dir == -2 -> lastCardKey()
            key == "back" && dir == 2 && cardKeys.isEmpty() -> "guide"
            key in listOf("next", "previous") && dir == -2 -> lastCardKey()
            key == "guide" && dir == 1 -> if ("previous" in nodes) "previous" else "next"
            key == "previous" && dir == 1 -> "next"
            key == "previous" && dir == -1 -> "guide"
            key == "next" && dir == -1 -> if ("previous" in nodes) "previous" else "guide"
            key in listOf("back", "parent", "continue") && dir == 2 -> lastCardKey()
            key in listOf("back", "continue") && dir == 1 -> "parent"
            key == "parent" && dir == -1 -> if (screen == "home") "continue" else "back"
            else -> key
        }
        nodes[target]?.requestFocus()
        return true
    }

    private fun lastCardKey(): String =
        (if (screen == "home") lastCategoryFocus else lastVideoFocus).takeIf { it in cardKeys }
            ?: cardKeys.firstOrNull() ?: "back"
    private fun voiceHint() {
        Toast.makeText(this, "Выбирай стрелками. Нажми OK, чтобы открыть. BACK — назад.", Toast.LENGTH_LONG).show()
        aiLog("voice_control_only")
    }
    private fun aiLog(reason: String) {
        val runtime = VoiceRuntime.peek()
        Log.i(TAG, "$reason AI=${runtime?.initializationCount ?: 0} runtimePresent=${runtime != null}")
    }
    @Suppress("DEPRECATION")
    private fun hideWatchBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    companion object { private const val TAG = "FamilyWatch" }
}
