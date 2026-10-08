package com.geoffchan.glucosewidget.wear

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON here is exactly what the phone's WatchProtocol encoders produce. */
class ProtocolTest {
    @Test fun `decodes the assistant's answer, and older proposals without one`() {
        val p = decodeProposal("""{"ok":true,"error":null,"parser":"openai","answer":"In range 82% this week.","rows":[]}""")
        org.junit.Assert.assertEquals("In range 82% this week.", p.answer)
        org.junit.Assert.assertEquals("openai", p.parser)
        org.junit.Assert.assertEquals("", decodeProposal("""{"ok":true,"error":null,"parser":"rules","rows":[]}""").answer)
    }

    @Test fun `decodes a phone proposal`() {
        val json = """{"ok":true,"error":null,"parser":"rules","rows":[
            {"text":"event: coffee @ 10:40","label":"coffee","time":"10:40 AM","status":"confirm"},
            {"text":"dose: long-acting 19u @ 10:40","label":"19u long","time":"10:40 AM","status":"already"}]}"""
        val p = decodeProposal(json)
        assertTrue(p.ok)
        assertNull(p.error)
        assertEquals("rules", p.parser)
        assertEquals(Row("dose: long-acting 19u @ 10:40", "19u long", "10:40 AM", Protocol.STATUS_ALREADY), p.rows[1])
    }

    @Test fun `decodes an error proposal`() {
        val p = decodeProposal("""{"ok":false,"error":"This phone isn't set as the main phone.","parser":"none","rows":[]}""")
        assertFalse(p.ok)
        assertEquals("This phone isn't set as the main phone.", p.error)
        assertTrue(p.rows.isEmpty())
    }

    @Test fun `save body lists the ticked texts`() {
        val o = JSONObject(encodeSave(listOf("event: coffee @ 10:40", "dose: short-acting 4u @ 10:40")))
        assertEquals(2, o.getJSONArray("texts").length())
        assertEquals("dose: short-acting 4u @ 10:40", o.getJSONArray("texts").getString(1))
    }

    @Test fun `saved summary`() {
        val s = decodeSaved("""{"ok":true,"error":null,"saved":2,"confirmed":1,"already":1}""")
        assertEquals("Saved 3 · 1 already logged", savedSummary(s))
        assertEquals("Nothing new to save", savedSummary(Saved(true, null, 0, 0, 0)))
        assertEquals("2 already logged", savedSummary(Saved(true, null, 0, 0, 2)))
    }
}
