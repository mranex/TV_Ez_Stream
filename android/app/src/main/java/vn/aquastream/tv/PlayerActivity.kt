package vn.aquastream.tv

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlin.math.abs

@UnstableApi
class PlayerActivity : Activity() {
    internal var player: ExoPlayer? = null
        private set
    internal val seeks = SeekAccumulator()
    internal val uiState = PlayerUiState()
    internal var scrubPosition: Long? = null
        private set
    private lateinit var view: PlayerView
    internal lateinit var osd: PlayerOsd
        private set
    private lateinit var feedback: TextView
    private lateinit var anchor: View
    private lateinit var settings: PlayerSettings
    internal lateinit var intelligence: PlaybackIntelligence
        private set
    private lateinit var playbackNotice: TextView
    private var intelligenceModal = false
    private val modalKeyUps = HashSet<Int>()
    private var restoreChosen = false
    private val hidePlaybackNotice = Runnable { playbackNotice.visibility = View.GONE }
    private var settingsDialog: Dialog? = null
    internal val settingsDecor: View? get() = settingsDialog?.window?.decorView
    private val handler = Handler(Looper.getMainLooper())
    private var resumePosition = 0L
    private var resumePlay = true
    private var unmutedVolume = 1f
    private var appMuted = false
    private var lastKeyTime = 0L
    private var renderedSinceSeek = false
    private var failed = false
    private var seekCount = 0
    private var burstDelta = 0L
    private var feedbackUntil = 0L
    private var lastUiUpdate = 0L
    private var started = false
    private var bufferSession: SmartBufferSession? = null
    internal var seekDiagnostics: SeekDiagnostics? = null
        private set
    private var dispatchInputNs=0L
    private val consumedKeyUps = HashSet<Int>()
    private var revealKey: Int? = null
    private var heldConfirmTarget: View? = null
    private var lastBottomFocus: View? = null
    private var scrubDirty = false
    private val commitScrub = Runnable { commitScrubTarget() }
    @Volatile internal var decodedFrameUs: Long = Long.MIN_VALUE
        private set

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val now = SystemClock.elapsedRealtime()
            // Original asynchronous short-seek settlement rule, unchanged.
            if (seeks.target != null && renderedSinceSeek && now - lastKeyTime >= 700
                && p.playbackState == Player.STATE_READY && abs(p.currentPosition - (seeks.target ?: 0)) < 2000) {
                Log.i("AquaSeek", "settled target=${seeks.target} position=${p.currentPosition}")
                seeks.settled()
            }
            intelligence.heartbeat()
            if (!failed && !intelligenceModal && uiState.expire(now, p.isPlaying)) hideOsd()
            if (feedback.visibility == View.VISIBLE && now >= feedbackUntil) feedback.visibility = View.GONE
            if (now - lastUiUpdate >= 250) { osd.update(p, seeks.target, scrubPosition, settings); lastUiUpdate = now }
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); Ui.fullscreen(this)
        resumePosition = state?.getLong("position") ?: 0
        resumePlay = state?.getBoolean("playing") ?: true
        unmutedVolume = state?.getFloat("unmuted_volume", 1f) ?: 1f
        appMuted = state?.getBoolean("app_muted", false) ?: false
        restoreChosen = state?.getBoolean("media_chosen", state.containsKey("position")) ?: false
        for (key in listOf("file_id", "name", "parent_id")) state?.getString("active_$key")?.let { intent.putExtra(key, it) }
        state?.let { intent.putExtra("offset", it.getInt("active_offset", intent.getIntExtra("offset",0))) }
        settings = PlayerSettings(this)
        if(state!=null) intent.putExtra("suppress_auto_next",state.getBoolean("suppress_auto_next",false))
        intelligence = PlaybackIntelligence(this, settings)
        val root = FrameLayout(this)
        view = PlayerView(this).apply { useController = false; isFocusable = false; keepScreenOn = true }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        anchor = View(this).apply { isFocusable = true; isFocusableInTouchMode = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))
        osd = PlayerOsd(this, intent.getStringExtra("name") ?: "Video",
            back = { intelligence.userStopped(); finish() }, settings = ::openSettings, toggle = ::toggle,
            rewind = { move(false); touch() }, forward = { move(true); touch() }, scrub = ::beginScrub, mute = ::toggleMute)
        root.addView(osd, FrameLayout.LayoutParams(-1, -1))
        feedback = Ui.text(this, "", 24f, Ui.ink, medium = true).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
            background = Ui.rounded(context, 0xE6191C1E.toInt(), 16)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 20), Ui.dp(context, 14))
        }
        root.addView(feedback, FrameLayout.LayoutParams(Ui.dp(this, 190), -2, Gravity.CENTER))
        playbackNotice = Ui.text(this, "", 17f, Ui.ink).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
            background = SmokySurface(this@PlayerActivity, 12f)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 20), Ui.dp(context, 14))
        }
        root.addView(playbackNotice, FrameLayout.LayoutParams(Ui.dp(this, 400), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dp(this@PlayerActivity, 72) })
        setContentView(root); anchor.requestFocus()
    }

    override fun onStart() {
        super.onStart(); started = true
        if (intent.getStringExtra("file_id").isNullOrBlank()) { finish(); return }
        val diagnostic = SeekDiagnostics(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
            java.io.File(filesDir,"seek-traces"))
        seekDiagnostics = diagnostic
        val http = BridgeDataSource.factory(diagnostic)
        val buffering = SmartBufferSession(this)
        bufferSession = buffering
        val renderers = if (diagnostic.enabledForProbe && intent.getBooleanExtra("seek_probe",false)) {
            // Debug source is absent from release. Production pipeline/configuration is unchanged.
            Class.forName("vn.aquastream.tv.SeekProbeRenderers").getConstructor(android.content.Context::class.java,
                SeekDiagnostics::class.java,Boolean::class.javaPrimitiveType).newInstance(this,diagnostic,
                    SeekTuning.useAsyncCodec(android.os.Build.VERSION.SDK_INT,android.os.Build.DEVICE)) as DefaultRenderersFactory
        } else DefaultRenderersFactory(this)
        renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if(diagnostic.enabledForProbe && intent.getBooleanExtra("seek_probe",false))
            renderers.javaClass.getMethod("setProbeOperatingRate",Float::class.javaPrimitiveType).invoke(renderers,intent.getFloatExtra("seek_probe_rate",0f))
        if(SeekTuning.useAsyncCodec(android.os.Build.VERSION.SDK_INT,android.os.Build.DEVICE)) renderers.forceEnableMediaCodecAsynchronousQueueing()
        val p = ExoPlayer.Builder(this, renderers).setLoadControl(buffering.loadControl)
            .experimentalSetDynamicSchedulingEnabled(diagnostic.enabledForProbe && intent.getBooleanExtra("seek_probe_dynamic",false))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        player = p
        if(diagnostic.enabledForProbe) osd.onLoadingVisibilityChanged={diagnostic.spinner(it)}
        p.volume = if (appMuted) 0f else unmutedVolume
        failed = false
        decodedFrameUs = Long.MIN_VALUE
        p.setVideoFrameMetadataListener { presentationTimeUs, releaseNs, _, _ ->
            decodedFrameUs = presentationTimeUs; diagnostic.frame(presentationTimeUs, releaseNs)
        }
        p.setSeekParameters(SeekParameters.EXACT)
        view.player = p
        p.addListener(diagnostic)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) p.addAnalyticsListener(diagnostic)
        buffering.start(p)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedSinceSeek = true
                Log.i("AquaSeek", "frame target=${seeks.target} position=${p.currentPosition}")
            }
            override fun onPlaybackStateChanged(state: Int) {
                osd.playbackState(state)
                Log.i("AquaPlayer", "state=$state position=${p.currentPosition}")
                if (state == Player.STATE_ENDED && uiState.mode == PlayerUiState.Mode.WATCH) enter(PlayerUiState.Mode.INFO)
            }
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                val reason = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "TV không giải mã được video này."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Định dạng không hỗ trợ hoặc video bị lỗi."
                    else -> "Không đọc được video từ máy tính."
                }
                enter(PlayerUiState.Mode.INFO); osd.error(reason, error.errorCodeName)
                Log.e("AquaPlayer", error.errorCodeName, error)
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i("AquaPlayer", "decoder=$decoderName")
            }
        })
        intelligence.start(p, if (restoreChosen || intelligence.chosen) resumePosition else null, resumePlay)
        enter(PlayerUiState.Mode.WATCH); handler.post(tick)
    }

    internal fun playbackMessage(message: String) {
        playbackNotice.text = message; playbackNotice.visibility = View.VISIBLE
        handler.removeCallbacks(hidePlaybackNotice); handler.postDelayed(hidePlaybackNotice, 3200)
    }
    internal fun consumeDialogRelease(event: KeyEvent): Boolean {
        if(event.keyCode !in modalKeyUps) return false
        if(event.action==KeyEvent.ACTION_UP) modalKeyUps.remove(event.keyCode)
        return true
    }
    internal fun blockDialogRelease(code: Int) { modalKeyUps.add(code) }
    internal fun dialogKeyReleased(code: Int) { modalKeyUps.remove(code) }
    internal fun beginPlaybackDialog() { intelligenceModal = true; anchor.requestFocus() }
    internal fun endPlaybackDialog() { intelligenceModal = false; anchor.requestFocus() }
    internal fun closeSettingsForNext() { SettingsPanel.dismissPopups(this); settingsDialog?.dismiss() }
    internal fun playbackDialogCancelled() { if (started) focusBottom(osd.playButton) }
    internal fun playbackStarted() { restoreChosen = true; hideOsd() }
    internal fun newPlaybackFile(file: EpisodeFile, parent: String?) {
        seekDiagnostics?.cancel()
        handler.removeCallbacks(commitScrub); scrubDirty = false; scrubPosition = null; seeks.settled()
        renderedSinceSeek = false; decodedFrameUs = Long.MIN_VALUE; failed = false
        intent.putExtra("file_id", file.id).putExtra("name", file.name).putExtra("parent_id", parent).putExtra("offset", file.offset)
        osd.setFilename(file.name); osd.clearError(); restoreChosen = false
        setResult(RESULT_OK, android.content.Intent().putExtra("file_id", file.id).putExtra("offset", file.offset))
    }

    private fun step(key: String) = getSharedPreferences("seek", MODE_PRIVATE).getInt(key, 2).coerceAtLeast(1)
    private fun touch() = uiState.touch(SystemClock.elapsedRealtime(), settings.timeoutSeconds * 1000L)
    private fun enter(mode: PlayerUiState.Mode) {
        uiState.show(mode, SystemClock.elapsedRealtime(), settings.timeoutSeconds * 1000L)
        osd.presentation(mode)
        when (mode) {
            PlayerUiState.Mode.WATCH -> anchor.requestFocus()
            PlayerUiState.Mode.INFO -> { lastBottomFocus = osd.playButton; osd.playButton.requestFocus() }
            PlayerUiState.Mode.SCRUB -> osd.timeline.requestFocus()
            else -> Unit
        }
        player?.let { osd.update(it, seeks.target, scrubPosition, settings) }
        Log.d("AquaOsd", "mode=$mode")
    }
    private fun hideOsd() { leaveTimeline(); uiState.hide(); osd.presentation(uiState.mode); anchor.requestFocus() }
    private fun toggle() {
        val p = player ?: return
        intelligence.userPlaying()
        if (failed) { failed = false; osd.clearError(); p.prepare(); p.playWhenReady = resumePlay }
        else if (p.playbackState == Player.STATE_ENDED) { seeks.settled(); p.seekTo(0); p.play() }
        else p.playWhenReady = !p.playWhenReady
        if (uiState.mode == PlayerUiState.Mode.WATCH) enter(PlayerUiState.Mode.INFO) else touch()
        osd.update(p, seeks.target, scrubPosition, settings)
    }

    internal fun move(forward: Boolean) {
        val inputNs = dispatchInputNs.takeIf{it>0} ?: SystemClock.elapsedRealtimeNanos()
        val p = player ?: return
        if (failed) return
        if (!p.isCurrentMediaItemSeekable) {
            feedback.text = "Video chưa sẵn sàng để tua"
            feedback.visibility = View.VISIBLE; feedbackUntil = SystemClock.elapsedRealtime() + 1800
            return
        }
        val delta = step(if (forward) "forward" else "back").toLong() * 1000 * if (forward) 1 else -1
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val previous = seeks.target ?: p.currentPosition
        val target = seeks.step(delta, p.currentPosition, duration)
        renderedSinceSeek = false
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyTime > 700) burstDelta = 0
        burstDelta += target - previous
        lastKeyTime = now
        touch()
        seekCount++
        Log.i("AquaSeek", "key=$seekCount delta=$delta target=$target actual=${p.currentPosition}")
        seekDiagnostics?.begin(target, p, inputNs)
        seekDiagnostics?.uiContext(uiState.mode.name,osd.loadingVisible)
        seekDiagnostics?.invoked()
        p.seekTo(target)
        if (settings.feedback) {
            feedback.text = "${if (burstDelta >= 0) "+" else "−"}${abs(burstDelta) / 1000}s\n${Ui.time(target)}"
            (feedback.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.CENTER_VERTICAL or if (forward) Gravity.RIGHT else Gravity.LEFT
                leftMargin = Ui.dp(this@PlayerActivity, 54); rightMargin = Ui.dp(this@PlayerActivity, 54)
            }.also { feedback.layoutParams = it }
            if (feedback.visibility != View.VISIBLE) {
                feedback.alpha = 0f; feedback.translationY = Ui.dp(this, 6).toFloat()
                feedback.animate().alpha(1f).translationY(0f).setDuration(150).start()
            }
            feedback.visibility = View.VISIBLE; feedbackUntil = now + 1000
        }
        osd.update(p, seeks.target, scrubPosition, settings)
    }

    private fun toggleMute() {
        val p = player ?: return
        if (p.volume > 0f) { unmutedVolume = p.volume; appMuted = true; p.volume = 0f }
        else { appMuted = false; p.volume = unmutedVolume }
        touch(); osd.update(p, seeks.target, scrubPosition, settings)
    }

    internal fun openSettings() {
        if (settingsDialog?.isShowing == true) return
        leaveTimeline(); enter(PlayerUiState.Mode.SETTINGS)
        settingsDialog = SettingsPanel.show(this, changed = {
            intelligence.applySpeed()
            if (!settings.feedback) feedback.visibility = View.GONE
            touch(); player?.let { osd.update(it, seeks.target, scrubPosition, settings) }
        }, dismissed = {
            settingsDialog = null
            if (started) focusBottom(osd.settingsButton)
        })
    }
    /** Timeline focus is the scrub mode; there is no confirmation step. */
    internal fun beginScrub() {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable || p.duration <= 0 || failed) return
        currentFocus?.takeIf { it in osd.bottomControls }?.let { lastBottomFocus = it }
        osd.timeline.nextFocusDownId = (lastBottomFocus ?: osd.playButton).id
        scrubPosition = seeks.target ?: p.currentPosition
        scrubDirty = false
        enter(PlayerUiState.Mode.SCRUB)
    }
    private fun scrub(direction: Int) {
        val p = player ?: return
        val amount = (p.duration / 100).coerceAtLeast(5000)
        scrubPosition = ((scrubPosition ?: p.currentPosition) + direction * amount).coerceIn(0, p.duration.coerceAtLeast(0))
        scrubDirty = true
        osd.update(p, seeks.target, scrubPosition, settings)
        handler.removeCallbacks(commitScrub)
        handler.postDelayed(commitScrub, 180)
    }
    private fun commitScrubTarget() {
        if (!scrubDirty) return
        scrubPosition?.let { target ->
            seeks.settled(); renderedSinceSeek = false; lastKeyTime = SystemClock.elapsedRealtime()
            player?.let { seekDiagnostics?.begin(target, it); seekDiagnostics?.uiContext(uiState.mode.name,osd.loadingVisible); seekDiagnostics?.invoked(); it.seekTo(target) }
            Log.i("AquaOsd", "scrub=$target")
        }
        scrubDirty = false
    }
    private fun leaveTimeline() {
        handler.removeCallbacks(commitScrub)
        commitScrubTarget()
        scrubPosition = null
    }
    internal val focusRegion: PlayerUiState.FocusRegion
        get() = when (uiState.mode) {
            PlayerUiState.Mode.SCRUB -> PlayerUiState.FocusRegion.TIMELINE
            PlayerUiState.Mode.SETTINGS -> PlayerUiState.FocusRegion.SETTINGS
            PlayerUiState.Mode.CONTROLS -> if (osd.isTopFocused()) PlayerUiState.FocusRegion.TOP else PlayerUiState.FocusRegion.ACTIONS
            else -> PlayerUiState.FocusRegion.PLAYBACK
        }
    private fun focusTop() {
        leaveTimeline(); enter(PlayerUiState.Mode.CONTROLS); osd.backButton.requestFocus()
    }
    private fun focusBottom(button: View = lastBottomFocus ?: osd.playButton) {
        leaveTimeline(); enter(PlayerUiState.Mode.CONTROLS)
        lastBottomFocus = button; button.requestFocus()
    }
    private fun focusPlayback() = focusBottom()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val previous=dispatchInputNs
        dispatchInputNs=if(event.action==KeyEvent.ACTION_DOWN)SystemClock.elapsedRealtimeNanos() else 0L
        try { return handleRemoteKey(event) } finally { dispatchInputNs=previous }
    }
    private fun handleRemoteKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode in modalKeyUps) {
            if (event.action == KeyEvent.ACTION_UP) modalKeyUps.remove(event.keyCode)
            return true
        }
        if (intelligenceModal) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            if (down && event.repeatCount == 0) { intelligence.userStopped(); enter(PlayerUiState.Mode.INFO) }
            return true
        }
        val code = event.keyCode
        // A reveal gesture owns its entire press, including repeats and key-up.
        if (revealKey == code) {
            if (event.action == KeyEvent.ACTION_UP) revealKey = null
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) heldConfirmTarget = null
        if (event.action == KeyEvent.ACTION_UP && consumedKeyUps.remove(code)) return true
        fun consume(): Boolean { if (down) consumedKeyUps.add(code); return true }
        val directional = code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (uiState.mode == PlayerUiState.Mode.SETTINGS) return super.dispatchKeyEvent(event)
        if (down && (directional || confirm)) touch()
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (down && event.repeatCount == 0) toggle()
            return consume()
        }
        if (code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (down) { leaveTimeline(); if (uiState.mode == PlayerUiState.Mode.SCRUB) focusPlayback(); move(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }
            return consume()
        }
        if (!directional && !confirm) return super.dispatchKeyEvent(event)
        if (!down) return super.dispatchKeyEvent(event)
        if (uiState.mode == PlayerUiState.Mode.WATCH) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                move(code == KeyEvent.KEYCODE_DPAD_RIGHT)
                return consume()
            }
            enter(PlayerUiState.Mode.INFO)
            revealKey = code
            return true
        }
        if (confirm) {
            val target = currentFocus
            if (event.repeatCount == 0) {
                heldConfirmTarget = target
                if (uiState.mode != PlayerUiState.Mode.SCRUB) target?.performClick()
            } else if (target === heldConfirmTarget && (target === osd.rewindButton || target === osd.forwardButton)) {
                // Reuse the verified accumulator on every remote OK repeat.
                target?.performClick()
            }
            return consume()
        }
        val horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (uiState.mode == PlayerUiState.Mode.SCRUB) {
            if (horizontal) scrub(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            else if (event.repeatCount == 0) {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusBottom() else focusTop()
            }
            return consume()
        }
        if (currentFocus === osd.backButton) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.repeatCount == 0) beginScrub()
            return consume()
        }
        val index = osd.bottomControls.indexOf(currentFocus)
        if (index < 0) { focusBottom(osd.playButton); return consume() }
        if (horizontal) {
            val next = (index + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                .coerceIn(0, osd.bottomControls.lastIndex)
            focusBottom(osd.bottomControls[next])
        } else if (code == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0) beginScrub()
        // DOWN at the bottom edge stays on the same visible control.
        return consume()
    }
    @Deprecated("TV Back") override fun onBackPressed() {
        when (uiState.mode) {
            PlayerUiState.Mode.SETTINGS -> settingsDialog?.dismiss()
            PlayerUiState.Mode.WATCH -> { intelligence.userStopped(); finish() }
            else -> hideOsd()
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putFloat("unmuted_volume", unmutedVolume)
        out.putBoolean("app_muted", appMuted)
        out.putBoolean("suppress_auto_next", intelligence.suppressForRestore())
        out.putInt("active_offset", intent.getIntExtra("offset",0))
        out.putBoolean("media_chosen", intelligence.chosen)
        for (key in listOf("file_id", "name", "parent_id")) out.putString("active_$key", intent.getStringExtra(key))
        out.putLong("position", if (intelligence.chosen) intelligence.localPosition() else resumePosition)
        out.putBoolean("playing", player?.playWhenReady ?: resumePlay)
        super.onSaveInstanceState(out)
    }
    override fun onPause() { intelligence.save(); super.onPause() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); bufferSession?.trim(level) }
    override fun onLowMemory() { super.onLowMemory(); bufferSession?.lowMemory() }
    override fun onDestroy() { intelligence.destroy(); super.onDestroy() }
    override fun onStop() {
        restoreChosen = intelligence.chosen
        if (restoreChosen) resumePosition = intelligence.localPosition()
        player?.let { resumePlay = it.playWhenReady }
        intelligence.stop()
        started = false; SettingsPanel.dismissPopups(this); settingsDialog?.dismiss(); settingsDialog = null
        handler.removeCallbacksAndMessages(null)

        view.player = null
        bufferSession?.close(); bufferSession = null
        seekDiagnostics?.close(); seekDiagnostics = null
        player?.release(); player = null; seeks.settled()
        scrubPosition = null; super.onStop()
    }
}
