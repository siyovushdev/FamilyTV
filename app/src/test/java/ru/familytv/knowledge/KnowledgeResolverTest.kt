package ru.familytv.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.InteractionSurface
import ru.familytv.interaction.RouteContext
import ru.familytv.interaction.RouteResult
import ru.familytv.interaction.VoiceCommandRouter

class KnowledgeResolverTest {
    private val spacePack = KnowledgeParser.parsePack(
        """
        {
          "id":"space","adventureId":"space-expedition","world":"explore",
          "entries":[{
            "id":"moon-air","topic":"moon_air","entityId":"moon","aliases":["луна","воздух"],
            "questionPatterns":["почему на луне","нет воздуха"],
            "facts":["На Луне почти нет атмосферы."],
            "shortAnswer":"На Луне почти нет воздуха.",
            "spokenAnswer":"На Луне почти нет воздуха."
          }]
        }
        """.trimIndent(),
    )
    private val forestPack = KnowledgeParser.parsePack(
        """
        {
          "id":"forest","adventureId":"forest-trail","world":"explore",
          "entries":[{
            "id":"owl","topic":"owl_night","entityId":"owl","aliases":["сова","ночь"],
            "questionPatterns":["почему сова","летает ночью"],
            "facts":["У совы хороший слух ночью."],
            "shortAnswer":"Сова охотится ночью — хорошо слышит.",
            "spokenAnswer":"Сова хорошо слышит ночью."
          }]
        }
        """.trimIndent(),
    )

    @Test
    fun adventureContextPreferred() {
        val resolver = KnowledgeResolver(listOf(spacePack, forestPack))
        val r = resolver.resolve(
            KnowledgeQuery(
                transcript = "почему на луне почти нет воздуха",
                intent = CuriosityIntent.WHY,
                adventureId = "space-expedition",
                world = "explore",
            ),
        )
        assertTrue(r is KnowledgeResult.Found)
        assertEquals("moon-air", (r as KnowledgeResult.Found).entry.id)
    }

    @Test
    fun forestOwlWhy() {
        val resolver = KnowledgeResolver(listOf(spacePack, forestPack))
        val r = resolver.resolve(
            KnowledgeQuery(
                transcript = "почему сова летает ночью",
                adventureId = "forest-trail",
            ),
        )
        assertTrue(r is KnowledgeResult.Found)
        assertEquals("owl", (r as KnowledgeResult.Found).entry.id)
    }

    @Test
    fun unknownWhenNoMatch() {
        val resolver = KnowledgeResolver(listOf(spacePack))
        val r = resolver.resolve(KnowledgeQuery("почему динозавры вымерли"))
        assertTrue(r is KnowledgeResult.Unknown)
        assertTrue((r as KnowledgeResult.Unknown).softReply.contains("не знаю") ||
            r.softReply.contains("нет точного"))
    }

    @Test
    fun groundingRejectsExternalFact() {
        val entry = spacePack.entries.first()
        assertFalse(KnowledgeGrounding.isGroundedInEntry("Динозавры жили миллионы лет назад", entry))
        assertTrue(KnowledgeGrounding.isGroundedInEntry("На Луне почти нет воздуха", entry))
    }

    @Test
    fun curiosityIntentBeforeAnswer() {
        val ctx = RouteContext(surface = InteractionSurface.ADVENTURE, awaitingAnswer = true)
        val routed = VoiceCommandRouter.route(ctx, "почему лёд тает")
        assertTrue(routed is RouteResult.Action)
        val action = (routed as RouteResult.Action).action
        assertTrue(action is FamilyAction.AskLumi)
        assertEquals(CuriosityIntent.WHY, (action as FamilyAction.AskLumi).intent)
    }

    @Test
    fun detectIntents() {
        assertEquals(CuriosityIntent.WHAT_IF, CuriosityIntentDetector.detect("а что будет если сделать теплее"))
        assertEquals(CuriosityIntent.TELL_ME_MORE, CuriosityIntentDetector.detect("расскажи ещё про сову"))
        assertEquals(CuriosityIntent.HOW, CuriosityIntentDetector.detect("как это работает"))
    }
}
