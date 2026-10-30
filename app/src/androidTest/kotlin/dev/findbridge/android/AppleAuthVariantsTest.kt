package dev.findbridge.android

import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Intercepted Apple-origin fixtures. No real credentials or authentication requests. */
@RunWith(AndroidJUnit4::class)
class AppleAuthVariantsTest {
    @Test fun legacyArrowButtonConfirmsAccountBeforePassword() = runVariant("legacy")

    @Test fun japaneseContinueButtonWithoutLegacyIdCompletesBothSteps() = runVariant("continue")

    @Test fun passwordOnlyDocumentDoesNotWaitForAnAccountField() = runVariant("password")

    @Test fun continueInsideNestedAppleFramesReachesTheMainWebViewSafely() = runVariant("nested")

    private fun runVariant(variant: String) {
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web =
                    AuthActivity::class
                        .java
                        .getDeclaredField("webView")
                        .apply { isAccessible = true }
                        .get(activity) as WebView
                web.stopLoading()
                web.webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse {
                            val page =
                                when {
                                    variant != "nested" -> fixture(variant)
                                    request.url.host == "www.icloud.com" ->
                                        wrapper(
                                            "https://idmsa.apple.com/appleauth/auth/outer",
                                            null,
                                        )
                                    request.url.host == "idmsa.apple.com" ->
                                        wrapper(
                                            "https://account.apple.com/auth/signin",
                                            "https://www.icloud.com",
                                        )
                                    else ->
                                        fixture("continue")
                                            .replace(
                                                "<script>",
                                                "<script>setInterval(()=>parent.postMessage({type:'fixture-state',state:document.body.dataset.state},'https://idmsa.apple.com'),100);",
                                            )
                                }
                            return WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                ByteArrayInputStream(page.toByteArray()),
                            )
                        }
                    }
                AuthActivity::class
                    .java
                    .getDeclaredMethod("installAutomaticLogin", SavedLogin::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, SavedLogin("fixture@example.invalid", "fixture-password"))
                web.loadUrl(
                    if (variant == "nested") "https://www.icloud.com/find"
                    else "https://idmsa.apple.com/appleauth/auth/authorize/signin"
                )
            }
            val deadline = SystemClock.uptimeMillis() + 25000
            var state = ""
            while (state != "\"done\"" && SystemClock.uptimeMillis() < deadline) {
                val read = CountDownLatch(1)
                scenario.onActivity {
                    web.evaluateJavascript("document.body.dataset.state || ''") {
                        state = it
                        read.countDown()
                    }
                }
                assertTrue(read.await(3, TimeUnit.SECONDS))
                assertFalse(
                    "Fixture must receive trusted touches and the expected staged fields",
                    state.contains("bad-") || state.contains("untrusted"),
                )
                SystemClock.sleep(150)
            }
            assertEquals(
                "The $variant Apple form must complete without manual taps",
                "\"done\"",
                state,
            )
        }
    }

    private fun wrapper(child: String, parent: String?): String =
        """
        <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
        <body><iframe src="$child" style="margin:18px 10px;width:calc(100% - 24px);height:620px;border:0"></iframe>
        <script>addEventListener('message', e=>{if(e.data?.type==='fixture-state'){
            document.body.dataset.state=e.data.state;
            ${if(parent == null) "" else "window.parent.postMessage(e.data,'$parent');"}
        }});</script></body>
    """
            .trimIndent()

    private fun fixture(variant: String): String {
        val legacy = variant == "legacy"
        val passwordOnly = variant == "password"
        return """
            <!doctype html><html lang="ja"><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:28px;font:18px sans-serif}input,button{box-sizing:border-box;width:100%;height:56px;margin:12px 0}#secret{display:${if(passwordOnly) "block" else "none"}}</style></head>
            <body data-state="${if(passwordOnly) "password" else "account"}">
            ${if(passwordOnly) "" else "<label>Apple Account<input id='${if(legacy) "account_name_text_field" else "account"}' autocomplete='username' type='email'></label>"}
            <div id="secret"><label>Password<input id="${if(legacy) "password_text_field" else "secret-input"}" autocomplete="current-password" type="password"></label></div>
            <button id="${if(legacy) "sign-in" else "step-continue"}"><span>${if(legacy) "Sign in" else "続ける"}</span></button>
            <script>document.querySelector('button').onclick=e=>{
              if(!e.isTrusted){document.body.dataset.state='untrusted';return;}
              if(document.body.dataset.state==='account'){
                if(document.querySelector('input[type=email]').value!=='fixture@example.invalid'){document.body.dataset.state='bad-account';return;}
                if(document.querySelector('input[type=password]').value){document.body.dataset.state='bad-order';return;}
                document.body.dataset.state='password';document.querySelector('input[type=email]').readOnly=true;
                document.querySelector('#secret').style.display='block';
              } else if(document.body.dataset.state==='password') {
                document.body.dataset.state=document.querySelector('input[type=password]').value==='fixture-password'?'done':'bad-password';
              }
            };</script></body></html>
        """
            .trimIndent()
    }
}
