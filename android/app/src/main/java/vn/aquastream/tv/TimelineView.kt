package vn.aquastream.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** Focus itself enables scrubbing; no confirmation state and no default SeekBar key handling. */
class TimelineView(c: Context) : View(c) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    var positionMs = 0L
    var durationMs = 0L
    var bufferedMs = 0L
    var bufferedStartMs = 0L
    var previewMs: Long? = null
    init {
        id = View.generateViewId(); isFocusable = true; isClickable = true
        contentDescription = "Dòng thời gian. Trái phải để tua, lên xuống để rời"
        background = null
        setPadding(Ui.dp(c, 5), 0, Ui.dp(c, 5), 0)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat(); val right = (width - paddingRight).toFloat()
        val cy = height / 2f; val half = height * if (hasFocus()) .125f else .10f
        fun x(value: Long): Float = left + (right - left) * (if (durationMs > 0) value.toDouble() / durationMs else 0.0).coerceIn(0.0, 1.0).toFloat()
        pen.color = 0xFF555C66.toInt(); canvas.drawRoundRect(left, cy - half, right, cy + half, half, half, pen)
        if (bufferedMs > bufferedStartMs) {
            pen.color = 0xFF858585.toInt(); canvas.drawRoundRect(x(bufferedStartMs), cy - half, x(bufferedMs), cy + half, half, half, pen)
        }
        pen.color = if (hasFocus()) 0xFFF2F4F8.toInt() else 0xFFD2D7DE.toInt(); canvas.drawRoundRect(left, cy - half, x(positionMs), cy + half, half, half, pen)
        if (hasFocus()) {
            pen.color = 0x24EEF3FF; canvas.drawCircle(x(previewMs ?: positionMs), cy, height * .45f, pen)
            pen.color = 0x40EEF3FF; canvas.drawCircle(x(previewMs ?: positionMs), cy, height * .33f, pen)
        }
        pen.color = 0xFFF4F6FA.toInt()
        canvas.drawCircle(x(previewMs ?: positionMs), cy, height * if (hasFocus()) .29f else .25f, pen)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = "${Ui.time(positionMs)} / ${Ui.time(durationMs)}. Trái phải để tua, lên xuống để rời"
    }
}
