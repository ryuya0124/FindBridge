package dev.findbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test
    fun savedCredentialsAreRestrictedToExactAppleAuthenticationOrigins() {
        listOf(
                "https://idmsa.apple.com/appleauth/auth/authorize/signin",
                "https://account.apple.com/auth/authorize",
            )
            .forEach { assertTrue(UrlPolicy.isCredentialOrigin(it)) }
        listOf(
                "http://idmsa.apple.com/",
                "https://idmsa.apple.com.evil.example/",
                "https://www.icloud.com/",
                "https://user@idmsa.apple.com/",
                "https://idmsa.apple.com:8443/",
                "https://idmsa.apple.com./",
            )
            .forEach { assertFalse(it, UrlPolicy.isCredentialOrigin(it)) }
    }

    @Test
    fun applePagesUseExactHttpsDomains() {
        listOf(
                UrlPolicy.findUrl,
                "https://idmsa.apple.com/appleauth/auth",
                "https://www.icloud.com.cn/find",
            )
            .forEach { assertTrue(it, UrlPolicy.isApplePage(it)) }
        listOf(
                "https://icloud.com.evil.example/",
                "https://evilicloud.com/",
                "https://evil@apple.com/",
                "http://www.icloud.com/find",
                "javascript:alert(1)",
                "file:///etc/hosts",
                "intent://icloud.com",
                "https://apple.com:8443/",
                "https://apple.com./",
                "bad uri",
            )
            .forEach { assertFalse(it, UrlPolicy.isApplePage(it)) }
    }

    @Test
    fun readOnlyTransportCannotCallSoundOrDeviceMutations() {
        val base = "https://p169-fmipweb.icloud.com/fmipservice/client/web/"
        assertTrue(UrlPolicy.isReadEndpoint(base + "refreshClient?dsid=test"))
        assertTrue(UrlPolicy.isReadEndpoint(base + "initClient"))
        listOf("playSound", "lostDevice", "remoteLock", "remoteWipe", "remove", "sendMessage")
            .forEach { assertFalse(it, UrlPolicy.isReadEndpoint(base + it)) }
        assertFalse(
            UrlPolicy.isReadEndpoint(
                "https://p169-fmipweb.icloud.com.evil.example/fmipservice/client/web/refreshClient"
            )
        )
    }

    @Test
    fun credentialsCannotBeEmbeddedInEndpointUrls() {
        assertFalse(
            UrlPolicy.isReadEndpoint(
                "https://user:password@p169-fmipweb.icloud.com/fmipservice/client/web/initClient"
            )
        )
        assertFalse(
            UrlPolicy.isReadEndpoint(
                "http://p169-fmipweb.icloud.com/fmipservice/client/web/initClient"
            )
        )
    }

    @Test
    fun explicitSoundTransportPreservesTheQueryAndRejectsOtherCommands() {
        val base = "https://p169-fmipweb.icloud.com/fmipservice/client/web/"
        val endpoint = UrlPolicy.soundEndpoint(base + "refreshClient?token=a%2Fb%2Bc&dsid=fixture")
        assertEquals(base + "playSound?token=a%2Fb%2Bc&dsid=fixture", endpoint)
        assertTrue(UrlPolicy.isSoundEndpoint(endpoint))
        assertFalse(UrlPolicy.isReadEndpoint(endpoint))
        listOf("remoteWipe", "remoteLock", "remove", "initClient", "playSound/extra").forEach {
            assertFalse(UrlPolicy.isSoundEndpoint(base + it))
        }
        listOf(
                "http://p169-fmipweb.icloud.com/fmipservice/client/web/playSound",
                "https://user@p169-fmipweb.icloud.com/fmipservice/client/web/playSound",
                "https://p169-fmipweb.icloud.com.evil.example/fmipservice/client/web/playSound",
                base + "playSound#fragment",
            )
            .forEach { assertFalse(UrlPolicy.isSoundEndpoint(it)) }
    }
}
