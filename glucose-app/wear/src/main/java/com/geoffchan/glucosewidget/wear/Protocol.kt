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
