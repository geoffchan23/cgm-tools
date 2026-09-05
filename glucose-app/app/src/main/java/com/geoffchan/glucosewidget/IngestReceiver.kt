package com.geoffchan.glucosewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only ADB entry point for Claude Code backfills. Inserting through
 * Room (instead of editing the SQLite file externally) keeps WAL
 * consistency while the app runs.
 *
 *   adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver \
 *     --es op insert --es day 2026-09-01 --es text "event: coffee @ 10:30"
 *
 *   adb shell am broadcast -n .../.IngestReceiver --es op delete --es day 2026-09-01 --el id 42
 *
 * Delete is by single row id only; there is deliberately no bulk delete,
 * since `event:` rows are user-logged data.
 */
class IngestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: return
        val day = intent.getStringExtra("day") ?: return
        val dao = GlucoseDb.get(context).dao()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (op) {
                    "insert" -> {
                        val text = intent.getStringExtra("text") ?: return@launch
                        val now = System.currentTimeMillis()
                        dao.insertJournal(
                            JournalEntity(day = day, text = text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY),
                        )
                        GlucoseWidget().updateAll(context)
                    }
                    "delete" -> {
                        val id = intent.getLongExtra("id", -1L)
                        dao.journalById(id)?.let { dao.deleteJournal(it) }
                        GlucoseWidget().updateAll(context)
                    }
                    "refresh" -> Refresh.enqueue(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
