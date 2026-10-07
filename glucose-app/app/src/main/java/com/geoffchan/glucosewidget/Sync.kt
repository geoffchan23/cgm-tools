package com.geoffchan.glucosewidget

import android.content.Context

/** Two-phone journal sync (her phone is main; Geoff's is a full peer). */
object Sync {
    /** Called after every local journal write. */
    suspend fun localChanged(context: Context, rows: List<JournalEntity>, deleted: List<JournalTombstone>) {
        // implemented by the sync layer
    }
}
