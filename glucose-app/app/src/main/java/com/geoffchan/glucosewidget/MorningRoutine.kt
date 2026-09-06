package com.geoffchan.glucosewidget

import android.content.Context
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Auto-logs the fixed 10:30 routine (short-acting, long-acting, coffee)
 * once per local day. Runs from the 5-minute refresh worker, so the
 * entries appear within a few minutes of 10:30 (stamped @ 10:30 either
 * way) and catch up later if the phone was asleep. Entries the wearer
 * already logged by hand that day are not duplicated.
 */
object MorningRoutine {
    suspend fun ensure(context: Context, zone: ZoneId = ZoneId.systemDefault()) {
        val now = ZonedDateTime.now(zone)
        if (now.toLocalTime() < LocalTime.parse(ROUTINE_TIME)) return
        val today = now.toLocalDate().toString()
        if (Store.routineLoggedDay(context) == today) return

        val dao = GlucoseDb.get(context).dao()
        val todayTexts = dao.dayJournalSince(today).filter { it.day == today }.map { it.text }
        val nowMs = System.currentTimeMillis()
        for (text in routineMissing(morningRoutine(now.dayOfWeek), todayTexts)) {
            dao.insertJournal(JournalEntity(day = today, text = text, createdAtMs = nowMs, updatedAtMs = nowMs, scope = SCOPE_DAY))
        }
        Store.saveRoutineLoggedDay(context, today)
    }
}
