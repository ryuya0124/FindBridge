package dev.findbridge.android

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
            val text =
                connection.inputStream.use {
                    BoundedInput.utf8(it, 65_536, connection.contentLengthLong)
                }
            return parse(JSONObject(text), expectedEmail)
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
