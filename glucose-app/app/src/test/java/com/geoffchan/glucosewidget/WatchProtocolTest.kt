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
        val existing = listOf(row("dose: long-acting 19u @ 10:30"), row("event: coffee @ 10:30 (guess)"))
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
        val existing = listOf(row("dose: long-acting 19u @ 10:30"), guess)
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
