package ru.familytv.journey

import android.content.Context
import android.util.Log

/** Lightweight journey loader — does not parse adventure/media packages. */
class JourneyCatalog(private val context: Context) {
    @Volatile private var cache: List<Journey>? = null

    fun all(): List<Journey> {
        cache?.let { return it }
        return loadAll().also { cache = it }
    }

    fun get(id: String): Journey? = all().find { it.id == id }

    fun reload(): List<Journey> {
        cache = null
        return all()
    }

    fun unlockDefs(): List<JournalUnlockDef> {
        val fromAssets = runCatching {
            context.assets.open("catalog/journal/unlocks.json").bufferedReader().use {
                JournalUnlockCatalog.parseDefs(it.readText())
            }
        }.getOrDefault(emptyList())
        // Also collect unlocks embedded in journeys
        val fromJourneys = all().flatMap { j ->
            j.nodes.flatMap { n ->
                n.journalUnlocks.mapNotNull { uid ->
                    // Placeholder — full text comes from unlocks.json
                    null
                }
            }
        }
        return fromAssets + fromJourneys
    }

    fun unlockDef(id: String): JournalUnlockDef? = unlockDefs().find { it.id == id }

    private fun loadAll(): List<Journey> {
        val names = runCatching {
            context.assets.list("catalog/journeys")?.toList().orEmpty()
        }.getOrDefault(emptyList())
        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching {
                val text = context.assets.open("catalog/journeys/$name").bufferedReader().use { it.readText() }
                val result = JourneyParser.parseAndValidate(text)
                result.warnings.forEach { Log.w(TAG, "journey $name: $it") }
                if (!result.ok) {
                    Log.e(TAG, "journey $name invalid: ${result.errors}")
                    null
                } else {
                    result.journey
                }
            }.onFailure { Log.e(TAG, "Failed to load journey $name", it) }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "FamilyJourney"
    }
}
