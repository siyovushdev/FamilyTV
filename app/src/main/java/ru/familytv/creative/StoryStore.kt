package ru.familytv.creative

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Device/family scoped saved stories — no child identity, no cloud. */
class StoryStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun list(): List<SavedStory> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sels = mutableMapOf<String, String>()
                o.optJSONObject("selections")?.let { s ->
                    s.keys().forEach { k -> sels[k] = s.getString(k) }
                }
                add(
                    SavedStory(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        createdAtMs = o.getLong("createdAtMs"),
                        retellText = o.getString("retellText"),
                        selections = sels,
                    ),
                )
            }
        }.sortedByDescending { it.createdAtMs }
    }

    fun save(story: SavedStory) {
        val existing = list().filter { it.id != story.id }.toMutableList()
        existing.add(0, story)
        val trimmed = existing.take(MAX)
        write(trimmed)
    }

    fun delete(id: String) {
        write(list().filter { it.id != id })
    }

    fun createId(): String = UUID.randomUUID().toString().take(12)

    private fun write(stories: List<SavedStory>) {
        val arr = JSONArray()
        stories.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("createdAtMs", s.createdAtMs)
                    .put("retellText", s.retellText)
                    .put(
                        "selections",
                        JSONObject().also { o -> s.selections.forEach { (k, v) -> o.put(k, v) } },
                    ),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "familytv_stories"
        private const val KEY = "stories"
        private const val MAX = 8
    }
}

object StoryGrammarParser {
    fun parse(json: String): StoryGrammar {
        val root = JSONObject(json)
        val steps = root.getJSONArray("steps").let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val options = o.getJSONArray("options").let { oa ->
                        buildList {
                            for (j in 0 until oa.length()) {
                                val x = oa.getJSONObject(j)
                                add(
                                    StoryOption(
                                        id = x.getString("id"),
                                        label = x.getString("label"),
                                        voiceAliases = stringList(x.optJSONArray("voiceAliases")),
                                        adjectives = stringList(x.optJSONArray("adjectives")),
                                        spokenLabel = x.optString("spokenLabel").ifBlank { null },
                                    ),
                                )
                            }
                        }
                    }
                    add(
                        StoryStepDef(
                            id = o.getString("id"),
                            kind = StoryStepKind.valueOf(o.getString("kind")),
                            prompt = o.getString("prompt"),
                            spokenPrompt = o.optString("spokenPrompt").ifBlank { null },
                            options = options,
                            groupHint = o.optString("groupHint").ifBlank { null },
                        ),
                    )
                }
            }
        }
        val choiceToEnding = mutableMapOf<String, String>()
        root.optJSONObject("choiceToEnding")?.let { obj ->
            obj.keys().forEach { k -> choiceToEnding[k] = obj.getString(k) }
        }
        return StoryGrammar(
            id = root.getString("id"),
            title = root.getString("title"),
            steps = steps,
            retellTemplate = root.getString("retellTemplate"),
            choiceToEnding = choiceToEnding,
        )
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }
}
