package vn.aquastream.tv

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded debug traces. Metadata scheduling and codec-render notification are distinct. */
@UnstableApi
class SeekDiagnostics(private val enabled: Boolean, private val traceDirectory:File?=null): Player.Listener, TransferListener, AnalyticsListener {
    // Debug only: bounded queue and two 256 KiB files; no UI/renderer-thread file IO.
    private val writer=if(enabled && traceDirectory!=null) ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(32),{job->Thread(job,"aqua-seek-trace").apply{isDaemon=true}},ThreadPoolExecutor.DiscardOldestPolicy()) else null
    private fun retire(t:Trace) {
        publish(t)
        if(writer==null)return
        val line=JSONObject(t.values.toString()).put("frame_observed",t.metadata).put("capture_elapsed_ms",(now()-t.input)/1e6).toString()+"\n"
        writer.execute {
            try {
                val directory=traceDirectory!!;directory.mkdirs()
                val file=File(directory,"seek.jsonl")
                if(file.length()+line.toByteArray(Charsets.UTF_8).size>256*1024) {
                    val previous=File(directory,"seek-previous.jsonl")
                    if(previous.exists() && !previous.delete()) return@execute
                    if(file.exists() && !file.renameTo(previous)) return@execute
                }
                file.appendText(line,Charsets.UTF_8)
            }catch(error:IOException){Log.w("AquaSeekTrace","Cannot persist debug trace",error)}
        }
    }
    @Synchronized fun close(){cancel();writer?.shutdown()}
    internal val enabledForProbe get()=enabled
    @Synchronized fun uiContext(mode:String,spinner:Boolean) { trace?.values?.put("ui_mode",mode)?.put("spinner_at_input",spinner) }
    @Synchronized fun spinner(visible:Boolean) { if(enabled){mark(if(visible)"spinner_shown_ms" else "spinner_hidden_ms");trace?.values?.put("spinner_visible",visible)} }
    @Synchronized fun mainDelay(ms:Double) { trace?.let{it.values.put("main_thread_delay_max_ms",maxOf(ms,it.values.optDouble("main_thread_delay_max_ms",0.0)))} }
    @Synchronized fun videoInput(pts:Long) { trace?.let { if(it.ack && !it.metadata && it.values.has("codec_flush_end_ms")) {
        mark("first_video_input_ms");if(!it.values.has("first_video_input_pts_ms"))it.values.put("first_video_input_pts_ms",pts/1000)
        it.values.put("video_inputs",it.values.optInt("video_inputs")+1)
    } } }
    @Synchronized fun videoOutput(pts:Long) { trace?.let { if(it.ack && !it.metadata && it.values.has("codec_flush_end_ms")) {
        lastCodecOutputPts=pts
        mark("first_video_output_ms");if(!it.values.has("first_video_output_pts_ms"))it.values.put("first_video_output_pts_ms",pts/1000)
        it.values.put("video_outputs",it.values.optInt("video_outputs")+1)
    } } }
    private data class Trace(val id:Int,val target:Long,val input:Long,val bytes:Long,val requests:Int,val ranges:Int,
        val inits:Int,val releases:Int,val flushes:Int,val values:JSONObject,
        var ack:Boolean=false,var metadata:Boolean=false,var codecFrame:Boolean=false)
    private var sequence=0
    private var bytes=0L
    private var requests=0
    private var ranges=0
    private var inits=0
    private var releases=0
    private var flushes=0
    private var codec="unknown"
    private var lastCodecOutputPts:Long?=null
    internal val currentId get()=sequence
    var approximate=false
    private var trace:Trace?=null
    private val requestIds=java.util.IdentityHashMap<DataSource,Int>()
    @Volatile var lastResult:JSONObject?=null;private set
    @Volatile var completedId=0;private set
    @Volatile var lastPtsMs=Long.MIN_VALUE;private set
    @Volatile var totalBufferingEvents=0;private set
    private fun now()=SystemClock.elapsedRealtimeNanos()
    @Synchronized fun begin(target:Long,player:Player,inputNs:Long=now()):Int {
        if(!enabled)return 0
        trace?.let(::retire)
        sequence++
        val j=JSONObject().put("id",sequence).put("t0_elapsed_ns",inputNs).put("target_ms",target).put("source_ms",player.currentPosition)
            .put("buffered_ms",player.bufferedPosition).put("buffered_duration_ms",player.totalBufferedDuration)
            .put("play_when_ready",player.playWhenReady).put("speed",player.playbackParameters.speed.toDouble())
            .put("forward_boundary_covered_target",target>=player.currentPosition && target<=player.bufferedPosition)
            .put("codec",codec).put("buffering_events",0).put("dropped_frames",0)
        trace=Trace(sequence,target,inputNs,bytes,requests,ranges,inits,releases,flushes,j)
        lastCodecOutputPts=null
        mark("target_calculated_ms");return sequence
    }
    @Synchronized fun invoked() {mark("seek_invoked_ms")}
    @Synchronized fun cancel(){trace?.let(::retire);trace=null}
    private fun mark(key:String){trace?.let{if(!it.values.has(key))it.values.put(key,(now()-it.input)/1e6)}}
    private fun publish(t:Trace) {
        t.values.put("http_requests",requests-t.requests).put("range_requests",ranges-t.ranges)
            .put("network_bytes",bytes-t.bytes).put("decoder_inits",inits-t.inits)
            .put("decoder_releases",releases-t.releases).put("codec_flushes",flushes-t.flushes)
        lastResult=JSONObject(t.values.toString())
    }
    @Synchronized fun snapshot():JSONObject? {val t=trace?:return lastResult?.let{JSONObject(it.toString())};publish(t);return lastResult}
    override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
        if(reason==Player.DISCONTINUITY_REASON_SEEK)synchronized(this){trace?.ack=true;mark("seek_ack_ms")}
        if(reason==Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)synchronized(this){mark("seek_adjusted_ms");trace?.values?.put("adjusted_position_ms",newPosition.positionMs)}
    }
    override fun onPlaybackStateChanged(state:Int){if(!enabled)return;synchronized(this){
        if(state==Player.STATE_BUFFERING){totalBufferingEvents++;trace?.let{it.values.put("buffering_events",it.values.optInt("buffering_events")+1)};mark("buffering_ms")}
        if(state==Player.STATE_READY){mark("ready_ms");trace?.let{if(it.metadata){publish(it);Log.d("AquaSeekTrace","ready $lastResult")}}}
    }}
    override fun onIsLoadingChanged(loading:Boolean){synchronized(this){mark(if(loading)"is_loading_true_ms" else "is_loading_false_ms")}}
    override fun onIsPlayingChanged(playing:Boolean){if(playing)synchronized(this){mark("is_playing_ms")}}
    override fun onRenderedFirstFrame(){synchronized(this){mark("player_first_frame_callback_ms")}}
    @Synchronized fun frame(ptsUs:Long,releaseNs:Long) {
        lastPtsMs=ptsUs/1000
        if(!enabled)return
        val t=trace?:return
        val pts=ptsUs/1000
        val tolerance=if(approximate)3000 else 350
        if(!t.ack || t.metadata || pts<t.target-(if(approximate)3000 else 25) || pts>t.target+tolerance)return
        if(approximate && !t.values.has("codec_flush_end_ms"))return
        t.metadata=true
        // MediaCodec PTS includes the renderer stream offset; frame metadata is in media time.
        lastCodecOutputPts?.let { codecPts ->
            val offset=codecPts-ptsUs
            t.values.put("codec_pts_offset_us",offset)
            if(t.values.has("first_video_input_pts_ms"))t.values.put("first_video_input_media_ms",t.values.getLong("first_video_input_pts_ms")-offset/1000)
            if(t.values.has("first_video_output_pts_ms"))t.values.put("first_video_output_media_ms",t.values.getLong("first_video_output_pts_ms")-offset/1000)
            t.values.put("video_outputs_before_first_target",(t.values.optInt("video_outputs")-1).coerceAtLeast(0))
        }
        val elapsed=now();val nano=System.nanoTime()
        val scheduled=if(releaseNs in (nano-100_000_000)..(nano+500_000_000))elapsed+(releaseNs-nano).coerceAtLeast(0) else elapsed
        t.values.put("frame_pts_ms",pts).put("metadata_callback_ms",(elapsed-t.input)/1e6)
            .put("frame_target_error_ms",pts-t.target)
            .put("scheduled_frame_ms",(scheduled-t.input)/1e6)
        completedId=t.id;publish(t)
        Log.d("AquaSeekTrace",lastResult.toString())
        // Keep trace alive for READY and hardware notifications arriving after metadata.
    }
    @Synchronized fun codecRendered(ptsUs:Long,renderNano:Long) {
        val t=trace?:return
        val pts=ptsUs/1000
        if(!t.ack || t.codecFrame || pts<t.target-(if(approximate)3000 else 25) || pts>t.target+(if(approximate)3000 else 350))return
        t.codecFrame=true
        val elapsed=now();val nano=System.nanoTime()
        t.values.put("codec_render_callback_ms",(elapsed-t.input)/1e6)
        if(renderNano in (nano-2_000_000_000)..(nano+500_000_000)) t.values.put("codec_render_timestamp_ms",(elapsed+renderNano-nano-t.input)/1e6)
        publish(t)
    }
    @Synchronized fun codecFlush(begin:Boolean,durationNs:Long=0) {
        if(begin){flushes++;mark("codec_flush_start_ms")}
        else {mark("codec_flush_end_ms");trace?.values?.put("codec_flush_duration_ms",durationNs/1e6)}
    }
    @Synchronized fun audioFlush(begin:Boolean,durationNs:Long=0){if(begin)mark("audio_flush_start_ms")else{mark("audio_flush_end_ms");trace?.values?.put("audio_flush_duration_ms",durationNs/1e6)}}
    override fun onAudioPositionAdvancing(time:AnalyticsListener.EventTime,stamp:Long){synchronized(this){mark("audio_advancing_ms")}}
    override fun onAudioTrackInitialized(time:AnalyticsListener.EventTime,config:androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig){synchronized(this){mark("audio_track_initialized_ms")}}
    override fun onAudioTrackReleased(time:AnalyticsListener.EventTime,config:androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig){synchronized(this){mark("audio_track_released_ms")}}
    override fun onVideoDecoderInitialized(time:AnalyticsListener.EventTime,name:String,stamp:Long,duration:Long){synchronized(this){codec=name;inits++;mark("decoder_initialized_ms")}}
    override fun onVideoDecoderReleased(time:AnalyticsListener.EventTime,name:String){synchronized(this){releases++}}
    override fun onDroppedVideoFrames(time:AnalyticsListener.EventTime,count:Int,elapsed:Long){synchronized(this){trace?.let{it.values.put("dropped_frames",it.values.optInt("dropped_frames")+count)}}}
    override fun onLoadStarted(time:AnalyticsListener.EventTime,info:LoadEventInfo,data:MediaLoadData){synchronized(this){mark("load_started_ms");trace?.values?.put("load_id",info.loadTaskId)}}
    override fun onLoadCompleted(time:AnalyticsListener.EventTime,info:LoadEventInfo,data:MediaLoadData){synchronized(this){mark("load_completed_ms")}}
    override fun onLoadError(time:AnalyticsListener.EventTime,info:LoadEventInfo,data:MediaLoadData,error:IOException,canceled:Boolean){synchronized(this){mark("load_error_ms")}}
    override fun onTransferInitializing(source:DataSource,spec:DataSpec,network:Boolean){if(enabled&&network)synchronized(this){
        requests++;if(spec.position>0||spec.length>=0)ranges++
        if(requestIds.size>16)requestIds.clear()
        requestIds[source]=requests
        mark("http_request_init_ms");trace?.values?.put("request_byte_position",spec.position)?.put("http_request_id",requests)
    }}
    override fun onTransferStart(source:DataSource,spec:DataSpec,network:Boolean){if(enabled&&network)synchronized(this){
        mark("http_open_ms")
        trace?.let{if((requestIds[source]?:0)>it.requests)mark("new_request_open_ms")}
    }}
    override fun onBytesTransferred(source:DataSource,spec:DataSpec,network:Boolean,count:Int){if(enabled&&network)synchronized(this){
        bytes+=count;mark("first_payload_byte_ms") // includes bytes from an older in-flight load
        trace?.let{if((requestIds[source]?:0)>it.requests)mark("new_request_first_payload_ms")}
    }}
    override fun onTransferEnd(source:DataSource,spec:DataSpec,network:Boolean){synchronized(this){requestIds.remove(source)}}
}
