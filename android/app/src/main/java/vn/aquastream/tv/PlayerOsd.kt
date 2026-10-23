package vn.aquastream.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Native reproduction of mockup.png. No media IO or player construction belongs here. */
@UnstableApi
class PlayerOsd(c: Context, name: String, back: () -> Unit, settings: () -> Unit,
    toggle: () -> Unit, rewind: () -> Unit, forward: () -> Unit, scrub: () -> Unit,
    mute: () -> Unit) : FrameLayout(c) {
    val controls = MockupLayout(c).apply { visibility = View.GONE }
    val timeline = TimelineView(c)
    val playButton = PlayerControl(c, R.drawable.ic_player_pause, "Tạm dừng", true, click = toggle)
    val settingsButton = PlayerControl(c, R.drawable.ic_player_settings, "Cài đặt phát video", utility = true, surface = false, click = settings).apply { iconFraction = .69f }
    val muteButton = PlayerControl(c, R.drawable.ic_player_volume, "Tắt tiếng AquaStream", utility = true, click = mute).apply { iconFraction = .59f }
    val backButton = PlayerControl(c, R.drawable.ic_player_back, "Về danh sách file", utility = true, click = back)
    val rewindButton = PlayerControl(c, R.drawable.ic_player_rewind, "Tua lùi", click = rewind)
    val forwardButton = PlayerControl(c, R.drawable.ic_player_forward, "Tua tiến", click = forward)
    val bottomControls = listOf(muteButton, rewindButton, playButton, forwardButton, settingsButton)
    internal val current = label("00:00")
    internal val duration = label("--:--", Gravity.END)
    internal val timestamp = label("00:00 / --:--", Gravity.CENTER)
    internal val remaining = label("Còn --:--", Gravity.CENTER)
    private val notice = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
    private val noticeTitle = Ui.text(c, "", 22f, medium = true).apply { gravity = Gravity.CENTER }
    private val noticeBody = Ui.text(c, "", 16f, Ui.muted).apply { gravity = Gravity.CENTER; maxLines = 4 }
    private val loading = ProgressBar(c).apply { visibility = View.GONE; indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }
    private val loadingPresentation=LoadingPresentation()
    internal var onLoadingVisibilityChanged:((Boolean)->Unit)?=null
    internal val loadingVisible get()=loading.visibility==View.VISIBLE
    private var lastPlaybackState=Player.STATE_IDLE
    private val revealLoading=Runnable { playbackState(lastPlaybackState) }
    fun playbackState(state:Int) {
        lastPlaybackState=state
        loading.removeCallbacks(revealLoading)
        val now=android.os.SystemClock.elapsedRealtime()
        val buffering=!hasError && state==Player.STATE_BUFFERING
        val visible=loadingPresentation.update(buffering,now)
        if(visible!=loadingVisible) { loading.visibility=if(visible)View.VISIBLE else View.GONE; onLoadingVisibilityChanged?.invoke(visible) }
        if(buffering && !visible) loading.postDelayed(revealLoading,loadingPresentation.remaining(now))
    }
    override fun onDetachedFromWindow() {
        loading.removeCallbacks(revealLoading);loadingPresentation.update(false,0)
        super.onDetachedFromWindow()
    }
    private lateinit var filename: TextView
    fun setFilename(value: String) {
        filename.text = value; filename.contentDescription = value
        timeline.positionMs = 0; timeline.durationMs = 0; timeline.bufferedStartMs = 0; timeline.bufferedMs = 0; timeline.previewMs = null
        timeline.invalidate()
    }
    private var hasError = false
    private var showing = false
    private fun label(text: String, align: Int = Gravity.START) = Ui.text(context, text, 16f, 0xFFE2E5EA.toInt()).apply {
        gravity = align or Gravity.CENTER_VERTICAL; includeFontPadding = false; maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    init {
        isFocusable = false
        addView(controls, LayoutParams(-1, -1))
        val topShade = View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xA6000000.toInt(), Color.TRANSPARENT)) }
        val bottomShade = View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0x4D0C1722, 0xB8000000.toInt())) }
        controls.place(topShade, 0f, 0f, 960f, 130f)
        controls.place(bottomShade, 0f, 330f, 960f, 210f)
        controls.place(PlayerAtmosphere(c), 0f, 0f, 960f, 540f)
        controls.place(backButton, 29f, 18f, 46f, 46f)
        val title = label(name).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", 0); Ui.ellipsize(this); contentDescription = name; setTextColor(Color.WHITE) }
        filename = title
        controls.place(title, 94f, 20f, 834f, 42f, 20f)
        controls.place(current, 32f, 389f, 320f, 24f, 16f)
        controls.place(duration, 690f, 389f, 238f, 24f, 16f)
        controls.place(timeline, 32f, 407f, 896f, 20f)
        timeline.setOnClickListener { scrub() }
        // Each circle is independently anchored on the screen, never in a shared toolbar.
        controls.place(rewindButton, 368f, 433f, 72f, 72f)
        controls.place(playButton, 438f, 425f, 84f, 84f)
        controls.place(forwardButton, 520f, 433f, 72f, 72f)
        val left = MockupLayout(c, 190f, 38f).apply { background = PlayerPillDrawable() }
        left.place(muteButton, 5f, 0f, 38f, 38f)
        left.place(View(c).apply { setBackgroundColor(0x709DA6AF) }, 62f, 10f, .6f, 18f)
        timestamp.setTextColor(0xFFD4DEEC.toInt())
        left.place(timestamp, 75f, 0f, 104f, 38f, 12.5f)
        controls.place(left, 32f, 452f, 190f, 38f)
        val right = MockupLayout(c, 148f, 38f).apply { background = PlayerPillDrawable() }
        right.place(settingsButton, 4f, 0f, 42f, 38f)
        right.place(View(c).apply { setBackgroundColor(0x709DA6AF) }, 53f, 10f, .6f, 18f)
        remaining.setTextColor(0xFFD4DEEC.toInt())
        right.place(remaining, 62f, 0f, 78f, 38f, 12.5f)
        controls.place(right, 780f, 452f, 148f, 38f)
        // One spatial row, matching the visual left-to-right order.
        bottomControls.forEachIndexed { index, button ->
            button.nextFocusLeftId = bottomControls[(index-1).coerceAtLeast(0)].id
            button.nextFocusRightId = bottomControls[(index+1).coerceAtMost(bottomControls.lastIndex)].id
            button.nextFocusUpId = timeline.id; button.nextFocusDownId = button.id
        }
        timeline.nextFocusUpId = backButton.id; timeline.nextFocusDownId = playButton.id
        backButton.nextFocusDownId = timeline.id
        backButton.nextFocusLeftId = backButton.id; backButton.nextFocusRightId = backButton.id
        backButton.nextFocusUpId = backButton.id
        notice.setPadding(Ui.dp(c, 28), Ui.dp(c, 22), Ui.dp(c, 28), Ui.dp(c, 22))
        notice.background = Ui.rounded(c, 0xE6191C1E.toInt())
        notice.addView(noticeTitle); notice.addView(Ui.gap(c, height = 10)); notice.addView(noticeBody)
        addView(notice, LayoutParams(Ui.dp(c, 540), -2, Gravity.CENTER))
        addView(loading, LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36), Gravity.CENTER))
    }
    fun isTopFocused() = backButton.hasFocus()
    fun presentation(mode: PlayerUiState.Mode) {
        val visible = mode != PlayerUiState.Mode.WATCH
        controls.descendantFocusability = if (visible) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (visible != showing) {
            showing = visible; controls.animate().cancel()
            if (visible) {
                controls.visibility = View.VISIBLE; controls.alpha = 0f
                controls.animate().alpha(1f).setDuration(180).start()
            } else controls.animate().alpha(0f).setDuration(210)
                .withEndAction { if (!showing) controls.visibility = View.GONE }.start()
        }
    }
    fun update(p: Player, pending: Long?, preview: Long?, settings: PlayerSettings) {
        playbackState(p.playbackState)
        if (controls.visibility != View.VISIBLE) return
        val actual = p.currentPosition.coerceAtLeast(0)
        val known = p.duration != C.TIME_UNSET && p.duration >= 0
        val total = if (known) Ui.time(p.duration) else "--:--"
        current.text = Ui.time(preview ?: actual); current.contentDescription = if (preview != null) "Đích tua ${Ui.time(preview)}" else "Vị trí ${Ui.time(actual)}"
        duration.text = total
        timestamp.text = "${Ui.time(actual)} / $total"
        remaining.text = "Còn ${if (known) Ui.time(PlaybackMath.remaining(p.duration, actual, p.playbackParameters.speed) ?: 0L) else "--:--"}"
        timeline.positionMs = actual; timeline.durationMs = p.duration.coerceAtLeast(0)
        timeline.bufferedStartMs = actual.coerceAtMost(timeline.durationMs)
        timeline.bufferedMs = BufferedWindow.end(actual,timeline.durationMs,p.bufferedPosition,p.totalBufferedDuration,p.playbackState==Player.STATE_READY)
        timeline.previewMs = preview; timeline.invalidate()
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        playButton.icon(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        playButton.contentDescription = if (hasError) "Thử phát lại" else if (playing) "Tạm dừng" else "Phát"
        rewindButton.seconds = settings.step(false); forwardButton.seconds = settings.step(true)
        rewindButton.contentDescription = "Tua lùi ${settings.step(false)} giây"; forwardButton.contentDescription = "Tua tiến ${settings.step(true)} giây"
        muteButton.icon(if (p.volume == 0f) R.drawable.ic_player_muted else R.drawable.ic_player_volume)
        muteButton.contentDescription = if (p.volume == 0f) "Bật tiếng AquaStream" else "Tắt tiếng AquaStream"
    }
    fun error(reason: String, code: String) {
        hasError = true; playbackState(Player.STATE_IDLE); notice.visibility = View.VISIBLE
        noticeTitle.text = reason; noticeBody.text = "$code\nOK thử lại · Back ẩn điều khiển, nhấn lại để thoát"
    }
    fun clearError() { hasError = false; notice.visibility = View.GONE }
}
