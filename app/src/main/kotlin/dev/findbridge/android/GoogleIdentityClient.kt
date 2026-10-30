package dev.findbridge.android

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

/** Verified Google identity only. No Find Hub permission or location is implied. */
internal class GoogleIdentity(val subject: String, val email: String)

internal object GoogleIdentityClient {
    private const val USERINFO = "https://openidconnect.googleapis.com/v1/userinfo"

    fun fetch(accessToken: String, expectedEmail: String?): GoogleIdentity {
        require(accessToken.isNotBlank())
        val connection = URI(USERINFO).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")
        try {
            check(connection.responseCode == 200) { "Google identity unavailable" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    check(output.size() + count <= 65_536)
                    output.write(chunk, 0, count)
                }
            }
            return parse(JSONObject(output.toString(Charsets.UTF_8.name())), expectedEmail)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(response: JSONObject, expectedEmail: String?): GoogleIdentity {
        val subject = response.optString("sub")
        val email = response.optString("email")
        require(subject.isNotBlank() && subject.length <= 255)
        require(email.length in 3..320 && '@' in email && !email.any { it.isISOControl() })
        require(response.optBoolean("email_verified", false))
        require(expectedEmail == null || expectedEmail.equals(email, ignoreCase = true))
        return GoogleIdentity(subject, email)
    }
}
