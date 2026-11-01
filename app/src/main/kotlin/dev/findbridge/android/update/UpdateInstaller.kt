package dev.findbridge.android.update

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

internal object UpdateInstaller {
    fun canInstallAutomatically(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !context.packageManager.canRequestPackageInstalls())
            return false
        val processes =
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?: return false
        return processes.none {
            it.uid == Process.myUid() &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }
    }

    @android.annotation.SuppressLint(
        "ApplySharedPref"
    ) // Persist callback correlation before replacement can kill this process.
    fun install(
        context: Context,
        repository: UpdateRepository,
        release: UpdateRelease,
        automatic: Boolean,
    ) {
        check(context.packageManager.canRequestPackageInstalls())
        check(repository.prefs.getInt("sessionId", 0) == 0)
        UpdateApkVerifier(context).verify(repository.apk, release)
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(repository.apk.length())
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                if (Build.VERSION.SDK_INT >= 31)
                    setRequireUserAction(
                        if (automatic) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    )
                if (Build.VERSION.SDK_INT >= 33)
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, repository.apk.length()).use { output ->
                    repository.apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                check(
                    repository.prefs
                        .edit()
                        .putInt("sessionId", id)
                        .putBoolean("automaticSession", automatic)
                        .commit()
                )
                repository.phase(UpdatePhase.INSTALLING)
                val intent =
                    Intent(context, UpdateInstallReceiver::class.java)
                        .setAction("${context.packageName}.UPDATE_INSTALL_RESULT")
                val flags =
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val result = PendingIntent.getBroadcast(context, id, intent, flags).intentSender
                if (automatic && Build.VERSION.SDK_INT >= 34) {
                    installer.commitSessionAfterInstallConstraintsAreMet(
                        id,
                        result,
                        PackageInstaller.InstallConstraints.Builder()
                            .setAppNotForegroundRequired()
                            .setAppNotInteractingRequired()
                            .build(),
                        12 * 60 * 60 * 1000L,
                    )
                } else session.commit(result)
            }
        } catch (failure: Exception) {
            runCatching { installer.abandonSession(id) }
            repository.prefs.edit().remove("sessionId").apply()
            throw failure
        }
    }

    fun abandon(context: Context, repository: UpdateRepository) {
        val id = repository.prefs.getInt("sessionId", 0)
        if (id != 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        repository.prefs.edit().remove("sessionId").remove("automaticSession").apply()
        repository.takeApproval()
        UpdateNotifications.cancel(context)
    }
}
