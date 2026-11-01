package dev.findbridge.android.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.findbridge.android.AppLanguage
import dev.findbridge.android.R

internal object UpdateNotifications {
    private const val CHANNEL = "app_updates"
    private const val ID = 0x16fb0203

    fun ready(context: Context, release: UpdateRelease, approval: Intent? = null) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val localized = AppLanguage.wrap(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL,
                    localized.getString(R.string.updates_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                }
        )
        val intent =
            Intent(context, UpdateActivity::class.java).apply {
                if (approval != null) putExtra(UpdateActivity.APPROVAL, approval)
            }
        val pending =
            PendingIntent.getActivity(
                context,
                ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Find Bridge ${release.version}")
                .setContentText(localized.getString(R.string.update_ready))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun cancel(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(ID)
}
