package dev.findbridge.android

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleGmsErrorTest {
    @Test
    fun meaningfulGmsErrorIsNotLostWhenActivityWasCanceled() {
        assertEquals(
            GoogleGmsError.CONFIGURATION,
            GoogleGmsError.classify(8, "[8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]."),
        )
        assertEquals(GoogleGmsError.CONFIGURATION, GoogleGmsError.classify(10, null))
    }

    @Test
    fun permissionFailureIsSeparateFromUserCancelAndNetwork() {
        assertEquals(GoogleGmsError.SCOPE, GoogleGmsError.classify(8, "invalid_scope"))
        assertEquals(GoogleGmsError.SCOPE, GoogleGmsError.classify(8, "access_denied"))
        assertEquals(GoogleGmsError.CANCELED, GoogleGmsError.classify(16, null))
        assertEquals(GoogleGmsError.OTHER, GoogleGmsError.classify(7, null))
    }
}
