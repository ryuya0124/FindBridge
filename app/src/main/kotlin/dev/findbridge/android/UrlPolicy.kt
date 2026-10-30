package dev.findbridge.android

import java.net.URI
import java.util.Locale

internal object UrlPolicy {
    const val findUrl = "https://www.icloud.com/find"
    val credentialOrigins = setOf("https://idmsa.apple.com", "https://account.apple.com")
    val loginContainerOrigins = setOf("https://www.icloud.com", "https://www.icloud.com.cn")

    fun isCredentialOrigin(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme == "https" &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 443) &&
            uri.host in listOf("idmsa.apple.com", "account.apple.com")
    }
        .getOrDefault(false)

    fun isApplePage(address: String): Boolean = runCatching {
        val uri = URI(address)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        uri.scheme.equals("https", true) &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 443) &&
            listOf("icloud.com", "icloud.com.cn", "apple.com").any {
                host == it || host.endsWith(".$it")
            }
    }
        .getOrDefault(false)

    fun isReadEndpoint(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme == "https" &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 443) &&
            uri.host.orEmpty().matches(Regex("p[0-9]+-fmipweb\\.icloud\\.com(?:\\.cn)?")) &&
            uri.path.orEmpty().matches(Regex("/fmipservice/client/web/(initClient|refreshClient)"))
    }
        .getOrDefault(false)
}
