package dev.findbridge.android

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Captures read responses from trusted iCloud documents, including the embedded Find app. */
internal object AppleSessionObserver {
    @android.annotation.SuppressLint("RequiresFeature")
    fun install(
        view: WebView,
        script: String,
        response: (String) -> Unit,
        state: (String) -> Unit,
    ): Boolean {
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        )
            return false
        val origins = UrlPolicy.loginContainerOrigins
        fun trusted(origin: String) =
            origin.trimEnd('/') in origins && UrlPolicy.isSessionContainer(view.url.orEmpty())
        WebViewCompat.addWebMessageListener(view, "FindBridgeSessionState", origins) {
            _,
            message,
            origin,
            _,
            _ ->
            if (trusted(origin.toString())) {
                message.data
                    ?.takeIf { it in setOf("authenticationRequired", "serviceUnavailable") }
                    ?.let(state)
            }
        }
        WebViewCompat.addWebMessageListener(view, "FindBridgeData", origins) {
            _,
            message,
            origin,
            _,
            _ ->
            if (trusted(origin.toString())) message.data?.let(response)
        }
        WebViewCompat.addDocumentStartJavaScript(view, script, origins)
        return true
    }
}
