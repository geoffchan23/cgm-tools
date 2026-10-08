package com.geoffchan.glucosewidget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class InteractionRecordTest {
    private val ctx = InteractionContext("Thu 2026-10-08 14:33", "2026-10-08", "6.4 mmol/L Flat (3 min ago)", "Now: …\nFrancine: sourdough")

    private fun ai() = AssistantResult(
        answer = "Logged it.", detail = null, entries = emptyList(), rejected = 0, model = "gpt-6-luna",
        inputTokens = 3000, cachedTokens = 2000, outputTokens = 200, ms = 2100, reasoningTokens = 80, effort = "low",
    )

    @Test fun `data carries context, attempts, usage, trace and proposals`() {
        val trace = JSONArray().put(JSONObject().put("round", 1).put("calls", JSONArray()))
        val d = interactionData(
            ctx,
            listOf(ParseAttempt("openai", false, "timeout after 24000 ms", 24_000), ParseAttempt("rules", true)),
            listOf(ProposalLog("event: sourdough bread with 15 g cheddar @ 14:33", "new")),
            answer = "Logged it.", ai = ai(), trace = trace,
        )
        assertEquals("2026-10-08", d.getJSONObject("context").getString("targetDay"))
        assertEquals("6.4 mmol/L Flat (3 min ago)", d.getJSONObject("context").getString("currentReading"))
        val a = d.getJSONArray("attempts")
        assertEquals("timeout after 24000 ms", a.getJSONObject(0).getString("reason"))
        assertTrue(a.getJSONObject(1).getBoolean("ok"))
        assertEquals(80, d.getJSONObject("tokens").getInt("reasoning"))
        assertEquals("low", d.getString("effort"))
        assertTrue(d.getDouble("costUsd") > 0)
        assertEquals(1, d.getJSONArray("trace").length())
        assertEquals("new", d.getJSONArray("proposals").getJSONObject(0).getString("status"))
    }

    @Test fun `no model, no usage fields`() {
        val d = interactionData(ctx, listOf(ParseAttempt("rules", true)), emptyList())
        assertFalse(d.has("model")); assertFalse(d.has("trace")); assertTrue(d.isNull("error"))
    }

    @Test fun `outcome round trip`() {
        val o = Outcome(
            OUTCOME_SAVED, 123,
            listOf(SavedItem("event: cheese @ 14:33", "event: 15 g cheddar @ 14:33", "u1", "insert"), SavedItem(null, "dose: short-acting 3u @ 14:33", null, "already")),
            unticked = listOf("event: butter @ 14:33"),
        )
        assertEquals(o, decodeOutcome(encodeOutcome(o)))
        assertNull(decodeOutcome(null)); assertNull(decodeOutcome("nope"))
    }

    @Test fun `a save is never replaced by a later cancel, otherwise later wins`() {
        val saved = encodeOutcome(Outcome(OUTCOME_SAVED, 100))
        val cancelled = encodeOutcome(Outcome(OUTCOME_CANCELLED, 200))
        val timeout = encodeOutcome(Outcome(OUTCOME_TIMEOUT, 50))
        assertEquals(saved, mergeOutcome(saved, cancelled))
        assertEquals(saved, mergeOutcome(cancelled, saved))
        assertEquals(cancelled, mergeOutcome(timeout, cancelled))
        assertEquals(cancelled, mergeOutcome(cancelled, timeout)) // older doesn't win
        assertEquals(timeout, mergeOutcome(null, timeout))
        val guesses = encodeOutcome(Outcome(OUTCOME_GUESSES, 10))
        assertEquals(guesses, mergeOutcome(guesses, encodeOutcome(Outcome(OUTCOME_ASK_AGAIN, 999))))
    }
}

class InteractionSyncTest {
    private fun entity(data: String, input: String = "had sourdough bread with butter and jam and 15 g of cheddar cheese", outcome: String? = null) =
        AssistantLogEntity(uid = "u1", createdAtMs = 1, updatedAtMs = 2, source = SOURCE_WATCH, input = input, parser = "openai", data = data, outcome = outcome)

    private fun bigTrace(): JSONObject {
        val readings = JSONArray((0 until 150).map { JSONObject().put("t", "2026-10-08 %02d:%02d".format(it / 6, it % 6 * 10)).put("mmol", 6.0 + it % 7) })
        val calls = JSONArray()
            .put(JSONObject().put("name", "get_readings").put("args", JSONObject().put("from", "a").put("to", "b")).put("result", JSONObject().put("points", readings).toString()))
            .put(JSONObject().put("name", "reply").put("args", JSONObject().put("answer", "x")).put("result", "{\"ok\":true}"))
        return interactionData(
            InteractionContext("now", "2026-10-08", "6.4", "Now: … ".repeat(40)),
            listOf(ParseAttempt("openai", true, null, 3000)),
            (1..4).map { ProposalLog("event: thing $it @ 14:33", "new") },
            answer = "You went low at 3am after the late donut dose.", detail = "Detail ".repeat(100),
            trace = JSONArray().put(JSONObject().put("round", 1).put("calls", calls)),
        )
    }

    @Test fun `a small record syncs whole`() {
        val e = entity(interactionData(InteractionContext("now", "d", null), listOf(ParseAttempt("rules", true)), emptyList()).toString())
        assertEquals(e.data, trimForSync(e).data)
    }

    @Test fun `a big trace is trimmed to fit one relay message, keeping the eval essentials`() {
        val outcome = encodeOutcome(Outcome(OUTCOME_SAVED, 5, listOf(SavedItem("event: thing 1 @ 14:33", "event: thing 1 @ 14:33", "j1", "insert"))))
        val e = entity(bigTrace().toString(), outcome = outcome)
        assertTrue(syncInteractionBytes(e.toSyncInteraction()) > INTERACTION_SYNC_BUDGET)
        val t = trimForSync(e)
        assertTrue(syncInteractionBytes(t) <= INTERACTION_SYNC_BUDGET)
        assertEquals(e.input, t.input)
        assertEquals(outcome, t.outcome)
        val d = JSONObject(t.data)
        assertTrue(d.getBoolean("syncTrimmed"))
        assertEquals(4, d.getJSONArray("proposals").length())
        // and it really goes out as a single message
        val payloads = chunkPayloads("dev", emptyList(), emptyList(), listOf(t))
        assertEquals(1, payloads.size)
        val msg = SyncCrypto.encrypt(SyncCrypto.newKey(), encodePayload(payloads[0]))
        assertTrue(msg.length <= MAX_MESSAGE_BYTES)
    }

    @Test fun `even a huge input fits`() {
        val e = entity(bigTrace().toString(), input = "words ".repeat(2000))
        assertTrue(syncInteractionBytes(trimForSync(e)) <= INTERACTION_SYNC_BUDGET)
    }

    @Test fun `payloads carry interaction records and older payloads still decode`() {
        val i = SyncInteraction("u1", 1, 2, SOURCE_PHONE, "took 3", "rules", "{}", null)
        val p = SyncPayload("dev", inter = listOf(i))
        assertEquals(p, decodePayload(encodePayload(p)))
        val old = """{"v":1,"device":"d","rows":[],"tombs":[]}"""
        assertEquals(emptyList<SyncInteraction>(), decodePayload(old)!!.inter)
        assertFalse(encodePayload(SyncPayload("d")).contains("inter")) // unchanged wire format without records
    }

    @Test fun `remote records insert as not-full, then only newer outcomes move`() {
        val remote = SyncInteraction("u1", 1, 5, SOURCE_WATCH, "took 3", "openai", "{\"syncTrimmed\":true}", null)
        val inserted = applyRemoteInteraction(remote, null)!!
        assertFalse(inserted.full)
        assertEquals("took 3", inserted.input)

        val localFull = AssistantLogEntity(uid = "u1", createdAtMs = 1, updatedAtMs = 5, source = SOURCE_WATCH, input = "took 3", parser = "openai", full = true, data = "{\"trace\":[]}")
        assertNull(applyRemoteInteraction(remote, localFull)) // same updatedAtMs: ignore
        val saved = encodeOutcome(Outcome(OUTCOME_SAVED, 9))
        val updated = applyRemoteInteraction(remote.copy(updatedAtMs = 9, outcome = saved), localFull)!!
        assertEquals(saved, updated.outcome)
        assertEquals("{\"trace\":[]}", updated.data) // a full local trace is never replaced by a trimmed one
        assertEquals(9, updated.updatedAtMs)

        val notFull = localFull.copy(full = false, data = "{}")
        assertEquals("{\"syncTrimmed\":true}", applyRemoteInteraction(remote.copy(updatedAtMs = 9), notFull)!!.data)
        // a later cancel from the other phone doesn't undo a local save
        val localSaved = localFull.copy(outcome = saved)
        val cancel = encodeOutcome(Outcome(OUTCOME_CANCELLED, 20))
        assertEquals(saved, applyRemoteInteraction(remote.copy(updatedAtMs = 20, outcome = cancel), localSaved)!!.outcome)
    }

    @Test fun `parse requests - json with id, or the bare transcript from older watches`() {
        assertEquals(ParseRequest("abc", "took 6"), decodeParseRequest("""{"id":"abc","text":"took 6"}"""))
        assertEquals(ParseRequest(null, "took 6"), decodeParseRequest("took 6"))
        assertEquals(ParseRequest(null, "{not json"), decodeParseRequest("{not json"))
        assertEquals(WatchOutcome("abc", "cancelled"), decodeWatchOutcome("""{"id":"abc","kind":"cancelled"}"""))
        assertNull(decodeWatchOutcome("""{"id":"","kind":"cancelled"}"""))
    }

    @Test fun `long tool results are cut with a marker`() {
        val s = "x".repeat(MAX_TOOL_RESULT_CHARS + 50)
        val c = cut(s, MAX_TOOL_RESULT_CHARS)
        assertTrue(c.startsWith("x".repeat(MAX_TOOL_RESULT_CHARS)))
        assertTrue(c.endsWith("…[truncated 50 chars]"))
        assertEquals("short", cut("short", 10))
    }
}

/** v3 → v4 against Room's own schema export and a real SQLite. */
class MigrationTest {
    private fun roomCreateSql(): String {
        val f = File("schemas/com.geoffchan.glucosewidget.GlucoseDb/4.json")
        val entities = JSONObject(f.readText()).getJSONObject("database").getJSONArray("entities")
        val e = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == "assistant_log" }
        return e.getString("createSql").replace("\${TABLE_NAME}", "assistant_log")
    }

    @Test fun `migration creates exactly what Room expects`() {
        assertEquals(roomCreateSql(), MIGRATION_3_4_SQL.single())
    }

    @Test fun `migration runs on a v3 database and keeps the journal`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE journal (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, day TEXT NOT NULL, text TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, scope TEXT NOT NULL DEFAULT 'day', uid TEXT NOT NULL DEFAULT '')")
                s.execute("INSERT INTO journal (day, text, createdAtMs, updatedAtMs, uid) VALUES ('2026-10-08', 'event: coffee @ 10:30', 1, 1, 'j1')")
                MIGRATION_3_4_SQL.forEach { s.execute(it) }
                s.execute("INSERT INTO assistant_log VALUES ('u1', 1, 2, 'watch', 'took 3', 'rules', 1, '{}', NULL)")
                s.executeQuery("SELECT COUNT(*) FROM journal").use { r -> r.next(); assertEquals(1, r.getInt(1)) }
                s.executeQuery("SELECT outcome FROM assistant_log WHERE uid='u1'").use { r -> r.next(); assertNull(r.getString(1)) }
                val cols = s.executeQuery("PRAGMA table_info(assistant_log)").use { r ->
                    buildList { while (r.next()) add(r.getString("name")) }
                }
                assertEquals(listOf("uid", "createdAtMs", "updatedAtMs", "source", "input", "parser", "full", "data", "outcome"), cols)
            }
        }
        assertNotNull(MIGRATION_3_4)
    }
}
