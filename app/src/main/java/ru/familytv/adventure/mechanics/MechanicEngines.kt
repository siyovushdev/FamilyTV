package ru.familytv.adventure.mechanics

import ru.familytv.adventure.BuildAndTestParams
import ru.familytv.adventure.GridPos
import ru.familytv.adventure.MechanicParams
import ru.familytv.adventure.MechanicType
import ru.familytv.adventure.PathLabelRule
import ru.familytv.adventure.PathMazeParams
import ru.familytv.adventure.PatternSequenceParams
import ru.familytv.adventure.PredictAndTestParams
import ru.familytv.adventure.SortClassifyParams
import ru.familytv.adventure.VisualSearchParams

enum class MechanicVerdict {
    CORRECT,
    WRONG,
    INCOMPLETE,
    UNKNOWN,
    /** Discovery observation — never treated as a scored failure. */
    OBSERVED,
}

data class MechanicSnapshot(
    val type: MechanicType,
    val selectedIds: Set<String> = emptySet(),
    val placements: Map<String, String> = emptyMap(), // itemId → categoryId / slotId
    val chosenOption: String? = null,
    val path: List<GridPos> = emptyList(),
    val cursor: GridPos? = null,
    val highlightIds: Set<String> = emptySet(),
    val simplified: Boolean = false,
    /** PREDICT | TESTED | BUILD | RESULT */
    val phase: String = "",
    val observation: String? = null,
    val exploredIds: Set<String> = emptySet(),
)

/**
 * Pure mechanic engines — deterministic, no Android deps.
 * Expected answers never leave these rules into Qwen.
 */
interface MechanicEngine {
    val type: MechanicType
    fun initial(params: MechanicParams, simplified: Boolean = false): MechanicSnapshot
    fun applySelect(snapshot: MechanicSnapshot, params: MechanicParams, objectId: String): MechanicSnapshot
    fun applyVoice(snapshot: MechanicSnapshot, params: MechanicParams, normalized: String): Pair<MechanicSnapshot, MechanicVerdict>
    fun evaluate(snapshot: MechanicSnapshot, params: MechanicParams): MechanicVerdict
}

object MechanicRegistry {
    private val engines: Map<MechanicType, MechanicEngine> = mapOf(
        MechanicType.VISUAL_SEARCH to VisualSearchEngine,
        MechanicType.SORT_AND_CLASSIFY to SortClassifyEngine,
        MechanicType.PATTERN_SEQUENCE to PatternSequenceEngine,
        MechanicType.PATH_MAZE to PathMazeEngine,
        MechanicType.PREDICT_AND_TEST to PredictAndTestEngine,
        MechanicType.BUILD_AND_TEST to BuildAndTestEngine,
    )

    fun get(type: MechanicType): MechanicEngine = engines.getValue(type)

    fun isDiscovery(type: MechanicType): Boolean =
        type == MechanicType.PREDICT_AND_TEST || type == MechanicType.BUILD_AND_TEST
}

object VisualSearchEngine : MechanicEngine {
    override val type = MechanicType.VISUAL_SEARCH

    override fun initial(params: MechanicParams, simplified: Boolean): MechanicSnapshot {
        val p = params as VisualSearchParams
        val required = if (simplified && p.requiredIds.size > 1) {
            setOf(p.requiredIds.first())
        } else p.requiredIds
        return MechanicSnapshot(
            type = type,
            highlightIds = emptySet(),
            simplified = simplified,
            selectedIds = emptySet(),
        ).also { /* required tracked via params */ }
    }

    override fun applySelect(snapshot: MechanicSnapshot, params: MechanicParams, objectId: String): MechanicSnapshot {
        val next = if (objectId in snapshot.selectedIds) snapshot.selectedIds - objectId
        else snapshot.selectedIds + objectId
        return snapshot.copy(selectedIds = next)
    }

    override fun applyVoice(
        snapshot: MechanicSnapshot,
        params: MechanicParams,
        normalized: String,
    ): Pair<MechanicSnapshot, MechanicVerdict> {
        val p = params as VisualSearchParams
        val match = p.objects.firstOrNull { obj ->
            obj.label.normalizeMatch() == normalized ||
                obj.voiceAliases.any { it.normalizeMatch() == normalized } ||
                normalized.contains(obj.label.normalizeMatch())
        } ?: return snapshot to MechanicVerdict.UNKNOWN
        val updated = applySelect(snapshot, params, match.id)
        return updated to evaluate(updated, effectiveParams(p, snapshot.simplified))
    }

    override fun evaluate(snapshot: MechanicSnapshot, params: MechanicParams): MechanicVerdict {
        val p = effectiveParams(params as VisualSearchParams, snapshot.simplified)
        if (snapshot.selectedIds.isEmpty()) return MechanicVerdict.INCOMPLETE
        val need = p.minSelect ?: p.requiredIds.size
        if (snapshot.selectedIds.size < need) return MechanicVerdict.INCOMPLETE
        val extras = snapshot.selectedIds - p.requiredIds
        return if (snapshot.selectedIds.containsAll(p.requiredIds) && extras.isEmpty()) {
            MechanicVerdict.CORRECT
        } else if (extras.isNotEmpty()) {
            MechanicVerdict.WRONG
        } else {
            MechanicVerdict.INCOMPLETE
        }
    }

    fun withVisualHint(snapshot: MechanicSnapshot, params: MechanicParams): MechanicSnapshot {
        val p = effectiveParams(params as VisualSearchParams, snapshot.simplified)
        return snapshot.copy(highlightIds = p.requiredIds)
    }

    private fun effectiveParams(p: VisualSearchParams, simplified: Boolean): VisualSearchParams {
        if (!simplified || p.requiredIds.size <= 1) return p
        val keep = setOf(p.requiredIds.first())
        return p.copy(requiredIds = keep, minSelect = 1)
    }
}

object SortClassifyEngine : MechanicEngine {
    override val type = MechanicType.SORT_AND_CLASSIFY

    override fun initial(params: MechanicParams, simplified: Boolean): MechanicSnapshot =
        MechanicSnapshot(type = type, simplified = simplified)

    override fun applySelect(snapshot: MechanicSnapshot, params: MechanicParams, objectId: String): MechanicSnapshot {
        // objectId format: "itemId:categoryId"
        val parts = objectId.split(":", limit = 2)
        if (parts.size != 2) return snapshot
        return snapshot.copy(placements = snapshot.placements + (parts[0] to parts[1]))
    }

    override fun applyVoice(
        snapshot: MechanicSnapshot,
        params: MechanicParams,
        normalized: String,
    ): Pair<MechanicSnapshot, MechanicVerdict> {
        val p = params as SortClassifyParams
        val item = p.items.firstOrNull { it.label.normalizeMatch() == normalized ||
            it.voiceAliases.any { a -> a.normalizeMatch() == normalized } }
        val category = p.categories.firstOrNull { it.label.normalizeMatch() == normalized }
        // "кит вода" style
        val byPair = p.items.firstOrNull { item ->
            p.categories.any { cat ->
                normalized.contains(item.label.normalizeMatch()) &&
                    normalized.contains(cat.label.normalizeMatch())
            }
        }
        when {
            byPair != null -> {
                val cat = p.categories.first {
                    normalized.contains(it.label.normalizeMatch())
                }
                val updated = applySelect(snapshot, params, "${byPair.id}:${cat.id}")
                return updated to evaluate(updated, params)
            }
            item != null -> {
                // Place into correct category via voice of item alone (helpful for kids)
                val updated = applySelect(snapshot, params, "${item.id}:${item.correctCategoryId}")
                return updated to evaluate(updated, params)
            }
            else -> return snapshot to MechanicVerdict.UNKNOWN
        }
    }

    override fun evaluate(snapshot: MechanicSnapshot, params: MechanicParams): MechanicVerdict {
        val p = params as SortClassifyParams
        val items = if (snapshot.simplified && p.items.size > 2) p.items.take(2) else p.items
        if (snapshot.placements.size < items.size) return MechanicVerdict.INCOMPLETE
        val ok = items.all { snapshot.placements[it.id] == it.correctCategoryId }
        return if (ok) MechanicVerdict.CORRECT else MechanicVerdict.WRONG
    }
}

object PatternSequenceEngine : MechanicEngine {
    override val type = MechanicType.PATTERN_SEQUENCE

    override fun initial(params: MechanicParams, simplified: Boolean): MechanicSnapshot =
        MechanicSnapshot(type = type, simplified = simplified)

    override fun applySelect(snapshot: MechanicSnapshot, params: MechanicParams, objectId: String): MechanicSnapshot =
        snapshot.copy(chosenOption = objectId)

    override fun applyVoice(
        snapshot: MechanicSnapshot,
        params: MechanicParams,
        normalized: String,
    ): Pair<MechanicSnapshot, MechanicVerdict> {
        val p = params as PatternSequenceParams
        val accept = (listOf(p.correctOption) + p.voiceAccept + p.options)
            .map { it.normalizeMatch() }
        val hit = p.options.firstOrNull { opt ->
            opt.normalizeMatch() == normalized || normalized.contains(opt.normalizeMatch())
        } ?: p.voiceAccept.firstOrNull { it.normalizeMatch() == normalized }?.let { p.correctOption }
        if (hit == null && normalized !in accept) return snapshot to MechanicVerdict.UNKNOWN
        val choice = hit ?: p.correctOption
        val updated = snapshot.copy(chosenOption = choice)
        return updated to evaluate(updated, params)
    }

    override fun evaluate(snapshot: MechanicSnapshot, params: MechanicParams): MechanicVerdict {
        val p = params as PatternSequenceParams
        val chosen = snapshot.chosenOption ?: return MechanicVerdict.INCOMPLETE
        return if (chosen == p.correctOption) MechanicVerdict.CORRECT else MechanicVerdict.WRONG
    }
}

object PathMazeEngine : MechanicEngine {
    override val type = MechanicType.PATH_MAZE

    override fun initial(params: MechanicParams, simplified: Boolean): MechanicSnapshot {
        val p = params as PathMazeParams
        return MechanicSnapshot(
            type = type,
            path = listOf(p.start),
            cursor = p.start,
            simplified = simplified,
        )
    }

    override fun applySelect(snapshot: MechanicSnapshot, params: MechanicParams, objectId: String): MechanicSnapshot {
        val p = params as PathMazeParams
        val target = when (objectId) {
            "UP" -> snapshot.cursor!!.copy(y = snapshot.cursor.y - 1)
            "DOWN" -> snapshot.cursor!!.copy(y = snapshot.cursor.y + 1)
            "LEFT" -> snapshot.cursor!!.copy(x = snapshot.cursor.x - 1)
            "RIGHT" -> snapshot.cursor!!.copy(x = snapshot.cursor.x + 1)
            else -> runCatching { GridPos.parse(objectId) }.getOrNull() ?: return snapshot
        }
        if (target.x !in 0 until p.width || target.y !in 0 until p.height) return snapshot
        if (target in p.blocked) return snapshot
        if (!labelAllowed(p, target)) return snapshot
        // Adjacent only for directional moves
        val cur = snapshot.cursor!!
        val manhattan = kotlin.math.abs(target.x - cur.x) + kotlin.math.abs(target.y - cur.y)
        if (manhattan != 1 && objectId in setOf("UP", "DOWN", "LEFT", "RIGHT")) return snapshot
        if (manhattan != 1 && !objectId.contains(",")) return snapshot
        return snapshot.copy(path = snapshot.path + target, cursor = target)
    }

    override fun applyVoice(
        snapshot: MechanicSnapshot,
        params: MechanicParams,
        normalized: String,
    ): Pair<MechanicSnapshot, MechanicVerdict> {
        val dir = when {
            "вверх" in normalized || normalized == "up" -> "UP"
            "вниз" in normalized || normalized == "down" -> "DOWN"
            "лев" in normalized || normalized == "left" -> "LEFT"
            "прав" in normalized || normalized == "right" -> "RIGHT"
            else -> null
        } ?: return snapshot to MechanicVerdict.UNKNOWN
        val updated = applySelect(snapshot, params, dir)
        return updated to evaluate(updated, params)
    }

    override fun evaluate(snapshot: MechanicSnapshot, params: MechanicParams): MechanicVerdict {
        val p = params as PathMazeParams
        val cursor = snapshot.cursor ?: return MechanicVerdict.INCOMPLETE
        if (cursor != p.goal) return MechanicVerdict.INCOMPLETE
        if (p.requiredVisit.isNotEmpty() && !snapshot.path.containsAll(p.requiredVisit)) {
            return MechanicVerdict.WRONG
        }
        if (snapshot.path.any { it in p.blocked }) return MechanicVerdict.WRONG
        if (snapshot.path.any { !labelAllowed(p, it) }) return MechanicVerdict.WRONG
        return MechanicVerdict.CORRECT
    }

    private fun labelAllowed(p: PathMazeParams, pos: GridPos): Boolean {
        if (p.allowedLabelRule == PathLabelRule.ANY) return true
        val label = p.cellLabels[pos] ?: return true
        val n = label.toIntOrNull() ?: return true
        return when (p.allowedLabelRule) {
            PathLabelRule.EVEN_NUMBERS -> n % 2 == 0
            PathLabelRule.ODD_NUMBERS -> n % 2 == 1
            PathLabelRule.ANY -> true
        }
    }
}

/**
 * Predict → Test discovery. Wrong hypothesis is never WRONG — child always observes.
 */
object PredictAndTestEngine : MechanicEngine {
    override val type = MechanicType.PREDICT_AND_TEST

    override fun initial(params: MechanicParams, simplified: Boolean): MechanicSnapshot =
        MechanicSnapshot(type = type, phase = "PREDICT", simplified = simplified)

    override fun applySelect(snapshot: MechanicSnapshot, params: MechanicParams, objectId: String): MechanicSnapshot {
        val p = params as PredictAndTestParams
        if (objectId == "__test__" || objectId in p.testPhrases) {
            return runTest(snapshot, p)
        }
        if (p.options.none { it.id == objectId }) return snapshot
        return snapshot.copy(
            chosenOption = objectId,
            selectedIds = setOf(objectId),
            exploredIds = snapshot.exploredIds + objectId,
            phase = "PREDICT",
        )
    }

    override fun applyVoice(
        snapshot: MechanicSnapshot,
        params: MechanicParams,
        normalized: String,
    ): Pair<MechanicSnapshot, MechanicVerdict> {
        val p = params as PredictAndTestParams
        if (p.testPhrases.any { normalized.contains(it) || normalized == it }) {
            val next = runTest(snapshot, p)
            return next to evaluate(next, params)
        }
        val match = p.options.firstOrNull { opt ->
            opt.label.normalizeMatch() == normalized ||
                opt.voiceAliases.any { it.normalizeMatch() == normalized || normalized.contains(it.normalizeMatch()) } ||
                normalized.contains(opt.label.normalizeMatch())
        } ?: return snapshot to MechanicVerdict.UNKNOWN
        val updated = applySelect(snapshot, params, match.id)
        return updated to MechanicVerdict.INCOMPLETE
    }

    override fun evaluate(snapshot: MechanicSnapshot, params: MechanicParams): MechanicVerdict {
        if (snapshot.phase == "TESTED" && snapshot.observation != null) return MechanicVerdict.OBSERVED
        if (snapshot.chosenOption == null) return MechanicVerdict.INCOMPLETE
        return MechanicVerdict.INCOMPLETE
    }

    private fun runTest(snapshot: MechanicSnapshot, p: PredictAndTestParams): MechanicSnapshot {
        val choice = snapshot.chosenOption ?: return snapshot
        val obs = p.outcomeByOptionId[choice]
            ?: p.outcomeByOptionId.values.firstOrNull()
            ?: "Посмотрим, что получилось."
        return snapshot.copy(phase = "TESTED", observation = obs)
    }
}

/**
 * Build → Test. Fail shows why; never scores as a game loss.
 */
object BuildAndTestEngine : MechanicEngine {
    override val type = MechanicType.BUILD_AND_TEST

    override fun initial(params: MechanicParams, simplified: Boolean): MechanicSnapshot =
        MechanicSnapshot(type = type, phase = "BUILD", simplified = simplified)

    override fun applySelect(snapshot: MechanicSnapshot, params: MechanicParams, objectId: String): MechanicSnapshot {
        val p = params as BuildAndTestParams
        if (objectId == "__test__" || objectId in p.testPhrases) {
            return runTest(snapshot, p)
        }
        val part = p.parts.firstOrNull { it.id == objectId } ?: return snapshot
        val next = snapshot.placements.toMutableMap()
        // One part per slot: clear previous occupant of same slot
        next.entries.removeAll { it.value == part.slotId }
        next[part.id] = part.slotId
        return snapshot.copy(
            placements = next,
            exploredIds = snapshot.exploredIds + part.id,
            phase = "BUILD",
            observation = null,
        )
    }

    override fun applyVoice(
        snapshot: MechanicSnapshot,
        params: MechanicParams,
        normalized: String,
    ): Pair<MechanicSnapshot, MechanicVerdict> {
        val p = params as BuildAndTestParams
        if (p.testPhrases.any { normalized.contains(it) || normalized == it }) {
            val next = runTest(snapshot, p)
            return next to evaluate(next, params)
        }
        val match = p.parts.firstOrNull { part ->
            part.label.normalizeMatch() == normalized ||
                part.voiceAliases.any { it.normalizeMatch() == normalized || normalized.contains(it.normalizeMatch()) } ||
                normalized.contains(part.label.normalizeMatch())
        } ?: return snapshot to MechanicVerdict.UNKNOWN
        val updated = applySelect(snapshot, params, match.id)
        return updated to MechanicVerdict.INCOMPLETE
    }

    override fun evaluate(snapshot: MechanicSnapshot, params: MechanicParams): MechanicVerdict {
        if (snapshot.phase != "RESULT") return MechanicVerdict.INCOMPLETE
        val p = params as BuildAndTestParams
        return if (matchesSuccess(snapshot, p)) MechanicVerdict.CORRECT else MechanicVerdict.OBSERVED
    }

    private fun runTest(snapshot: MechanicSnapshot, p: BuildAndTestParams): MechanicSnapshot {
        val ok = matchesSuccess(snapshot, p)
        return snapshot.copy(
            phase = "RESULT",
            observation = if (ok) p.successObservation else p.failObservation,
        )
    }

    private fun matchesSuccess(snapshot: MechanicSnapshot, p: BuildAndTestParams): Boolean {
        if (p.successPlacements.isEmpty()) return snapshot.placements.isNotEmpty()
        return p.successPlacements.all { (partId, slotId) -> snapshot.placements[partId] == slotId }
    }
}

internal fun String.normalizeMatch(): String =
    lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
