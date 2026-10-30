package dev.findbridge.android

import java.net.URI
import java.util.Locale

/** Only public artwork CDNs; no account cookies or bearer tokens are sent to these URLs. */
internal object DeviceImageUrls {
    private val googleHosts =
        (1..6).flatMap { listOf("lh$it.googleusercontent.com", "lh$it.ggpht.com") }.toSet() +
            setOf("www.gstatic.com", "ssl.gstatic.com", "play-lh.googleusercontent.com")

    private fun uri(value: String): URI? = runCatching {
        require(value.length in 1..4096)
        URI(value).also {
            require(
                it.scheme == "https" &&
                    it.userInfo == null &&
                    it.port in listOf(-1, 443) &&
                    it.fragment == null
            )
        }
    }
        .getOrNull()

    fun google(value: String): String =
        uri(value)
            ?.takeIf { it.host?.lowercase(Locale.ROOT) in googleHosts }
            ?.toASCIIString()
            .orEmpty()

    fun apple(value: String): String =
        uri(value)
            ?.takeIf {
                it.host == "statici.icloud.com" &&
                    it.path.startsWith("/fmipmobile/deviceImages-9.0/") &&
                    it.path.endsWith(".png") &&
                    !it.path.contains("/../") &&
                    !it.path.contains("/./") &&
                    !it.rawPath.contains('%')
            }
            ?.toASCIIString()
            .orEmpty()

    fun allowed(value: String) = google(value).isNotEmpty() || apple(value).isNotEmpty()

    fun appleDevice(
        base: String,
        kind: String,
        model: String,
        color: String,
        online: Boolean,
    ): String {
        if (
            listOf(kind, model).any { !it.matches(Regex("[A-Za-z0-9,._-]{1,80}")) } ||
                !color.matches(Regex("[A-Za-z0-9,-]{0,100}"))
        )
            return ""
        val root = base.trimEnd('/').ifEmpty { "https://statici.icloud.com" }
        val variant = model + if (color.isEmpty()) "" else "-$color"
        return apple(
            "$root/fmipmobile/deviceImages-9.0/$kind/$variant/${if (online) "online" else "offline"}-sourcelist__3x.png"
        )
    }
}
