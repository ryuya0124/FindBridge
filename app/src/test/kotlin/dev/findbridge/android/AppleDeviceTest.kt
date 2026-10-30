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
        assertEquals(AppleDevice.State.NO_LOCATION to 0L, device(false).status())
        assertEquals(AppleDevice.State.OFFLINE to 0L, device(false, online = false).status())
    }

    @Test
    fun staleLocationsRetainTheirActualAge() {
        assertEquals(AppleDevice.State.NOW to 0L, device(true).status(600_000))
        assertEquals(AppleDevice.State.MINUTES to 5L, device(true).status(900_000))
        assertEquals(AppleDevice.State.HOURS to 2L, device(true).status(7_800_000))
    }
}
