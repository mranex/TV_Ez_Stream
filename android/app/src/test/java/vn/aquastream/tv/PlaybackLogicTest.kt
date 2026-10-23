package vn.aquastream.tv
import org.junit.Assert.*
import org.junit.Test
class PlaybackLogicTest {
    @Test fun arbitrarySpeedAndWallTime() {
        assertEquals(137,PlaybackMath.parseSpeed("1.37")); assertEquals(235,PlaybackMath.parseSpeed("2.35"))
        assertNull(PlaybackMath.parseSpeed("0.24"));assertNull(PlaybackMath.parseSpeed("4.01"));assertNull(PlaybackMath.parseSpeed("NaN"))
        assertEquals(2400000L,PlaybackMath.remaining(3600000,0,1.5f));assertEquals(1800000L,PlaybackMath.remaining(3600000,0,2f))
        assertNull(PlaybackMath.remaining(-1,0,1f))
    }
    private fun next(current: String, vararg names: String) = EpisodeResolver.next(current,names.map{EpisodeFile(it,it)})?.name
    @Test fun namingFamiliesAndNaturalSort() {
        for((a,b) in listOf("EP09.mp4" to "EP10.mkv","Episode 9.mp4" to "Episode 10.mp4","Tập 09.mp4" to "Tập 10.mp4",
            "Frieren S01E09.mkv" to "Frieren S01E10.mkv","Frieren - 09.mp4" to "Frieren - 10.mp4",
            "[Group] Frieren - 09 [1080p].mkv" to "[Other] Frieren - 10 [720p].mkv")) assertEquals(b,next(a,b))
        assertEquals("Series - 2.mp4",next("Series - 1.mp4","Series - 10.mp4","Series - 2.mp4"))
        assertTrue(EpisodeResolver.compareNames("Episode 2","Episode 10")<0)
    }
    @Test fun unrelatedAndLastEpisodesNeverLoop() {
        assertNull(next("Holiday.mp4","Birthday.mp4"))
        assertNull(next("Frieren EP10.mp4","Other EP11.mp4","Frieren EP01.mp4"))
        assertNull(next("Movie 2023.mp4","Movie 2024.mp4"))
        assertNull(next("1.mp4","2.mp4"))
    }
}
