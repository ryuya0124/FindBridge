package dev.findbridge.android

import android.os.SystemClock
import android.view.View
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

    @Test fun onlyRequiredVerificationRevealsTheOfficialPage() = runVariant("verification")

    @Test
    fun accountSubmissionResumesAfterFocusIsLostForMoreThanFourSeconds() =
        runVariant("continue", true)

    @Test fun nestedFrameSubmissionResumesAfterFocusReturns() = runVariant("nested", true)

    private fun runVariant(variant: String, interruptFocus: Boolean = false) {
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
                                                """<script>
                                                const report=()=>parent.postMessage({type:'fixture-state',state:document.body.dataset.state},'https://idmsa.apple.com');
                                                new MutationObserver(report).observe(document.body,{subtree:true,attributes:true,childList:true});
                                                for(const name of ['findbridge.auth.result','findbridge.auth.resume','findbridge.auth.suspend','keydown','keyup','click'])
                                                  addEventListener(name,e=>parent.postMessage({type:'fixture-debug',event:name,target:e.target?.tagName,status:e.detail?.status,reason:e.detail?.reason},'https://idmsa.apple.com'),true);
                                                report();
                                                """,
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
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(AuthActivity.AUTO_STATUS_ID).visibility,
                )
                assertEquals(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    web.importantForAutofill,
                )
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    web.importantForAccessibility,
                )
                web.loadUrl(
                    if (variant == "nested") "https://www.icloud.com/find"
                    else "https://idmsa.apple.com/appleauth/auth/authorize/signin"
                )
                if (interruptFocus) {
                    val overlay =
                        android.app.AlertDialog.Builder(activity)
                            .setTitle("Focus interruption fixture")
                            .setMessage("Synthetic confirmation window")
                            .setCancelable(false)
                            .show()
                    web.postDelayed({ overlay.dismiss() }, 10_000)
                }
            }
            val deadline = SystemClock.uptimeMillis() + 25000
            var state = ""
            val expected = if (variant == "verification") "\"verification\"" else "\"done\""
            while (state != expected && SystemClock.uptimeMillis() < deadline) {
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
                "The $variant Apple form must complete without manual taps ${diagnostics(scenario, web)}",
                expected,
                state,
            )
            if (variant == "verification") {
                var revealed = false
                val revealUntil = SystemClock.uptimeMillis() + 3000
                while (!revealed && SystemClock.uptimeMillis() < revealUntil) {
                    scenario.onActivity {
                        revealed =
                            it.findViewById<View>(AuthActivity.AUTO_STATUS_ID).visibility ==
                                View.GONE
                    }
                    SystemClock.sleep(50)
                }
                assertTrue("Only required verification exposes the official input page", revealed)
            }
        }
    }

    private fun diagnostics(scenario: ActivityScenario<AuthActivity>, web: WebView): String {
        var metadata = ""
        val read = CountDownLatch(1)
        scenario.onActivity {
            web.evaluateJavascript("JSON.stringify(window.fixtureDebug || [])") {
                metadata = it
                read.countDown()
            }
        }
        read.await(2, TimeUnit.SECONDS)
        return metadata
    }

    private fun wrapper(child: String, parent: String?): String =
        """
        <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
        <body><iframe src="$child" style="margin:18px 10px;width:calc(100% - 24px);height:620px;border:0"></iframe>
        <script>window.fixtureDebug=[];addEventListener('message',e=>{if(e.data?.type==='fixture-debug'){
            window.fixtureDebug.push(e.data);if(window.fixtureDebug.length>24)window.fixtureDebug.shift();
            ${if(parent == null) "" else "window.parent.postMessage(e.data,'$parent');"}
        }});addEventListener('message', e=>{if(e.data?.type==='fixture-state'){
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
                ${if(variant=="verification") "document.body.dataset.state='verification';document.querySelector('#secret').style.display='block';document.querySelector('#secret').innerHTML='<input autocomplete=one-time-code>';return;" else ""}
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
