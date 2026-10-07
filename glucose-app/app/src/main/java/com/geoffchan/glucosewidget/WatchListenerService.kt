package com.geoffchan.glucosewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Her watch's voice log, phone side (see [WatchProtocol]). The watch sends
 * what she said; [WatchParse] breaks it into rows and this replies with a
 * proposal; on her Save it writes the rows to today via [Journal]. Entries
 * the watch held while the phone was out of reach arrive on /log/queued and
 * are saved as guesses.
 *
 * Only the main phone answers; Geoff's phone isn't paired to her watch anyway.
 */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        // Already on a background binder thread; blocking keeps the service alive until we reply.
        runBlocking {
            val body = String(event.data, Charsets.UTF_8)
            try {
                when (event.path) {
                    WatchProtocol.PATH_PARSE -> reply(event, WatchProtocol.PATH_PROPOSAL, WatchParse.propose(this@WatchListenerService, body))
                    WatchProtocol.PATH_SAVE -> reply(event, WatchProtocol.PATH_SAVED, WatchParse.save(this@WatchListenerService, body))
                    WatchProtocol.PATH_QUEUED -> reply(event, WatchProtocol.PATH_QUEUED_ACK, WatchParse.saveQueued(this@WatchListenerService, body))
                }
            } catch (e: Exception) {
                Log.w(WatchParse.TAG, "watch ${event.path} failed", e)
                val (path, err) = when (event.path) {
                    WatchProtocol.PATH_SAVE -> WatchProtocol.PATH_SAVED to encodeSaved(0, 0, 0, WatchParse.SAVE_FAILED)
                    WatchProtocol.PATH_QUEUED -> WatchProtocol.PATH_QUEUED_ACK to
                        encodeQueuedAck(decodeQueued(body)?.id.orEmpty(), 0, false, WatchParse.SAVE_FAILED)
                    else -> WatchProtocol.PATH_PROPOSAL to encodeProposal(emptyList(), "none", WatchParse.READ_FAILED)
                }
                runCatching { reply(event, path, err) }
            }
        }
    }

    private suspend fun reply(event: MessageEvent, path: String, body: String) {
        Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, path, body.toByteArray(Charsets.UTF_8)).await()
    }
}

/** Parsing + saving for watch entries; shared by the listener and the ADB test op. */
object WatchParse {
    const val TAG = "WatchLog"
    const val NOT_MAIN = "Your phone isn't set up for watch logging yet."
    const val READ_FAILED = "Your phone couldn't read that — try again."
    const val SAVE_FAILED = "Your phone couldn't save that — try again."

    private const val DIRECT_TIMEOUT_MS = 4_000L // in-process Nano: works only if the app is on screen
    private const val SCREEN_TIMEOUT_MS = 12_000L // full-screen-intent Nano; the watch waits ~25 s in all
    private const val CHANNEL = "watch-voice-log"
    private const val NOTIFICATION_ID = 4711

    private suspend fun todayEntries(context: Context, day: String): List<JournalEntity> =
        GlucoseDb.get(context).dao().dayJournalSince(day).filter { it.day == day && it.scope == SCOPE_DAY }

    /** /log/parse → /log/proposal body. Nano when it can run, else [parseSpoken]. */
    suspend fun propose(context: Context, transcript: String): String {
        if (!Store.isMainPhone(context)) return encodeProposal(emptyList(), "none", NOT_MAIN)
        val zone = ZoneId.systemDefault()
        val now = LocalTime.now(zone).withSecond(0).withNano(0)
        val t0 = System.currentTimeMillis()
        val (entries, parser) = nanoRows(context, transcript, now) ?: (parseSpoken(transcript, now) to "rules")
        val today = LocalDate.now(zone).toString()
        val existing = todayEntries(context, today)
        val rows = entries.mapNotNull { watchRow(if (it.time == null) it.copy(time = now) else it, existing) }
        Log.i(TAG, "parse via $parser in ${System.currentTimeMillis() - t0} ms: \"$transcript\" → ${rows.map { it.text }}")
        return encodeProposal(rows, parser)
    }

    /** Rows from Gemini Nano with the parser name, or null to fall back to rules. */
    private suspend fun nanoRows(context: Context, transcript: String, now: LocalTime): Pair<List<ProposedEntry>, String>? {
        val model = Generation.getClient()
        val available = try {
            withTimeoutOrNull(DIRECT_TIMEOUT_MS) { model.checkStatus() } == FeatureStatus.AVAILABLE
        } catch (e: Exception) {
            false
        }
        if (!available) { model.close(); return null }
        val prompt = describePrompt(watchParagraph(transcript, now))

        // 1) The app may already be on screen (she has it open): just ask.
        val direct = try {
            withTimeoutOrNull(DIRECT_TIMEOUT_MS) {
                model.generateContent(generateContentRequest(TextPart(prompt)) { temperature = 0f; topK = 1; maxOutputTokens = 256 })
                    .candidates.firstOrNull()?.text
            }
        } catch (e: Exception) {
            Log.i(TAG, "Nano in-process refused (${e.message}); trying the lock-screen route")
            null
        } finally {
            model.close()
        }
        rowsOf(direct)?.let { return it to "nano" }

        // 2) Bring an invisible activity up over the lock screen and ask from there.
        return rowsOf(viaFullScreen(context, prompt))?.let { it to "nano-screen" }
    }

    private fun rowsOf(raw: String?): List<ProposedEntry>? =
        raw?.let { parseBreakdown(it).entries }?.takeIf { it.isNotEmpty() }

    private suspend fun viaFullScreen(context: Context, prompt: String): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.canUseFullScreenIntent()) {
            Log.i(TAG, "full-screen intents not allowed (appops USE_FULL_SCREEN_INTENT); using rules")
            return null
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watch voice log", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Briefly shown while the phone reads an entry spoken into the watch"
            },
        )
        val id = java.util.UUID.randomUUID().toString()
        val answer = NanoBridge.register(id)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, NanoParseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(NanoParseActivity.EXTRA_ID, id)
                .putExtra(NanoParseActivity.EXTRA_PROMPT, prompt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Reading your watch entry…")
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(SCREEN_TIMEOUT_MS + 3_000)
                .build(),
        )
        return try {
            withTimeoutOrNull(SCREEN_TIMEOUT_MS) { answer.await() }
        } catch (e: Exception) {
            null
        } finally {
            NanoBridge.cancel(id)
            nm.cancel(NOTIFICATION_ID)
        }
    }

    /** /log/save → /log/saved body. */
    suspend fun save(context: Context, json: String): String {
        if (!Store.isMainPhone(context)) return encodeSaved(0, 0, 0, NOT_MAIN)
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val existing = todayEntries(context, today)
        val now = System.currentTimeMillis()
        var saved = 0; var confirmed = 0; var already = 0
        for (op in planWatchSave(decodeSave(json), existing)) when (op) {
            is WatchSaveOp.Insert -> {
                Journal.insert(context, JournalEntity(day = today, text = op.text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY))
                saved++
            }
            is WatchSaveOp.Confirm -> { Journal.update(context, op.guess.copy(text = op.text, updatedAtMs = now)); confirmed++ }
            is WatchSaveOp.Already -> already++
        }
        if (saved + confirmed > 0) GlucoseWidget().updateAll(context)
        return encodeSaved(saved, confirmed, already)
    }

    /**
     * /log/queued → /log/queued-ack body. Rule parser only (no one is looking
     * at the phone and nothing can be confirmed), times relative to when she
     * spoke, saved as guesses to the day she spoke. Idempotent by id.
     */
    suspend fun saveQueued(context: Context, json: String): String {
        val q = decodeQueued(json) ?: return encodeQueuedAck("", 0, false, SAVE_FAILED)
        if (!Store.isMainPhone(context)) return encodeQueuedAck(q.id, 0, false, NOT_MAIN)
        val remembered = rememberQueuedId(Store.watchQueuedIds(context), q.id)
            ?: return encodeQueuedAck(q.id, 0, duplicate = true)
        val spokenAt = Instant.ofEpochMilli(q.spokenAtMs).atZone(ZoneId.systemDefault())
        val day = spokenAt.toLocalDate().toString()
        val rows = parseSpoken(q.text, spokenAt.toLocalTime().withSecond(0).withNano(0))
        val now = System.currentTimeMillis()
        val texts = planQueuedSave(rows, todayEntries(context, day))
        for (text in texts) {
            Journal.insert(context, JournalEntity(day = day, text = text, createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY))
        }
        Store.saveWatchQueuedIds(context, remembered)
        if (texts.isNotEmpty()) GlucoseWidget().updateAll(context)
        Log.i(TAG, "queued ${q.id} from $spokenAt: \"${q.text}\" → $texts")
        return encodeQueuedAck(q.id, texts.size, duplicate = false)
    }
}

/**
 * Said into the watch in the moment, so untimed items happened now — except
 * items from an earlier meal she mentions ("coffee this morning"), which get
 * that meal's usual time.
 */
fun watchParagraph(transcript: String, now: LocalTime): String {
    val hhmm = "%02d:%02d".format(now.hour, now.minute)
    return "$transcript (Said at $hhmm. Items with no stated time happened at $hhmm, " +
        "except items from a meal that was clearly earlier than $hhmm, which get that meal's usual time.)"
}
