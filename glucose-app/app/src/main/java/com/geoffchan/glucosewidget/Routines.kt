package com.geoffchan.glucosewidget

import android.content.Context
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Auto-logs Geoff's fixed routines once per local day:
 *  - morning (10:30; Sundays 14:00): short-acting, long-acting, coffee
 *  - evening (Mon-Thu, 17:30): 6u short-acting
 *
 * Runs from the 5-minute refresh worker, so entries appear within a few
 * minutes of their time (stamped at the routine time either way) and catch
 * up later if the phone was asleep. Anything already logged in that half of
 * the day is not duplicated — see [routineMissing]. A wrong entry is deleted
 * in the app like any other row.
 */
object Routines {
    suspend fun ensure(context: Context, zone: ZoneId = ZoneId.systemDefault()) {
        if (!Store.isMainPhone(context)) return // the main phone logs; sync brings them here
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate().toString()
        val nowMinute = now.toLocalTime().toSecondOfDay() / 60
        val dao = GlucoseDb.get(context).dao()

        suspend fun run(
            routine: List<String>,
            due: String,
            from: Int,
            to: Int,
            anyEventCovers: Boolean,
            loggedDay: String?,
            markDone: suspend (String) -> Unit,
        ) {
            if (routine.isEmpty() || loggedDay == today) return
            if (nowMinute < LocalTime.parse(due).toSecondOfDay() / 60) return
            val texts = dao.dayJournalSince(today).filter { it.day == today }.map { it.text }
            val nowMs = System.currentTimeMillis()
            for (text in routineMissing(routine, texts, from, to, anyEventCovers)) {
                Journal.insert(
                    context,
                    JournalEntity(day = today, text = text, createdAtMs = nowMs, updatedAtMs = nowMs, scope = SCOPE_DAY),
                )
            }
            markDone(today)
        }

        run(
            morningRoutine(now.dayOfWeek), morningRoutineTime(now.dayOfWeek), 0, ROUTINE_SPLIT_MINUTE, false,
            Store.routineLoggedDay(context),
        ) { Store.saveRoutineLoggedDay(context, it) }

        run(
            eveningRoutine(now.dayOfWeek), EVENING_ROUTINE_TIME, ROUTINE_SPLIT_MINUTE, 24 * 60, true,
            Store.eveningRoutineLoggedDay(context),
        ) { Store.saveEveningRoutineLoggedDay(context, it) }
    }
}
