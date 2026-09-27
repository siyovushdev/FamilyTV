package ru.familytv.creative.stitch

import org.junit.Assert.*
import org.junit.Test
import ru.familytv.creative.*
import ru.familytv.interaction.FamilyAction
import ru.familytv.voice.VoiceRuntime
import java.io.File
import java.security.MessageDigest

class StoryPreviewStateTest {
    private val domain = StoryPreviewDomain(File("src/main/assets/catalog/stories/create-story-v1.json").readText())
    private fun home() = StoryPreviewState(domain)
    private fun hero() = home().apply { select() }
    private fun world() = hero().apply { activate("hero:ROBI") }
    private fun objects() = world().apply { activate("world:FOREST") }
    private fun revealing() = objects().apply { activate("object:KEY") }
    private fun choice() = revealing().apply { tick(StoryRules.KEY_REVEAL_MS) }
    private fun consequence() = choice().apply { activate("action:DOOR") }
    private fun ending() = consequence().apply { tick(StoryRules.CONTINUE_AT_MS); activate("inside") }
    private fun harness() = File("src/debug/java/ru/familytv/creative/stitch/StitchStoryBuilderPreviewActivity.kt").readText()
    private fun production() = File("src/main/java/ru/familytv/creative/CreativeStudioActivity.kt").readText()
    private val judgment = listOf("неправ", "ошиб", "правильно", "верно", "молодец", "очк", "балл", "звёзд за", "награ")

    @Test fun previewNeverMutatesProductionStores() {
        val sources = harness()
        assertTrue(sources.contains(": CreativeStudioActivity()"))
        assertTrue(sources.contains("override val recordsActivity = false"))
        for (forbidden in listOf("StoryStore(", "ActivityHistoryStore(", "activityHistory(", "journeyCoordinator(",
            "TrialEventStore(", "trialEvents(", "onStoryCreated(")) {
            assertFalse("Forbidden preview boundary: $forbidden", sources.contains(forbidden))
        }
        assertTrue(production().contains("if (!recordsActivity)"))
        assertTrue(production().contains("save_preview_only persisted=false"))
        val s = ending(); s.activate("save")
        assertTrue(s.value.saved)
        val draft = domain.draft(s.value, 1L, "preview")!!
        assertEquals(mapOf("HERO" to "robi", "WORLD" to "forest", "OBJECT" to "key", "CHOICE" to "door", "ENDING" to "secret_library"), draft.selections)
    }

    @Test fun createHomeOpensTheStoryFlowAndFutureCraftsStayHonest() {
        val s = home()
        assertEquals(StoryScreen.CREATE_HOME, s.value.screen)
        assertEquals("place:BOOK", s.value.focus)
        assertEquals("Давай придумаем историю!", s.prompt())
        for (future in listOf("place:THEATER", "place:DRAWING")) {
            val f = home(); f.activate(future)
            assertEquals(StoryScreen.CREATE_HOME, f.value.screen)
            assertEquals(future, f.value.soon)
            assertTrue(f.prompt().contains("скоро", ignoreCase = true))
        }
        s.select()
        assertEquals(StoryScreen.HERO, s.value.screen)
        assertEquals("hero:ROBI", s.value.focus)
        assertEquals("Кто будет героем?", s.prompt())
    }

    @Test fun robiSelectionPersistsThroughTheWholeStory() {
        assertEquals(StoryHero.ROBI, world().value.hero)
        assertEquals(StoryScreen.WORLD, world().value.screen)
        for (s in listOf(objects(), choice(), consequence(), ending())) assertEquals(StoryHero.ROBI, s.value.hero)
    }

    @Test fun forestSelectionPersistsAndBecomesTheScene() {
        val s = objects()
        assertEquals(StoryScreen.OBJECT, s.value.screen)
        assertEquals(StoryWorld.FOREST, s.value.world)
        for (next in listOf(choice(), consequence(), ending())) assertEquals(StoryWorld.FOREST, next.value.world)
    }

    @Test fun keySelectionPersistsAndLandsWithRobi() {
        val s = revealing()
        assertEquals(StoryObject.KEY, s.value.item)
        assertTrue(s.value.revealing)
        assertEquals(StoryScreen.OBJECT, s.value.screen)
        assertEquals("С собой он взял золотой ключ.", s.prompt())
        s.tick(StoryRules.KEY_REVEAL_MS - 1); assertEquals(StoryScreen.OBJECT, s.value.screen)
        s.tick(1); assertEquals(StoryScreen.STORY_CHOICE, s.value.screen)
        assertEquals(StoryObject.KEY, s.value.item)
    }

    @Test fun selectedKeyStaysInEveryLaterSceneSemantics() {
        for (s in listOf(choice(), consequence(), ending())) {
            assertEquals(StoryObject.KEY, s.value.item)
            assertTrue(domain.lines(s.value)[2].contains("золотой ключ"))
        }
        val end = ending().value
        assertEquals("Тайна зачарованного дуба", domain.title(end))
        assertEquals(listOf("Жил-был Роби.", "Он отправился в волшебный лес.", "С собой он взял золотой ключ.", "У старого дуба Роби решил открыть дверь ключом.", "И нашёл тайную библиотеку!"), domain.lines(end))
    }

    @Test fun doorAndPathAreChoicesWithoutCorrectOrWrong() {
        val fields = StorySnapshot::class.java.declaredFields.map { it.name.lowercase() }
        for (bad in listOf("correct", "wrong", "score", "star", "xp", "reward", "attempt")) assertTrue(fields.none { it.contains(bad) })
        val s = choice()
        assertEquals("Что сделаем?", s.prompt())
        s.move(Direction.RIGHT); assertEquals("action:PATH", s.value.focus)
        s.select()
        // The path has no authored outcome yet: it stays an honest "soon", never a mistake.
        assertEquals(StoryScreen.STORY_CHOICE, s.value.screen)
        assertEquals(StoryObject.KEY, s.value.item)
        assertNull(s.value.action)
        val pathLine = s.prompt()
        s.move(Direction.LEFT); assertEquals("action:DOOR", s.value.focus); assertNull(s.value.soon)
        s.select(); assertEquals(StoryScreen.CONSEQUENCE, s.value.screen)
        s.tick(StoryRules.CONTINUE_AT_MS)
        for (line in listOf(pathLine, s.prompt(), "Что сделаем?")) judgment.forEach { assertFalse(line, line.lowercase().contains(it)) }
    }

    @Test fun doorChoiceGivesADeterministicConsequence() {
        val a = consequence(); val b = consequence()
        repeat(120) { a.tick(40); b.tick(40); assertEquals(a.value, b.value) }
        assertEquals("secret_library", domain.endingId(a.value))
        assertEquals("secret_library", domain.grammar.choiceToEnding["door"])
        assertEquals("Дверь открылась! А там тайная библиотека.", a.prompt())
        assertTrue(domain.storyState(a.value).completed)
    }

    @Test fun consequenceAndRevealCannotBeSkippedByARandomNext() {
        val s = consequence()
        assertEquals("", s.prompt())
        assertEquals(listOf("back", "scene"), s.controls())
        repeat(10) {
            assertFalse(s.semantic(FamilyAction.Next)); assertFalse(s.semantic(FamilyAction.Select))
            s.tick(StoryRules.CONTINUE_AT_MS / 12)
            assertEquals(StoryScreen.CONSEQUENCE, s.value.screen)
        }
        s.tick(StoryRules.CONTINUE_AT_MS)
        assertTrue(s.value.canContinue); assertEquals("inside", s.value.focus)
        assertTrue(s.semantic(FamilyAction.Next))
        assertEquals(StoryScreen.ENDING, s.value.screen)

        val r = revealing()
        Direction.entries.forEach { r.move(it) }
        assertFalse(r.semantic(FamilyAction.Select)); assertFalse(r.semantic(FamilyAction.Next))
        assertEquals("object:KEY", r.value.focus); assertEquals(StoryScreen.OBJECT, r.value.screen)
    }

    @Test fun endingIsFinite() {
        val s = ending()
        assertEquals(listOf("back", "replay", "save", "new"), s.controls())
        assertEquals("replay", s.value.focus)
        assertEquals("Вот какую историю мы придумали!", s.prompt())
        s.tick(10 * 60_000L)
        assertEquals(StoryScreen.ENDING, s.value.screen)
        assertFalse(s.semantic(FamilyAction.Next))
        assertEquals(StoryScreen.ENDING, s.value.screen)
    }

    @Test fun replayRetellsTheOwnStoryAndReturnsToTheEndingWithoutWrites() {
        val s = ending(); val before = s.value
        s.activate("replay")
        val seen = mutableListOf<String>()
        while (s.value.screen == StoryScreen.REPLAY) { seen += s.prompt(); s.tick(StoryRules.REPLAY_BEAT_MS / 2) }
        assertEquals(domain.lines(before), seen.distinct())
        assertEquals(StoryScreen.ENDING, s.value.screen)
        assertEquals("replay", s.value.focus)
        assertEquals(listOf(before.hero, before.world, before.item, before.action), listOf(s.value.hero, s.value.world, s.value.item, s.value.action))
        // Back also leaves a replay early, straight to the same ending.
        s.activate("replay"); s.tick(StoryRules.REPLAY_BEAT_MS); s.back()
        assertEquals(StoryScreen.ENDING, s.value.screen)
    }

    @Test fun newStoryClearsThePreviousStory() {
        val s = ending(); s.activate("save"); s.activate("new")
        val v = s.value
        assertEquals(StoryScreen.HERO, v.screen)
        assertEquals("hero:ROBI", v.focus)
        assertNull(v.hero); assertNull(v.world); assertNull(v.item); assertNull(v.action)
        assertFalse(v.saved)
        assertEquals(StorySnapshot(screen = StoryScreen.HERO, focus = "hero:ROBI"), v)
    }

    @Test fun backWalksTheStoryBackwardsAndRestoresEachSelection() {
        val s = consequence()
        s.back(); assertEquals(StoryScreen.STORY_CHOICE, s.value.screen); assertEquals("action:DOOR", s.value.focus); assertNull(s.value.action)
        s.back(); assertEquals(StoryScreen.OBJECT, s.value.screen); assertEquals("object:KEY", s.value.focus); assertNull(s.value.item)
        s.back(); assertEquals(StoryScreen.WORLD, s.value.screen); assertEquals("world:FOREST", s.value.focus)
        s.back(); assertEquals(StoryScreen.HERO, s.value.screen); assertEquals("hero:ROBI", s.value.focus)
        s.back(); assertEquals(StoryScreen.CREATE_HOME, s.value.screen); assertEquals("place:BOOK", s.value.focus)
        assertFalse(s.exitRequested)
        s.back(); assertTrue(s.exitRequested)
        // Back during the key landing puts the key back into its orb.
        val r = revealing(); r.back()
        assertEquals(StoryScreen.OBJECT, r.value.screen); assertNull(r.value.item); assertEquals("object:KEY", r.value.focus)
        // From the ending, Back returns to the workshop with the story book focused.
        val e = ending(); e.back()
        assertEquals(StorySnapshot(), e.value)
        // Browsing the worlds does not move the hero focus away from the chosen hero.
        val w = world(); w.move(Direction.LEFT); w.move(Direction.RIGHT); w.move(Direction.RIGHT); w.back()
        assertEquals("hero:ROBI", w.value.focus)
    }

    @Test fun pauseAndRestoreKeepHeroWorldObjectAndAction() {
        val r = revealing(); r.tick(1000); r.pause()
        r.tick(10_000); assertEquals(1000, r.value.elapsedMs); assertEquals(StoryScreen.OBJECT, r.value.screen)
        val restored = StoryPreviewState(domain, StorySnapshot.decode(r.value.encode()))
        assertEquals(r.value, restored.value)
        restored.resume(); restored.tick(StoryRules.KEY_REVEAL_MS)
        assertEquals(StoryScreen.STORY_CHOICE, restored.value.screen)

        val c = choice(); c.move(Direction.RIGHT); c.pause()
        val back = StoryPreviewState(domain, StorySnapshot.decode(c.value.encode()))
        assertEquals(c.value, back.value)
        assertEquals(listOf(StoryHero.ROBI, StoryWorld.FOREST, StoryObject.KEY), listOf(back.value.hero, back.value.world, back.value.item))
        assertEquals("action:PATH", back.value.focus)
        assertTrue(back.select()); assertFalse(back.value.paused); assertEquals(StoryScreen.STORY_CHOICE, back.value.screen)

        val e = ending(); val eb = StoryPreviewState(domain, StorySnapshot.decode(e.value.encode()))
        assertEquals(StoryAction.DOOR, eb.value.action)
    }

    @Test fun noAiInitialization() {
        ending().apply { activate("replay"); tick(StoryRules.REPLAY_BEAT_MS * 6); activate("new") }
        assertNull(VoiceRuntime.peek())
        for (forbidden in listOf("VoiceRuntime.get(", "newPipeline(", "prewarm(", "acquireLease(", "AudioRecord(", "SpeechRecognizer", "GroundedQwenMatcher(", ".gguf", ".onnx", "listening")) {
            assertFalse("Forbidden AI path: $forbidden", (harness() + production()).contains(forbidden))
        }
        assertTrue(production().contains("VoiceRuntime.peek()"))
    }

    @Test fun productionHasNoDependencyOnTheDebugPreview() {
        val activity = "StitchStoryBuilderPreviewActivity"
        assertTrue(File("src/debug/AndroidManifest.xml").readText().contains(".creative.stitch.$activity"))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains(activity))
        val main = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(main.contains(activity))
        assertFalse(main.contains("creative.stitch"))
        assertTrue(File("src/main/assets/create_story_v1/create_home.jpg").isFile)
        assertTrue(File("src/main/assets/catalog/stories/create-story-v1.json").isFile)
    }

    @Test fun adapterUsesTheProductionStoryDomain() {
        assertEquals(listOf(StoryStepKind.HERO, StoryStepKind.WORLD, StoryStepKind.OBJECT, StoryStepKind.CHOICE, StoryStepKind.ENDING), domain.grammar.steps.map { it.kind })
        val state = domain.storyState(ending().value)
        assertTrue(state.completed); assertTrue(state.groupMode)
        // Every visual option is a real grammar option; only the authored path is selectable.
        StoryHero.entries.forEach { assertEquals(it == StoryHero.ROBI, domain.isAuthored(StoryStepKind.HERO, it.id)) }
        StoryWorld.entries.forEach { assertEquals(it == StoryWorld.FOREST, domain.isAuthored(StoryStepKind.WORLD, it.id)) }
        StoryObject.entries.forEach { assertEquals(it == StoryObject.KEY, domain.isAuthored(StoryStepKind.OBJECT, it.id)) }
        StoryAction.entries.forEach { assertEquals(it == StoryAction.DOOR, domain.isAuthored(StoryStepKind.CHOICE, it.id)) }
        assertFalse(domain.isAuthored(StoryStepKind.HERO, "dragon"))
    }

    @Test fun voiceAndDpadResolveToTheSameStorySemantics() {
        assertEquals("hero:ROBI", domain.voiceTarget(StoryScreen.HERO, "пусть будет робот роби"))
        assertEquals("world:FOREST", domain.voiceTarget(StoryScreen.WORLD, "в лес"))
        assertEquals("object:KEY", domain.voiceTarget(StoryScreen.OBJECT, "возьмём ключик"))
        assertEquals("action:DOOR", domain.voiceTarget(StoryScreen.STORY_CHOICE, "открой дверь"))
        assertNull(domain.voiceTarget(StoryScreen.HERO, "дракон с лазерами"))
        val voice = hero(); assertTrue(voice.semantic(FamilyAction.Answer("роби")))
        val dpad = hero(); dpad.select()
        assertEquals(dpad.value, voice.value)
        val tim = hero(); tim.semantic(FamilyAction.Answer("тим"))
        assertEquals("hero:TIM", tim.value.soon); assertEquals(StoryScreen.HERO, tim.value.screen)
    }

    @Test fun everyScreenIsFreeOfFocusTraps() {
        val states = listOf(home(), hero(), world(), objects(), choice(), consequence().apply { tick(StoryRules.CONTINUE_AT_MS) }, ending())
        for (s in states) {
            for (start in s.controls()) {
                s.focus(start)
                repeat(6) { Direction.entries.forEach { d -> s.move(d); assertTrue(s.value.focus in s.controls()) } }
                s.focus(start); s.move(Direction.UP); assertEquals("back", s.value.focus)
                s.move(Direction.DOWN); assertEquals(s.defaultFocus(), s.value.focus)
                val reachable = mutableSetOf(s.value.focus)
                repeat(4) { s.move(Direction.LEFT); reachable += s.value.focus }
                repeat(8) { s.move(Direction.RIGHT); reachable += s.value.focus }
                assertEquals(s.controls().filter { it != "back" }.toSet(), reachable)
            }
        }
    }

    @Test fun packagedArtIsTheUnchangedApprovedStitchArt() {
        val provenance = File("src/main/assets/create_story_v1/provenance.json").readText()
        for (name in StoryRoutes.ART) {
            val asset = File("src/main/assets/create_story_v1/$name.jpg")
            val sha = sha256(asset)
            assertTrue(name, provenance.contains(sha))
            assertEquals(sha, sha256(File("../docs/design/stitch/create-story-v1/android-preview/reference/$name.jpg")))
            assertEquals(sha, sha256(File("src/debug/assets/create_story_v1/$name.jpg")))
        }
        assertTrue(provenance.contains(sha256(File("src/main/assets/catalog/stories/create-story-v1.json"))))
        for (screen in listOf("01_create_home", "02_choose_hero", "03_choose_world", "04_choose_object", "05_story_choice", "06_consequence", "07_ending")) {
            assertTrue(screen, provenance.contains("create-story-v1/$screen/code.html"))
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
