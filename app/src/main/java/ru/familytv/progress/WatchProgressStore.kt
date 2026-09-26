package ru.familytv.progress

/**
 * Progress is device/family scoped in Alpha.
 * Profile(id) is reserved so storage keys can grow without rewriting Home/player.
 */
sealed interface ProgressScope {
    data object Device : ProgressScope
    data class Profile(val profileId: String) : ProgressScope
}

data class WatchProgress(
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val lastPlayedAt: Long,
    val profileId: String? = null,
)

class WatchProgressStore(
    private val prefs: android.content.SharedPreferences,
    private val scope: ProgressScope = ProgressScope.Device,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: android.content.Context, scope: ProgressScope = ProgressScope.Device) : this(
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE),
        scope,
    )

    fun get(contentId: String): WatchProgress? {
        val prefix = keyPrefix(contentId)
        if (!prefs.contains("${prefix}positionMs")) return null
        return WatchProgress(
            contentId = contentId,
            positionMs = prefs.getLong("${prefix}positionMs", 0L),
            durationMs = prefs.getLong("${prefix}durationMs", 0L),
            completed = prefs.getBoolean("${prefix}completed", false),
            lastPlayedAt = prefs.getLong("${prefix}lastPlayedAt", 0L),
            profileId = (scope as? ProgressScope.Profile)?.profileId,
        )
    }

    fun save(contentId: String, positionMs: Long, durationMs: Long, completed: Boolean = false) {
        val nonResumable = isNonResumable(durationMs)
        val nearEnd = isNearEnd(positionMs, durationMs)
        val done = completed || nearEnd || nonResumable
        val safePosition = when {
            done -> 0L
            else -> positionMs.coerceAtLeast(0L)
        }
        val prefix = keyPrefix(contentId)
        prefs.edit()
            .putLong("${prefix}positionMs", if (done) 0L else safePosition)
            .putLong("${prefix}durationMs", durationMs.coerceAtLeast(0L))
            .putBoolean("${prefix}completed", done)
            .putLong("${prefix}lastPlayedAt", clock())
            .apply()
    }

    fun markCompleted(contentId: String, durationMs: Long) {
        save(contentId, positionMs = durationMs, durationMs = durationMs, completed = true)
    }

    /** Unfinished videos for Continue Watching, newest first. */
    fun continueWatching(knownIds: Set<String> = emptySet()): List<WatchProgress> {
        val ids = prefs.all.keys
            .mapNotNull { key ->
                val marker = ":positionMs"
                if (!key.endsWith(marker)) return@mapNotNull null
                val without = key.removeSuffix(marker)
                val scopePrefix = scopePrefix()
                if (!without.startsWith(scopePrefix)) return@mapNotNull null
                without.removePrefix(scopePrefix)
            }
            .distinct()
        return ids.mapNotNull { id ->
            if (knownIds.isNotEmpty() && id !in knownIds) return@mapNotNull null
            get(id)?.takeIf { !it.completed && it.positionMs >= resumeMinMs(it.durationMs) }
        }.sortedByDescending { it.lastPlayedAt }
    }

    fun resumePositionMs(contentId: String): Long {
        val progress = get(contentId) ?: return 0L
        if (progress.completed) return 0L
        if (progress.positionMs < resumeMinMs(progress.durationMs)) return 0L
        if (isNearEnd(progress.positionMs, progress.durationMs)) return 0L
        return progress.positionMs
    }

    private fun scopePrefix(): String = when (scope) {
        ProgressScope.Device -> "device:"
        is ProgressScope.Profile -> "profile:${scope.profileId}:"
    }

    private fun keyPrefix(contentId: String) = "${scopePrefix()}$contentId:"

    companion object {
        private const val PREFS = "familytv_watch_progress"
        const val RESUME_MIN_MS = 1_000L
        const val COMPLETION_TOLERANCE_MS = 2_000L

        /**
         * Ultra-short clips (demo stubs under ~8s) are not Continuable.
         * Real FamilyTV videos are longer; treating these as completed avoids
         * bogus Continue Watching rows when ExoPlayer still reports position=0 on early BACK.
         */
        const val NON_RESUMABLE_MAX_MS = 8_000L

        fun isNonResumable(durationMs: Long): Boolean =
            durationMs > 0L && durationMs < NON_RESUMABLE_MAX_MS

        fun resumeMinMs(durationMs: Long): Long = when {
            durationMs <= 0L -> RESUME_MIN_MS
            isNonResumable(durationMs) -> Long.MAX_VALUE / 4 // never resume
            else -> RESUME_MIN_MS
        }

        fun isNearEnd(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0L) return false
            if (isNonResumable(durationMs)) return true
            val tol = when {
                durationMs < 20_000L -> 1_500L
                else -> COMPLETION_TOLERANCE_MS
            }
            return positionMs >= durationMs - tol
        }
    }
}
