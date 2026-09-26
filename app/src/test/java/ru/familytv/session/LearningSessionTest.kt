package ru.familytv.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.familytv.content.memoryPrefs

class LearningSessionTest {
    @Test
    fun parsesSession() {
        val session = LearningSessionParser.parse(
            """
            {"id":"morning","title":"Утро","estimatedMinutes":12,"items":[
              {"contentId":"a"},{"contentId":"b","optional":true}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, session.items.size)
        assertFalse(session.items[0].optional)
        assertEquals(true, session.items[1].optional)
    }

    @Test
    fun progressPersists() {
        val store = SessionProgressStore(memoryPrefs())
        store.save("morning", 1)
        assertEquals(1, store.get("morning")!!.index)
        store.save("morning", 2, completed = true)
        assertEquals(true, store.get("morning")!!.completed)
    }
}
