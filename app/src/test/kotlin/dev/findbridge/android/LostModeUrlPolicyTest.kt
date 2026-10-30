package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class LostModeUrlPolicyTest {
    @Test
    fun lockRequestsRemainSeparateFromReadingSoundAndErase() {
        val read = "https://p01-fmipweb.icloud.com/fmipservice/client/web/refreshClient?fixture=1"
        for (family in listOf(false, true)) {
            val lost = UrlPolicy.lostModeEndpoint(read, family)
            assertTrue(UrlPolicy.isLostModeEndpoint(lost))
            assertFalse(UrlPolicy.isReadEndpoint(lost))
            assertFalse(UrlPolicy.isSoundEndpoint(lost))
            assertTrue(lost.endsWith("?fixture=1"))
        }
        for (bad in
            listOf(
                "https://p01-fmipweb.icloud.com/fmipservice/client/web/remoteErase",
                "https://p01-fmipweb.icloud.com/fmipservice/client/web/playSound",
                "https://p01-fmipweb.icloud.com.evil.invalid/fmipservice/client/web/lostDevice",
                "https://owner@p01-fmipweb.icloud.com/fmipservice/client/web/lostDevice",
                "http://p01-fmipweb.icloud.com/fmipservice/client/web/lostDevice",
                "https://p01-fmipweb.icloud.com:444/fmipservice/client/web/lostDevice",
            )) assertFalse(bad, UrlPolicy.isLostModeEndpoint(bad))
    }
}
