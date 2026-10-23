package dev.luma.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Colours and shapes of the overlays, taken from the app's dark theme and its gradient send button. */
internal object OverlayStyle {
    const val SURFACE = 0xF21D2230.toInt()      // surfaceContainer, slightly translucent
    const val CARD = 0xF7191D29.toInt()
    const val STROKE = 0x26FFFFFF
    const val TEXT = 0xFFE9EDF8.toInt()         // onSurface
    const val MUTED = 0xFFBFC5D6.toInt()        // onSurfaceVariant
    const val ACCENT = 0xFFB5C4FF.toInt()       // primary
    const val ON_ACCENT = 0xFF2D3550.toInt()    // icon colour of the send button
    val GRADIENT = intArrayOf(0xFFF9FDFF.toInt(), 0xFFB8E2FF.toInt(), 0xFFCCB7FF.toInt())

    fun dp(context: Context, value: Float) = (context.resources.displayMetrics.density * value + .5f).toInt()
    fun surface(context: Context, color: Int = SURFACE, radius: Float = 22f, stroke: Boolean = true) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat(); if (stroke) setStroke(dp(context, 1f), STROKE)
    }
    fun gradient(context: Context, radius: Float = 18f) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, GRADIENT).apply { cornerRadius = dp(context, radius).toFloat() }
}

/** The small round mark of the Stop pill: the app's gradient circle with a dark stop square, like the chat's stop button. */
internal class StopMark(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f; val cx = width / 2f; val cy = height / 2f
        paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, OverlayStyle.GRADIENT, null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null; paint.color = OverlayStyle.ON_ACCENT
        val s = r * .62f; box.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        canvas.drawRoundRect(box, s * .22f, s * .22f, paint)
    }
}
