package ru.familytv.ui

import android.graphics.Color

/**
 * Shared FamilyTV visual tokens (Home 3.0 + Adventures).
 * See docs/ART_DIRECTION.md.
 */
object FamilyTvTheme {
    val cream = Color.rgb(255, 249, 239)
    val ink = Color.rgb(38, 55, 72)
    val muted = Color.rgb(101, 117, 123)
    val teal = Color.rgb(25, 115, 99)
    val peach = Color.rgb(255, 230, 217)
    val lavender = Color.rgb(231, 230, 251)

    data class WorldIdentity(
        val id: String,
        val title: String,
        val accent: Int,
        val accentDeep: Int,
        val card: Int,
        val motif: String,
    )

    val worlds: Map<String, WorldIdentity> = mapOf(
        "explore" to WorldIdentity("explore", "Исследуй", Color.rgb(32, 110, 130), Color.rgb(18, 48, 78), Color.rgb(210, 236, 240), "🔭"),
        "solve" to WorldIdentity("solve", "Решай", Color.rgb(90, 100, 180), Color.rgb(50, 55, 120), Color.rgb(226, 228, 250), "◇"),
        "create" to WorldIdentity("create", "Создавай", Color.rgb(200, 110, 80), Color.rgb(140, 70, 50), Color.rgb(255, 232, 220), "✦"),
        "stories" to WorldIdentity("stories", "Истории", Color.rgb(120, 90, 150), Color.rgb(60, 40, 90), Color.rgb(236, 226, 245), "☾"),
        "move" to WorldIdentity("move", "Двигайся", Color.rgb(50, 150, 110), Color.rgb(30, 90, 70), Color.rgb(220, 245, 230), "↷"),
        "continue" to WorldIdentity("continue", "Продолжить", teal, Color.rgb(20, 80, 70), Color.rgb(225, 242, 235), "▶"),
        "today" to WorldIdentity("today", "Сегодня", Color.rgb(180, 120, 50), Color.rgb(120, 70, 30), Color.rgb(255, 244, 220), "☀"),
        "mission" to WorldIdentity("mission", "Сейчас в пути", Color.rgb(70, 90, 160), Color.rgb(30, 40, 90), Color.rgb(230, 225, 255), "🚀"),
    )

    fun worldForSection(sectionId: String): WorldIdentity =
        worlds[sectionId] ?: WorldIdentity(sectionId, sectionId, teal, Color.rgb(20, 60, 50), cream, "·")

    /** Space adventure atmosphere. */
    val spaceNear = Color.rgb(12, 18, 42)
    val spaceFar = Color.rgb(40, 30, 70)
    val spaceAccent = Color.rgb(120, 200, 255)

    /** Forest adventure atmosphere. */
    val forestNear = Color.rgb(20, 48, 32)
    val forestFar = Color.rgb(48, 90, 50)
    val forestAccent = Color.rgb(180, 220, 140)
}
