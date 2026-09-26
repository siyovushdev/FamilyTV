package ru.familytv.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.content.memoryPrefs
import ru.familytv.interaction.ReadingSupportMode

class ParentStoresTest {
    @Test
    fun pinSetAndVerify() {
        val store = ParentPinStore(memoryPrefs())
        assertFalse(store.isSet())
        assertTrue(store.setPin("1234"))
        assertTrue(store.isSet())
        assertTrue(store.verify("1234"))
        assertFalse(store.verify("0000"))
        assertFalse(store.setPin("12"))
    }

    @Test
    fun screenTimeReminderWarnsBeforeLimit() {
        var now = 1_000_000L
        val settings = FamilySettingsStore(memoryPrefs())
        settings.saveScreenTime(
            ScreenTimeSettings(
                enabled = true,
                dailyLimitMinutes = 30,
                sessionReminderMinutes = null,
                restReminderMinutes = null,
                warnMinutesBefore = 5,
            ),
        )
        val history = ActivityHistoryStore(memoryPrefs()) { now }
        val tracker = ScreenTimeTracker(
            prefs = memoryPrefs(),
            settings = { settings.screenTime() },
            activity = history,
            clock = { now },
        )
        tracker.onAppForeground()
        now += 26 * 60_000L
        val reminder = tracker.reminder()
        assertTrue(reminder is ScreenTimeReminder.Warn)
    }

    @Test
    fun staleSessionAcrossProcessDoesNotTriggerBreak() {
        var now = 1_000_000L
        val settings = FamilySettingsStore(memoryPrefs())
        settings.saveScreenTime(
            ScreenTimeSettings(
                enabled = true,
                dailyLimitMinutes = null,
                sessionReminderMinutes = 20,
                restReminderMinutes = 25,
                warnMinutesBefore = 5,
            ),
        )
        val prefs = memoryPrefs()
        // Simulate prefs left from a previous process hours ago
        prefs.edit().putLong("sessionStart", now - 3 * 60 * 60_000L).apply()
        val tracker = ScreenTimeTracker(
            prefs = prefs,
            settings = { settings.screenTime() },
            activity = ActivityHistoryStore(memoryPrefs()) { now },
            clock = { now },
        )
        assertTrue(tracker.reminder() == null)
        tracker.onAppForeground()
        assertTrue(tracker.reminder() == null)
        now += 16 * 60_000L
        assertTrue(tracker.reminder() is ScreenTimeReminder.Warn)
    }

    @Test
    fun activityHistoryNeutralTopics() {
        val store = ActivityHistoryStore(memoryPrefs()) { 1_700_000_000_000L }
        store.record(ActivityKind.INTERACTIVE, "Мир животных", "pkg-animal", listOf("animals", "nature"), 120_000)
        store.record(ActivityKind.GAME, "Математика", "game-math", listOf("math"), 60_000)
        val day = store.todaySummary()
        assertEquals(2, day.events.size)
        assertTrue(day.topics.contains("animals"))
        assertTrue(day.topics.contains("math"))
    }

    @Test
    fun readingModeIndependentOfAgeFilter() {
        val settings = FamilySettingsStore(memoryPrefs())
        settings.saveContentFilter(FamilyContentFilter(ageMin = 4, ageMax = 8))
        settings.saveReadingSupportMode(ReadingSupportMode.AUDIO_FIRST)
        assertEquals(4, settings.householdAgeBand().ageMin)
        assertEquals(ReadingSupportMode.AUDIO_FIRST, settings.readingSupportMode())
        settings.saveReadingSupportMode(ReadingSupportMode.TEXT_RICH)
        assertEquals(4, settings.contentFilter().ageMin)
        assertEquals(ReadingSupportMode.TEXT_RICH, settings.readingSupportMode())
    }
}
