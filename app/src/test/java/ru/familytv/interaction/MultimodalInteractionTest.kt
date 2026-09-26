package ru.familytv.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.adventure.AdventureParser
import ru.familytv.adventure.AdventureRuntime
import ru.familytv.adventure.ScaffoldLevel
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentMetadata
import ru.familytv.content.ContentType
import ru.familytv.narration.NarrationProvider
import java.io.File
import org.junit.Assert.assertNotEquals

class ReadingSupportModeTest {
    @Test
    fun parseIndependentOfAge() {
        assertEquals(ReadingSupportMode.AUDIO_FIRST, ReadingSupportMode.parse("AUDIO_FIRST"))
        assertEquals(ReadingSupportMode.TEXT_RICH, ReadingSupportMode.parse("text-rich"))
        assertEquals(ReadingSupportMode.AUDIO_AND_TEXT, ReadingSupportMode.parse(null))
    }
}

class VoiceCommandRouterTest {
    private val catalog = listOf(
        ContentItem(
            id = "adv-space-expedition",
            title = "Космическая экспедиция",
            description = "space",
            type = ContentType.ADVENTURE,
            tags = listOf("space"),
            homeSections = listOf("explore", "mission"),
            metadata = ContentMetadata(topics = listOf("space"), ageMin = 5, ageMax = 9),
        ),
        ContentItem(
            id = "game-math",
            title = "Математика",
            type = ContentType.GAME,
            route = "game/math",
            homeSections = listOf("play"),
            metadata = ContentMetadata(topics = listOf("math")),
        ),
    )

    @Test
    fun remoteParityActionsExist() {
        // Smoke: sealed actions compile as shared set
        val actions = listOf(
            FamilyAction.Home, FamilyAction.Back, FamilyAction.Repeat, FamilyAction.Help,
            FamilyAction.Next, FamilyAction.ContinueCurrent,
        )
        assertTrue(actions.size >= 5)
    }

    @Test
    fun voiceHomeAndBack() {
        val home = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "домой")
        assertEquals(FamilyAction.Home, (home as RouteResult.Action).action)
        val back = VoiceCommandRouter.route(RouteContext(InteractionSurface.ADVENTURE), "назад")
        assertEquals(FamilyAction.Back, (back as RouteResult.Action).action)
    }

    @Test
    fun repeatAndHelpNotAnswers() {
        val ctx = RouteContext(InteractionSurface.ADVENTURE, awaitingAnswer = true)
        assertEquals(FamilyAction.Repeat, (VoiceCommandRouter.route(ctx, "повтори") as RouteResult.Action).action)
        assertEquals(FamilyAction.Help, (VoiceCommandRouter.route(ctx, "помоги") as RouteResult.Action).action)
        assertEquals(FamilyAction.Help, (VoiceCommandRouter.route(ctx, "дай подсказку") as RouteResult.Action).action)
    }

    @Test
    fun answerDoesNotBecomeNavigation() {
        val ctx = RouteContext(InteractionSurface.ADVENTURE, awaitingAnswer = true)
        val r = VoiceCommandRouter.route(ctx, "я думаю шесть")
        assertTrue(r is RouteResult.Action)
        assertTrue((r as RouteResult.Action).action is FamilyAction.Answer)
        assertEquals("я думаю шесть", ((r as RouteResult.Action).action as FamilyAction.Answer).transcript)
    }

    @Test
    fun sixIsAnswerNotHome() {
        val ctx = RouteContext(InteractionSurface.ADVENTURE, awaitingAnswer = true)
        val r = VoiceCommandRouter.route(ctx, "шесть") as RouteResult.Action
        assertTrue(r.action is FamilyAction.Answer)
    }

    @Test
    fun parentSensitiveBlocked() {
        val r = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "смени пин")
        assertEquals(FamilyAction.BlockedSensitive, (r as RouteResult.Action).action)
        val gate = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "родителям")
        assertEquals(FamilyAction.OpenParentGate, (gate as RouteResult.Action).action)
    }

    @Test
    fun voiceContentDiscovery() {
        val ctx = RouteContext(InteractionSurface.HOME, catalog = catalog)
        val r = VoiceCommandRouter.route(ctx, "начать космическую экспедицию") as RouteResult.Action
        assertTrue(r.action is FamilyAction.StartAdventure)
        assertEquals("adv-space-expedition", (r.action as FamilyAction.StartAdventure).contentId)
        val games = VoiceCommandRouter.route(ctx, "покажи игры") as RouteResult.Action
        assertEquals(FamilyAction.OpenWorld("play"), games.action)
    }

    @Test
    fun dispatcherFansOut() {
        var seen: FamilyAction? = null
        val d = FamilyActionDispatcher()
        d.attach(FamilyActionHandler { _, a -> seen = a; true })
        assertTrue(d.dispatch(InputSource.VOICE, FamilyAction.Repeat))
        assertEquals(FamilyAction.Repeat, seen)
        assertTrue(d.dispatch(InputSource.REMOTE, FamilyAction.Help))
        assertEquals(FamilyAction.Help, seen)
    }
}

class NarrationCoordinatorTest {
    private class FakeProvider : NarrationProvider {
        var spoken = mutableListOf<String>()
        override fun speakText(text: String) { spoken += text }
        override fun playFile(file: File?): Boolean = false
        override fun stop() = Unit
        override fun release() = Unit
    }

    @Test
    fun repeatDoesNotRequireNewMessage() {
        val provider = FakeProvider()
        val coord = NarrationCoordinator(provider, { ReadingSupportMode.AUDIO_FIRST })
        coord.present(PresentationMessage("Найди три предмета"))
        assertEquals(1, provider.spoken.size)
        coord.repeat()
        assertEquals(2, provider.spoken.size)
        assertEquals("Найди три предмета", provider.spoken.last())
    }

    @Test
    fun lifecycleRestoreCurrent() {
        val provider = FakeProvider()
        val coord = NarrationCoordinator(provider, { ReadingSupportMode.AUDIO_AND_TEXT })
        val msg = PresentationMessage("Курс по чётным")
        coord.present(msg)
        coord.restore(msg)
        assertEquals(msg, coord.current)
        coord.repeat()
        assertEquals("Курс по чётным", provider.spoken.last())
    }

    @Test
    fun audioFirstGapWhenTextOnly() {
        val provider = FakeProvider()
        val coord = NarrationCoordinator(provider, { ReadingSupportMode.AUDIO_FIRST }, ttsUsable = { false })
        val r = coord.present(PresentationMessage("Длинная инструкция без аудио файла здесь"))
        assertEquals(NarrationPath.TEXT_ONLY, r.path)
        assertTrue(r.audioFirstGap)
    }
}

class AdventureHelpRepeatTest {
    private fun adventure() = AdventureParser.parse(
        """
        {"schemaVersion":1,"id":"t","title":"T","world":"w","startSceneId":"a","learningGoals":["COUNTING"],
         "scenes":[
           {"id":"a","kind":"STORY","text":"Hi","spokenPrompt":"Привет","next":"b"},
           {"id":"b","kind":"CHALLENGE","challenge":{
             "id":"c1","mechanic":"PATTERN_SEQUENCE","prompt":"?","spokenPrompt":"Что дальше",
             "learningGoals":["PATTERN_RECOGNITION"],
             "scaffolding":{"hint1":"плюс два","hint2":"после четырёх"},
             "params":{"display":["2","4","?"],"blankIndex":2,"options":["5","6"],"correctOption":"6"}
           }}
         ]}
        """.trimIndent(),
    )

    @Test
    fun helpAdvancesScaffoldWithoutWrongDifficulty() {
        val rt = AdventureRuntime(adventure())
        rt.advanceStory()
        val beforeDiff = rt.snapshot().difficulty
        val beforeToken = rt.challengeMutationToken()
        assertTrue(rt.requestHelp())
        assertEquals(ScaffoldLevel.HINT1, rt.snapshot().scaffoldLevel)
        assertEquals("плюс два", rt.snapshot().guideLine)
        // difficulty unchanged after single help
        assertEquals(beforeDiff, rt.snapshot().difficulty)
        assertNotEquals(beforeToken, rt.challengeMutationToken()) // scaffold advanced
        assertTrue(rt.requestHelp())
        assertEquals(ScaffoldLevel.HINT2_VISUAL, rt.snapshot().scaffoldLevel)
    }

    @Test
    fun expectedAnswerNeverInRouterCommands() {
        // Isolation: router never invents curriculum — only Answer(transcript)
        val ctx = RouteContext(InteractionSurface.ADVENTURE, awaitingAnswer = true)
        val r = VoiceCommandRouter.route(ctx, "наверное шесть") as RouteResult.Action
        assertEquals("наверное шесть", (r.action as FamilyAction.Answer).transcript)
    }
}

class AudioFirstValidatorTest {
    @Test
    fun errorsMissingSpokenPathAndReadingChoices() {
        val f = AudioFirstValidator.validateAdventureScene(
            sceneId = "x",
            kind = "CHALLENGE",
            text = "Очень длинная инструкция которую нужно обязательно прочитать на экране телевизора",
            spokenPrompt = null,
            narrationRef = null,
            readingSupport = ReadingSupportMode.AUDIO_FIRST,
            choiceLabelsRequireReading = true,
        )
        assertTrue(f.any { it.level == AudioFirstValidator.Level.ERROR && it.message.contains("spoken") })
        assertTrue(f.any { it.message.contains("choices") })
    }

    @Test
    fun textRichSkips() {
        assertTrue(
            AudioFirstValidator.validateAdventureScene(
                "x", "STORY", "text", null, null, ReadingSupportMode.TEXT_RICH,
            ).isEmpty(),
        )
    }
}

class ContentDiscoveryTest {
    @Test
    fun findsByTopic() {
        val item = ContentItem(
            id = "adv-forest-trail",
            title = "Следопыты: тайна леса",
            type = ContentType.ADVENTURE,
            metadata = ContentMetadata(topics = listOf("nature", "forest")),
            homeSections = listOf("explore"),
        )
        assertEquals(item.id, ContentDiscovery.find(listOf(item), "хочу лес")?.id)
    }
}
