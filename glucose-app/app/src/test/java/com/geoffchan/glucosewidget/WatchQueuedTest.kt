package com.geoffchan.glucosewidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** Entries her watch held while the phone was away (/log/queued), phone side. */
class WatchQueuedTest {
    private fun row(text: String) = JournalEntity(day = "2026-10-07", text = text, createdAtMs = 0, updatedAtMs = 0)

    @Test fun `decodes the watch's queued entry and rejects junk`() {
        val q = decodeQueued("""{"id":"a1","text":"took 6","spokenAtMs":1759871400000}""")
        assertEquals(QueuedEntry("a1", "took 6", 1759871400000), q)
        assertNull(decodeQueued("""{"text":"took 6"}"""))
        assertNull(decodeQueued("""{"id":"","text":"x","spokenAtMs":1}"""))
        assertNull(decodeQueued("not json"))
    }

    @Test fun `ack says saved, duplicate or error`() {
        val ok = JSONObject(encodeQueuedAck("a1", 2, duplicate = false))
        assertTrue(ok.getBoolean("ok")); assertEquals(2, ok.getInt("saved")); assertFalse(ok.getBoolean("duplicate"))
        val dup = JSONObject(encodeQueuedAck("a1", 0, duplicate = true))
        assertTrue(dup.getBoolean("ok")); assertTrue(dup.getBoolean("duplicate"))
        val err = JSONObject(encodeQueuedAck("a1", 0, false, "nope"))
        assertFalse(err.getBoolean("ok")); assertEquals("nope", err.getString("error"))
    }

    @Test fun `a resent id is not saved twice`() {
        val first = rememberQueuedId(emptyList(), "a1")
        assertEquals(listOf("a1"), first)
        assertNull(rememberQueuedId(first!!, "a1"))
        assertEquals(listOf("a1", "b2"), rememberQueuedId(first, "b2"))
    }

    @Test fun `remembered ids are capped, oldest dropped`() {
        val many = (1..50).map { "id$it" }
        val next = rememberQueuedId(many, "new")!!
        assertEquals(50, next.size)
        assertEquals("id2", next.first()); assertEquals("new", next.last())
    }

    @Test fun `queued rows are saved as guesses, skipping what's already logged`() {
        val rows = parseSpoken("took 6 and a chicken burger", LocalTime.of(17, 40))
        assertEquals(
            listOf("dose: short-acting 6u @ 17:40 (guess)", "event: chicken burger @ 17:40 (guess)"),
            planQueuedSave(rows, emptyList()),
        )
        // the evening routine already logged dinner at 17:30
        val existing = listOf(row("dose: short-acting 6u @ 17:30"), row("event: chicken burger @ 17:30"))
        assertEquals(emptyList<String>(), planQueuedSave(rows, existing))
        // an earlier guess of the same thing isn't duplicated either
        assertEquals(emptyList<String>(), planQueuedSave(rows, listOf(row("dose: short-acting 6u @ 17:45 (guess)"), row("event: chicken burger @ 17:45 (guess)"))))
    }

    @Test fun `the same thing twice in one sentence saves once`() {
        val rows = listOf(
            ProposedEntry(false, name = "candy", time = LocalTime.of(3, 10)),
            ProposedEntry(false, name = "candy", time = LocalTime.of(3, 15)),
        )
        assertEquals(listOf("event: candy @ 03:10 (guess)"), planQueuedSave(rows, emptyList()))
    }

    @Test fun `prompt ties a stated time only to its own item`() {
        val p = describePrompt("x")
        assertTrue(p.contains("A stated time belongs only to the item it is attached to"))
        assertTrue(p.contains("the chicken burger and the 6 units are \"17:30\" and only the cookie is \"19:00\""))
    }

    @Test fun `watch wording stamps untimed items with the time she spoke`() {
        val w = watchParagraph("took 6", LocalTime.of(18, 5))
        assertTrue(w.startsWith("took 6 (Said at 18:05."))
        assertTrue(w.contains("happened at 18:05"))
    }
}
