package vn.aquastream.tv

import org.junit.Assert.*
import org.junit.Test

class BufferPolicyTest {
    private val mib=1024L*1024
    private val healthy=BufferMemory(350*mib,64*mib,false,80*mib,30*mib,192*mib)
    @Test fun noPromotionWithoutMeasuredEvidence() {
        assertEquals(BufferProfile.BALANCED,BufferPolicy.initial(healthy))
        assertEquals(BufferProfile.BALANCED,BufferPolicy.initial(healthy,BufferProfile.MAXIMUM))
        assertEquals(BufferProfile.BALANCED,BufferPolicy.initial(healthy,BufferProfile.MAXIMUM,true))
        assertEquals(BufferProfile.MAXIMUM,BufferPolicy.initial(healthy.copy(available=600*mib),BufferProfile.MAXIMUM,true))
        assertEquals(67108864,BufferProfile.MAXIMUM.bytes)
    }
    @Test fun pressureFromSystemHeapOrProcessForcesLow() {
        for(m in listOf(healthy.copy(lowMemory=true),healthy.copy(available=110*mib),
            healthy.copy(javaUsed=180*mib),healthy.copy(appPssBytes=200*mib))) {
            assertTrue(BufferPolicy.pressured(m))
            assertEquals(BufferProfile.LOW_MEMORY,BufferPolicy.initial(m))
        }
    }
    @Test fun bufferedWindowNeverPaintsStaleSeekGapsOrUnknownDuration() {
        assertEquals(120000L,BufferedWindow.end(100000,500000,300000,20000,true))
        assertEquals(350000L,BufferedWindow.end(350000,500000,300000,20000,true))
        assertEquals(100000L,BufferedWindow.end(100000,500000,300000,20000,false))
        assertEquals(0L,BufferedWindow.end(100000,0,300000,20000,true))
        assertEquals(500000L,BufferedWindow.end(499000,500000,Long.MAX_VALUE,Long.MAX_VALUE,true))
    }
}
