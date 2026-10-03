package ru.familytv.explore

import org.json.JSONObject
import ru.familytv.adventure.PredictAndTestParams
import ru.familytv.adventure.PredictOption
import ru.familytv.content.ContentRepository
import ru.familytv.discovery.CauseEffectEngine
import ru.familytv.discovery.CauseEffectModel
import ru.familytv.discovery.SimEffect
import ru.familytv.discovery.SimRule
import ru.familytv.discovery.SimVariable

/** How long each illustrated beat waits. The state machine still owns the clock. */
internal data class WeatherTiming(
    val placeMs: Long,
    val experimentMs: Long,
    val causeMs: Long,
    val buildMs: Long,
)

/** A prediction the child can point at. The renderer only knows [IcePlace.SUN] and [IcePlace.SHADE]. */
internal data class WeatherPlace(
    val id: IcePlace,
    val label: String,
    val caption: String,
    val voice: List<String>,
    val description: String,
)

/** A shelter the child can place. The renderer only knows [Shelter.CANOPY] and [Shelter.LEAVES]. */
internal data class WeatherShelterOption(
    val id: Shelter,
    val label: String,
    val voice: List<String>,
    val description: String,
)

/** Child-facing copy for the existing Weather loop. */
internal data class WeatherLines(
    val home: String,
    val homeSoon: String,
    val intro: String,
    val prediction: String,
    val placing: String,
    val watching: String,
    val observation: String,
    val askWarm: String,
    val askMelt: String,
    val buildChoose: String,
    val buildPlaced: String,
    val buildWatching: String,
    val sheltered: String,
    val coveredCaption: String,
    val sunCaption: String,
    val explanation: String,
)

/**
 * One Weather experiment. Kotlin still decides how prediction, the cause chain and Build & Test run.
 * This only says which prompts, options, timings and results that experiment uses.
 */
internal data class WeatherDefinition(
    val contentId: String,
    val experimentId: String,
    val title: String,
    val timing: WeatherTiming,
    val places: List<WeatherPlace>,
    val shelters: List<WeatherShelterOption>,
    val chain: List<String>,
    val lines: WeatherLines,
    val narrationRef: String? = null,
) {
    fun place(id: IcePlace): WeatherPlace = places.first { it.id == id }
    fun shelter(id: Shelter): WeatherShelterOption = shelters.first { it.id == id }

    /** Prediction is never a correct answer. Both options share this experiment's observation. */
    fun toPrediction(): PredictAndTestParams = PredictAndTestParams(
        options = places.map { PredictOption(it.id.name, it.label, it.voice) },
        outcomeByOptionId = places.associate { it.id.name to lines.observation },
    )

    /** Cover changes the observation. The rule order is the runtime's; the sentences are authored. */
    fun observe(cover: Shelter?): String {
        val model = CauseEffectModel(
            variables = listOf(SimVariable("cover", listOf("NONE") + shelters.map { it.id.name }, "NONE")),
            rules = listOf(
                SimRule(
                    "protected",
                    whenAny = mapOf("cover" to shelters.map { it.id.name }),
                    effect = SimEffect(observation = lines.sheltered),
                ),
                SimRule(
                    "open",
                    whenAll = mapOf("cover" to "NONE"),
                    effect = SimEffect(observation = lines.observation),
                ),
            ),
        )
        return requireNotNull(
            CauseEffectEngine.applyAction(
                model,
                CauseEffectEngine.initial(model),
                mapOf("cover" to (cover?.name ?: "NONE")),
            ).lastObservation,
        )
    }
}

internal sealed class WeatherLoad {
    data class Ready(val definition: WeatherDefinition) : WeatherLoad()
    data class Rejected(val reason: String) : WeatherLoad()
}

internal object WeatherParser {
    const val TEMPLATE = "weather_explore"
    const val SCHEMA_VERSION = 1

    fun parse(json: String, expectedContentId: String): WeatherLoad {
        if (expectedContentId.isBlank()) return WeatherLoad.Rejected("content id is blank")
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return WeatherLoad.Rejected("definition is not JSON")
        }
        val body = root.optJSONObject("explore") ?: root
        if (body.optString("template").trim() != TEMPLATE) {
            return WeatherLoad.Rejected("weather definition missing")
        }
        if (body.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            return WeatherLoad.Rejected("unsupported schemaVersion")
        }
        if (body.has("correctPredictionId") && body.optString("correctPredictionId").isNotBlank()) {
            return WeatherLoad.Rejected("prediction is not graded")
        }
        val contentId = body.optString("contentId").trim()
        if (contentId != expectedContentId) {
            return WeatherLoad.Rejected("definition contentId '$contentId' does not match '$expectedContentId'")
        }
        val experimentId = body.optString("experimentId").trim()
        val title = body.optString("title").trim()
        if (experimentId.isEmpty() || title.isEmpty()) return WeatherLoad.Rejected("experiment id or title is blank")
        val timing = timing(body.optJSONObject("timing")) ?: return WeatherLoad.Rejected("timing is incomplete")
        val places = places(body) ?: return WeatherLoad.Rejected("places must be SUN then SHADE")
        val shelters = shelters(body) ?: return WeatherLoad.Rejected("shelters must be CANOPY then LEAVES")
        val chain = strings(body, "chain") ?: return WeatherLoad.Rejected("chain must be four labels")
        if (chain.size != 4) return WeatherLoad.Rejected("chain must be four labels")
        val lines = lines(body.optJSONObject("lines")) ?: return WeatherLoad.Rejected("a child-facing line is blank")
        val narration = body.optString("narrationRef").trim().ifEmpty { null }
        if (narration != null && (narration.contains("..") || narration.startsWith("/"))) {
            return WeatherLoad.Rejected("narrationRef is not a relative path")
        }
        return WeatherLoad.Ready(
            WeatherDefinition(contentId, experimentId, title, timing, places, shelters, chain, lines, narration),
        )
    }

    private fun timing(json: JSONObject?): WeatherTiming? {
        if (json == null) return null
        val place = millis(json, "placeMs") ?: return null
        val experiment = millis(json, "experimentMs") ?: return null
        val cause = millis(json, "causeMs") ?: return null
        val build = millis(json, "buildMs") ?: return null
        if (place <= 0 || experiment <= 0 || cause <= 0 || build <= 0) return null
        return WeatherTiming(place, experiment, cause, build)
    }

    private fun millis(json: JSONObject, key: String): Long? {
        val raw = json.opt(key)
        if (raw !is Number) return null
        return raw.toLong()
    }

    private fun places(body: JSONObject): List<WeatherPlace>? {
        val array = body.optJSONArray("places") ?: return null
        if (array.length() != 2) return null
        val parsed = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: return null
            val id = IcePlace.entries.find { it.name == item.optString("id").trim() } ?: return null
            val voice = strings(item, "voice") ?: return null
            if (voice.isEmpty()) return null
            val label = item.optString("label").trim()
            val caption = item.optString("caption").trim()
            val description = item.optString("description").trim()
            if (label.isEmpty() || caption.isEmpty() || description.isEmpty()) return null
            WeatherPlace(id, label, caption, voice, description)
        }
        if (parsed.map { it.id } != listOf(IcePlace.SUN, IcePlace.SHADE)) return null
        return parsed
    }

    private fun shelters(body: JSONObject): List<WeatherShelterOption>? {
        val array = body.optJSONArray("shelters") ?: return null
        if (array.length() != 2) return null
        val parsed = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: return null
            val id = Shelter.entries.find { it.name == item.optString("id").trim() } ?: return null
            val voice = strings(item, "voice") ?: return null
            if (voice.isEmpty()) return null
            val label = item.optString("label").trim()
            val description = item.optString("description").trim()
            if (label.isEmpty() || description.isEmpty()) return null
            WeatherShelterOption(id, label, voice, description)
        }
        if (parsed.map { it.id } != listOf(Shelter.CANOPY, Shelter.LEAVES)) return null
        return parsed
    }

    private fun lines(json: JSONObject?): WeatherLines? {
        if (json == null) return null
        fun text(key: String) = json.optString(key).trim()
        val lines = WeatherLines(
            home = text("home"),
            homeSoon = text("homeSoon"),
            intro = text("intro"),
            prediction = text("prediction"),
            placing = text("placing"),
            watching = text("watching"),
            observation = text("observation"),
            askWarm = text("askWarm"),
            askMelt = text("askMelt"),
            buildChoose = text("buildChoose"),
            buildPlaced = text("buildPlaced"),
            buildWatching = text("buildWatching"),
            sheltered = text("sheltered"),
            coveredCaption = text("coveredCaption"),
            sunCaption = text("sunCaption"),
            explanation = text("explanation"),
        )
        val present = listOf(
            lines.home, lines.homeSoon, lines.intro, lines.prediction, lines.placing, lines.watching,
            lines.observation, lines.askWarm, lines.askMelt, lines.buildChoose, lines.buildPlaced,
            lines.buildWatching, lines.sheltered, lines.coveredCaption, lines.sunCaption, lines.explanation,
        )
        return lines.takeIf { present.none { it.isEmpty() } }
    }

    private fun strings(json: JSONObject, key: String): List<String>? {
        val array = json.optJSONArray(key) ?: return null
        val values = (0 until array.length()).map { index ->
            if (array.opt(index) !is String) return null
            array.getString(index).trim()
        }
        if (values.any { it.isEmpty() }) return null
        return values
    }
}

/** Loads the Weather definition for the content item that was opened. A bad file is a rejection. */
internal object WeatherCatalog {
    fun load(repo: ContentRepository, contentId: String): WeatherLoad {
        if (contentId.isBlank()) return WeatherLoad.Rejected("content id is blank")
        val item = repo.getItem(contentId) ?: return WeatherLoad.Rejected("unknown content '$contentId'")
        if (!ExploreRoutes.opensWeather(item)) {
            return WeatherLoad.Rejected("'$contentId' is not the weather experience")
        }
        val json = repo.openAdventureJson(item) ?: return WeatherLoad.Rejected("adventure missing for '$contentId'")
        return WeatherParser.parse(json, contentId)
    }
}

/** How the two ice samples move. The rates belong to this illustrated experiment, not to authored text. */
internal object WeatherMotion {
    fun samples(fraction: Float): List<IceSample> {
        val f = fraction.coerceIn(0f, 1f)
        return listOf(
            IceSample(IcePlace.SUN, 1f - .78f * f, .95f * f),
            IceSample(IcePlace.SHADE, 1f - .19f * f, .22f * f),
        )
    }
}
