package vn.aquastream.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/** Debug-only instrumentation around the actual Media3 adapter. No alternate decoder. */
@UnstableApi
class SeekProbeRenderers(context:Context,private val trace:SeekDiagnostics,async:Boolean):DefaultRenderersFactory(context) {
    var probeOperatingRate=0f
    init { if(async)forceEnableMediaCodecAsynchronousQueueing() }
    override fun getCodecAdapterFactory():MediaCodecAdapter.Factory {
        val factory=super.getCodecAdapterFactory()
        return MediaCodecAdapter.Factory { configuration ->
            if(configuration.surface!=null && probeOperatingRate>0) configuration.mediaFormat.setFloat(android.media.MediaFormat.KEY_OPERATING_RATE,probeOperatingRate)
            val adapter=factory.createAdapter(configuration)
            object:MediaCodecAdapter by adapter {
                private val video=configuration.surface!=null
                init { if(video)attach(null,Handler(Looper.getMainLooper())) }
                private fun attach(listener:MediaCodecAdapter.OnFrameRenderedListener?,handler:Handler) {
                    adapter.setOnFrameRenderedListener({ _,pts,nano ->
                        trace.codecRendered(pts,nano)
                        listener?.onFrameRendered(this,pts,nano)
                    },handler)
                }
                override fun setOnFrameRenderedListener(listener:MediaCodecAdapter.OnFrameRenderedListener,handler:Handler)=attach(listener,handler)
                override fun registerOnBufferAvailableListener(listener:MediaCodecAdapter.OnBufferAvailableListener)=adapter.registerOnBufferAvailableListener(listener)
                override fun queueInputBuffer(index:Int,offset:Int,size:Int,pts:Long,flags:Int) {
                    if(video)trace.videoInput(pts)
                    adapter.queueInputBuffer(index,offset,size,pts,flags)
                }
                override fun dequeueOutputBufferIndex(info:android.media.MediaCodec.BufferInfo):Int {
                    val index=adapter.dequeueOutputBufferIndex(info)
                    if(video && index>=0)trace.videoOutput(info.presentationTimeUs)
                    return index
                }
                override fun flush() {
                    if(video)trace.codecFlush(true)else trace.audioFlush(true)
                    val start=SystemClock.elapsedRealtimeNanos()
                    try { adapter.flush() } finally {
                        if(video)trace.codecFlush(false,SystemClock.elapsedRealtimeNanos()-start)else trace.audioFlush(false,SystemClock.elapsedRealtimeNanos()-start)
                    }
                }
            }
        }
    }
}
