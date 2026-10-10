package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * The pure half of the assistant interaction log — what Francine said or
 * typed, what handled it, what it proposed, and what she did with it (the
 * eval label). No Android here, so it's unit-tested; [InteractionLog] does
 * the I/O. Records live in Room (`assistant_log`) on the phone where they
 * happened and sync to the other phone, trimmed to fit a relay message.
 */
const val SOURCE_WATCH = "watch"
const val SOURCE_WATCH_QUEUED = "watch-queued"
const val SOURCE_PHONE = "phone"
const val SOURCE_DEBUG = "debug"
const val SOURCE_CHAT = "chat" // the chat screen (Francine or Geoff, see data.chat.author)

/** What she did with a proposal. */
const val OUTCOME_SAVED = "saved" // saved (some of) the proposed rows
const val OUTCOME_GUESSES = "saved-as-guesses" // queued from the watch: saved unconfirmed
const val OUTCOME_ANSWERED = "answered" // read an answer with nothing to save
const val OUTCOME_CANCELLED = "cancelled"
const val OUTCOME_ASK_AGAIN = "ask-again" // threw it away and spoke/typed again
const val OUTCOME_TIMEOUT = "timeout" // the watch gave up waiting for the phone
const val OUTCOME_REPLIED = "replied" // answered the assistant's question (the reply is its own record)

/** Tool results bigger than this are cut in the local trace. */
const val MAX_TOOL_RESULT_CHARS = 20_000

/** One parser tried for an input: openai, nano, nano-screen, rules. */
data class ParseAttempt(val parser: String, val ok: Boolean, val reason: String? = null, val ms: Long = 0)

/** A row as shown to her: canonical journal text and its match status (new/already/confirm). */
data class ProposalLog(val text: String, val status: String)

/** What was known when she spoke: local time, the day entries go on, the current reading. */
data class InteractionContext(
    val now: String,
    val targetDay: String,
    val currentReading: String?,
    val userTurn: String? = null,
)

/** One saved row: what was proposed, what was saved (differs if she edited it), the journal row. */
data class SavedItem(val proposed: String?, val saved: String, val journalUid: String?, val op: String)

data class Outcome(
    val kind: String,
    val atMs: Long,
    val saved: List<SavedItem> = emptyList(),
    val unticked: List<String> = emptyList(),
    val note: String? = null,
)

fun cut(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max) + "…[truncated ${s.length - max} chars]"

private fun JSONObject.optNullableString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

/**
 * The `data` blob of a record: context, parsers tried, model usage, the
 * model trace (openai only) and what was shown to her.
 */
fun interactionData(
    ctx: InteractionContext,
    attempts: List<ParseAttempt>,
    proposals: List<ProposalLog>,
    answer: String = "",
    detail: String? = null,
    rejected: Int = 0,
    ai: AssistantResult? = null,
    trace: JSONArray? = null,
    error: String? = null,
    history: List<Turn> = emptyList(),
): JSONObject {
    val o = JSONObject()
        .put(
            "context",
            JSONObject().put("now", ctx.now).put("targetDay", ctx.targetDay)
                .put("currentReading", ctx.currentReading ?: JSONObject.NULL)
                .put("userTurn", ctx.userTurn ?: JSONObject.NULL),
        )
        .put("attempts", JSONArray(attempts.map {
            JSONObject().put("parser", it.parser).put("ok", it.ok).put("reason", it.reason ?: JSONObject.NULL).put("ms", it.ms)
        }))
        .put("answer", answer)
        .put("detail", detail ?: JSONObject.NULL)
        .put("proposals", JSONArray(proposals.map { JSONObject().put("text", it.text).put("status", it.status) }))
        .put("rejected", rejected)
        .put("error", error ?: JSONObject.NULL)
    if (ai != null) {
        o.put("model", ai.model).put("effort", ai.effort ?: JSONObject.NULL).put("ms", ai.ms)
            .put(
                "tokens",
                JSONObject().put("input", ai.inputTokens).put("cached", ai.cachedTokens)
                    .put("output", ai.outputTokens).put("reasoning", ai.reasoningTokens),
            )
            .put("costUsd", ai.estimatedCostUsd)
    }
    if (ai != null && (ai.changes.isNotEmpty() || ai.changeRejects.isNotEmpty())) {
        o.put("changes", encodeChanges(ai.changes)).put("changeRejects", JSONArray(ai.changeRejects))
    }
    if (ai?.awaitingAnswer == true) o.put("awaitingAnswer", true)
    if (history.isNotEmpty()) o.put("history", encodeHistory(history))
    if (trace != null && trace.length() > 0) o.put("trace", trace)
    return o
}

fun encodeOutcome(o: Outcome): String = JSONObject()
    .put("kind", o.kind)
    .put("atMs", o.atMs)
    .put("saved", JSONArray(o.saved.map {
        JSONObject().put("proposed", it.proposed ?: JSONObject.NULL).put("saved", it.saved)
            .put("journalUid", it.journalUid ?: JSONObject.NULL).put("op", it.op)
    }))
    .put("unticked", JSONArray(o.unticked))
    .put("note", o.note ?: JSONObject.NULL)
    .toString()

fun decodeOutcome(json: String?): Outcome? = runCatching {
    val o = JSONObject(json ?: return null)
    val saved = o.optJSONArray("saved") ?: JSONArray()
    val unticked = o.optJSONArray("unticked") ?: JSONArray()
    Outcome(
        kind = o.getString("kind"),
        atMs = o.optLong("atMs"),
        saved = (0 until saved.length()).map {
            val s = saved.getJSONObject(it)
            SavedItem(s.optNullableString("proposed"), s.getString("saved"), s.optNullableString("journalUid"), s.optString("op"))
        },
        unticked = (0 until unticked.length()).map { unticked.getString(it) },
        note = o.optNullableString("note"),
    )
}.getOrNull()

/**
 * Which outcome a record keeps when a second one arrives. What she saved is
 * the label that matters: a save is never replaced by a later cancel/ask
 * again/timeout (e.g. the watch gave up but the phone had already saved);
 * two saves combine (today's rows saved on the watch, then the other days'
 * changes reviewed on the phone); otherwise the later outcome wins.
 */
fun mergeOutcome(current: String?, incoming: String?): String? {
    val cur = decodeOutcome(current) ?: return incoming
    val inc = decodeOutcome(incoming) ?: return current
    val curSaved = cur.kind == OUTCOME_SAVED || cur.kind == OUTCOME_GUESSES
    val incSaved = inc.kind == OUTCOME_SAVED || inc.kind == OUTCOME_GUESSES
    return when {
        curSaved && !incSaved -> current
        !curSaved && incSaved -> incoming
        curSaved && incSaved -> encodeOutcome(
            Outcome(
                kind = if (cur.kind == OUTCOME_SAVED || inc.kind == OUTCOME_SAVED) OUTCOME_SAVED else OUTCOME_GUESSES,
                atMs = maxOf(cur.atMs, inc.atMs),
                saved = (cur.saved + inc.saved).distinct(),
                unticked = (cur.unticked + inc.unticked).distinct(),
                note = listOfNotNull(cur.note, inc.note).distinct().joinToString("; ").ifEmpty { null },
            ),
        )
        else -> if (inc.atMs >= cur.atMs) incoming else current
    }
}

// ---------------------------------------------------------------- sync

/**
 * A record on the relay. [data] may be trimmed to fit a message (see
 * [trimForSync]); the receiving phone then stores it with full=false and
 * never lets it overwrite a full local trace.
 */
data class SyncInteraction(
    val uid: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val source: String,
    val input: String,
    val parser: String,
    val data: String,
    val outcome: String?,
)

fun AssistantLogEntity.toSyncInteraction(data: String = this.data) =
    SyncInteraction(uid, createdAtMs, updatedAtMs, source, input, parser, data, outcome)

fun encodeSyncInteraction(i: SyncInteraction): JSONObject = JSONObject()
    .put("uid", i.uid).put("c", i.createdAtMs).put("u", i.updatedAtMs)
    .put("src", i.source).put("in", i.input).put("p", i.parser)
    .put("data", i.data).put("out", i.outcome ?: JSONObject.NULL)

fun decodeSyncInteraction(o: JSONObject): SyncInteraction = SyncInteraction(
    o.getString("uid"), o.getLong("c"), o.getLong("u"), o.getString("src"), o.getString("in"),
    o.getString("p"), o.getString("data"), o.optNullableString("out"),
)

/** Bytes a record takes inside a payload. */
fun syncInteractionBytes(i: SyncInteraction): Int = encodeSyncInteraction(i).toString().toByteArray(Charsets.UTF_8).size

/**
 * Plaintext budget for one record so that a payload holding just it still
 * encrypts under [MAX_MESSAGE_BYTES] (base64 of nonce + ciphertext + tag,
 * less the payload envelope).
 */
val INTERACTION_SYNC_BUDGET: Int = MAX_MESSAGE_BYTES / 4 * 3 - 12 - 16 - 200

/**
 * The record as it travels: the full trace stays on the phone where it
 * happened; what syncs is cut down step by step until it fits — tool
 * results, then the trace, then the long texts. The input, the proposals
 * and the outcome (the eval essentials) are kept as long as possible.
 */
fun trimForSync(e: AssistantLogEntity, budget: Int = INTERACTION_SYNC_BUDGET): SyncInteraction {
    fun fits(i: SyncInteraction) = syncInteractionBytes(i) <= budget
    val whole = e.toSyncInteraction()
    if (fits(whole)) return whole
    val d = runCatching { JSONObject(e.data) }.getOrDefault(JSONObject())
    d.put("syncTrimmed", true)

    // 1) tool results → their size only
    d.optJSONArray("trace")?.let { trace ->
        for (r in 0 until trace.length()) {
            val calls = trace.optJSONObject(r)?.optJSONArray("calls") ?: continue
            for (c in 0 until calls.length()) {
                val call = calls.optJSONObject(c) ?: continue
                call.optString("result").takeIf { call.has("result") }?.let {
                    call.remove("result"); call.put("resultChars", it.length)
                }
            }
        }
    }
    var i = e.toSyncInteraction(d.toString()); if (fits(i)) return i

    // 2) the trace → tool names per round
    d.optJSONArray("trace")?.let { trace ->
        d.remove("trace")
        d.put("toolCalls", JSONArray((0 until trace.length()).flatMap { r ->
            val calls = trace.optJSONObject(r)?.optJSONArray("calls") ?: JSONArray()
            (0 until calls.length()).map { calls.optJSONObject(it)?.optString("name").orEmpty() }
        }))
    }
    i = e.toSyncInteraction(d.toString()); if (fits(i)) return i

    // 3) long texts
    d.optJSONObject("context")?.remove("userTurn")
    d.optNullableString("detail")?.let { d.put("detail", cut(it, 300)) }
    d.put("answer", cut(d.optString("answer"), 300))
    i = e.toSyncInteraction(d.toString()); if (fits(i)) return i

    // 4) the input itself, then the proposal list
    i = e.toSyncInteraction(d.toString()).copy(input = cut(e.input, 600)); if (fits(i)) return i
    d.optJSONArray("proposals")?.let { p ->
        if (p.length() > 6) d.put("proposals", JSONArray((0 until 6).map { p.get(it) })).put("proposalsCut", p.length())
    }
    i = i.copy(data = d.toString()); if (fits(i)) return i

    // 5) last resort: the essentials only
    val minimal = JSONObject().put("syncTrimmed", true).put("parser", e.parser)
        .put("model", d.opt("model")).put("costUsd", d.opt("costUsd"))
    return i.copy(input = cut(e.input, 400), data = minimal.toString(), outcome = e.outcome?.let { cut(it, 800) })
}

enum class InteractionAction { INSERT, UPDATE, IGNORE }

/**
 * A record from the other phone. New → insert (marked not full). Known →
 * only a newer updatedAtMs counts, and then only the outcome moves (merged
 * by [mergeOutcome]); a local full trace is never replaced by a trimmed one.
 */
fun decideInteraction(remote: SyncInteraction, local: AssistantLogEntity?): InteractionAction = when {
    local == null -> InteractionAction.INSERT
    remote.updatedAtMs > local.updatedAtMs -> InteractionAction.UPDATE
    else -> InteractionAction.IGNORE
}

fun applyRemoteInteraction(remote: SyncInteraction, local: AssistantLogEntity?): AssistantLogEntity? =
    when (decideInteraction(remote, local)) {
        InteractionAction.INSERT -> AssistantLogEntity(
            uid = remote.uid, createdAtMs = remote.createdAtMs, updatedAtMs = remote.updatedAtMs,
            source = remote.source, input = remote.input, parser = remote.parser,
            full = false, data = remote.data, outcome = remote.outcome,
        )
        InteractionAction.UPDATE -> local!!.copy(
            updatedAtMs = remote.updatedAtMs,
            outcome = mergeOutcome(local.outcome, remote.outcome),
            data = if (local.full) local.data else remote.data,
        )
        InteractionAction.IGNORE -> null
    }

// ---------------------------------------------------------------- watch protocol bits

/**
 * /log/parse body: {"id","text","replyTo"?} from current watches, or the bare
 * transcript from older ones. [replyTo]: the record whose question she's answering.
 */
data class ParseRequest(val id: String?, val text: String, val replyTo: String? = null)

fun decodeParseRequest(body: String): ParseRequest {
    val t = body.trim()
    if (t.startsWith("{")) runCatching {
        val o = JSONObject(t)
        if (o.has("text")) return ParseRequest(
            o.optNullableString("id")?.takeIf { it.isNotBlank() }, o.getString("text"),
            o.optNullableString("replyTo")?.takeIf { it.isNotBlank() },
        )
    }
    return ParseRequest(null, body)
}

/** /log/outcome body: {"id","kind"}; fire-and-forget from the watch. */
data class WatchOutcome(val id: String, val kind: String)

fun decodeWatchOutcome(json: String): WatchOutcome? = runCatching {
    val o = JSONObject(json)
    WatchOutcome(o.getString("id"), o.getString("kind")).takeIf { it.id.isNotBlank() && it.kind.isNotBlank() }
}.getOrNull()
