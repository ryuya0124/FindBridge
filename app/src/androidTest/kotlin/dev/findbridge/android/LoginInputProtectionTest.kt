package dev.findbridge.android

import android.text.method.PasswordTransformationMethod
import android.view.WindowManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginInputProtectionTest {
    @Test
    fun loginEntryIsMaskedSecureAndExcludedFromPlaintextSavedState() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val password = activity.findViewById<EditText>(LoginActivity.PASSWORD_ID)
                assertTrue(password.transformationMethod is PasswordTransformationMethod)
                assertFalse(password.isSaveEnabled)
                assertFalse(activity.findViewById<EditText>(LoginActivity.ACCOUNT_ID).isSaveEnabled)
                assertTrue(
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                )
            }
        }
    }
}
