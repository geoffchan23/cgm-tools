package com.geoffchan.glucosewidget.wear

import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch side of the phone's WatchProtocol (app/…/WatchProtocol.kt) — same
 * paths and field names; change both together.
 */
object Protocol {
    const val PATH_PARSE = "/log/parse"
    const val PATH_PROPOSAL = "/log/proposal"
    const val PATH_SAVE = "/log/save"
    const val PATH_SAVED = "/log/saved"
    const val PATH_QUEUED = "/log/queued"
    const val PATH_QUEUED_ACK = "/log/queued-ack"
    const val STATUS_ALREADY = "already"
    const val STATUS_CONFIRM = "confirm"
}

data class Row(val text: String, val label: String, val time: String, val status: String)

data class Proposal(val ok: Boolean, val error: String?, val parser: String, val rows: List<Row>)

data class Saved(val ok: Boolean, val error: String?, val saved: Int, val confirmed: Int, val already: Int)

private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)

fun decodeProposal(json: String): Proposal {
    val o = JSONObject(json)
    val arr = o.optJSONArray("rows") ?: JSONArray()
    val rows = (0 until arr.length()).map {
        val r = arr.getJSONObject(it)
        Row(r.getString("text"), r.getString("label"), r.getString("time"), r.getString("status"))
    }
    return Proposal(o.optBoolean("ok"), o.optNullableString("error"), o.optString("parser"), rows)
}

fun encodeSave(texts: List<String>): String = JSONObject().put("texts", JSONArray(texts)).toString()

fun decodeSaved(json: String): Saved {
    val o = JSONObject(json)
    return Saved(o.optBoolean("ok"), o.optNullableString("error"), o.optInt("saved"), o.optInt("confirmed"), o.optInt("already"))
}

/** What the ✓ screen says, e.g. "Saved 2 · 1 already logged". */
fun savedSummary(s: Saved): String {
    val parts = mutableListOf<String>()
    if (s.saved + s.confirmed > 0) parts += "Saved ${s.saved + s.confirmed}"
    if (s.already > 0) parts += "${s.already} already logged"
    return parts.joinToString(" · ").ifEmpty { "Nothing new to save" }
}

/** Something she said while the phone was out of reach, held until it's back. */
data class QueuedItem(val id: String, val text: String, val spokenAtMs: Long)

data class QueuedAck(val id: String, val ok: Boolean, val duplicate: Boolean)

fun encodeQueued(q: QueuedItem): String =
    JSONObject().put("id", q.id).put("text", q.text).put("spokenAtMs", q.spokenAtMs).toString()

fun decodeQueuedAck(json: String): QueuedAck {
    val o = JSONObject(json)
    return QueuedAck(o.optString("id"), o.optBoolean("ok"), o.optBoolean("duplicate"))
}

/** The ack settles the item (saved now, or saved by an earlier send). */
fun ackSettles(item: QueuedItem, ack: QueuedAck): Boolean = ack.id == item.id && (ack.ok || ack.duplicate)

fun encodeQueue(items: List<QueuedItem>): String =
    JSONArray(items.map { JSONObject().put("id", it.id).put("text", it.text).put("spokenAtMs", it.spokenAtMs) }).toString()

fun decodeQueue(json: String?): List<QueuedItem> = runCatching {
    val arr = JSONArray(json ?: return emptyList())
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        QueuedItem(o.getString("id"), o.getString("text"), o.getLong("spokenAtMs"))
    }
}.getOrDefault(emptyList())
