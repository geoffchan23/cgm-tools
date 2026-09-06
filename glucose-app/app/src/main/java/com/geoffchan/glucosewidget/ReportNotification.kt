package com.geoffchan.glucosewidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** "New weekly report" notification; tapping opens that report in ReportsActivity. */
object ReportNotification {
    private const val CHANNEL = "reports"

    fun show(context: Context, fileName: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weekly reports", NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val open = Intent(context, ReportsActivity::class.java)
            .putExtra(ReportsActivity.EXTRA_OPEN, fileName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, fileName.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Weekly glucose report")
            .setContentText(reportTitle(fileName) + " is ready")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(fileName.hashCode(), n)
    }
}
