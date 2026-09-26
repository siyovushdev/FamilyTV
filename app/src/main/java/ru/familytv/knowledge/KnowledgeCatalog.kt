package ru.familytv.knowledge

import android.content.Context
import java.io.File

/**
 * Loads approved knowledge packs from assets and optional package files.
 */
object KnowledgeCatalog {
    fun loadForAdventure(context: Context, adventureId: String, world: String?): KnowledgeResolver {
        val packs = mutableListOf<KnowledgePack>()
        loadAsset(context, "catalog/knowledge/$adventureId.json")?.let { packs += it }
        world?.let { w ->
            loadAsset(context, "catalog/knowledge/world-$w.json")?.let { packs += it }
        }
        // Shared fallbacks
        loadAsset(context, "catalog/knowledge/common.json")?.let { packs += it }
        return KnowledgeResolver(packs)
    }

    fun loadAsset(context: Context, path: String): KnowledgePack? = runCatching {
        context.assets.open(path).bufferedReader().use { KnowledgeParser.parsePack(it.readText()) }
    }.getOrNull()

    fun loadFile(file: File): KnowledgePack? = runCatching {
        KnowledgeParser.parsePack(file.readText())
    }.getOrNull()
}
