package ru.familytv.journey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyEngineTest {
    private val spaceJson = """
        {
          "schemaVersion":1,"id":"space-grand-expedition","title":"Космос","world":"explore",
          "startNode":"launch","version":1,
          "nodes":[
            {"id":"launch","title":"Старт","contentId":"adv-space-expedition","type":"ADVENTURE","next":"moon-hub"},
            {"id":"moon-hub","title":"Луна","contentId":null,"type":"HUB","completionPolicy":"MANUAL",
             "prerequisites":["launch"],
             "branches":[
               {"id":"to-light","label":"Свет","next":"light-lab"},
               {"id":"to-temp","label":"Тепло","next":"temp-lab"}
             ]},
            {"id":"light-lab","title":"Свет","contentId":"game-color","type":"GAME","prerequisites":["moon-hub"]},
            {"id":"temp-lab","title":"Тепло","contentId":"adv-weather-lab","type":"DISCOVERY","prerequisites":["moon-hub"]},
            {"id":"final","title":"Финал","contentId":"video-warm","type":"VIDEO",
             "prerequisites":["light-lab","temp-lab"],"next":"end"},
            {"id":"end","title":"Конец","contentId":null,"type":"CONCLUSION","completionPolicy":"MANUAL",
             "prerequisites":["final"]},
            {"id":"side","title":"История","contentId":"lumi-story","type":"CREATE","optional":true,
             "prerequisites":["launch"]}
          ]
        }
    """.trimIndent()

    @Test
    fun parse_and_branch_opens_both_paths() {
        val journey = JourneyParser.parse(spaceJson)
        var p = JourneyEngine.start(journey, JourneyEngine.initialProgress(journey))
        p = JourneyEngine.completeNode(journey, p, "launch")
        assertTrue("moon-hub" in p.openNodeIds)
        p = JourneyEngine.completeNode(journey, p, "moon-hub", branchId = "to-light")
        assertTrue("light-lab" in p.openNodeIds)
        assertTrue("temp-lab" in p.openNodeIds)
        assertFalse(p.completed)
    }

    @Test
    fun final_requires_both_branches() {
        val journey = JourneyParser.parse(spaceJson)
        var p = JourneyEngine.start(journey, JourneyEngine.initialProgress(journey))
        p = JourneyEngine.completeNode(journey, p, "launch")
        p = JourneyEngine.completeNode(journey, p, "moon-hub")
        p = JourneyEngine.completeNode(journey, p, "light-lab")
        assertFalse("final" in p.openNodeIds || JourneyEngine.prerequisitesMet(journey.node("final")!!, p.completedNodeIds))
        p = JourneyEngine.completeNode(journey, p, "temp-lab")
        assertTrue(JourneyEngine.prerequisitesMet(journey.node("final")!!, p.completedNodeIds))
        p = JourneyEngine.completeNode(journey, p, "final")
        p = JourneyEngine.completeNode(journey, p, "end")
        assertTrue(p.completed)
    }

    @Test
    fun sanitize_drops_missing_nodes_on_version_change() {
        val journey = JourneyParser.parse(spaceJson)
        val stale = JourneyProgress(
            journeyId = journey.id,
            openNodeIds = setOf("gone-node", "launch"),
            completedNodeIds = setOf("gone-node"),
            lastVisitedNodeId = "gone-node",
            started = true,
            completed = false,
            journeyVersion = 0,
            updatedAt = 1L,
        )
        val fixed = JourneyEngine.sanitize(journey, stale)
        assertFalse("gone-node" in fixed.openNodeIds)
        assertFalse("gone-node" in fixed.completedNodeIds)
        assertEquals(journey.version, fixed.journeyVersion)
    }

    @Test
    fun narrator_uses_structured_state_only() {
        val journey = JourneyParser.parse(spaceJson)
        var p = JourneyEngine.start(journey, JourneyEngine.initialProgress(journey))
        p = JourneyEngine.completeNode(journey, p, "launch")
        p = p.copy(lastVisitedNodeId = "launch")
        val line = JourneyNarrator.whereWeAre(journey, p)
        assertTrue(line.contains("Старт") || line.contains("launch") || line.contains("закончили"))
        val next = JourneyNarrator.whatNext(journey, p)
        assertTrue(next.contains("Луна") || next.contains("можно"))
    }

    @Test
    fun journal_recap_does_not_invent() {
        val entries = listOf(
            JournalEntry(
                id = "1",
                type = JournalEntryType.FACT_DISCOVERED,
                title = "Лёд",
                body = "Лёд тает быстрее от тепла.",
                createdAtMs = 1L,
            ),
        )
        val text = JourneyNarrator.whatWeDiscovered(entries)
        assertTrue(text.contains("Лёд тает"))
        assertFalse(text.contains("чёрн"))
    }

    @Test
    fun forest_journey_parses() {
        val forest = """
            {"schemaVersion":1,"id":"forest-secrets","title":"Лес","world":"explore","startNode":"trail","version":1,
             "nodes":[
               {"id":"trail","title":"Тропа","contentId":"adv-forest-trail","type":"ADVENTURE","next":"end"},
               {"id":"end","title":"Конец","contentId":null,"type":"CONCLUSION","completionPolicy":"MANUAL","prerequisites":["trail"]}
             ]}
        """.trimIndent()
        val j = JourneyParser.parse(forest)
        assertEquals("forest-secrets", j.id)
        assertEquals(JourneyMapStyle.PLANETS, j.mapStyle)
    }
}
