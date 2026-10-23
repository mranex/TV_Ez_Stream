package vn.aquastream.tv

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** Browser-only palette: the player's shared Ui tokens are unchanged. */
object BrowserStyle {
    val ink = Color.rgb(241, 242, 244)
    val muted = Color.rgb(164, 169, 176)
    fun text(c: Context, value: String, size: Float, strong: Boolean = false) = Ui.text(c, value, size, ink, strong)
    fun surface(view: View, radius: Float = 14f, scale: Float = 1.02f, focused: ((Boolean) -> Unit)? = null) {
        view.id = View.generateViewId(); view.isFocusable = true; view.isClickable = true
        view.background = SmokySurface(view.context, radius); view.stateListAnimator = null
        view.setOnFocusChangeListener { _, hasFocus ->
            view.animate().cancel()
            view.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f).setDuration(150).start()
            focused?.invoke(hasFocus)
        }
    }
    fun iconButton(c: Context, icon: String, label: String, click: () -> Unit) = FrameLayout(c).apply {
        contentDescription = label; surface(this, 13f)
        addView(BrowserIcon(c, icon), FrameLayout.LayoutParams(Ui.dp(c, 23), Ui.dp(c, 23), Gravity.CENTER))
        setOnClickListener { click() }
    }
    fun nav(c: Context, icon: String, label: String, click: () -> Unit) = FrameLayout(c).apply {
        contentDescription = label; surface(this, 13f)
        addView(BrowserIcon(c, icon), FrameLayout.LayoutParams(Ui.dp(c, 25), Ui.dp(c, 25), Gravity.CENTER_VERTICAL).apply { leftMargin = Ui.dp(c, 20) })
        addView(text(c, label, 17f, true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = Ui.dp(c, 67) })
        setOnClickListener { click() }
    }
}

/** Lightweight gradients and nested thin strokes; no blur, bitmap or offscreen layers. */
class SmokySurface(c: Context, private val radiusDp: Float) : Drawable() {
    private val density = c.resources.displayMetrics.density
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var focus = false
    private var active = false
    private var fill: Shader? = null
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val f = android.R.attr.state_focused in state; val a = android.R.attr.state_selected in state
        if (f == focus && a == active) return false
        focus = f; active = a; updateFill(); invalidateSelf(); return true
    }
    override fun onBoundsChange(bounds: Rect) { updateFill() }
    private fun updateFill() {
        fill = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            if (focus) intArrayOf(0xE34B535D.toInt(), 0xB522272D.toInt(), 0xD34B535C.toInt())
            else if (active) intArrayOf(0xB6383E45.toInt(), 0xA51D2228.toInt(), 0xB62C3239.toInt())
            else intArrayOf(0xB72A3037.toInt(), 0xA411151A.toInt(), 0xBF272E35.toInt()),
            floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        val inset = 5f * density; val radius = radiusDp * density
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        pen.style = Paint.Style.FILL; pen.alpha = 255; pen.shader = fill
        canvas.drawRoundRect(rect, radius, radius, pen); pen.shader = null; pen.style = Paint.Style.STROKE
        if (focus) for (n in 4 downTo 1) {
            pen.strokeWidth = (1f + n * 1.8f) * density; pen.color = Color.argb(5 + (4-n)*3, 222, 231, 240)
            canvas.drawRoundRect(rect, radius, radius, pen)
        }
        pen.strokeWidth = (if (focus) 1.15f else .8f) * density
        pen.color = if (focus) 0xFFE5EAF0.toInt() else if (active) 0x705A626C else 0x6657606B
        canvas.drawRoundRect(rect, radius, radius, pen)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class BrowserBackdrop : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        canvas.drawColor(0xFF090C0F.toInt())
        pen.alpha = 255
        pen.shader = LinearGradient(0f, h, w, 0f, intArrayOf(0xFF10161B.toInt(), 0xFF070A0D.toInt(), 0xFF1E262E.toInt()), floatArrayOf(0f,.48f,1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds,pen)
        fun haze(x: Float, y: Float, r: Float, alpha: Int) {
            pen.shader = RadialGradient(x*w,y*h,r*w,intArrayOf(Color.argb(alpha,117,127,139),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(bounds,pen)
        }
        haze(.94f,-.08f,.34f,46); haze(.04f,.61f,.18f,23); haze(.84f,.84f,.28f,17)
        // Two translucent diagonal bands reproduce the reference's smoke without a bitmap/blur.
        canvas.save(); canvas.scale(w/960f,h/540f); canvas.rotate(-34f,480f,270f)
        fun wisp(y: Float, alpha: Int) {
            pen.shader=LinearGradient(0f,y-27f,0f,y+27f,intArrayOf(Color.TRANSPARENT,Color.argb(alpha,126,139,151),Color.TRANSPARENT),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
            canvas.drawRect(-300f,y-27f,1260f,y+27f,pen)
        }
        wisp(155f,17); wisp(532f,15); canvas.restore()
        pen.shader = null; pen.color = 0x1A798089; pen.strokeWidth = 1f
        canvas.drawLine(w*206/960,0f,w*206/960,h,pen)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Resolution-independent vector logo and connection glyph. */
class BrowserIcon(c: Context, private val kind: String) : FrameLayout(c) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    init { if (kind == "logo") addView(android.widget.ImageView(c).apply { setImageResource(R.drawable.aquastream_logo); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }, LayoutParams(-1,-1)); setWillNotDraw(false); if (kind != "logo" && kind != "computer") addView(SymbolView(c,kind,0xFFCFD6DD.toInt()),LayoutParams(-1,-1)) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (kind == "logo" || kind != "computer") return
        canvas.save(); canvas.scale(width/24f,height/24f); pen.color = 0xFFCBD6DF.toInt()
        if (kind == "logo") {
            pen.style = Paint.Style.FILL
            path.reset(); path.moveTo(4f,2f); path.lineTo(21f,12f); path.lineTo(4f,22f); path.close(); canvas.drawPath(path,pen)
            pen.color = 0xFF8F9FAA.toInt(); path.reset(); path.moveTo(4f,2f); path.lineTo(10f,5.5f); path.lineTo(10f,18.5f); path.lineTo(4f,22f); path.close(); canvas.drawPath(path,pen)
        } else {
            pen.style = Paint.Style.STROKE; pen.strokeWidth = 1.7f; pen.strokeCap = Paint.Cap.ROUND
            canvas.drawRoundRect(2f,3f,22f,17f,2f,2f,pen); canvas.drawLine(12f,17f,12f,21f,pen); canvas.drawLine(8f,21f,16f,21f,pen)
        }
        canvas.restore()
    }
}
