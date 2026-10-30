package dev.findbridge.verification

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.ranging.RangingCapabilities
import android.ranging.RangingManager
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only, opt-in capability callback. Never creates or starts a ranging session. */
class UwbCapabilitiesProbeTest {
    @Test
    fun capturePublicUwbCapabilitiesWithoutTransmitting() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("uwbCapabilities") == "true")
        if (Build.VERSION.SDK_INT < 36) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val result = Bundle()
        val manager = context.getSystemService(RangingManager::class.java)
        result.putBoolean("rangingManagerAvailable", manager != null)
        if (manager == null) {
            instrumentation.sendStatus(0, result)
            return
        }
        val arrived = CountDownLatch(1)
        val received = AtomicReference<RangingCapabilities>()
        val callback = RangingManager.RangingCapabilitiesCallback {
            received.set(it)
            arrived.countDown()
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.RANGING)
        try {
            manager.registerCapabilitiesCallback(context.mainExecutor, callback)
            try {
                assertTrue(
                    "Capability callback did not arrive",
                    arrived.await(10, TimeUnit.SECONDS),
                )
                val caps = received.get()
                result.putInt(
                    "uwbAvailability",
                    caps.technologyAvailability[RangingManager.UWB] ?: -1,
                )
                val uwb = caps.uwbCapabilities
                result.putBoolean("uwbCapabilitiesAvailable", uwb != null)
                if (uwb != null) {
                    result.putBoolean("distance", uwb.isDistanceMeasurementSupported)
                    result.putBoolean("azimuth", uwb.isAzimuthalAngleSupported)
                    result.putBoolean("elevation", uwb.isElevationAngleSupported)
                    result.putIntegerArrayList("channels", ArrayList(uwb.supportedChannels))
                    result.putIntegerArrayList("configIds", ArrayList(uwb.supportedConfigIds))
                    result.putIntegerArrayList("preambles", ArrayList(uwb.supportedPreambleIndexes))
                    result.putIntegerArrayList(
                        "slotDurations",
                        ArrayList(uwb.supportedSlotDurations),
                    )
                }
                instrumentation.sendStatus(0, result)
            } finally {
                manager.unregisterCapabilitiesCallback(callback)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
