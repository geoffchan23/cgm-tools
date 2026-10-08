package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pure half of two-phone sync: wire format, encryption and the merge
 * rules. No Android here, so it's all unit-tested; [Sync] does the I/O.
 *
 * A message is base64(nonce ‖ AES-256-GCM ciphertext) of a JSON payload
 * `{"v":1,"device":…,"rows":[…],"tombs":[…],"inter":[…]}`. The relay
 * (ntfy.sh) only ever sees the base64. "inter" (assistant interaction log
 * records, see InteractionCore.kt) is optional, so older payloads still decode.
 */
const val SYNC_FORMAT = 1

/** ntfy turns messages over 4096 bytes into attachments; stay well under. */
const val MAX_MESSAGE_BYTES = 3800

data class SyncRow(
    val uid: String,
    val day: String,
    val text: String,
    val scope: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class SyncTomb(val uid: String, val deletedAtMs: Long)

data class SyncPayload(
    val device: String,
    val rows: List<SyncRow> = emptyList(),
    val tombs: List<SyncTomb> = emptyList(),
    val v: Int = SYNC_FORMAT,
    val inter: List<SyncInteraction> = emptyList(),
)

fun JournalEntity.toSyncRow() = SyncRow(uid, day, text, scope, createdAtMs, updatedAtMs)

fun encodePayload(p: SyncPayload): String = JSONObject()
    .put("v", p.v)
    .put("device", p.device)
    .put("rows", JSONArray(p.rows.map {
        JSONObject().put("uid", it.uid).put("day", it.day).put("text", it.text).put("scope", it.scope)
            .put("createdAtMs", it.createdAtMs).put("updatedAtMs", it.updatedAtMs)
    }))
    .put("tombs", JSONArray(p.tombs.map { JSONObject().put("uid", it.uid).put("deletedAtMs", it.deletedAtMs) }))
    .apply { if (p.inter.isNotEmpty()) put("inter", JSONArray(p.inter.map { encodeSyncInteraction(it) })) }
    .toString()

/** Null for anything that isn't a well-formed payload of a format we know. */
fun decodePayload(json: String): SyncPayload? = runCatching {
    val o = JSONObject(json)
    val v = o.getInt("v")
    if (v != SYNC_FORMAT) return null
    val rows = o.optJSONArray("rows") ?: JSONArray()
    val tombs = o.optJSONArray("tombs") ?: JSONArray()
    val inter = o.optJSONArray("inter") ?: JSONArray()
    SyncPayload(
        device = o.getString("device"),
        rows = (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            SyncRow(
                r.getString("uid"), r.getString("day"), r.getString("text"),
                r.optString("scope", SCOPE_DAY), r.getLong("createdAtMs"), r.getLong("updatedAtMs"),
            )
        },
        tombs = (0 until tombs.length()).map { i ->
            val t = tombs.getJSONObject(i)
            SyncTomb(t.getString("uid"), t.getLong("deletedAtMs"))
        },
        v = v,
        inter = (0 until inter.length()).map { decodeSyncInteraction(inter.getJSONObject(it)) },
    )
}.getOrNull()

object SyncCrypto {
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun keyFromBase64(b64: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull()?.takeIf { it.size == 32 }

    fun encrypt(key: ByteArray, plaintext: String): String {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /** Null when the message wasn't made with this key or was tampered with. */
    fun decrypt(key: ByteArray, message: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(message.trim())
        if (bytes.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

/** Encrypted size of a plaintext of [plainBytes]: nonce + tag, base64-expanded. */
fun encryptedSize(plainBytes: Int): Int = (plainBytes + 12 + 16 + 2) / 3 * 4

/**
 * Split rows, tombstones and interaction records into payloads whose
 * encrypted message stays under [maxMessageBytes]. A single oversized item
 * still goes out alone (interaction records are trimmed beforehand, see
 * [trimForSync], so they fit).
 */
fun chunkPayloads(
    device: String,
    rows: List<SyncRow>,
    tombs: List<SyncTomb>,
    inter: List<SyncInteraction> = emptyList(),
    maxMessageBytes: Int = MAX_MESSAGE_BYTES,
): List<SyncPayload> {
    val out = mutableListOf<SyncPayload>()
    var curRows = mutableListOf<SyncRow>()
    var curTombs = mutableListOf<SyncTomb>()
    var curInter = mutableListOf<SyncInteraction>()
    fun size(r: List<SyncRow>, t: List<SyncTomb>, i: List<SyncInteraction>) =
        encryptedSize(encodePayload(SyncPayload(device, r, t, inter = i)).toByteArray(Charsets.UTF_8).size)
    fun flush() {
        if (curRows.isNotEmpty() || curTombs.isNotEmpty() || curInter.isNotEmpty()) out += SyncPayload(device, curRows, curTombs, inter = curInter)
        curRows = mutableListOf(); curTombs = mutableListOf(); curInter = mutableListOf()
    }
    for (r in rows) {
        if (size(curRows + r, curTombs, curInter) > maxMessageBytes) flush()
        curRows += r
    }
    for (t in tombs) {
        if (size(curRows, curTombs + t, curInter) > maxMessageBytes) flush()
        curTombs += t
    }
    for (i in inter) {
        if (size(curRows, curTombs, curInter + i) > maxMessageBytes) flush()
        curInter += i
    }
    flush()
    return out
}

enum class RowAction { INSERT, UPDATE, IGNORE }

/**
 * What to do with a row from the other phone. Last write wins on
 * updatedAtMs; on a tie the local copy stays. A row no newer than a local
 * delete of the same uid stays deleted.
 */
fun decideRow(remote: SyncRow, local: JournalEntity?, localTombAt: Long?): RowAction = when {
    local != null -> if (remote.updatedAtMs > local.updatedAtMs) RowAction.UPDATE else RowAction.IGNORE
    localTombAt != null && remote.updatedAtMs <= localTombAt -> RowAction.IGNORE
    else -> RowAction.INSERT
}

/** A remote delete removes the local row unless the row was edited after it. */
fun tombDeletesLocal(remote: SyncTomb, local: JournalEntity?): Boolean =
    local != null && local.updatedAtMs <= remote.deletedAtMs
