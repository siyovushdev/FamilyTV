package ru.familytv.interactive

import android.content.Context
import android.content.SharedPreferences

data class ExperienceProgress(
    val experienceId: String,
    val packageId: String?,
    val packageVersion: Int?,
    val currentStepId: String,
    val completed: Boolean,
    val updatedAt: Long,
)

/**
 * Device-scoped interactive progress. Incompatible package version clears resume.
 */
class ExperienceProgressStore(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun get(experienceId: String): ExperienceProgress? {
        val prefix = key(experienceId)
        if (!prefs.contains("${prefix}step")) return null
        return ExperienceProgress(
            experienceId = experienceId,
            packageId = prefs.getString("${prefix}packageId", null),
            packageVersion = prefs.getInt("${prefix}packageVersion", -1).takeIf { it > 0 },
            currentStepId = prefs.getString("${prefix}step", "") ?: return null,
            completed = prefs.getBoolean("${prefix}completed", false),
            updatedAt = prefs.getLong("${prefix}updatedAt", 0L),
        )
    }

    fun save(
        experienceId: String,
        stepId: String,
        packageId: String?,
        packageVersion: Int?,
        completed: Boolean = false,
    ) {
        val prefix = key(experienceId)
        prefs.edit()
            .putString("${prefix}step", stepId)
            .putString("${prefix}packageId", packageId)
            .putInt("${prefix}packageVersion", packageVersion ?: -1)
            .putBoolean("${prefix}completed", completed)
            .putLong("${prefix}updatedAt", clock())
            .apply()
    }

    fun clear(experienceId: String) {
        val prefix = key(experienceId)
        prefs.edit()
            .remove("${prefix}step")
            .remove("${prefix}packageId")
            .remove("${prefix}packageVersion")
            .remove("${prefix}completed")
            .remove("${prefix}updatedAt")
            .apply()
    }

    /**
     * Returns a resume step id when package identity/version still matches and step exists.
     */
    fun resumeStepId(
        experienceId: String,
        packageId: String?,
        packageVersion: Int?,
        knownStepIds: Set<String>,
    ): String? {
        val progress = get(experienceId) ?: return null
        if (progress.completed) return null
        if (progress.packageId != packageId || progress.packageVersion != packageVersion) {
            clear(experienceId)
            return null
        }
        if (progress.currentStepId !in knownStepIds) {
            clear(experienceId)
            return null
        }
        return progress.currentStepId
    }

    private fun key(experienceId: String) = "device:$experienceId:"

    companion object {
        private const val PREFS = "familytv_experience_progress"
    }
}
