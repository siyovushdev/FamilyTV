package ru.familytv.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.familytv.R
import kotlin.math.ceil
import kotlin.math.min

internal object GameStyle {
    val background = Color.rgb(255, 249, 239)
    val ink = Color.rgb(38, 55, 72)
    val muted = Color.rgb(101, 117, 123)
    val teal = Color.rgb(25, 115, 99)
    val peach = Color.rgb(255, 230, 217)
    val lavender = Color.rgb(231, 230, 251)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun rounded(context: Context, color: Int, radius: Int = 22, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        if (stroke != 0) setStroke(dp(context, 3), stroke)
    }
    fun focusBackground(context: Context, color: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(context, color, stroke = teal))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(context, color, stroke = teal))
        addState(intArrayOf(), rounded(context, color))
    }
    fun text(context: Context, value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(ink)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    fun button(context: Context, title: String, action: () -> Unit) = Button(context).apply {
        text = title
        textSize = 18f
        isAllCaps = false
        setTextColor(ink)
        minHeight = dp(context, 52)
        minimumWidth = dp(context, 130)
        setPadding(dp(context, 20), 0, dp(context, 20), 0)
        background = focusBackground(context, Color.WHITE)
        setOnClickListener { action() }
    }
}

/** Local vector art, scaled to the available landscape/portrait area. No emoji/network. */
internal class AppleBoard(context: Context) : View(context) {
    var leftCount = 2
        set(value) { field = value; invalidate() }
    var rightCount = 3
        set(value) { field = value; invalidate() }
    private val apple = checkNotNull(ContextCompat.getDrawable(context, R.drawable.game_apple))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(value: Int) = GameStyle.dp(context, value).toFloat()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = dp(64)
        val groupWidth = (width - gap) / 2f
        paint.color = Color.rgb(255, 245, 231)
        for (x in listOf(0f, groupWidth + gap))
            canvas.drawRoundRect(RectF(x, 0f, x + groupWidth, height.toFloat()), dp(24), dp(24), paint)
        paint.color = GameStyle.ink
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = dp(48)
        canvas.drawText("+", width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        fun group(count: Int, x: Float) {
            val columns = if (groupWidth / count < dp(48)) min(3, count) else count
            val rows = ceil(count.toFloat() / columns).toInt()
            val size = min(dp(82), min((groupWidth - dp(16)) / columns, (height - dp(12)) / rows))
            for (i in 0 until count) {
                val row = i / columns
                val countInRow = min(columns, count - row * columns)
                val px = x + (groupWidth - size * countInRow) / 2 + (i % columns) * size
                val py = (height - size * rows) / 2 + row * size
                apple.setBounds(px.toInt(), py.toInt(), (px + size).toInt(), (py + size).toInt())
                apple.draw(canvas)
            }
        }
        group(leftCount, 0f)
        group(rightCount, groupWidth + gap)
    }
}

internal class ColorBoard(context: Context) : View(context) {
    var question = ColorQuestion(1, GameColor.BLUE, listOf(GameColor.RED, GameColor.BLUE, GameColor.GREEN))
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(value: Int) = GameStyle.dp(context, value).toFloat()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = question.choices.size
        val radius = min(height * .29f, width / (n * 2.8f))
        val cy = height * .45f
        question.choices.forEachIndexed { i, color ->
            val x = width * (i + .5f) / n
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(x, cy, radius + dp(11), paint)
            paint.color = color.rgb or (0xFF shl 24)
            canvas.drawCircle(x, cy, radius, paint)
            if (color == question.target) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3)
                paint.color = GameStyle.ink
                canvas.drawCircle(x, cy, radius + dp(8), paint)
                paint.style = Paint.Style.FILL
                val arrow = Path().apply {
                    moveTo(x - dp(7), cy - radius - dp(24)); lineTo(x + dp(7), cy - radius - dp(24))
                    lineTo(x, cy - radius - dp(14)); close()
                }
                canvas.drawPath(arrow, paint)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = dp(18)
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                canvas.drawText("Этот круг", x, height - dp(9), paint)
            }
        }
    }
}
