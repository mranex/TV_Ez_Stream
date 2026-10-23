package vn.aquastream.tv

/** Allocator goals, not reservations or hard limits on process/decoder RAM. */
enum class BufferProfile(val bytes: Int, val minMs: Int, val forwardMs: Int, val backMs: Int) {
    LOW_MEMORY(16*1024*1024,3_000,25_000,8_000),
    BALANCED(32*1024*1024,15_000,120_000,60_000),
    EXTENDED(48*1024*1024,15_000,120_000,60_000),
    MAXIMUM(64*1024*1024,15_000,120_000,60_000)
}

data class BufferMemory(val available: Long, val threshold: Long, val lowMemory: Boolean,
    val appPssBytes: Long, val javaUsed: Long, val javaMax: Long)

object BufferPolicy {
    private const val MIB=1024L*1024
    fun pressured(m: BufferMemory): Boolean = m.lowMemory ||
        m.available <= maxOf(m.threshold+48*MIB,128*MIB) ||
        m.appPssBytes >= 192*MIB || (m.javaMax>0 && m.javaUsed.toDouble()/m.javaMax>=.82)

    fun initial(m: BufferMemory, requested: BufferProfile=BufferProfile.BALANCED,
                promotionBenchmarked: Boolean=false): BufferProfile {
        if(pressured(m)) return BufferProfile.LOW_MEMORY
        if(requested==BufferProfile.LOW_MEMORY) return requested
        if(requested==BufferProfile.BALANCED || !promotionBenchmarked) return BufferProfile.BALANCED
        val reserve=if(requested==BufferProfile.MAXIMUM) 384*MIB else 256*MIB
        return if(m.available-m.threshold>=reserve && m.appPssBytes<128*MIB) requested else BufferProfile.BALANCED
    }
}

/** A conservative forward-only display window. Never infers retained back buffer. */
object BufferedWindow {
    fun end(position: Long, duration: Long, boundary: Long, forwardDuration: Long, ready: Boolean): Long {
        if(duration<=0 || position<0 || position>duration || !ready || boundary<position || forwardDuration<=0) return position.coerceIn(0,duration.coerceAtLeast(0))
        return minOf(duration,boundary,position+forwardDuration.coerceAtMost(duration-position))
    }
}
