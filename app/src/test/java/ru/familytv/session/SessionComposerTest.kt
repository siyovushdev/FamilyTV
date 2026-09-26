package ru.familytv.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentMetadata
import ru.familytv.content.ContentType

class SessionComposerTest {
    @Test
    fun composesDeterministicRhythm() {
        val catalog = listOf(
            item("v1", ContentType.VIDEO, listOf("learn"), 2),
            item("i1", ContentType.INTERACTIVE, listOf("learn"), 5, skills = listOf("COUNTING")),
            item("g1", ContentType.GAME, listOf("play"), 4),
            item("m1", ContentType.INTERACTIVE, listOf("move"), 3, skills = listOf("MOVE")),
            item("s1", ContentType.VIDEO, listOf("stories"), 3, purpose = "story"),
        )
        val session = SessionComposer.compose(catalog)
        assertEquals("Сегодня вместе", session.title)
        assertTrue(session.items.isNotEmpty())
        assertTrue(session.items.size <= 6)
        assertEquals("v1", session.items.first().contentId)
    }

    private fun item(
        id: String,
        type: ContentType,
        sections: List<String>,
        minutes: Int,
        skills: List<String> = emptyList(),
        purpose: String? = null,
    ) = ContentItem(
        id = id,
        title = id,
        type = type,
        homeSections = sections,
        metadata = ContentMetadata(
            estimatedMinutes = minutes,
            skills = skills,
            contentPurpose = purpose,
        ),
    )
}
