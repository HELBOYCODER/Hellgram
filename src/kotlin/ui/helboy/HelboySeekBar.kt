package desu.inugram.ui.helboy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

// helboy: minimal seek bar with buffered track, YouTube-red played portion and a knob.
class HelboySeekBar(view: android.content.Context) : View(view) {

    var onSeek: ((Double) -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF.toInt() }
    private val bufPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0033.toInt() }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0033.toInt() }
    private val rect = RectF()

    private var fraction = 0.0
    private var buffered = 0f
    private var dragging = false

    fun setFraction(f: Double, buf: Float) {
        if (!dragging) fraction = f
        buffered = buf
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val barH = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics)
        rect.set(0f, cy - barH / 2, width.toFloat(), cy + barH / 2)
        canvas.drawRoundRect(rect, barH, barH, bgPaint)
        rect.set(0f, cy - barH / 2, width * buffered, cy + barH / 2)
        canvas.drawRoundRect(rect, barH, barH, bufPaint)
        rect.set(0f, cy - barH / 2, (width * fraction).toFloat(), cy + barH / 2)
        canvas.drawRoundRect(rect, barH, barH, playPaint)
        val knobR = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, if (dragging) 7f else 5f, resources.displayMetrics)
        canvas.drawCircle((width * fraction).toFloat(), cy, knobR, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> fraction = ((event.x / width).coerceIn(0f, 1f)).toDouble()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fraction = ((event.x / width).coerceIn(0f, 1f)).toDouble()
                onSeek?.invoke(fraction)
                dragging = false
            }
        }
        invalidate()
        return true
    }
}
