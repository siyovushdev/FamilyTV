package ru.familytv.discovery

/**
 * Limited deterministic cause/effect for children's interactive simulations.
 * Not a general physics engine.
 */
data class SimVariable(
    val id: String,
    val values: List<String>,
    val initial: String,
)

data class SimEffect(
    val set: Map<String, String> = emptyMap(),
    val observation: String,
    val spokenObservation: String? = null,
)

data class SimRule(
    val id: String,
    val whenAll: Map<String, String> = emptyMap(),
    val whenAny: Map<String, List<String>> = emptyMap(),
    val effect: SimEffect,
)

data class CauseEffectModel(
    val variables: List<SimVariable>,
    val rules: List<SimRule>,
    val defaultObservation: String = "Посмотрим, что получилось.",
)

data class SimState(
    val values: Map<String, String>,
    val lastObservation: String? = null,
    val explored: Set<String> = emptySet(),
)

object CauseEffectEngine {
    fun initial(model: CauseEffectModel): SimState =
        SimState(values = model.variables.associate { it.id to it.initial })

    fun applyAction(model: CauseEffectModel, state: SimState, sets: Map<String, String>): SimState {
        val nextValues = state.values.toMutableMap()
        sets.forEach { (k, v) -> nextValues[k] = v }
        val matched = model.rules.firstOrNull { rule ->
            rule.whenAll.all { (k, v) -> nextValues[k] == v } &&
                rule.whenAny.all { (k, allowed) -> nextValues[k] in allowed }
        }
        val obs = matched?.effect?.observation ?: model.defaultObservation
        val explored = state.explored + (matched?.id ?: "default")
        val withEffects = if (matched != null) {
            nextValues.apply { putAll(matched.effect.set) }
        } else nextValues
        return SimState(values = withEffects, lastObservation = obs, explored = explored)
    }
}
