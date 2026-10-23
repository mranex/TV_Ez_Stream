package vn.aquastream.tv

import org.junit.Assert.*
import org.junit.Test

class LoadingPresentationTest {
    @Test fun briefSeekNeverFlashesAndRealStallRemainsVisible() {
        val gate=LoadingPresentation()
        assertFalse(gate.update(true,1000));assertFalse(gate.update(true,1150))
        assertFalse(gate.update(false,1180))
        assertFalse(gate.update(true,1300));assertFalse(gate.update(true,1499))
        assertTrue(gate.update(true,1500));assertTrue(gate.update(true,3000))
        assertFalse(gate.update(false,3001))
        assertFalse(gate.update(true,3010));assertEquals(200L,gate.remaining(3010))
    }
}
