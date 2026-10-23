package vn.aquastream.tv

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Independent anchors in the approved 960x540 reference coordinate space.
 * Center transport never shifts when timestamps in the side pills change width.
 */
class MockupLayout(c: Context, private val designWidth: Float = 960f, private val designHeight: Float = 540f) : FrameLayout(c) {
    private data class Anchor(val view: View, val x: Float, val y: Float, val w: Float, val h: Float, val textSize: Float?)
    private val anchors = ArrayList<Anchor>()
    init { clipChildren = false; clipToPadding = false; isFocusable = false }
    fun place(view: View, x: Float, y: Float, w: Float, h: Float, textSize: Float? = null) {
        anchors.add(Anchor(view, x, y, w, h, textSize)); addView(view)
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
        for (a in anchors) {
            val lp = a.view.layoutParams as LayoutParams
            lp.width = (a.w * width / designWidth).roundToInt().coerceAtLeast(1)
            lp.height = (a.h * height / designHeight).roundToInt().coerceAtLeast(1)
            lp.leftMargin = (a.x * width / designWidth).roundToInt()
            lp.topMargin = (a.y * height / designHeight).roundToInt()
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            if (a.view is TextView && a.textSize != null) a.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, a.textSize * width / designWidth)
        }
        super.onMeasure(w, h)
    }
}
