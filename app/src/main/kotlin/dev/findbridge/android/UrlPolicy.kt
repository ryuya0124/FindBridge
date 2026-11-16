package dev.findbridge.android

import java.net.URI
import java.util.Locale

internal object UrlPolicy {
    const val findUrl = "https://www.icloud.com/find"
    val credentialOrigins = setOf("https://idmsa.apple.com", "https://account.apple.com")
    val loginContainerOrigins = setOf("https://www.icloud.com", "https://www.icloud.com.cn")
    private val credentialHosts = setOf("idmsa.apple.com", "account.apple.com")
    private val appleDomains = listOf("icloud.com", "icloud.com.cn", "apple.com")
    private val serviceHost = Regex("p[0-9]+-fmipweb\\.icloud\\.com(?:\\.cn)?")
    private const val servicePath = "/fmipservice/client/web/"
    private val readPaths = setOf("${servicePath}initClient", "${servicePath}refreshClient")
    private val soundPaths = setOf("${servicePath}playSound")
    private val lostModePaths =
        setOf("${servicePath}lostDevice", "${servicePath}lostDeviceWithFamilyAuth")
    private val notificationPaths = setOf("${servicePath}saveLocFoundPref")

    fun isSessionContainer(address: String): Boolean = runCatching {
        isApplePage(address) && URI(address).host in setOf("www.icloud.com", "www.icloud.com.cn")
    }
        .getOrDefault(false)

    fun isCredentialOrigin(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme == "https" &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 443) &&
            uri.host in credentialHosts
    }
        .getOrDefault(false)

    fun isApplePage(address: String): Boolean = runCatching {
        val uri = URI(address)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        uri.scheme.equals("https", true) &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 443) &&
            appleDomains.any {
                host == it || host.endsWith(".$it")
            }
    }
        .getOrDefault(false)

    private fun isEndpoint(address: String, paths: Set<String>): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme == "https" &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 443) &&
            uri.fragment == null &&
            serviceHost.matches(uri.host.orEmpty()) &&
            uri.rawPath == uri.path &&
            uri.path in paths
    }
        .getOrDefault(false)

    fun isReadEndpoint(address: String) = isEndpoint(address, readPaths)

    fun isSoundEndpoint(address: String) = isEndpoint(address, soundPaths)

    fun isLostModeEndpoint(address: String) = isEndpoint(address, lostModePaths)

    fun isNotificationEndpoint(address: String) = isEndpoint(address, notificationPaths)

    private fun endpoint(refreshAddress: String, action: String): String {
        require(isReadEndpoint(refreshAddress))
        val uri = URI(refreshAddress)
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://${uri.rawAuthority}$servicePath$action$query"
    }

    fun soundEndpoint(refreshAddress: String) = endpoint(refreshAddress, "playSound")

    fun lostModeEndpoint(refreshAddress: String, family: Boolean) =
        endpoint(refreshAddress, if (family) "lostDeviceWithFamilyAuth" else "lostDevice")

    fun notificationEndpoint(refreshAddress: String) = endpoint(refreshAddress, "saveLocFoundPref")
}
