package dev.findbridge.android

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LostModeTest {
    private fun device(needsCode: Boolean = false) =
        AppleDevice.fromJson(
            JSONObject()
                .put("id", "fixture-only-device")
                .put("name", "Fixture Phone")
                .put("deviceClass", "iPhone")
                .put("lostModeCapable", true)
                .put("features", JSONObject().put("KPD", needsCode))
        )

    @Test
    fun commandRequiresSupportedAppleTargetAndOnlyCreatesALostModeRequest() {
        val target = device(true)
        val body =
            LostMode.body(target, LostModeDraft("+1 202 555 0100", "Synthetic test", "000000"))
        assertEquals("fixture-only-device", body.getString("device"))
        assertTrue(body.getBoolean("lostModeEnabled"))
        assertEquals("000000", body.getString("passcode"))
        assertFalse(body.has("authToken"))
        assertFalse(body.has("eraseDevice"))
        assertFalse(body.has("playSound"))
        val base =
            JSONObject()
                .put("serverContext", JSONObject().put("fixture-context", "fixture-value"))
                .put(
                    "clientContext",
                    JSONObject().put("apiVersion", "3.0").put("inactiveTime", 800),
                )
                .put("selectedDevice", "a-different-read-target")
        val contextual = LostMode.body(target, LostModeDraft("", "", "000000"), base)
        assertEquals(
            "fixture-value",
            contextual.getJSONObject("serverContext").getString("fixture-context"),
        )
        assertEquals("3.0", contextual.getJSONObject("clientContext").getString("apiVersion"))
        assertEquals(0, contextual.getJSONObject("clientContext").getInt("inactiveTime"))
        assertEquals(800, base.getJSONObject("clientContext").getInt("inactiveTime"))
        assertFalse(contextual.has("selectedDevice"))
        assertThrows(IllegalArgumentException::class.java) {
            LostMode.body(
                target.copy(provider = DeviceProvider.GOOGLE),
                LostModeDraft("", "", "000000"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LostMode.body(target, LostModeDraft("", "", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LostMode.body(
                target.copy(lostModeState = LostModeState.ENABLED),
                LostModeDraft("", "", "000000"),
            )
        }
        assertEquals(
            "LostModeDraft(redacted)",
            LostModeDraft("private", "private", "private").toString(),
        )
    }

    @Test
    fun resultDoesNotConfuseServerAcceptanceWithAnEnabledDevice() {
        assertEquals(LostModeResult.UNKNOWN, LostMode.result(JSONObject(), "fixture-only-device"))
        assertEquals(
            LostModeResult.AUTH_REQUIRED,
            LostMode.result(JSONObject().put("statusCode", "450"), "fixture-only-device"),
        )
        val target =
            JSONObject()
                .put("id", "fixture-only-device")
                .put("lostDevice", JSONObject().put("statusCode", "205"))
        val result = JSONObject().put("statusCode", "200").put("content", JSONArray().put(target))
        assertEquals(LostModeResult.REQUESTED, LostMode.result(result, "fixture-only-device"))
        assertEquals(LostModeState.PENDING, LostMode.state(target))
        assertEquals(LostModeResult.UNKNOWN, LostMode.result(result, "a-different-device"))
        target.getJSONObject("lostDevice").put("statusCode", "500")
        assertEquals(LostModeResult.REJECTED, LostMode.result(result, "fixture-only-device"))
        target.remove("lostDevice")
        target.put("lostModeEnabled", true)
        assertEquals(LostModeState.PENDING, LostMode.state(target))
        target.put("lostTimestamp", "1000")
        assertEquals(LostModeState.ENABLED, LostMode.state(target))
    }

    @Test
    fun nativePreviewRequiresFinalConfirmationAndBlocksRepeatedSubmission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var calls = 0
                val root = activity.findViewById<FrameLayout>(android.R.id.content)
                val form = LostModeForm(activity, device(), canceled = {}, confirmed = { calls++ })
                root.addView(form, FrameLayout.LayoutParams(-1, -1))
                val fields = children(form).filterIsInstance<EditText>()
                fields[0].setText("+1 202 555 0100")
                fields[1].setText("Synthetic message")
                assertEquals(0, calls)
                form.findViewById<View>(LostModeForm.CONTINUE_ID).performClick()
                assertEquals(0, calls)
                assertTrue(
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                )
                val confirm = form.findViewById<View>(LostModeForm.CONFIRM_ID)
                confirm.performClick()
                confirm.performClick()
                assertEquals(1, calls)
                root.removeView(form)
                assertTrue(fields.all { it.text.isEmpty() })
            }
        }
    }

    @Test
    fun cancelNeverCallsTheSender() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var calls = 0
                var canceled = false
                val root = activity.findViewById<FrameLayout>(android.R.id.content)
                val form =
                    LostModeForm(
                        activity,
                        device(),
                        canceled = { canceled = true },
                        confirmed = { calls++ },
                    )
                root.addView(form, FrameLayout.LayoutParams(-1, -1))
                children(form)
                    .filterIsInstance<Button>()
                    .first { it.text == activity.getString(R.string.cancel) }
                    .performClick()
                assertTrue(canceled)
                assertEquals(0, calls)
                root.removeView(form)
            }
        }
    }

    @Test
    fun googleFormShowsAccountConsequencesAndStillRequiresFinalConfirmation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var calls = 0
                val target =
                    device().copy(provider = DeviceProvider.GOOGLE, lostModeMessageLimit = 80)
                val root = activity.findViewById<FrameLayout>(android.R.id.content)
                val form = LostModeForm(activity, target, canceled = {}, confirmed = { calls++ })
                root.addView(form, FrameLayout.LayoutParams(-1, -1))
                assertTrue(
                    children(form).filterIsInstance<android.widget.TextView>().any {
                        it.text.toString() == activity.getString(R.string.google_lost_explanation)
                    }
                )
                val fields = children(form).filterIsInstance<EditText>()
                assertEquals(2, fields.size)
                fields[1].setText("Fixture recovery message")
                form.findViewById<View>(LostModeForm.CONTINUE_ID).performClick()
                assertEquals(0, calls)
                form.findViewById<View>(LostModeForm.CONFIRM_ID).performClick()
                assertEquals(1, calls)
                root.removeView(form)
            }
        }
    }

    private fun children(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
            else emptyList()
}
