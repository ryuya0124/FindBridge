package dev.findbridge.android

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleGmsUiTest {
    @Test
    fun nativeConnectionDoesNotCreateWebViewOrClaimDataAccessAfterCancelOrBrokerError() {
        ActivityScenario.launch(GoogleAuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val repository = GoogleRepository.get(activity)
                val previouslyConnected = repository.connected
                fun hasWebView(view: View): Boolean =
                    view is WebView ||
                        (view is ViewGroup &&
                            (0 until view.childCount).any { hasWebView(view.getChildAt(it)) })
                assertFalse(hasWebView(activity.window.decorView))
                assertNotNull(activity.findViewById<View>(GoogleAuthActivity.CONNECT_ID))
                GoogleAuthActivity::class
                    .java
                    .getDeclaredMethod(
                        "onActivityResult",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        android.content.Intent::class.java,
                    )
                    .apply { isAccessible = true }
                    .invoke(activity, 31, Activity.RESULT_CANCELED, null)
                assertEquals(
                    activity.getString(R.string.google_gms_help),
                    activity.findViewById<TextView>(GoogleAuthActivity.STATUS_ID).text,
                )
                GoogleAuthActivity::class
                    .java
                    .getDeclaredMethod("failed", Exception::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, ApiException(Status(8, "UNREGISTERED_ON_API_CONSOLE")))
                assertEquals(
                    activity.getString(R.string.google_gms_configuration),
                    activity.findViewById<TextView>(GoogleAuthActivity.STATUS_ID).text,
                )
                assertEquals(previouslyConnected, repository.connected)
                assertFalse(hasWebView(activity.window.decorView))
            }
        }
    }
}
