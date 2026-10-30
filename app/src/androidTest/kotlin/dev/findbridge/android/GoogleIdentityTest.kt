package dev.findbridge.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleIdentityTest {
    @Test
    fun onlyVerifiedIdentityForTheSelectedAccountIsAccepted() {
        val response =
            JSONObject()
                .put("sub", "fixture-subject")
                .put("email", "owner@example.com")
                .put("email_verified", true)
        assertEquals(
            "fixture-subject",
            GoogleIdentityClient.parse(response, "owner@example.com").subject,
        )
        assertTrue(
            runCatching { GoogleIdentityClient.parse(response, "another@example.com") }.isFailure
        )
        response.put("email_verified", false)
        assertTrue(
            runCatching { GoogleIdentityClient.parse(response, "owner@example.com") }.isFailure
        )
        response.put("email_verified", true).put("sub", "")
        assertTrue(runCatching { GoogleIdentityClient.parse(response, null) }.isFailure)
    }
}
