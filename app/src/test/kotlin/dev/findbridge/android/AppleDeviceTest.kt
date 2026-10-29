package dev.findbridge.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleDeviceTest {
    private fun device(located: Boolean, online: Boolean = true, timestamp: Long = 600_000) =
        AppleDevice(
            "test-id",
            "Test iPhone",
            "iPhone",
            "iPhone",
            false,
            if (located) 35.0 else null,
            if (located) 135.0 else null,
            10.0,
            timestamp,
            online,
            .75,
            false,
            "",
        )

    @Test
    fun unknownLocationDoesNotClaimLiveCoordinates() {
        assertEquals("位置情報が見つかりません", device(false).status())
        assertEquals("オフライン", device(false, online = false).status())
    }

    @Test
    fun staleLocationsRetainTheirActualAge() {
        assertEquals("たった今", device(true).status(600_000))
        assertEquals("5分前", device(true).status(900_000))
        assertEquals("2時間前", device(true).status(7_800_000))
    }
}
