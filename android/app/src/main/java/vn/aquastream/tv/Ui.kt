package vn.aquastream.tv

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.util.Locale

/** Native TV tokens: dp scales to 720p/1080p; system font supplies Vietnamese and CJK fallback. */
object Ui {
    val ground = Color.rgb(13, 15, 17)
    val surface = Color.rgb(25, 28, 30)
    val raised = Color.rgb(43, 48, 51)
    val ink = Color.rgb(243, 246, 248)
    val muted = Color.rgb(170, 181, 188)
    val accent = Color.rgb(132, 207, 235)
    val error = Color.rgb(242, 158, 151)
    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density + .5f).toInt()
    fun fullscreen(a: Activity) {
        a.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        a.window.statusBarColor = ground; a.window.navigationBarColor = ground
    }
    fun rounded(c: Context, color: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radius).toFloat()
        if (stroke != null) setStroke(dp(c, 2), stroke)
    }
    fun focusBackground(c: Context, resting: Int = surface) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(c, raised, stroke = accent))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(c, raised, stroke = ink))
        addState(intArrayOf(android.R.attr.state_selected), rounded(c, raised, stroke = accent))
        addState(intArrayOf(), rounded(c, resting))
    }
    fun text(c: Context, value: String, size: Float = 18f, color: Int = ink, medium: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun column(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(ground)
        setPadding(dp(c, 32), dp(c, 24), dp(c, 32), dp(c, 24))
    }
    fun button(c: Context, label: String, action: () -> Unit) = Button(c).apply {
        id = View.generateViewId(); text = label; contentDescription = label
        textSize = 16f; isAllCaps = false; setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = 0; minimumWidth = 0; minHeight = dp(c, 44); minimumHeight = dp(c, 44)
        background = focusBackground(c); stateListAnimator = null
        setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8))
        setOnClickListener { action() }
    }
    fun iconButton(c: Context, icon: String, label: String, action: () -> Unit): FrameLayout = FrameLayout(c).apply {
        id = View.generateViewId(); contentDescription = label
        isFocusable = true; isClickable = true; background = focusBackground(c)
        addView(SymbolView(c, icon), FrameLayout.LayoutParams(dp(c, 24), dp(c, 24), Gravity.CENTER))
        setOnClickListener { action() }
    }
    fun gap(c: Context, width: Int = 0, height: Int = 0) = View(c).apply { layoutParams = LinearLayout.LayoutParams(dp(c, width), dp(c, height)) }
    fun ellipsize(view: TextView, lines: Int = 1) { view.maxLines = lines; view.ellipsize = TextUtils.TruncateAt.MIDDLE }
    fun time(ms: Long): String {
        if (ms < 0) return "--:--"
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }
    fun size(bytes: Long?): String {
        if (bytes == null || bytes < 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var n = 0
        while (v >= 1024 && n < units.lastIndex) { v /= 1024; n++ }
        return if (n == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", v, units[n])
    }
}

/** One vector stroke language; no bitmap assets or icon library. */
class SymbolView(c: Context, var symbol: String, var tint: Int = Ui.ink) : View(c) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.scale(width / 24f, height / 24f)
        pen.color = tint; pen.style = Paint.Style.STROKE
        fun line(a: Float, b: Float, x: Float, y: Float) = canvas.drawLine(a, b, x, y, pen)
        fun poly(vararg points: Float) {
            path.reset(); path.moveTo(points[0], points[1]); var i = 2
            while (i < points.size) { path.lineTo(points[i], points[i + 1]); i += 2 }
            canvas.drawPath(path, pen)
        }
        when (symbol) {
            "drive" -> { canvas.drawRoundRect(3f, 5f, 21f, 19f, 3f, 3f, pen); line(3f, 14f, 21f, 14f); canvas.drawCircle(17f, 16.5f, .5f, pen) }
            "folder" -> poly(3f, 19f, 3f, 5f, 9f, 5f, 12f, 8f, 21f, 8f, 21f, 19f, 3f, 19f)
            "video" -> { canvas.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, pen); poly(10f, 8f, 16f, 12f, 10f, 16f, 10f, 8f) }
            "play" -> { pen.style = Paint.Style.FILL; poly(8f, 5f, 19f, 12f, 8f, 19f, 8f, 5f) }
            "pause" -> { pen.style = Paint.Style.FILL; canvas.drawRoundRect(6f, 5f, 10f, 19f, 1f, 1f, pen); canvas.drawRoundRect(14f, 5f, 18f, 19f, 1f, 1f, pen) }
            "back" -> { poly(12f, 5f, 5f, 12f, 12f, 19f); line(5f, 12f, 20f, 12f) }
            "next" -> poly(9f, 5f, 16f, 12f, 9f, 19f)
            "rewind" -> { poly(12f, 6f, 5f, 12f, 12f, 18f); poly(20f, 6f, 13f, 12f, 20f, 18f) }
            "forward" -> { poly(4f, 6f, 11f, 12f, 4f, 18f); poly(12f, 6f, 19f, 12f, 12f, 18f) }
            "refresh" -> { canvas.drawArc(4f, 4f, 20f, 20f, 45f, 290f, false, pen); poly(20f, 4f, 20f, 10f, 14f, 10f) }
            "settings" -> { canvas.drawCircle(12f, 12f, 7f, pen); canvas.drawCircle(12f, 12f, 2.5f, pen); for (n in 0..7) { canvas.save(); canvas.rotate(n * 45f, 12f, 12f); line(12f, 2f, 12f, 5f); canvas.restore() } }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "check" -> poly(5f, 12f, 10f, 17f, 20f, 6f)
        }
        canvas.restore()
    }
}
