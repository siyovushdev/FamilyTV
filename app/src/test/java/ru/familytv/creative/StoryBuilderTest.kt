package ru.familytv.creative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryBuilderTest {
    private val grammar = StoryGrammarParser.parse(
        """
        {
          "id":"t","title":"Test",
          "retellTemplate":"{hero} в {world} хочет {goal}. Взял {object}. {obstacle}. Решил: {choice}. Финал: {ending}.",
          "choiceToEnding":{"help":"end_friend","run":"end_brave"},
          "steps":[
            {"id":"h","kind":"HERO","prompt":"Герой?","options":[
              {"id":"robot","label":"Робот","voiceAliases":["робот"],"adjectives":["смешной"]},
              {"id":"fox","label":"Лиса","voiceAliases":["лиса"]}
            ]},
            {"id":"w","kind":"WORLD","prompt":"Куда?","options":[
              {"id":"forest","label":"лес","voiceAliases":["лес"]},
              {"id":"moon","label":"луна","voiceAliases":["луна"]}
            ]},
            {"id":"g","kind":"GOAL","prompt":"Цель?","options":[
              {"id":"star","label":"найти звезду","voiceAliases":["звезду"]}
            ]},
            {"id":"o","kind":"OBJECT","prompt":"Предмет?","options":[
              {"id":"map","label":"карта","voiceAliases":["карта"]}
            ]},
            {"id":"ob","kind":"OBSTACLE","prompt":"Трудность?","options":[
              {"id":"dark","label":"темно","voiceAliases":["темно"]}
            ]},
            {"id":"c","kind":"CHOICE","prompt":"Решение?","groupHint":"Решите вместе","options":[
              {"id":"help","label":"попросит друга","voiceAliases":["друга"]},
              {"id":"run","label":"побежит","voiceAliases":["побежит"]}
            ]},
            {"id":"e","kind":"ENDING","prompt":"Финал?","options":[
              {"id":"end_friend","label":"стали друзьями","voiceAliases":["друзьями"]},
              {"id":"end_brave","label":"нашёл дорогу","voiceAliases":["дорогу"]}
            ]}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun freeformMapsToAllowlistedHeroOnly() {
        val step = grammar.steps.first()
        val mapped = StoryBuilderRuntime.mapFreeform(step, "хочу маленького смешного робота")
        assertEquals("robot", mapped!!.first)
        assertTrue(SafeStoryModifiers.isAllowed(mapped.second!!))
        assertEquals(null, StoryBuilderRuntime.mapFreeform(step, "хочу дракона с лазерами"))
    }

    @Test
    fun recapPreservesSelectedState() {
        var s = StoryBuilderRuntime.start(grammar)
        listOf("robot", "forest", "star", "map", "dark", "help").forEach { id ->
            s = StoryBuilderRuntime.select(grammar, s, id)
        }
        val retell = StoryBuilderRuntime.retell(grammar, s)
        assertTrue(StoryBuilderRuntime.recapPreservesState(grammar, s, retell))
        assertFalse(
            StoryBuilderRuntime.recapPreservesState(
                grammar, s,
                "Жил дракон на Марсе и встретил единорога",
            ),
        )
    }

    @Test
    fun voiceMapsToOptionWithAdjective() {
        val step = grammar.steps.first()
        val mapped = StoryBuilderRuntime.mapVoice(step, "хочу смешного робота")
        assertEquals("robot", mapped!!.first)
        assertEquals("смешной", mapped.second)
    }

    @Test
    fun choiceBranchesChangeEnding() {
        assertTrue(StoryBuilderRuntime.endingDiffersByChoice(grammar))
        var s = StoryBuilderRuntime.start(grammar)
        listOf("robot", "forest", "star", "map", "dark", "help").forEach { id ->
            s = StoryBuilderRuntime.select(grammar, s, id)
        }
        assertTrue(s.completed)
        assertEquals("end_friend", s.selections["ENDING"])
        val retellA = StoryBuilderRuntime.retell(grammar, s)

        var s2 = StoryBuilderRuntime.start(grammar)
        listOf("robot", "moon", "star", "map", "dark", "run").forEach { id ->
            s2 = StoryBuilderRuntime.select(grammar, s2, id)
        }
        assertEquals("end_brave", s2.selections["ENDING"])
        val retellB = StoryBuilderRuntime.retell(grammar, s2)
        assertNotEquals(retellA, retellB)
        assertTrue(retellA.contains("лес"))
        assertTrue(retellB.contains("луна"))
    }

    @Test
    fun groupModePreserved() {
        val s = StoryBuilderRuntime.start(grammar, groupMode = true)
        assertTrue(s.groupMode)
        assertEquals("Решите вместе", grammar.steps.first { it.kind == StoryStepKind.CHOICE }.groupHint)
    }
}
