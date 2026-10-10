package com.geoffchan.glucosewidget

import android.content.Context
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Auto-logs the morning routine once per local day: coffee at 10:30
 * (Sundays 14:00). Until 2026-10-10 it also logged 4u short + 19u long;
 * she logs her doses herself now. An evening dinner
 * routine existed 2026-09-17 … 10-08 and was dropped; dinner is logged by
 * hand or voice.
 *
 * Runs from the 5-minute refresh worker, so entries appear within a few
 * minutes of their time (stamped at the routine time either way) and catch
 * up later if the phone was asleep. Anything already logged that morning
 * is not duplicated — see [routineMissing]. A wrong entry is deleted
 * in the app like any other row.
 */
object Routines {
    suspend fun ensure(context: Context, zone: ZoneId = ZoneId.systemDefault()) {
        if (!Store.isMainPhone(context)) return // the main phone logs; sync brings them here
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate().toString()
        val nowMinute = now.toLocalTime().toSecondOfDay() / 60
        val dao = GlucoseDb.get(context).dao()

        if (Store.routineLoggedDay(context) == today) return
        val routine = morningRoutine(now.dayOfWeek)
        if (nowMinute < LocalTime.parse(morningRoutineTime(now.dayOfWeek)).toSecondOfDay() / 60) return
        val texts = dao.dayJournalSince(today).filter { it.day == today }.map { it.text }
        val nowMs = System.currentTimeMillis()
        for (text in routineMissing(routine, texts, 0, ROUTINE_SPLIT_MINUTE)) {
            Journal.insert(
                context,
                JournalEntity(day = today, text = text, createdAtMs = nowMs, updatedAtMs = nowMs, scope = SCOPE_DAY),
            )
        }
        Store.saveRoutineLoggedDay(context, today)
    }
}
