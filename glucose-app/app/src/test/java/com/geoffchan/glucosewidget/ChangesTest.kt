package com.geoffchan.glucosewidget

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChangesTest {
    private val today = LocalDate.of(2026, 10, 8)
    private val rows = listOf(
        JournalEntity(id = 1, day = "2026-10-07", text = "event: coffee @ 10:30", createdAtMs = 0, updatedAtMs = 0, uid = "u1"),
        JournalEntity(id = 2, day = "2026-10-07", text = "dose: short-acting 4u @ 10:30", createdAtMs = 0, updatedAtMs = 0, uid = "u2"),
        JournalEntity(id = 3, day = "2026-09-20", text = "event: pizza @ 18:00", createdAtMs = 0, updatedAtMs = 0, uid = "u3"),
        JournalEntity(id = 4, day = "2026-10-06", text = "event: chicken burger @ 17:30 (guess)", createdAtMs = 0, updatedAtMs = 0, uid = "u4"),
    )

    private fun change(action: String, id: Long? = null, day: String? = null, kind: String? = null, type: String? = null, units: Int? = null, name: String? = null, time: String? = null) =
        org.json.JSONObject().put("action", action).put("id", id ?: org.json.JSONObject.NULL).put("day", day ?: org.json.JSONObject.NULL)
            .put("kind", kind ?: org.json.JSONObject.NULL).put("type", type ?: org.json.JSONObject.NULL).put("units", units ?: org.json.JSONObject.NULL)
            .put("name", name ?: org.json.JSONObject.NULL).put("time", time ?: org.json.JSONObject.NULL)

    private fun parse(vararg c: org.json.JSONObject) = runBlocking {
        parseChanges(org.json.JSONObject().put("changes", org.json.JSONArray(c.toList())).toString(), today) { id -> rows.firstOrNull { it.id == id } }
    }

    @Test fun `delete, edit and add become ops sorted by day and time`() {
        val cs = parse(
            change("delete", id = 1),
            change("edit", id = 2, kind = "dose", type = "short-acting", units = 3), // null time keeps 10:30
            change("add", day = "2026-10-05", kind = "event", name = "1 cup rice and half cup chicken stir fry", time = "17:30"),
            change("edit", id = 4, kind = "event", name = "1 cup rice and half cup chicken stir fry", time = "17:30"), // a guess, replaced
        )
        assertEquals(emptyList<String>(), cs.rejected)
        assertEquals(
            listOf(
                ChangeOp.Add("2026-10-05", "event: 1 cup rice and half cup chicken stir fry @ 17:30"),
                ChangeOp.Edit("2026-10-06", "u4", "event: chicken burger @ 17:30 (guess)", "event: 1 cup rice and half cup chicken stir fry @ 17:30"),
                ChangeOp.Delete("2026-10-07", "u1", "event: coffee @ 10:30"), // same time: kept in the order proposed
                ChangeOp.Edit("2026-10-07", "u2", "dose: short-acting 4u @ 10:30", "dose: short-acting 3u @ 10:30"),
            ),
            cs.ops,
        )
    }

    @Test fun `out of window, unknown, repeated, no-op and malformed changes are rejected with a reason`() {
        val cs = parse(
            change("delete", id = 3), // 18 days back
            change("delete", id = 42),
            change("delete", id = 1),
            change("edit", id = 1, kind = "event", name = "coffee", time = "10:30"), // already touched
            change("edit", id = 2, kind = "dose", type = "short-acting", units = 4), // same as logged
            change("add", day = "2026-10-09", kind = "event", name = "cake", time = "15:00"), // future
            change("add", day = "2026-10-07", kind = "event", name = "cake"), // no time
            change("add", day = "2026-10-07", kind = "dose", type = "fast", units = 3, time = "15:00"),
            change("rename", id = 2),
        )
        assertEquals(listOf(ChangeOp.Delete("2026-10-07", "u1", "event: coffee @ 10:30")), cs.ops)
        assertEquals(8, cs.rejected.size)
        assertTrue(cs.rejected[0].contains("outside the last 14 days"))
        assertTrue(cs.rejected[1].contains("no entry with id 42"))
    }

    @Test fun `applying skips rows changed or deleted since, and adds already there`() {
        val coffee = rows[0]
        assertEquals(ChangeStep.Remove(coffee), planChange(ChangeOp.Delete(coffee.day, coffee.uid, coffee.text), coffee, emptyList()))
        assertEquals(ChangeStep.Skip("already deleted"), planChange(ChangeOp.Delete(coffee.day, coffee.uid, coffee.text), null, emptyList()))
        assertEquals(
            ChangeStep.Skip("entry changed since"),
            planChange(ChangeOp.Edit(coffee.day, coffee.uid, coffee.text, "event: tea @ 10:30"), coffee.copy(text = "event: latte @ 10:30"), emptyList()),
        )
        assertEquals(ChangeStep.Skip("already logged"), planChange(ChangeOp.Add("2026-10-07", coffee.text), null, listOf(coffee.text)))
    }

    @Test fun `changes survive the interaction log round trip`() {
        val ops = listOf(
            ChangeOp.Add("2026-10-05", "event: rice @ 17:30"),
            ChangeOp.Edit("2026-10-06", "u4", "event: a @ 17:30", "event: b @ 17:30"),
            ChangeOp.Delete("2026-10-07", "u1", "event: coffee @ 10:30"),
        )
        assertEquals(ops, decodeChanges(encodeChanges(ops)))
        assertEquals("Remove 10:30 AM · coffee", changeLabel(ops[2]))
    }

    @Test fun `a reply carries the conversation so far, capped`() {
        val first = org.json.JSONObject().put("answer", "Which days?").toString()
        val h1 = historyAfter("past few days rice", first)
        assertEquals(listOf(Turn("past few days rice", "Which days?")), h1)
        val second = org.json.JSONObject().put("answer", "Dinner?").put("history", encodeHistory(h1)).toString()
        assertEquals(listOf(Turn("past few days rice", "Which days?"), Turn("mon to wed", "Dinner?")), historyAfter("mon to wed", second))
        val long = org.json.JSONObject().put("answer", "z").put("history", encodeHistory((1..9).map { Turn("$it", "$it") })).toString()
        assertEquals(MAX_HISTORY_TURNS, historyAfter("x", long).size)
    }

    @Test fun `two saves on one record combine`() {
        val watch = encodeOutcome(Outcome(OUTCOME_SAVED, 10, listOf(SavedItem("a", "a", "u", "insert"))))
        val phone = encodeOutcome(Outcome(OUTCOME_SAVED, 20, listOf(SavedItem("delete x", "", "u1", "delete")), listOf("add y")))
        val merged = decodeOutcome(mergeOutcome(watch, phone))!!
        assertEquals(listOf("insert", "delete"), merged.saved.map { it.op })
        assertEquals(20, merged.atMs)
        assertEquals(mergeOutcome(watch, phone), mergeOutcome(mergeOutcome(watch, phone), phone)) // idempotent
    }

    @Test fun `a watch reply names the record it answers`() {
        assertEquals(ParseRequest("b", "monday", "a"), decodeParseRequest("""{"id":"b","text":"monday","replyTo":"a"}"""))
        val p = org.json.JSONObject(encodeProposal(emptyList(), "openai", answer = "Which days?", changes = 3, awaiting = true))
        assertEquals(3, p.getInt("changes")); assertTrue(p.getBoolean("awaiting"))
    }
}
