package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class DeviceImageUrlsTest {
    @Test
    fun artworkCannotRedirectCredentialsToOtherHostsOrLocalFiles() {
        assertTrue(
            DeviceImageUrls.google("https://lh3.googleusercontent.com/public-art=w120").isNotEmpty()
        )
        assertTrue(DeviceImageUrls.google("https://lh3.ggpht.com/public-art").isNotEmpty())
        for (value in
            listOf(
                "http://lh3.googleusercontent.com/art",
                "https://lh3.googleusercontent.com.evil.invalid/art",
                "https://owner@lh3.googleusercontent.com/art",
                "file:///data/local/art.png",
                "https://127.0.0.1/art",
                "https://lh3.googleusercontent.com:444/art",
            )) assertEquals("", DeviceImageUrls.google(value))
        assertEquals(
            "",
            DeviceImageUrls.apple(
                "https://statici.icloud.com/fmipmobile/deviceImages-9.0/../private.png"
            ),
        )
        assertEquals(
            "",
            DeviceImageUrls.apple(
                "https://statici.icloud.com/fmipmobile/deviceImages-9.0/%2e%2e/private.png"
            ),
        )
        assertEquals(
            "",
            DeviceImageUrls.apple(
                "https://statici.icloud.com.evil.invalid/fmipmobile/deviceImages-9.0/phone.png"
            ),
        )
    }

    @Test
    fun appleArtworkUsesTheReturnedHardwareModelAndColor() {
        assertEquals(
            "https://statici.icloud.com/fmipmobile/deviceImages-9.0/iPhone/iPhone10,6-1-2-0/online-sourcelist__3x.png",
            DeviceImageUrls.appleDevice(
                "https://statici.icloud.com",
                "iPhone",
                "iPhone10,6",
                "1-2-0",
                true,
            ),
        )
        assertEquals(
            "",
            DeviceImageUrls.appleDevice(
                "https://untrusted.invalid",
                "iPhone",
                "iPhone10,6",
                "1-2-0",
                true,
            ),
        )
        assertEquals(
            "",
            DeviceImageUrls.appleDevice(
                "https://statici.icloud.com",
                "iPhone",
                "../private",
                "",
                false,
            ),
        )
    }
}
