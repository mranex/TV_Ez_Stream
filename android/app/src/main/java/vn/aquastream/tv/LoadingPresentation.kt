package vn.aquastream.tv

/** Presentation only. Never changes player readiness or seek completion. */
class LoadingPresentation(private val delayMs:Long=200) {
    private var since:Long?=null
    fun update(buffering:Boolean,now:Long):Boolean {
        if(!buffering){since=null;return false}
        if(since==null)since=now
        return now-since!!>=delayMs
    }
    fun remaining(now:Long):Long=(delayMs-(now-(since?:now))).coerceAtLeast(0)
}
