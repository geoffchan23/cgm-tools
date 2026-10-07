package com.geoffchan.glucosewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoreTest {
    private val key = SyncCrypto.newKey()
    private fun row(uid: String, updated: Long, text: String = "event: coffee @ 10:30") =
        SyncRow(uid, "2026-10-07", text, SCOPE_DAY, 1_000L, updated)
    private fun local(uid: String, updated: Long) =
        JournalEntity(id = 7, day = "2026-10-07", text = "event: coffee @ 10:30", createdAtMs = 1_000L, updatedAtMs = updated, uid = uid)

    @Test fun `payload round-trips through encryption`() {
        val p = SyncPayload("dev1", listOf(row("a", 5, "event: Priscilla's cookie — ½ @ 19:00 (guess)")), listOf(SyncTomb("b", 9)))
        val msg = SyncCrypto.encrypt(key, encodePayload(p))
        assertFalse("relay must not see plaintext", msg.contains("cookie"))
        assertEquals(p, decodePayload(SyncCrypto.decrypt(key, msg)!!))
    }

    @Test fun `same plaintext encrypts differently each time (fresh nonce)`() {
        val plain = encodePayload(SyncPayload("d", listOf(row("a", 1))))
        assertNotEquals(SyncCrypto.encrypt(key, plain), SyncCrypto.encrypt(key, plain))
    }

    @Test fun `wrong key or tampering decrypts to null`() {
        val msg = SyncCrypto.encrypt(key, "hello")
        assertNull(SyncCrypto.decrypt(SyncCrypto.newKey(), msg))
        val bytes = java.util.Base64.getDecoder().decode(msg)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(SyncCrypto.decrypt(key, java.util.Base64.getEncoder().encodeToString(bytes)))
        assertNull(SyncCrypto.decrypt(key, "not base64 !!"))
    }

    @Test fun `unknown format version or junk is ignored`() {
        assertNull(decodePayload("""{"v":2,"device":"d","rows":[],"tombs":[]}"""))
        assertNull(decodePayload("plain text"))
    }

    @Test fun `key must be 32 bytes of base64`() {
        assertNull(SyncCrypto.keyFromBase64("abc"))
        val b64 = java.util.Base64.getEncoder().encodeToString(key)
        assertEquals(32, SyncCrypto.keyFromBase64(b64)!!.size)
    }

    @Test fun `chunks stay under the relay limit and keep every row`() {
        val rows = (1..300).map { row("uid%04d".format(it), it.toLong(), "event: half medium pizza hut and a donut @ 18:00 (guess)") }
        val tombs = (1..50).map { SyncTomb("t$it", it.toLong()) }
        val chunks = chunkPayloads("device-xyz", rows, tombs)
        assertTrue(chunks.size > 1)
        for (c in chunks) {
            val size = SyncCrypto.encrypt(key, encodePayload(c)).toByteArray().size
            assertTrue("chunk of $size bytes", size <= MAX_MESSAGE_BYTES)
        }
        assertEquals(rows, chunks.flatMap { it.rows })
        assertEquals(tombs, chunks.flatMap { it.tombs })
        assertTrue(chunkPayloads("d", emptyList(), emptyList()).isEmpty())
    }

    @Test fun `remote row - last write wins, ties keep local`() {
        assertEquals(RowAction.INSERT, decideRow(row("a", 5), null, null))
        assertEquals(RowAction.UPDATE, decideRow(row("a", 6), local("a", 5), null))
        assertEquals(RowAction.IGNORE, decideRow(row("a", 5), local("a", 5), null))
        assertEquals(RowAction.IGNORE, decideRow(row("a", 4), local("a", 5), null))
    }

    @Test fun `remote row older than a local delete stays deleted`() {
        assertEquals(RowAction.IGNORE, decideRow(row("a", 5), null, localTombAt = 5))
        assertEquals(RowAction.IGNORE, decideRow(row("a", 4), null, localTombAt = 5))
        // edited on the other phone after this phone deleted it: comes back
        assertEquals(RowAction.INSERT, decideRow(row("a", 6), null, localTombAt = 5))
    }

    @Test fun `remote delete removes the local row unless it was edited later`() {
        assertTrue(tombDeletesLocal(SyncTomb("a", 5), local("a", 5)))
        assertTrue(tombDeletesLocal(SyncTomb("a", 9), local("a", 5)))
        assertFalse(tombDeletesLocal(SyncTomb("a", 4), local("a", 5)))
        assertFalse(tombDeletesLocal(SyncTomb("a", 9), null))
    }
}
