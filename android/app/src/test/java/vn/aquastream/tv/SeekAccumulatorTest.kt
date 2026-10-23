package vn.aquastream.tv

import org.junit.Assert.*
import org.junit.Test

class SeekAccumulatorTest {
    @Test fun rapidPressesIgnoreStalePlaybackPosition() {
        val seek = SeekAccumulator()
        repeat(20) { assertEquals(10_000L + (it + 1) * 1000, seek.step(1000, 10_000, 120_000)) }
        assertEquals(28_000L, seek.step(-2000, 11_000, 120_000))
    }
    @Test fun boundariesAndIndependentSteps() {
        val seek = SeekAccumulator()
        assertEquals(0L, seek.step(-3000, 1000, 10_000))
        assertEquals(5000L, seek.step(5000, 1000, 10_000))
        assertEquals(10_000L, seek.step(10_000, 1000, 10_000))
        seek.settled()
        assertEquals(8000L, seek.step(-1000, 9000, 10_000))
    }
}
