package com.geoffchan.glucosewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val REFRESH_INTERVAL_MS = 5 * 60_000L

object Refresh {
    /** Fetch now (via WorkManager, so the network call survives the receiver). */
    fun enqueue(context: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("refresh", ExistingWorkPolicy.REPLACE, req)
    }

    /** Self-rescheduling exact alarm chain; falls back to inexact when not granted. */
    fun scheduleNext(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + REFRESH_INTERVAL_MS
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context) {
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java).cancel(pi)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Refresh.enqueue(context)
        Refresh.scheduleNext(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Refresh.enqueue(context)
            Refresh.scheduleNext(context)
        }
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val (username, password) = Store.credentials(ctx) ?: return Result.success()
        runCatching {
            val (history, session) = withContext(Dispatchers.IO) {
                ShareClient.fetchHistory(username, password, Store.session(ctx))
            }
            Store.saveSession(ctx, session)
            Store.saveReading(ctx, history.first()) // newest first; feeds the widget
            GlucoseDb.get(ctx).dao().insertReadings(
                history.map { ReadingEntity(it.timestampMs, it.mgdl, it.trend) },
            )
        }.onFailure { android.util.Log.w("GlucoseWidget", "refresh failed", it) }
        runCatching { MorningRoutine.ensure(ctx) }
            .onFailure { android.util.Log.w("GlucoseWidget", "routine failed", it) }
        // Always repaint: even on failure the age line must keep counting up.
        GlucoseWidget().updateAll(ctx)
        return Result.success()
    }
}
