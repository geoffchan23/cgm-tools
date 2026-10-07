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
 *   adb shell am broadcast -n .../.IngestReceiver --es op report-ready --es day x --es name report-...html
 *
 *   adb shell am broadcast -n .../.IngestReceiver --es op describe-test --es day x --es text "'coffee and 19+4'"
 *     → logcat tag "Describe": Gemini Nano status, raw output and the validated
 *       rows. Read-only; nothing is saved. (AICore may refuse while the app
 *       is in the background — the log says so.)
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
                        Journal.insert(
                            context,
                            JournalEntity(day = day, text = text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY),
                        )
                        GlucoseWidget().updateAll(context)
                    }
                    "delete" -> {
                        val id = intent.getLongExtra("id", -1L)
                        dao.journalById(id)?.let { Journal.delete(context, it) }
                        GlucoseWidget().updateAll(context)
                    }
                    "report-ready" -> intent.getStringExtra("name")?.let { ReportNotification.show(context, it) }
                    "refresh" -> Refresh.enqueue(context)
                    "describe-test" -> describeTest(intent.getStringExtra("text").orEmpty())
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun describeTest(text: String) {
        val model = com.google.mlkit.genai.prompt.Generation.getClient()
        try {
            val status = model.checkStatus()
            android.util.Log.i("Describe", "status=$status (0=unavailable 1=downloadable 2=downloading 3=available)")
            if (status == com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE ||
                status == com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING
            ) {
                // a broadcast may only run ~10 s: kick the download off, log its start, return
                kotlinx.coroutines.withTimeoutOrNull(8_000) {
                    model.download().collect { android.util.Log.i("Describe", "download: $it") }
                }
                return
            }
            val raw = model.generateContent(
                com.google.mlkit.genai.prompt.generateContentRequest(com.google.mlkit.genai.prompt.TextPart(describePrompt(text))) {
                    temperature = 0f; topK = 1; maxOutputTokens = 256 // API maximum; five rows of JSON need ~150
                },
            ).candidates.firstOrNull()?.text.orEmpty()
            android.util.Log.i("Describe", "raw=$raw")
            val b = parseBreakdown(raw)
            android.util.Log.i("Describe", "rows=${b.entries.map { it.noteText() ?: "$it (no time)" }} rejected=${b.rejected}")
        } catch (e: Exception) {
            android.util.Log.w("Describe", "failed: $e")
        } finally {
            model.close()
        }
    }
}
