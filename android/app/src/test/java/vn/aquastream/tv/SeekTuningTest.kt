package vn.aquastream.tv

import org.junit.Assert.*
import org.junit.Test

class SeekTuningTest {
    @Test fun asyncOverrideIsLimitedToValidatedOlderAquaman() {
        assertTrue(SeekTuning.useAsyncCodec(28,"aquaman"))
        assertTrue(SeekTuning.useAsyncCodec(23,"Aquaman"))
        assertFalse(SeekTuning.useAsyncCodec(22,"aquaman"))
        assertFalse(SeekTuning.useAsyncCodec(31,"aquaman"))
        assertFalse(SeekTuning.useAsyncCodec(28,"other"))
    }
}
