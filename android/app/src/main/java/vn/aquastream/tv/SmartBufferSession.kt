package vn.aquastream.tv

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Standard Media3 owns all sample data. No disk cache or second RAM cache. */
@UnstableApi
class SmartBufferSession(context: Context, requested: BufferProfile=BufferProfile.BALANCED, playbackThresholdMs: Int=500) {
    companion object { @Volatile private var lowUntilMs=0L }
    private val activityManager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val debug=context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0
    private val memoryInfo=ActivityManager.MemoryInfo()
    private val processInfo=Debug.MemoryInfo()
    private val pressure=AtomicBoolean(false)
    private val main=Handler(Looper.getMainLooper())
    private var monitor: java.util.concurrent.ScheduledExecutorService?=null
    @Volatile private var closed=false
    @Volatile var latestMemory=readMemory()
        private set
    val profile=if(SystemClock.elapsedRealtime()<lowUntilMs) BufferProfile.LOW_MEMORY else BufferPolicy.initial(latestMemory,requested)
    @Volatile var pendingProfile=profile
        private set
    val allocator=DefaultAllocator(true,C.DEFAULT_BUFFER_SEGMENT_SIZE)
    // 1.5.1 has immutable DefaultLoadControl thresholds. This small gate only
    // suppresses optional loading under pressure; all other behavior is inherited.
    // A full LOW_MEMORY configuration takes effect at the next player initialization.
    // Use the verified 1.5.1 protected constructor rather than Kotlin interface
    // delegation: Java default LoadControl methods do not all delegate in Kotlin.
    val loadControl: LoadControl=object: DefaultLoadControl(allocator,profile.minMs,
        profile.forwardMs,playbackThresholdMs,1500,profile.bytes,false,profile.backMs,true) {
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
            if(pressure.get() && parameters.bufferedDurationUs >= (4_000_000L*parameters.playbackSpeed.coerceAtLeast(1f)).toLong()) return false
            return super.shouldContinueLoading(parameters)
        }
    }

    private fun readMemory():BufferMemory {
        activityManager.getMemoryInfo(memoryInfo)
        Debug.getMemoryInfo(processInfo)
        val heap=Runtime.getRuntime()
        return BufferMemory(memoryInfo.availMem,memoryInfo.threshold,memoryInfo.lowMemory,
            processInfo.totalPss*1024L,heap.totalMemory()-heap.freeMemory(),heap.maxMemory())
    }
    fun start(player: ExoPlayer) {
        if(monitor!=null) return
        if(BufferPolicy.pressured(latestMemory)) reduce("initial memory pressure")
        monitor=Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable,"aqua-memory").apply{isDaemon=true}
        }.also { executor -> executor.scheduleWithFixedDelay({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            if(!closed) try {
                latestMemory=readMemory()
                if(BufferPolicy.pressured(latestMemory)) reduce("memory sample")
                // Trim only the allocator's unused pool, never active sample queues.
                if(pressure.get()) { allocator.setTargetBufferSize(BufferProfile.LOW_MEMORY.bytes);allocator.trim() }
                if(debug) main.post {
                    if(!closed) Log.d("AquaBuffer","profile=$profile pending=$pendingProfile pressure=${pressure.get()} allocator=${allocator.totalBytesAllocated} position=${player.currentPosition} forward=${player.totalBufferedDuration} wallForward=${(player.totalBufferedDuration/player.playbackParameters.speed.coerceAtLeast(.25f)).toLong()} avail=${latestMemory.available} threshold=${latestMemory.threshold} low=${latestMemory.lowMemory} pss=${latestMemory.appPssBytes} heap=${latestMemory.javaUsed}")
                }
            } catch(error:RuntimeException) { if(debug) Log.w("AquaBuffer","Memory sample unavailable",error) }
        },0,15,TimeUnit.SECONDS) }
        if(debug) Log.d("AquaBuffer","initial profile=$profile target=${profile.bytes} forwardGoal=${profile.forwardMs} backGoal=${profile.backMs}; allocator target is not a hard cap")
    }
    private fun reduce(reason:String) {
        if(closed) return
        pendingProfile=BufferProfile.LOW_MEMORY
        lowUntilMs=SystemClock.elapsedRealtime()+120_000
        if(pressure.compareAndSet(false,true) && debug) Log.w("AquaBuffer","Pressure: $reason; stop optional loading, LOW_MEMORY deferred to next initialization")
        allocator.setTargetBufferSize(BufferProfile.LOW_MEMORY.bytes)
        allocator.trim()
    }
    fun trim(level:Int) {
        if(level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE || level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level>=ComponentCallbacks2.TRIM_MEMORY_MODERATE) reduce("trim=$level")
        else allocator.trim() // UI_HIDDEN alone is not evidence of low memory.
    }
    fun lowMemory()=reduce("onLowMemory")
    fun close() {
        closed=true;monitor?.shutdownNow();monitor=null
        // Player.release() returns live allocations. Never reset an active allocator.
        allocator.trim()
    }
}
