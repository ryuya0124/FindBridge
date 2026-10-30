package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class NotificationUrlPolicyTest {
    @Test
    fun notificationCannotReachOtherActionsOrHosts() {
        val endpoint =
            UrlPolicy.notificationEndpoint(
                "https://p01-fmipweb.icloud.com/fmipservice/client/web/refreshClient?fixture=1"
            )
        assertTrue(UrlPolicy.isNotificationEndpoint(endpoint))
        assertFalse(UrlPolicy.isReadEndpoint(endpoint))
        assertFalse(UrlPolicy.isSoundEndpoint(endpoint))
        assertFalse(UrlPolicy.isLostModeEndpoint(endpoint))
        for (bad in
            listOf(
                endpoint.replace("https:", "http:"),
                endpoint.replace("icloud.com", "icloud.com.evil.invalid"),
                endpoint.replace("saveLocFoundPref", "remoteWipeWithUserAuth"),
                endpoint + "#fragment",
            )) assertFalse(UrlPolicy.isNotificationEndpoint(bad))
    }
}
