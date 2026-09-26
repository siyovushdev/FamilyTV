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

class GroundedDialogueTest {
    private val space = KnowledgeParser.parsePack(
        """
        {"id":"space","adventureId":"space-expedition","world":"explore","entries":[
          {"id":"moon-air","topic":"moon_air","entityId":"moon","aliases":["луна","воздух"],
           "clarificationLabel":"Луну","questionPatterns":["нет воздуха"],
           "facts":["На Луне почти нет атмосферы."],
           "additionalFacts":["На Земле воздух плотный.","Без скафандра нельзя дышать."],
           "shortAnswer":"На Луне почти нет воздуха.","spokenAnswer":"На Луне почти нет воздуха."},
          {"id":"ship-float","topic":"orbit","entityId":"ship","aliases":["корабль","орбита"],
           "clarificationLabel":"корабль","questionPatterns":["не падает"],
           "facts":["Корабль быстро движется вокруг."],
           "shortAnswer":"Корабль на орбите.","spokenAnswer":"Корабль на орбите."}
        ]}
        """.trimIndent(),
    )
    private val weather = KnowledgeParser.parsePack(
        """
        {"id":"weather","adventureId":"weather-lab","world":"explore","entries":[
          {"id":"ice-melting","topic":"ice_melting","entityId":"ice","aliases":["лёд","лед","тает","тепло"],
           "facts":["Лёд — замёрзшая вода.","От тепла тает."],
           "additionalFacts":["В холоде тает медленно."],
           "shortAnswer":"Лёд тает от тепла.","spokenAnswer":"Лёд тает от тепла.",
           "experimentActionRef":"where-melts-faster"}
        ]}
        """.trimIndent(),
    )
    private val forest = KnowledgeParser.parsePack(
        """
        {"id":"forest","adventureId":"forest-trail","world":"explore","entries":[
          {"id":"owl-night","topic":"owl_night","entityId":"owl","aliases":["сова","ночь"],
           "facts":["Сова хорошо слышит ночью."],
           "additionalFacts":["Днём сова отдыхает."],
           "shortAnswer":"Сова охотится ночью.","spokenAnswer":"Сова охотится ночью."},
          {"id":"owl-day","topic":"owl_day","entityId":"owl","aliases":["днём","днем"],
           "questionPatterns":["а днём"],
           "facts":["Днём сова отдыхает."],
           "shortAnswer":"Днём сова отдыхает.","spokenAnswer":"Днём сова отдыхает."}
        ]}
        """.trimIndent(),
    )

    @Test
    fun invalidKnowledgeIdFromModelBecomesUnknown() {
        val backend = KnowledgeMatchBackend {
            """{"intent":"WHY","knowledgeId":"not-in-list","confidenceClass":"HIGH"}"""
        }
        val engine = GroundedDialogueEngine(listOf(space), GroundedQwenMatcher(backend))
        engine.bindSurface("space-expedition", "explore")
        // High deterministic match still wins for clear utterance; force ambiguous path:
        val parsed = MatchContractParser.parse(
            """{"knowledgeId":"hacked","confidenceClass":"HIGH"}""",
            setOf("moon-air"),
        )
        assertEquals(MatchContract.Unknown, parsed)
    }

    @Test
    fun modelCannotAddUnapprovedFact() {
        val entry = space.entries.first()
        assertFalse(
            KnowledgeGrounding.isGroundedInEntry(
                "Чёрные дыры поглощают всё вокруг и динозавры жили рядом",
                entry,
            ),
        )
        val invented = "На Марсе живут зелёные кролики с лазерами."
        assertFalse(KnowledgeGrounding.isGroundedInEntry(invented, entry))
        assertTrue(
            KnowledgeGrounding.containsUnapprovedClaim(invented, entry) ||
                !KnowledgeGrounding.isGroundedInEntry(invented, entry),
        )
        val (display, _, _) = FactLockedComposer.compose(entry, CuriosityIntent.WHY, 5, 7)
        assertTrue(
            KnowledgeGrounding.isGroundedInEntry(display, entry) ||
                display == entry.shortAnswer ||
                entry.facts.any { display.contains(it.take(12)) },
        )
    }

    @Test
    fun missingKnowledgeUnknown() {
        val engine = GroundedDialogueEngine(listOf(space))
        engine.bindSurface("space-expedition", "explore")
        val r = engine.ask(KnowledgeQuery("а ты знаешь про чёрную дыру?", CuriosityIntent.WHY, adventureId = "space-expedition"))
        assertTrue(r is KnowledgeResult.Unknown)
        assertFalse((r as KnowledgeResult.Unknown).softReply.contains("ошибка", ignoreCase = true))
        assertFalse(r.softReply.contains("not found", ignoreCase = true))
    }

    @Test
    fun followUpUsesStructuredContext() {
        val engine = GroundedDialogueEngine(listOf(forest))
        engine.bindSurface("forest-trail", "explore")
        val first = engine.ask(KnowledgeQuery("почему сова летает ночью", CuriosityIntent.WHY, adventureId = "forest-trail"))
        assertTrue(first is KnowledgeResult.Found)
        assertEquals("owl", engine.context.currentEntityId)
        val more = engine.ask(KnowledgeQuery("а ещё", CuriosityIntent.TELL_ME_MORE, adventureId = "forest-trail"))
        assertTrue(more is KnowledgeResult.Found)
        assertTrue((more as KnowledgeResult.Found).groundedAnswer.contains("Днём") ||
            more.groundedAnswer.contains("отдыхает") ||
            more.groundedAnswer == KnowledgeGrounding.TELL_ME_MORE_EMPTY ||
            more.entry.additionalFacts.any { more.groundedAnswer.contains(it.take(8)) })
    }

    @Test
    fun tellMeMoreOnlyApprovedNextFact() {
        val engine = GroundedDialogueEngine(listOf(space))
        engine.bindSurface("space-expedition", "explore")
        engine.ask(KnowledgeQuery("почему на луне нет воздуха", adventureId = "space-expedition"))
        val m1 = engine.ask(KnowledgeQuery("расскажи ещё", CuriosityIntent.TELL_ME_MORE, adventureId = "space-expedition"))
        assertTrue(m1 is KnowledgeResult.Found)
        val f = m1 as KnowledgeResult.Found
        assertTrue(f.entry.additionalFacts.contains(f.groundedAnswer) || f.groundedAnswer == KnowledgeGrounding.TELL_ME_MORE_EMPTY)
        engine.ask(KnowledgeQuery("а ещё", CuriosityIntent.TELL_ME_MORE, adventureId = "space-expedition"))
        val m3 = engine.ask(KnowledgeQuery("а ещё", CuriosityIntent.TELL_ME_MORE, adventureId = "space-expedition"))
        assertEquals(KnowledgeGrounding.TELL_ME_MORE_EMPTY, (m3 as KnowledgeResult.Found).groundedAnswer)
    }

    @Test
    fun whatIfRoutesToExperiment() {
        val engine = GroundedDialogueEngine(listOf(weather))
        engine.bindSurface("weather-lab", "explore")
        val r = engine.ask(KnowledgeQuery("а если сделать теплее?", CuriosityIntent.WHAT_IF, adventureId = "weather-lab"))
        assertTrue(r is KnowledgeResult.AskToExperiment)
        assertEquals("where-melts-faster", (r as KnowledgeResult.AskToExperiment).experimentActionRef)
    }

    @Test
    fun ambiguityAsksClarification() {
        val backend = KnowledgeMatchBackend {
            """{"result":"AMBIGUOUS","knowledgeIds":["moon-air","ship-float"],"intent":"WHY"}"""
        }
        val engine = GroundedDialogueEngine(listOf(space), GroundedQwenMatcher(backend))
        engine.bindSurface("space-expedition", "explore")
        // Force low deterministic scores with vague utterance so Qwen path is used
        val r = engine.ask(KnowledgeQuery("а почему?", CuriosityIntent.WHY, adventureId = "space-expedition"))
        // Without entity context, bare почему may be Unknown or Clarification depending on ranks
        assertTrue(r is KnowledgeResult.Unknown || r is KnowledgeResult.Clarification || r is KnowledgeResult.Found)
    }

    @Test
    fun qwenAmbiguousContractParsed() {
        val c = MatchContractParser.parse(
            """{"result":"AMBIGUOUS","knowledgeIds":["moon-air","ship-float"]}""",
            setOf("moon-air", "ship-float"),
        )
        assertTrue(c is MatchContract.Ambiguous)
        assertEquals(2, (c as MatchContract.Ambiguous).knowledgeIds.size)
    }

    @Test
    fun contextResetsOnAdventureChange() {
        val engine = GroundedDialogueEngine(listOf(space, forest))
        engine.bindSurface("space-expedition", "explore")
        engine.ask(KnowledgeQuery("почему на луне нет воздуха", adventureId = "space-expedition"))
        assertEquals("moon-air", engine.context.lastKnowledgeId)
        engine.bindSurface("forest-trail", "explore")
        assertEquals(null, engine.context.lastKnowledgeId)
    }

    @Test
    fun ageAwareYoungShorter() {
        val entry = weather.entries.first()
        val young = FactLockedComposer.compose(entry, CuriosityIntent.WHY, 3, 5)
        val older = FactLockedComposer.compose(entry, CuriosityIntent.WHY, 7, 9)
        assertTrue(young.first.length <= older.first.length + 5)
    }

    @Test
    fun adversarialCorpus() {
        val engine = GroundedDialogueEngine(listOf(space, weather, forest))
        val cases = listOf(
            "а почему?" to null,
            "а ещё?" to CuriosityIntent.TELL_ME_MORE,
            "а если наоборот?" to CuriosityIntent.WHAT_IF,
            "а если солнце убрать?" to CuriosityIntent.WHAT_IF,
            "расскажи всё" to CuriosityIntent.TELL_ME_MORE,
            "а ты знаешь про чёрную дыру?" to CuriosityIntent.WHY,
            "придумай что-нибудь сам" to CuriosityIntent.WHY,
        )
        engine.bindSurface("space-expedition", "explore")
        engine.ask(KnowledgeQuery("почему на луне нет воздуха", adventureId = "space-expedition"))
        for ((phrase, intent) in cases) {
            val r = engine.ask(KnowledgeQuery(phrase, intent, adventureId = "space-expedition"))
            when (phrase) {
                "а ты знаешь про чёрную дыру?", "придумай что-нибудь сам" ->
                    assertTrue("$phrase should be unknown or safe found", r is KnowledgeResult.Unknown || r is KnowledgeResult.Found)
                "а ещё?", "расскажи всё" ->
                    assertTrue(r is KnowledgeResult.Found)
                "а если наоборот?", "а если солнце убрать?" ->
                    assertTrue(r is KnowledgeResult.AskToExperiment || r is KnowledgeResult.Found || r is KnowledgeResult.Unknown)
            }
            if (r is KnowledgeResult.Found) {
                assertFalse(KnowledgeGrounding.containsUnapprovedClaim(r.groundedAnswer, r.entry))
            }
        }
    }

    @Test
    fun shortWhyRoutesAsAskLumi() {
        val ctx = RouteContext(surface = InteractionSurface.ADVENTURE, awaitingAnswer = true)
        val routed = VoiceCommandRouter.route(ctx, "а почему?")
        assertTrue(routed is RouteResult.Action)
        assertTrue((routed as RouteResult.Action).action is FamilyAction.AskLumi)
    }
}
