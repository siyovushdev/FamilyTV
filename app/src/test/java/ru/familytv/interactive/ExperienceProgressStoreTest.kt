package ru.familytv.interactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.familytv.content.memoryPrefs

class ExperienceProgressStoreTest {
    @Test
    fun resumesCompatibleProgress() {
        val store = ExperienceProgressStore(memoryPrefs())
        store.save("exp", "step-2", "pkg", 1)
        assertEquals("step-2", store.resumeStepId("exp", "pkg", 1, setOf("step-1", "step-2")))
    }

    @Test
    fun clearsOnIncompatiblePackageVersion() {
        val store = ExperienceProgressStore(memoryPrefs())
        store.save("exp", "step-2", "pkg", 1)
        assertNull(store.resumeStepId("exp", "pkg", 2, setOf("step-1", "step-2")))
        assertNull(store.get("exp"))
    }

    @Test
    fun clearsOnMissingStep() {
        val store = ExperienceProgressStore(memoryPrefs())
        store.save("exp", "gone", "pkg", 1)
        assertNull(store.resumeStepId("exp", "pkg", 1, setOf("step-1")))
    }
}
