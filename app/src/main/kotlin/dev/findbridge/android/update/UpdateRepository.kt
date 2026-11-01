package dev.findbridge.android.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.findbridge.android.BuildConfig
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

internal enum class UpdatePhase {
    IDLE,
    CHECKING,
    CURRENT,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY,
    INSTALLING,
    APPROVAL,
    FAILED,
}

internal data class UpdateState(val phase: UpdatePhase, val release: UpdateRelease?)

/**
 * DownloadManager owns network work; broadcasts and lifecycle events reconcile persistent state.
 */
internal class UpdateRepository private constructor(private val context: android.app.Application) {
    internal val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val cancellation = Executors.newSingleThreadExecutor()
    private val checkGeneration = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var activeClient: GitHubUpdateClient? = null

    private fun interruptCheck(): Int {
        val generation = checkGeneration.incrementAndGet()
        activeClient?.let { client -> cancellation.execute { runCatching { client.cancel() } } }
        return generation
    }

    private var launchChecked = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val downloads = context.getSystemService(DownloadManager::class.java)
    val apk
        get() = File(context.filesDir, "updates/ready.apk")

    var approval: Intent? = null
        private set

    val automatic
        get() = prefs.getBoolean("automatic", true)

    val previews
        get() =
            prefs.getBoolean(
                "previews",
                ReleaseVersion.parse(BuildConfig.VERSION_NAME)?.preview == true,
            )

    val intervalHours
        get() = prefs.getInt("intervalHours", 12).takeIf { it in listOf(0, 6, 12, 24) } ?: 12

    fun setInterval(hours: Int) {
        require(hours in listOf(0, 6, 12, 24))
        prefs.edit().putInt("intervalHours", hours).apply()
        UpdateScheduler.configure(context)
    }

    fun launched() {
        if (launchChecked || !automatic) return
        launchChecked = true
        check(force = intervalHours == 0)
    }

    val state: UpdateState
        get() =
            UpdateState(
                runCatching { UpdatePhase.valueOf(prefs.getString("phase", "IDLE")!!) }
                    .getOrDefault(UpdatePhase.IDLE),
                prefs.getString("release", null)?.let(UpdateRelease::decode),
            )

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    internal fun phase(value: UpdatePhase) {
        prefs.edit().putString("phase", value.name).apply()
        main.post { listeners.forEach { it() } }
    }

    internal fun work(action: () -> Unit) = executor.execute {
        try {
            action()
        } catch (_: Exception) {
            phase(UpdatePhase.FAILED)
        }
    }

    fun setAutomatic(value: Boolean) {
        if (!value) interruptCheck()
        prefs.edit().putBoolean("automatic", value).apply()
        UpdateScheduler.configure(context)
        if (value) {
            prefs.edit().remove("dismissedDigest").apply()
            check(force = true)
        } else
            work {
                if (state.phase == UpdatePhase.CHECKING)
                    phase(if (state.release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE)
                if (prefs.getBoolean("automaticDownload", false)) cancelDownload()
                if (prefs.getBoolean("automaticSession", false)) {
                    UpdateInstaller.abandon(context, this)
                    phase(if (apk.exists()) UpdatePhase.READY else UpdatePhase.AVAILABLE)
                }
            }
    }

    fun setPreviews(value: Boolean) {
        if (value == previews) return
        interruptCheck()
        prefs.edit().putBoolean("previews", value).remove("checkedAt").apply()
        work {
            cancelDownload()
            UpdateInstaller.abandon(context, this)
            apk.delete()
            prefs.edit().remove("release").apply()
            phase(UpdatePhase.IDLE)
        }
        check(force = true)
    }

    fun check(
        force: Boolean = false,
        allowed: () -> Boolean = { true },
        completed: () -> Unit = {},
    ) {
        val generation = interruptCheck()
        executor.execute {
            try {
                if (!allowed() || generation != checkGeneration.get()) return@execute
                reconcileDownload()
                if (!allowed()) return@execute
                if (
                    state.phase in
                        setOf(UpdatePhase.READY, UpdatePhase.APPROVAL, UpdatePhase.INSTALLING)
                ) {
                    maybeInstallAutomatically()
                    return@execute
                }
                if (prefs.getLong("downloadId", 0) != 0L) return@execute
                val age = System.currentTimeMillis() - prefs.getLong("checkedAt", 0)
                val interval =
                    if (intervalHours == 0) Long.MAX_VALUE else intervalHours * 60 * 60 * 1000L
                if (!force && age in 0 until interval) return@execute
                phase(UpdatePhase.CHECKING)
                val client = GitHubUpdateClient().also { activeClient = it }
                val channel = previews
                val suffix = if (channel) "preview" else "stable"
                val response = client.releases(prefs.getString("etag-$suffix", null), channel)
                val json =
                    response.body
                        ?: prefs.getString("catalog-$suffix", null)
                        ?: client.releases(null, channel).body!!
                if (!allowed() || channel != previews || generation != checkGeneration.get()) {
                    if (generation == checkGeneration.get())
                        phase(
                            if (state.release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE
                        )
                    return@execute
                }
                val latest = UpdateRelease.latest(json, BuildConfig.VERSION_NAME, channel)
                prefs
                    .edit()
                    .putString("catalog-$suffix", json)
                    .putString("etag-$suffix", response.etag)
                    .putLong("checkedAt", System.currentTimeMillis())
                    .putString("release", latest?.json())
                    .apply()
                phase(if (latest == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE)
                if (latest != null && automatic) enqueueDownload(latest, true)
            } catch (_: Exception) {
                if (generation == checkGeneration.get()) phase(UpdatePhase.FAILED)
            } finally {
                activeClient = null
                completed()
            }
        }
    }

    fun download() = work { state.release?.let { enqueueDownload(it, false) } }

    private fun enqueueDownload(release: UpdateRelease, wifiOnly: Boolean) {
        if (prefs.getLong("downloadId", 0) != 0L) return
        val directory =
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("No download storage")
        val target = File(directory, "app-update.apk")
        target.delete()
        val request =
            DownloadManager.Request(Uri.parse(release.url))
                .setTitle("Find Bridge ${release.version}")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    target.name,
                )
        if (wifiOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        val id = downloads.enqueue(request)
        prefs.edit().putLong("downloadId", id).putBoolean("automaticDownload", wifiOnly).apply()
        phase(UpdatePhase.DOWNLOADING)
    }

    fun downloadCompleted(id: Long, completed: () -> Unit) = executor.execute {
        try {
            if (id == prefs.getLong("downloadId", 0)) reconcileDownload()
        } catch (_: Exception) {
            cancelDownload()
            phase(UpdatePhase.FAILED)
        } finally {
            completed()
        }
    }

    fun resume() = work {
        val release = state.release
        if (
            release != null &&
                ReleaseVersion.parse(release.version)!! <=
                    ReleaseVersion.parse(BuildConfig.VERSION_NAME)!!
        ) {
            installed()
            return@work
        }
        reconcileDownload()
        if (state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.VERIFYING)
            phase(if (release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE)
        if (
            state.phase in setOf(UpdatePhase.INSTALLING, UpdatePhase.APPROVAL) &&
                context.packageManager.packageInstaller.getSessionInfo(
                    prefs.getInt("sessionId", 0)
                ) == null
        )
            phase(if (apk.exists()) UpdatePhase.READY else UpdatePhase.AVAILABLE)
    }

    private fun reconcileDownload() {
        val id = prefs.getLong("downloadId", 0)
        if (id == 0L) return
        val status =
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) DownloadManager.STATUS_FAILED
                else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                val release = state.release ?: error("No release")
                phase(UpdatePhase.VERIFYING)
                apk.parentFile!!.mkdirs()
                val temporary = File(apk.parentFile, "download.part")
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(downloads.openDownloadedFile(id))
                        .use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    // Qualify the assertion: this class also has check(force).
                                    kotlin.check(total <= release.size)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    UpdateApkVerifier(context).verify(temporary, release)
                    apk.delete()
                    kotlin.check(temporary.renameTo(apk))
                    apk.setReadOnly()
                    downloads.remove(id)
                    prefs.edit().remove("downloadId").apply()
                    phase(UpdatePhase.READY)
                    UpdateNotifications.ready(context, release)
                    maybeInstallAutomatically()
                } finally {
                    temporary.delete()
                }
            }
            DownloadManager.STATUS_FAILED -> {
                cancelDownload()
                phase(UpdatePhase.FAILED)
            }
            else -> phase(UpdatePhase.DOWNLOADING)
        }
    }

    fun cancel() {
        interruptCheck()
        work {
            cancelDownload()
            UpdateInstaller.abandon(context, this)
            apk.delete()
            UpdateNotifications.cancel(context)
            phase(if (state.release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE)
        }
    }

    private fun cancelDownload() {
        val id = prefs.getLong("downloadId", 0)
        if (id != 0L) downloads.remove(id)
        prefs.edit().remove("downloadId").remove("automaticDownload").apply()
        phase(if (state.release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE)
    }

    fun install() = work {
        val release = state.release ?: return@work
        UpdateInstaller.abandon(context, this)
        UpdateInstaller.install(context, this, release, automatic = false)
    }

    fun installWhenIdle(allowed: () -> Boolean = { true }, completed: () -> Unit = {}) =
        executor.execute {
            try {
                if (allowed()) {
                    reconcileDownload()
                    if (allowed()) maybeInstallAutomatically()
                }
            } catch (_: Exception) {
                phase(UpdatePhase.FAILED)
            } finally {
                completed()
            }
        }

    private fun maybeInstallAutomatically() {
        if (
            automatic &&
                state.phase == UpdatePhase.READY &&
                prefs.getString("dismissedDigest", null) != state.release?.sha256 &&
                UpdateInstaller.canInstallAutomatically(context)
        )
            state.release?.let { UpdateInstaller.install(context, this, it, automatic = true) }
    }

    fun pendingApproval(intent: Intent) {
        approval = intent
        phase(UpdatePhase.APPROVAL)
        state.release?.let { UpdateNotifications.ready(context, it, intent) }
    }

    fun takeApproval(): Intent? = approval.also { approval = null }

    fun installFailed(canceled: Boolean = false) {
        if (canceled) prefs.edit().putString("dismissedDigest", state.release?.sha256).apply()
        approval = null
        UpdateNotifications.cancel(context)
        prefs.edit().remove("sessionId").apply()
        phase(if (apk.exists()) UpdatePhase.READY else UpdatePhase.FAILED)
    }

    fun installed() {
        cancelDownload()
        apk.delete()
        prefs.edit().remove("sessionId").remove("release").remove("checkedAt").apply()
        UpdateNotifications.cancel(context)
        phase(UpdatePhase.IDLE)
    }

    companion object {
        @Volatile private var instance: UpdateRepository? = null

        fun get(context: Context): UpdateRepository =
            instance
                ?: synchronized(this) {
                    instance
                        ?: UpdateRepository(context.applicationContext as android.app.Application)
                            .also { instance = it }
                }
    }
}
