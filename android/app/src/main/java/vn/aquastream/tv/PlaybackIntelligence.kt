package vn.aquastream.tv

import android.app.Dialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs

/** One player, one small IO queue, one current folder. Persistence lives on the PC. */
@UnstableApi
class PlaybackIntelligence(private val host: PlayerActivity, private val settings: PlayerSettings) {
    private val io=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var p: ExoPlayer?=null
    @Volatile private var generation=0
    @Volatile private var active=false
    private var loading=true
    private var ready=false
    private var saveAllowed=false
    internal var chosen=false
        private set
    internal var file=EpisodeFile(host.intent.getStringExtra("file_id") ?: "",host.intent.getStringExtra("name") ?: "Video",host.intent.getIntExtra("offset",0))
        private set
    private var parent=host.intent.getStringExtra("parent_id")
    private var next: EpisodeFile?=null
    private var listingReady=false
    private var endedHandled=false
    private var manualStopped=host.intent.getBooleanExtra("suppress_auto_next",false)
    private var pendingPosition: Long?=null
    private var desiredPlay=true
    private var dialog: Dialog?=null
    private var countdown: Runnable?=null
    private var lastSaveAt=0L
    private var lastSavedFile=""
    private var lastSavedPosition=-10000L
    private var lastCompleted=false
    private var lastNotice=0L
    private var lastSeekAt=0L
    private var bufferEvents=ArrayList<Long>()
    private var seekSave: Runnable?=null
    internal var droppedFrames=0
        private set

    fun start(player: ExoPlayer, localPosition: Long?, play: Boolean) {
        p=player; active=true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if(state==Player.STATE_READY) applyTimeline()
                if(state==Player.STATE_ENDED && ready) ended()
                if(state==Player.STATE_BUFFERING && ready && player.playWhenReady && player.playbackParameters.speed>1f && SystemClock.elapsedRealtime()-lastSeekAt>2500) {
                    val now=SystemClock.elapsedRealtime(); bufferEvents.add(now); bufferEvents.removeAll{now-it>30000}
                    if(bufferEvents.size>=3) warn("Thiếu buffer ở ${PlaybackMath.speedLabel((player.playbackParameters.speed*100).toInt())}; hãy giảm tốc độ.")
                }
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { applyTimeline() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if(!playWhenReady) save() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if(reason==Player.DISCONTINUITY_REASON_SEEK) lastSeekAt=SystemClock.elapsedRealtime()
                if(reason==Player.DISCONTINUITY_REASON_SEEK && ready) {
                    seekSave?.let { main.removeCallbacks(it) }
                    val stamp=generation
                    val task=object : Runnable {
                        override fun run() {
                            if(!active || generation!=stamp) return
                            if(player.playbackState==Player.STATE_BUFFERING) main.postDelayed(this,500) else save()
                        }
                    }
                    seekSave=task; main.postDelayed(task,900)
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFrames+=count
                if(player.playbackParameters.speed>1f && count>=12 && elapsedMs>=1000) warn("TV đang bỏ khung hình ở tốc độ cao; hãy giảm tốc độ.")
            }
        })
        applySpeed(true)
        load(file,parent,localPosition,play)
    }
    private fun warn(message: String) {
        val now=SystemClock.elapsedRealtime()
        if(now-lastNotice<15000 && lastNotice!=0L) return
        lastNotice=now; host.playbackMessage(message)
    }
    private fun load(target: EpisodeFile, folder: String?, localPosition: Long?=null, play: Boolean=true) {
        val stamp=++generation
        loading=true; ready=false; saveAllowed=false; chosen=false; file=target; parent=folder
        next=null; listingReady=false; pendingPosition=null; bufferEvents.clear()
        if(localPosition==null) { manualStopped=false; endedHandled=false }
        p?.stop(); host.newPlaybackFile(target,folder)
        io.execute {
            try {
                val result=BridgeApi.get("/progress/${target.id}")
                val folderId=folder ?: result.optString("parent_id").takeIf{it.isNotBlank()}
                val progress=result.optJSONObject("progress")
                main.post {
                    if(!active || stamp!=generation) return@post
                    parent=folderId; saveAllowed=true
                    val position=progress?.optLong("position_ms") ?: 0L
                    val duration=progress?.optLong("duration_ms") ?: 0L
                    val unfinished=progress!=null && !progress.optBoolean("completed") && position>=15000 && (duration<=0 || position<duration-1000)
                    if(localPosition!=null) prepare(localPosition,play)
                    else if(unfinished) {
                        val handle=PlaybackDialogs.choice(host,"Tiếp tục xem?","Bạn đã xem đến ${Ui.time(position)}","Tiếp tục","Xem từ đầu",
                            first={ prepare(position,true) },
                            second={ reset(duration); prepare(0,true) },
                            cancel={ host.finish() })
                        dialog=handle.dialog
                    } else prepare(0,play)
                }
                val candidate=try { folderId?.let { findNext(it,target,stamp) } } catch(_: Exception) { null }
                main.post { if(active && stamp==generation) { next=candidate; listingReady=true } }
            } catch(e: Exception) {
                main.post {
                    if(!active || stamp!=generation) return@post
                    saveAllowed=false
                    warn("Không đọc được tiến độ từ PC. Phiên này chưa lưu tiến độ.")
                    prepare(localPosition ?: 0L,play)
                }
            }
        }
    }
    private fun prepare(position: Long, play: Boolean) {
        val player=p ?: return
        if(!active) return
        dialog=null; pendingPosition=position.coerceAtLeast(0); desiredPlay=play
        chosen=true; ready=false; loading=true; lastSaveAt=SystemClock.elapsedRealtime()
        host.newPlaybackFile(file,parent)
        player.setMediaItem(MediaItem.fromUri("${BridgeApi.BASE}/stream/${file.id}"))
        // Fresh playback keeps the original pipeline's immediate play intent. Resume waits
        // only for a real seekable timeline, never for a paused decoder to become READY.
        player.playWhenReady=if(position==0L) play else false; player.prepare()
    }
    private fun applyTimeline() {
        val player=p ?: return
        val position=pendingPosition ?: return
        if(player.currentTimeline.isEmpty) return
        val window=player.currentTimeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        if(window.isPlaceholder) return
        if(position>0 && !player.isCurrentMediaItemSeekable && player.playbackState!=Player.STATE_READY) {
            player.playWhenReady=desiredPlay; return
        }
        android.util.Log.i("AquaResume","timeline position=$position duration=${player.duration} state=${player.playbackState}")
        pendingPosition=null
        if(position>0) {
            if(player.isCurrentMediaItemSeekable) player.seekTo(if(player.duration>0) position.coerceAtMost((player.duration-1000).coerceAtLeast(0)) else position)
            else { saveAllowed=false; warn("Video không hỗ trợ tua; không thể áp dụng mốc đã lưu.") }
        }
        loading=false; ready=true
        player.playWhenReady=desiredPlay
        host.playbackStarted()
    }
    private fun reset(duration: Long) {
        val token=file.id
        io.execute { try { BridgeApi.put("/progress/$token",JSONObject().put("position_ms",0).put("duration_ms",duration.coerceAtLeast(0)).put("completed",false).put("reset",true)) } catch(_: Exception) {} }
    }
    fun heartbeat() {
        val player=p ?: return
        if(player.isPlaying && SystemClock.elapsedRealtime()-lastSaveAt>=10000) save()
    }
    fun save(completed: Boolean=false) {
        val player=p ?: return
        if(!active || !ready || loading || !saveAllowed || !chosen) return
        if(player.playbackState!=Player.STATE_READY && !(completed && player.playbackState==Player.STATE_ENDED)) return
        val position=(host.seeks.target ?: player.currentPosition).coerceAtLeast(0)
        val duration=player.duration.takeIf{it!=C.TIME_UNSET && it>0} ?: 0L
        if(!completed && position==0L) return
        if(lastSavedFile==file.id && abs(position-lastSavedPosition)<500 && lastCompleted==completed) return
        lastSaveAt=SystemClock.elapsedRealtime(); lastSavedFile=file.id; lastSavedPosition=position; lastCompleted=completed
        val token=file.id
        val data=JSONObject().put("position_ms",position).put("duration_ms",duration).put("completed",completed)
        io.execute {
            try { BridgeApi.put("/progress/$token",data) }
            catch(_: Exception) { main.post { if(active) warn("Chưa lưu được tiến độ: kiểm tra kết nối PC.") } }
        }
    }
    fun applySpeed(silent: Boolean=false) {
        val player=p ?: return
        val requested=settings.speedHundredths/100f
        if(abs(player.playbackParameters.speed-requested)<.001f) return
        if(!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            if(!silent) warn("Bộ phát hiện không hỗ trợ thay đổi tốc độ.")
            settings.speedHundredths=(player.playbackParameters.speed*100).toInt(); return
        }
        try {
            player.setPlaybackParameters(PlaybackParameters(requested,1f))
            val actual=player.playbackParameters.speed
            if(abs(actual-requested)>.005f) { settings.speedHundredths=(actual*100).toInt(); warn("Bộ phát không áp dụng được tốc độ yêu cầu.") }
            else if(!silent) host.playbackMessage("Tốc độ: ${PlaybackMath.speedLabel(settings.speedHundredths)}")
        } catch(_: Exception) { settings.speedHundredths=(player.playbackParameters.speed*100).toInt(); warn("TV không áp dụng được tốc độ yêu cầu.") }
    }
    private fun findNext(folder: String, current: EpisodeFile, stamp: Int): EpisodeFile? {
        if(EpisodeResolver.key(current.name)==null) return null
        var offset=0; var best: EpisodeFile?=null
        while(offset<=100000) {
            if(!active || generation!=stamp) return null
            val response=BridgeApi.get("/browse?id=${URLEncoder.encode(folder,"UTF-8")}&offset=$offset")
            val entries=response.getJSONArray("entries"); val candidates=ArrayList<EpisodeFile>()
            for(i in 0 until entries.length()) {
                val e=entries.getJSONObject(i)
                if(!e.getBoolean("directory") && e.getString("id")!=current.id) candidates.add(EpisodeFile(e.getString("id"),e.getString("name"),offset))
            }
            val candidate=EpisodeResolver.next(current.name,candidates)
            best=EpisodeResolver.next(current.name,listOfNotNull(best,candidate))
            if(response.isNull("next_offset")) return best
            offset=response.getInt("next_offset")
        }
        return best
    }
    private fun ended() {
        if(p?.playWhenReady!=true || manualStopped) return
        save(true)
        if(endedHandled || manualStopped || !settings.autoNext) return
        endedHandled=true; val stamp=generation; val current=file; val folder=parent ?: return
        io.execute {
            try {
                val candidate=if(listingReady) next else findNext(folder,current,stamp)
                if(candidate==null || !BridgeApi.exists(candidate.id)) return@execute
                main.post {
                    if(!active || stamp!=generation || manualStopped || !settings.autoNext) return@post
                    offerNext(candidate,folder,stamp)
                }
            } catch(_: Exception) { main.post { if(active && stamp==generation) warn("Không kiểm tra được tập tiếp theo; đã dừng tự động phát.") } }
        }
    }
    private fun offerNext(candidate: EpisodeFile, folder: String, stamp: Int) {
        host.closeSettingsForNext()
        var consumed=false
        fun cancel() { consumed=true; manualStopped=true; countdown?.let{main.removeCallbacks(it)}; host.playbackDialogCancelled() }
        fun play() {
            if(consumed || !active || stamp!=generation) return
            consumed=true; countdown?.let{main.removeCallbacks(it)}; dialog?.dismiss(); dialog=null
            load(candidate,folder)
        }
        val handle=PlaybackDialogs.choice(host,"Sắp phát tập tiếp theo","${candidate.name}\nBắt đầu sau 5 giây","Phát ngay","Hủy",::play,::cancel,::cancel)
        dialog=handle.dialog
        val deadline=SystemClock.elapsedRealtime()+5000
        val task=object : Runnable {
            override fun run() {
                if(consumed || !active || stamp!=generation) return
                if(!settings.autoNext) { handle.dialog.dismiss(); cancel(); return }
                val left=((deadline-SystemClock.elapsedRealtime()+999)/1000).coerceAtLeast(0)
                if(left==0L) play()
                else { handle.body.text="${candidate.name}\nBắt đầu sau $left giây"; main.postDelayed(this,200) }
            }
        }
        countdown=task; main.post(task)
    }
    fun userStopped() { manualStopped=true; countdown?.let{main.removeCallbacks(it)}; dialog?.dismiss(); dialog=null; p?.pause(); save() }
    fun userPlaying() { manualStopped=false; if(p?.playbackState==Player.STATE_ENDED) endedHandled=false }
    fun suppressForRestore(): Boolean = manualStopped || p?.playbackState==Player.STATE_ENDED
    fun localPosition(): Long = pendingPosition ?: (host.seeks.target ?: p?.currentPosition ?: 0L)
    fun stop() {
        save(); if(p?.playbackState==Player.STATE_ENDED) manualStopped=true
        active=false; generation++
        countdown?.let{main.removeCallbacks(it)}; seekSave?.let{main.removeCallbacks(it)}
        dialog?.dismiss(); dialog=null; pendingPosition=null; p=null
    }
    fun destroy() { stop(); io.shutdown() }
}
