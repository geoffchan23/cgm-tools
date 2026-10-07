package com.geoffchan.glucosewidget

import android.content.Context

/**
 * The one write path for journal rows. Every insert/edit/delete — the app's
 * dialogs, the auto-routines, the ADB receiver, the watch — goes through
 * here so the change is also handed to [Sync] for the other phone.
 */
object Journal {
    suspend fun insert(context: Context, entry: JournalEntity): Long {
        val dao = GlucoseDb.get(context).dao()
        val id = dao.insertJournal(entry)
        dao.clearTombstone(entry.uid)
        Sync.localChanged(context, listOf(entry.copy(id = id)), emptyList())
        return id
    }

    /** Saves [entry] as given; callers set updatedAtMs (it decides sync conflicts). */
    suspend fun update(context: Context, entry: JournalEntity) {
        GlucoseDb.get(context).dao().updateJournal(entry)
        Sync.localChanged(context, listOf(entry), emptyList())
    }

    suspend fun delete(context: Context, entry: JournalEntity) {
        val dao = GlucoseDb.get(context).dao()
        dao.deleteJournal(entry)
        val t = JournalTombstone(entry.uid, System.currentTimeMillis())
        dao.insertTombstone(t)
        Sync.localChanged(context, emptyList(), listOf(t))
    }
}
