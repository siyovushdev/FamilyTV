package ru.familytv.creative

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.content.memoryPrefs
import ru.familytv.interaction.*
import ru.familytv.journey.CompletionPolicy
import ru.familytv.journey.JourneyParser
import ru.familytv.parent.ActivityHistoryStore
import ru.familytv.parent.ActivityKind
import ru.familytv.voice.VoiceRuntime

class StoryProductionTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val debug = File(main.parentFile, "debug")
    private fun source(path: String) = File(main, "java/ru/familytv/$path").readText()
    private val creativeSources by lazy {
        File(main, "java/ru/familytv/creative").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }
    private val domain = StoryPreviewDomain(File(main, "assets/catalog/stories/create-story-v1.json").readText())
    private fun home() = StoryPreviewState(domain)
    private fun hero() = home().apply { select() }
    private fun world() = hero().apply { activate("hero:ROBI") }
    private fun objects() = world().apply { activate("world:FOREST") }
    private fun revealing() = objects().apply { activate("object:KEY") }
    private fun choice() = revealing().apply { tick(StoryRules.KEY_REVEAL_MS) }
    private fun consequence() = choice().apply { activate("action:DOOR") }
    private fun ending() = consequence().apply { tick(StoryRules.CONTINUE_AT_MS); activate("inside") }

    @Test fun homeCreateRoutesToProductionStudio() {
        for (phrase in listOf("создавай", "хочу создавай", "открой создавай")) {
            val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), phrase) as RouteResult.Action
            assertEquals(FamilyAction.OpenWorld(StoryRoutes.WORLD_ID), routed.action)
        }
        val home = source("home/HomeActivity.kt")
        assertTrue(home.contains("action.worldId == StoryRoutes.WORLD_ID"))
        assertTrue(home.contains("startActivity(StoryRoutes.intent(this))"))
        assertTrue(home.contains("if (StoryRoutes.opens(item))"))
        assertEquals("exactly one catalog create entry", 1, Regex("\"id\": \"lumi-story\"").findAll(File(main, "assets/catalog/manifest.json").readText()).count())
        assertFalse(home.contains("chip(\"Создавать\")") || home.contains("chip(\"Создавай\")"))
        val manifest = File(main, "AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".creative.CreativeStudioActivity\"").substringBefore("/>")
        assertTrue(entry.contains("android:exported=\"false\""))
        assertTrue(source("home/ContentNavigator.kt").contains("if (StoryRoutes.opens(item))"))
        assertTrue(StoryRoutes.opens(ContentItem("lumi-story", "Придумать историю", type = ContentType.INTERACTIVE, route = "creative/story")))
        assertFalse(StoryRoutes.opens(ContentItem("other", "Другое", type = ContentType.INTERACTIVE, experienceRef = "x.json")))
    }

    @Test fun createHomeOpensStoryBuilder() {
        val s = home()
        assertEquals(StoryScreen.CREATE_HOME, s.value.screen)
        assertEquals("place:BOOK", s.value.focus)
        assertTrue(s.select())
        assertEquals(StoryScreen.HERO, s.value.screen)
        assertEquals("hero:ROBI", s.value.focus)
    }

    @Test fun robiCanonicalPath() {
        assertEquals(StoryHero.ROBI, world().value.hero)
        for (s in listOf(objects(), choice(), consequence(), ending())) assertEquals(StoryHero.ROBI, s.value.hero)
    }

    @Test fun forestCanonicalPath() {
        assertEquals(StoryWorld.FOREST, objects().value.world)
        for (s in listOf(choice(), consequence(), ending())) assertEquals(StoryWorld.FOREST, s.value.world)
    }

    @Test fun keyCanonicalPath() {
        val s = revealing()
        assertEquals(StoryObject.KEY, s.value.item)
        s.tick(StoryRules.KEY_REVEAL_MS)
        assertEquals(StoryScreen.STORY_CHOICE, s.value.screen)
        assertEquals(StoryObject.KEY, s.value.item)
    }

    @Test fun doorCanonicalPath() {
        val s = consequence()
        assertEquals(StoryAction.DOOR, s.value.action)
        s.tick(StoryRules.CONTINUE_AT_MS); s.activate("inside")
        assertEquals(StoryScreen.ENDING, s.value.screen)
        assertEquals("secret_library", domain.endingId(s.value))
    }

    @Test fun keyPersistsIntoStoryChoice() {
        val s = choice()
        assertEquals(StoryObject.KEY, s.value.item)
        assertTrue(domain.lines(s.value)[2].contains("золотой ключ"))
    }

    @Test fun doorAndPathHaveNoCorrectOrWrong() {
        val s = choice(); s.activate("action:PATH")
        assertEquals(StoryScreen.STORY_CHOICE, s.value.screen)
        assertNull(s.value.action)
        assertTrue(s.prompt().contains("другой сказке"))
        for (word in listOf("Правильно", "Неправильно", "Верно", "очки", "XP", "achievement")) {
            assertFalse(word, creativeSources.contains(word))
        }
    }

    @Test fun consequenceCannotBeSkipped() {
        val s = consequence()
        repeat(8) { assertFalse(s.semantic(FamilyAction.Next)); assertFalse(s.semantic(FamilyAction.Select)) }
        s.tick(StoryRules.CONTINUE_AT_MS)
        assertTrue(s.semantic(FamilyAction.Next))
        assertEquals(StoryScreen.ENDING, s.value.screen)
    }

    @Test fun endingIsFinite() {
        val s = ending()
        s.tick(10 * 60_000L)
        assertEquals(StoryScreen.ENDING, s.value.screen)
        assertFalse(s.semantic(FamilyAction.Next))
    }

    @Test fun saveUsesExistingStoryStore() {
        val store = StoryStore(memoryPrefs())
        val s = ending(); s.activate("save")
        val draft = domain.draft(s.value, 10L, store.createId())!!
        store.save(draft)
        assertEquals(1, store.list().size)
        assertEquals(draft.selections, store.list().single().selections)
        assertEquals("Тайна зачарованного дуба", store.list().single().title)
        store.save(draft.copy(title = "Тайна зачарованного дуба"))
        assertEquals(1, store.list().size)
        val again = store.findBySelections(draft.selections)
        assertEquals(draft.id, again?.id)
        assertTrue(source("creative/CreativeStudioActivity.kt").contains("StoryStore(this)"))
        assertTrue(source("creative/CreativeStudioActivity.kt").contains("store.save(saved)"))
        assertFalse(creativeSources.contains("familytv_stories_v2"))
    }

    @Test fun savedStoryMatchesGalleryJournalModel() {
        val store = StoryStore(memoryPrefs())
        val draft = domain.draft(ending().value, 20L, "abc123")!!
        store.save(draft)
        val saved = store.list().single()
        assertEquals(saved.id, draft.id)
        assertTrue(saved.retellText.contains("Роби"))
        assertTrue(saved.retellText.contains("волшебный лес"))
        assertTrue(saved.retellText.contains("золотой ключ"))
        val journal = source("journey/DiscoveryJournalActivity.kt")
        assertTrue(journal.contains("StoryStore(this).list()"))
        assertTrue(journal.contains("Наши истории"))
        assertTrue(source("journey/JourneyCoordinator.kt").contains("onStoryCreated"))
        assertTrue(source("journey/JourneyCoordinator.kt").contains("syncStoriesIntoJournal"))
    }

    @Test fun replayPreservesTheSameChoices() {
        val s = ending(); val before = s.value
        s.activate("replay")
        while (s.value.screen == StoryScreen.REPLAY) s.tick(StoryRules.REPLAY_BEAT_MS)
        assertEquals(StoryScreen.ENDING, s.value.screen)
        assertEquals(listOf(before.hero, before.world, before.item, before.action), listOf(s.value.hero, s.value.world, s.value.item, s.value.action))
    }

    @Test fun replayDoesNotCreateANewDraft() {
        val s = ending(); s.activate("save"); s.rememberSave("keep")
        val id = s.value.savedId
        s.activate("replay")
        while (s.value.screen == StoryScreen.REPLAY) s.tick(StoryRules.REPLAY_BEAT_MS)
        assertEquals(id, s.value.savedId)
        assertTrue(s.value.saved)
        val activity = source("creative/CreativeStudioActivity.kt")
        assertTrue(activity.contains("previous.screen == StoryScreen.CONSEQUENCE && s.screen == StoryScreen.ENDING"))
        assertFalse(activity.contains("if (s.screen == StoryScreen.REPLAY) persistSave"))
    }

    @Test fun newStoryClearsTransientState() {
        val s = ending(); s.activate("save"); s.activate("new")
        assertEquals(StorySnapshot(screen = StoryScreen.HERO, focus = "hero:ROBI"), s.value)
    }

    @Test fun backRestoresSelectionAndFocus() {
        val s = consequence()
        s.back(); assertEquals("action:DOOR", s.value.focus)
        s.back(); assertEquals("object:KEY", s.value.focus)
        s.back(); assertEquals("world:FOREST", s.value.focus)
        s.back(); assertEquals("hero:ROBI", s.value.focus)
        s.back(); assertEquals("place:BOOK", s.value.focus)
        val home = source("home/HomeActivity.kt")
        assertTrue(home.contains("putString(\"lastFocusId\", StoryRoutes.CONTENT_ID)"))
        assertTrue(home.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
            .contains("PlatformServices.actionDispatcher().attach(homeActionHandler)"))
    }

    @Test fun pauseAndResumePreserveState() {
        val r = revealing(); r.tick(800); r.pause(); r.tick(20_000)
        assertEquals(800, r.value.elapsedMs)
        val restored = StoryPreviewState(domain, StorySnapshot.decode(r.value.encode()))
        assertEquals(r.value, restored.value)
        restored.resume(); restored.tick(StoryRules.KEY_REVEAL_MS)
        assertEquals(StoryScreen.STORY_CHOICE, restored.value.screen)
    }

    @Test fun processRecreationRestoresSelections() {
        val c = choice(); c.move(Direction.RIGHT)
        val back = StoryPreviewState(domain, StorySnapshot.decode(c.value.encode()))
        assertEquals(c.value, back.value)
        assertEquals(StoryObject.KEY, back.value.item)
        assertEquals("action:PATH", back.value.focus)
        assertTrue(source("creative/CreativeStudioActivity.kt").contains("outState.putBoolean(\"visitCompleted\", visitCompleted)"))
    }

    @Test fun unavailableVariantsStaySafe() {
        for (key in listOf("hero:TIM", "hero:PUSHISTIK", "world:SPACE", "world:ISLAND", "object:MAP", "object:LANTERN", "action:PATH")) {
            val s = when {
                key.startsWith("hero") -> hero()
                key.startsWith("world") -> world()
                key.startsWith("object") -> objects()
                else -> choice()
            }
            s.activate(key)
            assertEquals(key, s.value.soon)
            if (key.startsWith("hero")) assertEquals(StoryScreen.HERO, s.value.screen)
            if (key.startsWith("world")) { assertEquals(StoryScreen.WORLD, s.value.screen); assertEquals(StoryHero.ROBI, s.value.hero) }
            if (key.startsWith("object")) { assertEquals(StoryScreen.OBJECT, s.value.screen); assertNull(s.value.item) }
            if (key.startsWith("action")) { assertEquals(StoryScreen.STORY_CHOICE, s.value.screen); assertNull(s.value.action) }
        }
    }

    @Test fun historyAndCompletionFollowExistingCreativePolicy() {
        val activity = source("creative/CreativeStudioActivity.kt")
        assertTrue(activity.contains("onContentOpened(StoryRoutes.CONTENT_ID)"))
        assertTrue(activity.contains("onContentCompleted(StoryRoutes.CONTENT_ID)"))
        assertTrue(activity.contains("PlatformServices.activityHistory(this).record("))
        assertTrue(activity.contains("onStoryCreated("))
        assertTrue(activity.contains("if (!recordsActivity) return"))
        val catalog = JSONObject(File(main, "assets/catalog/manifest.json").readText()).getJSONArray("items")
        val item = (0 until catalog.length()).map { catalog.getJSONObject(it) }.single { it.getString("id") == StoryRoutes.CONTENT_ID }
        assertEquals("INTERACTIVE", item.getString("type"))
        assertEquals("catalog/stories/create-story-v1.json", item.getString("experienceRef"))
        assertEquals("creative/story", item.getString("route"))
        val journey = JourneyParser.parse(File(main, "assets/catalog/journeys/forest-secrets.json").readText())
        val node = journey.nodes.single { it.contentId == StoryRoutes.CONTENT_ID }
        assertEquals(CompletionPolicy.CONTENT_COMPLETED, node.completionPolicy)
        assertTrue(File(main, "assets/catalog/stories/lumi-story.json").isFile)
        assertNotEquals(
            JSONObject(File(main, "assets/catalog/stories/lumi-story.json").readText()).getString("id"),
            JSONObject(File(main, "assets/catalog/stories/create-story-v1.json").readText()).getString("id"),
        )
    }

    @Test fun lifecycleDoesNotDuplicateHistory() {
        val activity = source("creative/CreativeStudioActivity.kt")
        val lifecycle = activity.substringAfter("override fun onResume()").substringBefore("override fun onSaveInstanceState")
        for (write in listOf("recordVisit(", "persistSave(", "activityHistory(")) assertFalse(write, lifecycle.contains(write))
        assertTrue(activity.contains("if (!visitCompleted && previous.screen == StoryScreen.CONSEQUENCE && s.screen == StoryScreen.ENDING)"))
        assertTrue(activity.contains("if (s.saved && !previous.saved) persistSave()"))
    }

    @Test fun productionFlowNeverInitializesAi() {
        ending().apply { activate("replay"); tick(StoryRules.REPLAY_BEAT_MS * 6); activate("new") }
        assertNull(VoiceRuntime.peek())
        for (forbidden in listOf("VoiceRuntime.get(", "newPipeline(", ".prewarm(", "startListening(", "acquireLease(", "AudioRecord(", "SpeechRecognizer", "GroundedQwenMatcher", ".gguf", ".onnx")) {
            assertFalse(forbidden, creativeSources.contains(forbidden))
        }
        assertTrue(creativeSources.contains("VoiceRuntime.peek()"))
        assertTrue(creativeSources.contains("PlatformServices.narrationCoordinator(this@CreativeStudioActivity)"))
    }

    @Test fun productionDoesNotDependOnDebugPreview() {
        for (name in StoryRoutes.ART) {
            assertTrue(name, File(main, "assets/create_story_v1/$name.jpg").isFile)
        }
        assertTrue(File(main, "assets/create_story_v1/provenance.json").readText().contains("\"asset\": \"app/src/main/assets/create_story_v1/create_home.jpg\""))
        val mainKotlin = File(main, "java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (forbidden in listOf("StitchStoryBuilderPreviewActivity", "creative.stitch", "src/debug")) {
            assertFalse(forbidden, mainKotlin.contains(forbidden))
        }
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("Stitch"))
        val harness = File(debug, "java/ru/familytv/creative/stitch/StitchStoryBuilderPreviewActivity.kt").readText()
        assertTrue(harness.contains(": CreativeStudioActivity()"))
        assertTrue(harness.contains("override val recordsActivity = false"))
    }
}
