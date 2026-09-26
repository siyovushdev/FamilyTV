package ru.familytv.home.stitch

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.familytv.R
import ru.familytv.adventure.GuideEmotion
import ru.familytv.home.ContentDetailsActivity
import ru.familytv.home.ContentNavigator
import ru.familytv.creative.CreativeStudioActivity
import ru.familytv.journey.JourneyMapActivity
import ru.familytv.parent.ParentModeActivity
import ru.familytv.ui.LumiView
import ru.familytv.voice.VoiceRuntime

/** Variant C design experiment. Registered only in the debug manifest; production Home is untouched. */
class StitchHomePreviewActivity : ComponentActivity() {
    private lateinit var stage: StitchStage
    private lateinit var backdrop: StitchBackdrop
    private lateinit var lumi: LumiView
    private lateinit var worlds: List<StitchWorldView>
    private lateinit var voice: StitchControl
    private lateinit var parent: StitchControl
    private lateinit var watch: StitchControl
    private lateinit var mission: StitchMissionView
    private var content: StitchPreviewContent? = null
    private var lastWorld = 1
    private var requestedFocus = "world:1"
    private var dataJob: Job? = null
    private var idleAnimator: ObjectAnimator? = null
    private var hasRestoredFocus = false
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        startedAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        lastWorld = savedInstanceState?.getInt("lastWorld", 1)?.coerceIn(0, 4) ?: 1
        requestedFocus = savedInstanceState?.getString("focus") ?: "world:$lastWorld"
        hideSystemBars()
        stage = StitchStage(this)
        stage.routeKey = ::routeKey
        backdrop = StitchBackdrop(this)
        stage.place(backdrop, 0f, 0f, 1920f, 1080f)
        stage.place(LumiView(this).apply {
            emotion = GuideEmotion.CURIOUS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, 76f, 80f, 76f, 80f)
        voice = StitchControl(this, "«Луми, давай играть!»", R.drawable.stitch_c_mic, 305f).apply {
            tag = "voice"
            contentDescription = "Голос. Подсказка"
            setOnClickListener { showVoiceHint() }
        }
        parent = StitchControl(this, "Родителям", R.drawable.stitch_c_parent, 209f).apply {
            tag = "parent"
            setOnClickListener { startActivity(Intent(this@StitchHomePreviewActivity, ParentModeActivity::class.java)) }
        }
        stage.place(voice, 1306f, 88f, 305f, 64f)
        stage.place(parent, 1631f, 88f, 209f, 64f)
        worlds = StitchWorld.ALL.indices.map { index ->
            StitchWorldView(this, index).apply {
                tag = "world:$index"
                onFocused = {
                    lastWorld = index
                    backdrop.selectedWorld = index
                    moveLumi()
                }
                setOnClickListener {
                    val item = content?.worlds?.get(index)
                    if (index == 0) {
                        startActivity(Intent(this@StitchHomePreviewActivity,
                            ru.familytv.home.stitch.watch.StitchWatchPreviewActivity::class.java))
                    } else if (index == 3 && content?.creativeShortcut == true) {
                        startActivity(Intent(this@StitchHomePreviewActivity, CreativeStudioActivity::class.java))
                    } else if (item == null) {
                        Toast.makeText(this@StitchHomePreviewActivity, "Этот остров пока отдыхает", Toast.LENGTH_SHORT).show()
                    } else {
                        // Existing details screen: preview itself never initializes game/voice models.
                        startActivity(Intent(this@StitchHomePreviewActivity, ContentDetailsActivity::class.java)
                            .putExtra(ContentDetailsActivity.EXTRA_CONTENT_ID, item.id))
                    }
                }
                stage.place(this, 40f + 357.5f * index, 275f, 410f, 436f)
            }
        }
        lumi = LumiView(this).apply {
            emotion = GuideEmotion.HAPPY
            contentDescription = "Луми рядом с выбранным островом"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            translationZ = 16f
        }
        stage.place(lumi, 691f, 182f, 128f, 136f)
        mission = StitchMissionView(this).apply {
            tag = "mission"
            setOnClickListener { openMission() }
        }
        stage.place(mission, 64f, 798f, 1792f, 162f)
        watch = StitchControl(this, "Досмотреть", R.drawable.stitch_c_watch, 256f).apply {
            tag = "continue"
            visibility = View.GONE
            setOnClickListener { content?.continueVideo?.let { ContentNavigator.open(this@StitchHomePreviewActivity, it) } }
        }
        stage.place(watch, 1268f, 847f, 256f, 64f)
        setContentView(stage)
        stage.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused != null) {
                val focusTag = focused.tag as? String ?: requestedFocus
                if (hasRestoredFocus) requestedFocus = focusTag
                Log.i(TAG, "focus=$focusTag lastWorld=$lastWorld")
            }
        }
        stage.post {
            if (!hasRestoredFocus) {
                restoreFocus()
                hasRestoredFocus = true
            }
            moveLumi()
            Log.i(TAG, "scene=${stage.width}x${stage.height} scale=${stage.sceneScale} onCreate_to_layout_ms=${SystemClock.elapsedRealtime() - startedAt}")
            logAi("layout")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        dataJob?.cancel()
        dataJob = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { StitchPreviewContent.load(this@StitchHomePreviewActivity) }
                content = loaded
                worlds.forEachIndexed { i, v ->
                    v.representative = loaded.worlds[i]?.title
                        ?: if (i == 3 && loaded.creativeShortcut) "История с Луми" else "Пока нет занятий"
                }
                mission.data = loaded
                watch.visibility = if (loaded.continueVideo != null) View.VISIBLE else View.GONE
                watch.contentDescription = loaded.continueVideo?.let { "Досмотреть: ${it.title}" }
                if (requestedFocus == "continue") {
                    if (loaded.continueVideo != null) watch.requestFocus() else mission.requestFocus()
                }
                Log.i(TAG, "catalog_bound_ms=${SystemClock.elapsedRealtime() - startedAt} worlds=${loaded.worlds.map { it?.id }} creativeShortcut=${loaded.creativeShortcut} journey=${loaded.journeyId} continue=${loaded.continueVideo?.id}")
                reportFullyDrawn()
                logAi("catalog")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Catalog unavailable", e)
                worlds.forEach { it.representative = "Пока нет занятий" }
                Toast.makeText(this@StitchHomePreviewActivity, "Каталог пока недоступен", Toast.LENGTH_SHORT).show()
            }
        }
        stage.post {
            idleAnimator?.cancel()
            idleAnimator = ObjectAnimator.ofFloat(lumi, View.TRANSLATION_Y, 0f, -6f * stage.sceneScale).apply {
                duration = 2400L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onPause() {
        idleAnimator?.cancel()
        lumi.animate().cancel()
        worlds.forEach { it.settle() }
        dataJob?.cancel()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("lastWorld", lastWorld)
        outState.putString("focus", requestedFocus)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun restoreFocus() {
        when (requestedFocus) {
            "voice" -> voice
            "parent" -> parent
            "mission", "continue" -> mission
            else -> worlds[lastWorld]
        }.requestFocus()
    }

    private fun moveLumi() {
        if (!::lumi.isInitialized) return
        val edgeOffset = when (lastWorld) { 0 -> 28f; 4 -> -48f; else -> 0f }
        lumi.animate().translationX(((lastWorld - 1) * 357.5f + edgeOffset) * stage.sceneScale)
            .setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Explicit spatial links, including bounded edges and return to the last island. */
    private fun routeKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (event.action == KeyEvent.ACTION_UP) showVoiceHint()
            return true
        }
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -2
            KeyEvent.KEYCODE_DPAD_DOWN -> 2
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val focused = currentFocus
        val world = worlds.indexOfFirst { it === focused }
        val target = when {
            world >= 0 && direction == -1 -> worlds[(world - 1).coerceAtLeast(0)]
            world >= 0 && direction == 1 -> worlds[(world + 1).coerceAtMost(4)]
            world >= 0 && direction == -2 -> if (world < 3) voice else parent
            world >= 0 && direction == 2 -> mission
            focused === voice && direction == 1 -> parent
            focused === parent && direction == -1 -> voice
            (focused === voice || focused === parent) && direction == 2 -> worlds[lastWorld]
            (focused === mission || focused === watch) && direction == -2 -> worlds[lastWorld]
            focused === mission && direction == -1 && watch.visibility == View.VISIBLE -> watch
            focused === watch && direction == 1 -> mission
            focused == null -> worlds[lastWorld]
            else -> focused
        }
        target?.requestFocus()
        return true
    }

    private fun openMission() {
        val d = content ?: return
        val journeyId = d.journeyId
        when {
            journeyId != null -> startActivity(Intent(this, JourneyMapActivity::class.java)
                .putExtra(JourneyMapActivity.EXTRA_JOURNEY_ID, journeyId))
            d.missionItem != null -> ContentNavigator.open(this, d.missionItem)
            else -> worlds[lastWorld].requestFocus()
        }
    }

    private fun showVoiceHint() {
        Toast.makeText(this, "Выбери остров стрелками и нажми OK. Голос доступен в занятиях.", Toast.LENGTH_LONG).show()
        logAi("voice_control_only")
    }

    private fun logAi(reason: String) {
        val runtime = VoiceRuntime.peek()
        Log.i(TAG, "$reason AI=${runtime?.initializationCount ?: 0} runtimePresent=${runtime != null}")
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    companion object { private const val TAG = "StitchHomePreview" }
}
