package com.android.virtualization.terminal

import org.junit.Assert.*
import org.junit.Test

class CaptureDisplaySizeTest {
    @Test fun followsLayoutAcrossRotationAndFullscreen() {
        assertEquals(628 to 1194, CaptureDisplaySize.requested(1256, 2388, .5f, true))
        assertEquals(1380 to 558, CaptureDisplaySize.requested(2760, 1116, .5f, true))
        assertEquals(628 to 1380, CaptureDisplaySize.requested(1256, 2760, .5f, true))
    }
    @Test fun roundsOddAvcDimensionsAndPreservesRawPixels() {
        assertEquals(628 to 1194, CaptureDisplaySize.requested(1259, 2391, .5f, true))
        assertEquals(629 to 1195, CaptureDisplaySize.requested(1259, 2391, .5f, false))
        assertEquals(2 to 2, CaptureDisplaySize.requested(1, 1, .5f, true))
        assertEquals(1258 to 2390, CaptureDisplaySize.requested(1259, 2391, 1f, true))
    }
}
