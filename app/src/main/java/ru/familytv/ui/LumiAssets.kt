package ru.familytv.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import ru.familytv.adventure.GuideEmotion

/**
 * Production art slots for Lumi. When a drawable exists, [LumiView] can show it
 * instead of procedural canvas. Naming matches docs/LUMI_PRODUCTION_SPEC.md.
 */
object LumiAssets {
    enum class SizeVariant { UI, SCENE }

    data class Slot(val id: String, val emotion: GuideEmotion)

    val SLOTS: List<Slot> = listOf(
        Slot("lumi_idle", GuideEmotion.CALM),
        Slot("lumi_listening", GuideEmotion.LISTENING),
        Slot("lumi_speaking", GuideEmotion.HAPPY),
        Slot("lumi_thinking", GuideEmotion.THINKING),
        Slot("lumi_helping", GuideEmotion.HELPING),
        Slot("lumi_curious", GuideEmotion.CURIOUS),
        Slot("lumi_happy", GuideEmotion.HAPPY),
        Slot("lumi_celebrating", GuideEmotion.CELEBRATING),
        Slot("lumi_encouraging", GuideEmotion.ENCOURAGING),
        Slot("lumi_surprised", GuideEmotion.SURPRISED),
        Slot("lumi_rest", GuideEmotion.REST),
    )

    fun slotFor(emotion: GuideEmotion): String = when (emotion) {
        GuideEmotion.CALM -> "lumi_idle"
        GuideEmotion.LISTENING -> "lumi_listening"
        GuideEmotion.HAPPY, GuideEmotion.EXCITED -> "lumi_happy"
        GuideEmotion.THINKING -> "lumi_thinking"
        GuideEmotion.HELPING -> "lumi_helping"
        GuideEmotion.CURIOUS -> "lumi_curious"
        GuideEmotion.CELEBRATING -> "lumi_celebrating"
        GuideEmotion.ENCOURAGING -> "lumi_encouraging"
        GuideEmotion.SURPRISED -> "lumi_surprised"
        GuideEmotion.REST -> "lumi_rest"
    }

    fun drawableName(slot: String, size: SizeVariant): String = when (size) {
        SizeVariant.UI -> "${slot}_ui"
        SizeVariant.SCENE -> "${slot}_scene"
    }

    fun resolve(context: Context, emotion: GuideEmotion, size: SizeVariant = SizeVariant.UI): Drawable? {
        val slot = slotFor(emotion)
        val name = drawableName(slot, size)
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id != 0) return ContextCompat.getDrawable(context, id)
        // Fallback: unsized slot name
        val bare = context.resources.getIdentifier(slot, "drawable", context.packageName)
        return if (bare != 0) ContextCompat.getDrawable(context, bare) else null
    }

    fun allSlotIds(): List<String> = SLOTS.map { it.id }
}
