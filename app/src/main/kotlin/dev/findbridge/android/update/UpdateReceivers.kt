package dev.findbridge.android.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Untrusted extras cannot choose a file, release, or download: only our stored request is queried.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val repository = UpdateRepository.get(context)
        if (id <= 0 || id != repository.prefs.getLong("downloadId", 0)) return
        val pending = goAsync()
        repository.downloadCompleted(id) { pending.finish() }
    }
}

/** This receiver is reached only through our explicit PackageInstaller status PendingIntent. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repository = UpdateRepository.get(context)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id <= 0 || id != repository.prefs.getInt("sessionId", 0)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                repository.pendingApproval(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> repository.work { repository.installed() }
            else ->
                repository.installFailed(
                    canceled =
                        intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) ==
                            PackageInstaller.STATUS_FAILURE_ABORTED
                )
        }
    }
}

class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateScheduler.configure(context)
        val pending = goAsync()
        UpdateRepository.get(context).work {
            try {
                UpdateRepository.get(context).installed()
            } finally {
                pending.finish()
            }
        }
    }
}
