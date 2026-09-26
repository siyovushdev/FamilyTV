package ru.familytv.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.familytv.R
import ru.familytv.games.GameStyle

/** Icon-first chrome for non-readers (Repeat / Help / Next). */
object ChildChrome {
    fun iconButton(
        context: Context,
        drawableRes: Int,
        contentDesc: String,
        onClick: () -> Unit,
    ): ImageButton {
        fun dp(v: Int) = GameStyle.dp(context, v)
        return ImageButton(context).apply {
            setImageResource(drawableRes)
            contentDescription = contentDesc
            background = GameStyle.focusBackground(context, Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumWidth = dp(72)
            minimumHeight = dp(56)
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun worldIcon(context: Context, sectionId: String): Int = when (sectionId) {
        "explore", "mission" -> R.drawable.ic_world_explore
        "solve", "play", "learn" -> R.drawable.ic_world_solve
        "create" -> R.drawable.ic_world_create
        "stories" -> R.drawable.ic_world_stories
        "move" -> R.drawable.ic_world_move
        else -> R.drawable.ic_world_explore
    }

    fun sectionHeader(context: Context, sectionId: String, title: String, nonReader: Boolean): LinearLayout {
        fun dp(v: Int) = GameStyle.dp(context, v)
        val world = FamilyTvTheme.worldForSection(sectionId)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val icon = ImageView(context).apply {
                setImageResource(worldIcon(context, sectionId))
                contentDescription = world.title
            }
            addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            if (!nonReader) {
                addView(
                    GameStyle.text(context, title, 24f, true).apply {
                        setTextColor(world.accentDeep)
                    },
                )
            } else {
                // Non-reader: title still in contentDescription via icon; tiny caption optional
                addView(
                    TextView(context).apply {
                        text = world.title
                        textSize = 18f
                        setTextColor(world.accentDeep)
                        alpha = 0.55f
                        contentDescription = world.title
                    },
                )
            }
        }
    }
}
