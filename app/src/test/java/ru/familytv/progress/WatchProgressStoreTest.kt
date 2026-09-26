package ru.familytv.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressStoreTest {
    private var now = 1_000_000L

    private fun store(scope: ProgressScope = ProgressScope.Device) = WatchProgressStore(
        prefs = memoryPrefs(),
        scope = scope,
        clock = { now },
    )

    @Test
    fun savesAndResumesPosition() {
        val store = store()
        store.save("v1", positionMs = 12_000, durationMs = 60_000)
        assertEquals(12_000L, store.resumePositionMs("v1"))
        assertFalse(store.get("v1")!!.completed)
    }

    @Test
    fun completedNotInContinueWatching() {
        val store = store()
        store.save("v1", positionMs = 10_000, durationMs = 60_000)
        store.markCompleted("v1", 60_000)
        assertTrue(store.get("v1")!!.completed)
        assertEquals(0L, store.resumePositionMs("v1"))
        assertTrue(store.continueWatching(setOf("v1")).isEmpty())
    }

    @Test
    fun nearEndMarksCompleted() {
        val store = store()
        store.save("v1", positionMs = 59_000, durationMs = 60_000)
        assertTrue(store.get("v1")!!.completed)
        assertTrue(store.continueWatching(setOf("v1")).isEmpty())
    }

    @Test
    fun continueWatchingOrdersByLastPlayed() {
        val store = store()
        store.save("a", 10_000, 60_000)
        now += 10
        store.save("b", 15_000, 60_000)
        assertEquals(listOf("b", "a"), store.continueWatching(setOf("a", "b")).map { it.contentId })
    }

    @Test
    fun deviceScopeIsolatedFromProfileScope() {
        val prefs = memoryPrefs()
        val device = WatchProgressStore(prefs, ProgressScope.Device, clock = { now })
        val profile = WatchProgressStore(prefs, ProgressScope.Profile("child-1"), clock = { now })
        device.save("v1", 10_000, 60_000)
        assertTrue(profile.continueWatching(setOf("v1")).isEmpty())
        assertEquals(1, device.continueWatching(setOf("v1")).size)
    }

    @Test
    fun ignoresTinyPositions() {
        val store = store()
        store.save("v1", positionMs = 500, durationMs = 60_000)
        assertTrue(store.continueWatching(setOf("v1")).isEmpty())
        assertEquals(0L, store.resumePositionMs("v1"))
    }

    @Test
    fun ultraShortClipMarkedCompletedNotContinuable() {
        val store = store()
        // 3–4s demos: treat as non-resumable so early BACK never leaves a bogus CW row
        store.save("demo", positionMs = 0, durationMs = 3_000)
        assertTrue(store.get("demo")!!.completed)
        assertTrue(store.continueWatching(setOf("demo")).isEmpty())
        assertEquals(0L, store.resumePositionMs("demo"))
    }

    @Test
    fun normalLengthVideoStillResumes() {
        val store = store()
        store.save("v1", positionMs = 12_000, durationMs = 90_000)
        assertFalse(store.get("v1")!!.completed)
        assertEquals(12_000L, store.resumePositionMs("v1"))
        assertEquals(1, store.continueWatching(setOf("v1")).size)
    }
}

private fun memoryPrefs(): android.content.SharedPreferences = ru.familytv.content.memoryPrefs()

