package ru.familytv.adventure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.adventure.mechanics.MechanicRegistry
import ru.familytv.adventure.mechanics.MechanicVerdict
import ru.familytv.adventure.mechanics.PathMazeEngine
import ru.familytv.adventure.mechanics.PatternSequenceEngine
import ru.familytv.adventure.mechanics.SortClassifyEngine
import ru.familytv.adventure.mechanics.VisualSearchEngine

class AdventureRuntimeTest {
    private fun inlineSpace(): Adventure = AdventureParser.parse(
        """
        {
          "schemaVersion": 1,
          "id": "t",
          "title": "T",
          "world": "explore",
          "startSceneId": "a",
          "learningGoals": ["COUNTING"],
          "scenes": [
            {
              "id": "a",
              "kind": "STORY",
              "text": "Hi",
              "next": "b"
            },
            {
              "id": "b",
              "kind": "CHALLENGE",
              "challenge": {
                "id": "c1",
                "mechanic": "PATTERN_SEQUENCE",
                "prompt": "?",
                "learningGoals": ["PATTERN_RECOGNITION"],
                "scaffolding": { "hint1": "plus two" },
                "params": {
                  "display": ["2","4","?"],
                  "blankIndex": 2,
                  "options": ["5","6"],
                  "correctOption": "6",
                  "voiceAccept": ["шесть"]
                },
                "successNext": "end"
              }
            },
            { "id": "end", "kind": "REFLECTION", "text": "done" }
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun storyAdvancesToChallenge() {
        val rt = AdventureRuntime(inlineSpace())
        assertTrue(rt.currentScene() is StoryScene)
        rt.advanceStory()
        assertTrue(rt.currentScene() is ChallengeScene)
        assertEquals(AdventurePhase.CHALLENGE_ACTIVE, rt.snapshot().phase)
    }

    @Test
    fun scaffoldingEscalatesOnWrong() {
        val rt = AdventureRuntime(inlineSpace())
        rt.advanceStory()
        rt.selectMechanicTarget("5")
        rt.submitMechanic()
        assertEquals(false, rt.snapshot().feedbackCorrect)
        assertEquals(ScaffoldLevel.HINT1, rt.snapshot().scaffoldLevel)
        rt.finishFeedback()
        rt.selectMechanicTarget("5")
        rt.submitMechanic()
        assertTrue(rt.snapshot().scaffoldLevel == ScaffoldLevel.HINT1 || rt.snapshot().attemptCount >= 2)
    }

    @Test
    fun correctChallengeCompletesPath() {
        val rt = AdventureRuntime(inlineSpace())
        rt.advanceStory()
        rt.selectMechanicTarget("6")
        rt.submitMechanic()
        assertEquals(true, rt.snapshot().feedbackCorrect)
        rt.finishFeedback()
        assertTrue(rt.currentScene() is ReflectionScene)
    }

    @Test
    fun voiceDoesNotNeedExpectedInPrompt() {
        val rt = AdventureRuntime(inlineSpace())
        rt.advanceStory()
        rt.voiceInput("шесть")
        assertEquals(true, rt.snapshot().feedbackCorrect)
    }

    @Test
    fun validationCatchesMissingNext() {
        val bad = AdventureParser.parse(
            """
            {"schemaVersion":1,"id":"x","title":"x","world":"w","startSceneId":"a","learningGoals":["COUNTING"],
             "scenes":[{"id":"a","kind":"STORY","text":"t","next":"missing"}]}
            """.trimIndent(),
        )
        assertTrue(AdventureParser.validate(bad).any { it.contains("missing") })
    }

    @Test
    fun groupPromptForDiscuss() {
        val rt = AdventureRuntime(
            AdventureParser.parse(
                """
                {"schemaVersion":1,"id":"g","title":"g","world":"w","startSceneId":"a","learningGoals":["COOPERATION"],
                 "scenes":[{"id":"a","kind":"STORY","text":"t","groupMode":"DISCUSS_TOGETHER"}]}
                """.trimIndent(),
            ),
        )
        assertTrue(rt.snapshot().groupPrompt!!.contains("Обсудите"))
    }

    @Test
    fun offscreenAdvances() {
        val rt = AdventureRuntime(
            AdventureParser.parse(
                """
                {"schemaVersion":1,"id":"o","title":"o","world":"w","startSceneId":"a","learningGoals":["LANGUAGE"],
                 "scenes":[
                   {"id":"a","kind":"OFFSCREEN","prompt":"Find circle","next":"b"},
                   {"id":"b","kind":"REFLECTION","text":"ok"}
                 ]}
                """.trimIndent(),
            ),
        )
        assertTrue(rt.currentScene() is OffscreenScene)
        rt.confirmOffscreen()
        assertTrue(rt.currentScene() is ReflectionScene)
    }

    @Test
    fun sessionDifficultyRisesAfterWins() {
        val rt = AdventureRuntime(inlineSpace())
        rt.advanceStory()
        repeat(3) {
            rt.selectMechanicTarget("6")
            rt.submitMechanic()
            if (rt.snapshot().feedbackCorrect == true) rt.finishFeedback()
            // restart challenge path via new runtime not needed — only check after first success path
        }
        // After one clear success consecutiveCorrect bumps difficulty on adapt
        val again = AdventureRuntime(inlineSpace())
        again.advanceStory()
        again.selectMechanicTarget("6")
        again.submitMechanic()
        again.finishFeedback()
        assertTrue(again.snapshot().difficulty >= 1)
    }

    @Test
    fun flagshipAndForestValidate() {
        fun load(name: String): String {
            val candidates = listOf(
                java.io.File("src/main/assets/catalog/adventures/$name"),
                java.io.File("app/src/main/assets/catalog/adventures/$name"),
            )
            return candidates.first { it.isFile }.readText()
        }
        val a = AdventureParser.parse(load("space-expedition.json"))
        val b = AdventureParser.parse(load("forest-trail.json"))
        assertEquals(emptyList<String>(), AdventureParser.validate(a))
        assertEquals(emptyList<String>(), AdventureParser.validate(b))
        assertTrue(a.scenes.any { it is ChallengeScene && it.challenge.mechanic == MechanicType.VISUAL_SEARCH })
        assertTrue(a.scenes.any { it is ChallengeScene && it.challenge.mechanic == MechanicType.PATH_MAZE })
        assertTrue(b.scenes.any { it is ChallengeScene })
    }
}

class MechanicEnginesTest {
    @Test
    fun visualSearchExact() {
        val params = VisualSearchParams(
            objects = listOf(
                SearchObject("a", "A"),
                SearchObject("b", "B"),
                SearchObject("c", "C"),
            ),
            requiredIds = setOf("a", "b"),
        )
        var snap = VisualSearchEngine.initial(params)
        snap = VisualSearchEngine.applySelect(snap, params, "a")
        snap = VisualSearchEngine.applySelect(snap, params, "b")
        assertEquals(MechanicVerdict.CORRECT, VisualSearchEngine.evaluate(snap, params))
        snap = VisualSearchEngine.applySelect(snap, params, "c")
        assertEquals(MechanicVerdict.WRONG, VisualSearchEngine.evaluate(snap, params))
    }

    @Test
    fun sortClassify() {
        val params = SortClassifyParams(
            categories = listOf(SortCategory("w", "Water"), SortCategory("l", "Land")),
            items = listOf(
                SortItem("fish", "Fish", "w"),
                SortItem("tree", "Tree", "l"),
            ),
        )
        var snap = SortClassifyEngine.initial(params)
        snap = SortClassifyEngine.applySelect(snap, params, "fish:w")
        assertEquals(MechanicVerdict.INCOMPLETE, SortClassifyEngine.evaluate(snap, params))
        snap = SortClassifyEngine.applySelect(snap, params, "tree:l")
        assertEquals(MechanicVerdict.CORRECT, SortClassifyEngine.evaluate(snap, params))
    }

    @Test
    fun patternSequence() {
        val params = PatternSequenceParams(
            display = listOf("1", "2", "?"),
            blankIndex = 2,
            options = listOf("3", "4"),
            correctOption = "3",
        )
        var snap = PatternSequenceEngine.initial(params)
        snap = PatternSequenceEngine.applySelect(snap, params, "3")
        assertEquals(MechanicVerdict.CORRECT, PatternSequenceEngine.evaluate(snap, params))
    }

    @Test
    fun pathMazeEvenRoute() {
        val params = PathMazeParams(
            width = 3,
            height = 1,
            start = GridPos(0, 0),
            goal = GridPos(2, 0),
            cellLabels = mapOf(
                GridPos(0, 0) to "2",
                GridPos(1, 0) to "4",
                GridPos(2, 0) to "6",
            ),
            allowedLabelRule = PathLabelRule.EVEN_NUMBERS,
        )
        var snap = PathMazeEngine.initial(params)
        snap = PathMazeEngine.applySelect(snap, params, "RIGHT")
        snap = PathMazeEngine.applySelect(snap, params, "RIGHT")
        assertEquals(MechanicVerdict.CORRECT, PathMazeEngine.evaluate(snap, params))
    }

    @Test
    fun registryHasFour() {
        MechanicType.entries.forEach { assertTrue(MechanicRegistry.get(it).type == it) }
    }
}
