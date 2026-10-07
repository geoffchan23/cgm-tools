package com.geoffchan.glucosewidget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Her watch's voice log, phone side (see [WatchProtocol]). The watch sends
 * what she said; this breaks it into rows (Gemini Nano if AICore lets it run
 * in the background — usually it doesn't — else [parseSpoken]) and replies
 * with a proposal; on her Save it writes the rows to today via [Journal].
 *
 * Only the main phone answers; Geoff's phone isn't paired to her watch anyway.
 */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        // Already on a background binder thread; blocking keeps the service alive until we reply.
        runBlocking {
            try {
                when (event.path) {
                    WatchProtocol.PATH_PARSE -> reply(event, WatchProtocol.PATH_PROPOSAL, propose(String(event.data, Charsets.UTF_8)))
                    WatchProtocol.PATH_SAVE -> reply(event, WatchProtocol.PATH_SAVED, save(String(event.data, Charsets.UTF_8)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "watch ${event.path} failed", e)
                val err = "Phone error: ${e.message}"
                val body = if (event.path == WatchProtocol.PATH_SAVE) encodeSaved(0, 0, 0, err) else encodeProposal(emptyList(), "none", err)
                runCatching { reply(event, if (event.path == WatchProtocol.PATH_SAVE) WatchProtocol.PATH_SAVED else WatchProtocol.PATH_PROPOSAL, body) }
            }
        }
    }

    private suspend fun reply(event: MessageEvent, path: String, body: String) {
        Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, path, body.toByteArray(Charsets.UTF_8)).await()
    }

    private suspend fun todayEntries(context: Context): Pair<String, List<JournalEntity>> {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        return today to GlucoseDb.get(context).dao().dayJournalSince(today).filter { it.day == today && it.scope == SCOPE_DAY }
    }

    private suspend fun propose(transcript: String): String {
        if (!Store.isMainPhone(this)) return encodeProposal(emptyList(), "none", "This phone isn't set as the main phone.")
        val now = LocalTime.now(ZoneId.systemDefault()).withSecond(0).withNano(0)
        val nano = tryNano(transcript, now)
        val (entries, parser) = if (!nano.isNullOrEmpty()) nano to "nano" else parseSpoken(transcript, now) to "rules"
        val (_, existing) = todayEntries(this)
        val rows = entries.mapNotNull { watchRow(if (it.time == null) it.copy(time = now) else it, existing) }
        Log.i(TAG, "parse via $parser: \"$transcript\" → ${rows.map { it.text }}")
        return encodeProposal(rows, parser)
    }

    /** Null when Nano isn't available, is blocked (background), is slow, or finds nothing. */
    private suspend fun tryNano(transcript: String, now: LocalTime): List<ProposedEntry>? = withTimeoutOrNull(NANO_TIMEOUT_MS) {
        val model = Generation.getClient()
        try {
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return@withTimeoutOrNull null
            val hhmm = "%02d:%02d".format(now.hour, now.minute)
            // Said into the watch in the moment: the prompt's meal-time defaults don't apply.
            val paragraph = "$transcript (Said at $hhmm. Anything without a stated time happened at $hhmm.)"
            val response = model.generateContent(
                generateContentRequest(TextPart(describePrompt(paragraph))) {
                    temperature = 0f; topK = 1; maxOutputTokens = 256
                },
            )
            parseBreakdown(response.candidates.firstOrNull()?.text.orEmpty()).entries.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.i(TAG, "Nano unavailable for the watch (${e.message}); using rules")
            null
        } finally {
            model.close()
        }
    }

    private suspend fun save(json: String): String {
        if (!Store.isMainPhone(this)) return encodeSaved(0, 0, 0, "This phone isn't set as the main phone.")
        val (today, existing) = todayEntries(this)
        val now = System.currentTimeMillis()
        var saved = 0; var confirmed = 0; var already = 0
        for (op in planWatchSave(decodeSave(json), existing)) when (op) {
            is WatchSaveOp.Insert -> {
                Journal.insert(this, JournalEntity(day = today, text = op.text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY))
                saved++
            }
            is WatchSaveOp.Confirm -> { Journal.update(this, op.guess.copy(text = op.text, updatedAtMs = now)); confirmed++ }
            is WatchSaveOp.Already -> already++
        }
        if (saved + confirmed > 0) GlucoseWidget().updateAll(this)
        return encodeSaved(saved, confirmed, already)
    }

    companion object {
        private const val TAG = "WatchLog"
        private const val NANO_TIMEOUT_MS = 5_000L // the watch waits ~15 s for the whole round trip
    }
}
