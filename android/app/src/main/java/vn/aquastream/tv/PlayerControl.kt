package vn.aquastream.tv

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.util.TypedValue

/** Reference-calibrated glass lighting. All shaders are cached, with no live blur. */
class PlayerGlassDrawable(private val focused: Boolean, private val utility: Boolean, private val primary: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circle = Path()
    private var glass: Shader? = null
    private var halo: Shader? = null
    private var rim: Shader? = null
    private val lights = ArrayList<Shader>()
    private var radius = 0f
    override fun onBoundsChange(b: Rect) {
        val extent = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        radius = extent * if (utility) .45f else if (primary) .37f else .39f
        circle.reset(); circle.addCircle(cx, cy, radius, Path.Direction.CW)
        glass = LinearGradient(0f, cy - radius, 0f, cy + radius,
            if (focused) intArrayOf(0xDD344152.toInt(), 0xD5303B49.toInt(), 0xDD354252.toInt())
            else intArrayOf(0xC02C343F.toInt(), 0xBD232B35.toInt(), 0xC0262F3A.toInt()),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        halo = RadialGradient(cx, cy, extent / 2,
            intArrayOf(0x0084A5C8, 0x4A719BC5, 0x00719BC5), floatArrayOf(0f, .69f, 1f), Shader.TileMode.CLAMP)
        rim = SweepGradient(cx, cy,
            if (focused) intArrayOf(0xB2AAC8E6.toInt(), 0xEAE5EDF6.toInt(), 0xFFF0F5FA.toInt(), 0xC1A1B8D0.toInt(), 0xB2AAC8E6.toInt())
            else intArrayOf(0x705A6877, 0x80727E89.toInt(), 0xA6A0ADB8.toInt(), 0xA9A0AEBB.toInt(), 0x705A6877),
            floatArrayOf(0f, .25f, .5f, .75f, 1f))
        lights.clear()
        fun light(x: Float, y: Float, size: Float, color: Int) {
            lights.add(RadialGradient(cx + x * radius, cy + y * radius, size * radius,
                intArrayOf(color, color and 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP))
        }
        if (focused) {
            light(.62f, -.62f, .83f, 0x32CBDDF1)
            light(-.52f, .57f, .92f, 0x40CDD8E7)
            light(-.70f, -.62f, .62f, 0x1DBBC9DB)
            light(.92f, .10f, .55f, 0x1BC3D3E4)
        } else {
            light(-.55f, -.72f, .86f, 0x0DCDD8E4)
            light(.66f, .60f, .70f, 0x09B9CADA)
        }
    }
    override fun draw(c: Canvas) {
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        paint.style = Paint.Style.FILL; paint.alpha = 255
        if (focused) { paint.shader = halo; c.drawCircle(x, y, minOf(bounds.width(), bounds.height()) / 2f, paint) }
        paint.shader = glass; c.drawCircle(x, y, radius, paint)
        val save = c.save(); c.clipPath(circle)
        for (light in lights) { paint.shader = light; c.drawRect(bounds, paint) }
        c.restoreToCount(save)
        paint.style = Paint.Style.STROKE; paint.shader = null
        paint.strokeWidth = radius * .065f; paint.color = if (focused) 0x206C92BA else 0x106A7888
        c.drawCircle(x, y, radius, paint)
        paint.shader = rim; paint.alpha = 255; paint.strokeWidth = radius * if (focused) .026f else .017f
        c.drawCircle(x, y, radius, paint)
        if (focused) {
            paint.shader = null; paint.color = 0xCFF2F6FC.toInt(); paint.strokeWidth = radius * .034f; paint.strokeCap = Paint.Cap.ROUND
            c.drawArc(x-radius, y-radius, x+radius, y+radius, -92f, 5f, false, paint)
            c.drawArc(x-radius, y-radius, x+radius, y+radius, 87f, 5f, false, paint)
        }
        paint.style = Paint.Style.FILL; paint.shader = null
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(filter: ColorFilter?) { }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class PlayerPillDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fill: Shader? = null
    private var edge: Shader? = null
    override fun onBoundsChange(b: Rect) {
        fill = LinearGradient(0f, 0f, b.width().toFloat(), b.height().toFloat(),
            intArrayOf(0x9A263342.toInt(), 0x77101720, 0x8C19222D.toInt()), floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
        edge = LinearGradient(0f, 0f, 0f, b.height().toFloat(), intArrayOf(0xAD8492A2.toInt(), 0x664B596A, 0x775D6B7C), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
    }
    override fun draw(c: Canvas) {
        val h = bounds.height().toFloat(); val inset = h * .009f
        p.style = Paint.Style.FILL; p.shader = fill
        c.drawRoundRect(inset, inset, bounds.width()-inset, h-inset, h/2, h/2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = inset; p.shader = edge
        c.drawRoundRect(inset, inset, bounds.width()-inset, h-inset, h/2, h/2, p)
        p.style = Paint.Style.FILL; p.shader = null
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(filter: ColorFilter?) { }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class PlayerAtmosphere(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ambient: Shader? = null
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        ambient = RadialGradient(w/2f, h*.865f, w*.164f,
            intArrayOf(0x65334F70, 0x00233D58), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }
    override fun onDraw(c: Canvas) { p.shader = ambient; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
}

/** Icon and live number share one canvas and one optical center. */
private class PlayerGlyphView(c: Context) : View(c) {
    private var glyph: Drawable? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(3f, 0f, 2f, 0xAA000000.toInt())
    }
    private val textBounds = Rect()
    var seconds: Int = 0
        set(value) { field = value; invalidate() }
    fun icon(resource: Int) {
        if (tag == resource) return
        tag = resource; glyph = context.getDrawable(resource)?.mutate(); invalidate()
    }
    override fun onDraw(c: Canvas) {
        val checkpoint = c.save()
        // 240-unit canvas keeps numeral optical bounds precise to 0.1 vector unit.
        c.scale(width / 240f, height / 240f)
        glyph?.setBounds(0,0,240,240); glyph?.draw(c)
        if (seconds > 0) {
            val text = seconds.toString()
            ink.textSize = if (text.length <= 2) 70f else 54f
            ink.getTextBounds(text,0,text.length,textBounds)
            c.drawText(text,120f-(textBounds.left+textBounds.right)/2f,
                133f-(textBounds.top+textBounds.bottom)/2f,ink)
        }
        c.restoreToCount(checkpoint)
    }
}

class PlayerControl(c: Context, icon: Int, label: String, private val primary: Boolean = false,
    private val utility: Boolean = false, private val surface: Boolean = true, click: () -> Unit) : FrameLayout(c) {
    private val image = PlayerGlyphView(c).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    var iconFraction: Float? = null
        set(value) { field = value; requestLayout() }
    var seconds: Int = 0
        set(value) { if (field != value) { field = value; image.seconds = value } }
    init {
        id = View.generateViewId(); isFocusable = true; isClickable = true
        contentDescription = label; image.icon(icon); addView(image)
        background = if (surface) PlayerGlassDrawable(false, utility, primary) else null
        setOnClickListener { click() }
        setOnFocusChangeListener { _, focused ->
            background = if (focused || surface) PlayerGlassDrawable(focused, utility, primary) else null
            animate().scaleX(if (focused) 1.02f else 1f).scaleY(if (focused) 1.02f else 1f).setDuration(150).start()
        }
    }
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, h)
        val extent = minOf(measuredWidth, measuredHeight)
        val fraction = iconFraction ?: if (utility) .49f else if (primary) .46f else .44f
        val iconSize = (extent * fraction).toInt()
        image.measure(MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, right: Int, b: Int) {
        val size = image.measuredWidth; val x = (width-size)/2; val y = (height-size)/2
        image.layout(x,y,x+size,y+size)
    }
    fun icon(resource: Int) { image.icon(resource) }
}
