package com.geoffchan.glucosewidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class WatchProtocolTest {
    private fun row(text: String) = JournalEntity(day = "2026-10-07", text = text, createdAtMs = 0, updatedAtMs = 0)

    @Test fun `proposal rows carry label, 12h time and status`() {
        val existing = listOf(row("dose: long-acting 19u @ 10:35"), row("event: coffee @ 10:30 (guess)"))
        val rows = parseSpoken("coffee and 19 and 4", LocalTime.of(10, 40)).mapNotNull { watchRow(it, existing) }
        assertEquals(
            listOf(
                WatchRow("event: coffee @ 10:40", "coffee", "10:40 AM", WatchProtocol.STATUS_CONFIRM),
                WatchRow("dose: long-acting 19u @ 10:40", "19u long", "10:40 AM", WatchProtocol.STATUS_ALREADY),
                WatchRow("dose: short-acting 4u @ 10:40", "4u short", "10:40 AM", WatchProtocol.STATUS_NEW),
            ),
            rows,
        )
        val json = JSONObject(encodeProposal(rows, "rules"))
        assertTrue(json.getBoolean("ok"))
        assertEquals("rules", json.getString("parser"))
        assertEquals(3, json.getJSONArray("rows").length())
        assertEquals("19u long", json.getJSONArray("rows").getJSONObject(1).getString("label"))
        assertEquals("already", json.getJSONArray("rows").getJSONObject(1).getString("status"))
    }

    @Test fun `error proposal`() {
        val json = JSONObject(encodeProposal(emptyList(), "none", "not the main phone"))
        assertFalse(json.getBoolean("ok"))
        assertEquals("not the main phone", json.getString("error"))
    }

    @Test fun `save round trip drops anything that isn't a dose or event line`() {
        val texts = listOf("dose: short-acting 4u @ 10:40", "event: coffee @ 10:40", "rm -rf", "event: x @ 10:40 (guess)")
        assertEquals(texts.take(2), decodeSave(encodeSave(texts)))
        assertEquals(emptyList<String>(), decodeSave("not json"))
    }

    @Test fun `save plan inserts new rows, confirms guesses, skips duplicates`() {
        val guess = row("event: coffee @ 10:30 (guess)")
        val existing = listOf(row("dose: long-acting 19u @ 10:35"), guess)
        val plan = planWatchSave(
            listOf("event: coffee @ 10:40", "dose: long-acting 19u @ 10:40", "dose: short-acting 4u @ 10:40", "dose: short-acting 4u @ 10:40"),
            existing,
        )
        assertEquals(
            listOf(
                WatchSaveOp.Confirm(guess, "event: coffee @ 10:40"),
                WatchSaveOp.Already("dose: long-acting 19u @ 10:40"),
                WatchSaveOp.Insert("dose: short-acting 4u @ 10:40"),
                WatchSaveOp.Already("dose: short-acting 4u @ 10:40"),
            ),
            plan,
        )
    }

    @Test fun `her own account replaces the auto-logged routine, any units`() {
        // Sat 2026-10-10: the routine logged 19u long + 4u short at 10:30, she said "23 long + 4 short" at 10:33
        val day = "2026-10-10"
        val long = row("dose: long-acting 19u @ 10:30").copy(day = day)
        val short = row("dose: short-acting 4u @ 10:30").copy(day = day)
        val rows = parseSpoken("23 long and 4 short", LocalTime.of(10, 33)).mapNotNull { watchRow(it, listOf(long, short)) }
        assertEquals(listOf(WatchProtocol.STATUS_REPLACE, WatchProtocol.STATUS_REPLACE), rows.map { it.status })
        assertEquals(
            listOf(WatchSaveOp.Confirm(long, "dose: long-acting 23u @ 10:33"), WatchSaveOp.Confirm(short, "dose: short-acting 4u @ 10:33")),
            planWatchSave(listOf("dose: long-acting 23u @ 10:33", "dose: short-acting 4u @ 10:33"), listOf(long, short)),
        )
        // exactly the routine row: nothing to change
        assertEquals(listOf(WatchSaveOp.Already("dose: short-acting 4u @ 10:30")), planWatchSave(listOf("dose: short-acting 4u @ 10:30"), listOf(short)))
        // once she's changed it, it's hers: a different dose is a second dose, the same one a duplicate
        val mine = long.copy(text = "dose: long-acting 23u @ 10:33")
        assertEquals(
            listOf(WatchSaveOp.Insert("dose: long-acting 5u @ 10:50"), WatchSaveOp.Already("dose: long-acting 23u @ 10:40")),
            planWatchSave(listOf("dose: long-acting 5u @ 10:50", "dose: long-acting 23u @ 10:40"), listOf(mine)),
        )
    }

    @Test fun `saved encoding`() {
        val json = JSONObject(encodeSaved(saved = 2, confirmed = 1, already = 1))
        assertTrue(json.getBoolean("ok"))
        assertEquals(2, json.getInt("saved"))
        assertEquals(1, json.getInt("confirmed"))
        assertEquals(1, json.getInt("already"))
    }

    @Test fun `proposal carries the assistant answer`() {
        val o = org.json.JSONObject(encodeProposal(emptyList(), "openai", answer = "In range 82% this week."))
        assertEquals("In range 82% this week.", o.getString("answer"))
        assertEquals("", org.json.JSONObject(encodeProposal(emptyList(), "rules")).getString("answer"))
    }
}

class WatchLogProtocolTest {
    @Test fun `proposal echoes the interaction id`() {
        val j = org.json.JSONObject(encodeProposal(emptyList(), "rules", id = "abc"))
        org.junit.Assert.assertEquals("abc", j.getString("id"))
        org.junit.Assert.assertTrue(org.json.JSONObject(encodeProposal(emptyList(), "rules")).isNull("id"))
    }

    @Test fun `save carries id and unticked for the log - old saves still decode`() {
        val json = encodeSave(listOf("dose: short-acting 3u @ 14:33"), "abc", listOf("event: butter @ 14:33"))
        org.junit.Assert.assertEquals(listOf("dose: short-acting 3u @ 14:33"), decodeSave(json))
        org.junit.Assert.assertEquals(SaveMeta("abc", listOf("event: butter @ 14:33")), decodeSaveMeta(json))
        org.junit.Assert.assertEquals(SaveMeta(null, emptyList()), decodeSaveMeta("""{"texts":[]}"""))
    }

    @Test fun `queued entries may name the parse that timed out`() {
        org.junit.Assert.assertEquals("p1", decodeQueued("""{"id":"q1","text":"candy","spokenAtMs":5,"parseId":"p1"}""")!!.parseId)
        org.junit.Assert.assertNull(decodeQueued("""{"id":"q1","text":"candy","spokenAtMs":5}""")!!.parseId)
    }
}
