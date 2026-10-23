package vn.aquastream.tv

/** A logical target survives asynchronous seeks; never uses a stale rendered position for a burst. */
class SeekAccumulator {
    var target: Long? = null
        private set

    fun step(deltaMs: Long, actualMs: Long, durationMs: Long?): Long {
        val base = target ?: actualMs.coerceAtLeast(0)
        val result = (base + deltaMs).coerceAtLeast(0).let {
            if (durationMs != null && durationMs > 0) it.coerceAtMost(durationMs) else it
        }
        target = result
        return result
    }

    fun settled() { target = null }
}
