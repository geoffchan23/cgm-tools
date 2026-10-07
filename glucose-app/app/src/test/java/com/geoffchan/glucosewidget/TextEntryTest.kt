package com.geoffchan.glucosewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class GuessEntryTest {
    @Test fun `guess suffix parses and is flagged`() {
        val d = parseDoseNote("dose: short-acting 6u @ 17:30 (guess)")!!
        assertTrue(d.isShort); assertEquals(6, d.units); assertEquals(17 * 60 + 30, d.minuteOfDay); assertTrue(d.isGuess)
        val e = parseEventNote("event: pizza @ 18:00 (guess)")!!
        assertEquals("pizza", e.name); assertEquals(18 * 60, e.minuteOfDay); assertTrue(e.isGuess)
    }

    @Test fun `normal entries are not guesses`() {
        assertFalse(parseDoseNote("dose: long-acting 19u @ 10:30")!!.isGuess)
        assertFalse(parseEventNote("event: coffee @ 10:30")!!.isGuess)
        // "(guess)" inside a name is just a name; only the exact suffix counts
        assertEquals("guess (guess) what", parseEventNote("event: guess (guess) what @ 12:00")!!.name)
    }

    @Test fun `keep strips the suffix to the canonical text`() {
        assertEquals("dose: short-acting 6u @ 17:30", confirmGuess("dose: short-acting 6u @ 17:30 (guess)"))
        assertEquals("event: coffee @ 10:30", confirmGuess("event: coffee @ 10:30"))
        assertTrue(isGuessEntry("event: pizza @ 18:00 (guess)"))
    }

    @Test fun `list label marks guesses`() {
        assertEquals("6:00 PM · pizza · guess", entryLabel("event: pizza @ 18:00 (guess)"))
        assertEquals("5:30 PM · 6u short-acting · guess", entryLabel("dose: short-acting 6u @ 17:30 (guess)"))
    }

    private fun entry(id: Long, day: String, text: String) =
        JournalEntity(id = id, day = day, text = text, createdAtMs = id, updatedAtMs = id)

    @Test fun `widget ignores guesses`() {
        val zone = ZoneId.of("America/Toronto")
        val entries = listOf(
            entry(1, "2026-09-25", "dose: short-acting 4u @ 10:30"),
            entry(2, "2026-09-25", "dose: short-acting 6u @ 17:30 (guess)"),
            entry(3, "2026-09-25", "event: coffee @ 10:30"),
            entry(4, "2026-09-25", "event: pizza @ 18:00 (guess)"),
        )
        assertEquals("▲ 4u", latestDose(entries, zone)!!.label)
        assertEquals("◆ coffee", latestEvent(entries, zone)!!.label)
    }

    @Test fun `guesses still cover routines so nothing double-logs`() {
        val evening = eveningRoutine(java.time.DayOfWeek.MONDAY)
        val logged = listOf("dose: short-acting 6u @ 17:30 (guess)", "event: pizza @ 18:00 (guess)")
        assertTrue(routineMissing(evening, logged, ROUTINE_SPLIT_MINUTE, 24 * 60, anyEventCovers = true).isEmpty())
    }

    @Test fun `chart markers carry the guess flag`() {
        val (doses, events) = markerData(
            listOf(entry(1, "2026-09-25", "dose: short-acting 6u @ 17:30 (guess)"), entry(2, "2026-09-25", "event: pizza @ 18:00")),
            LocalDate.parse("2026-09-25"),
        )
        assertTrue(doses.single().second.isGuess)
        assertFalse(events.single().second.isGuess)
    }
}

class TextEntryTest {
    /** What a correct model answer looks like for Geoff's example paragraph. */
    private val exampleOutput = """
        ```json
        {"entries":[
          {"kind":"event","name":"coffee","time":"10:30"},
          {"kind":"dose","type":"long-acting","units":19,"time":"10:30"},
          {"kind":"dose","type":"short-acting","units":4,"time":"10:30"},
          {"kind":"event","name":"chicken burger","time":"17:30"},
          {"kind":"dose","type":"short-acting","units":6,"time":"17:30"}
        ]}
        ```
    """.trimIndent()

    @Test fun `example paragraph output becomes the five canonical entries`() {
        val b = parseBreakdown(exampleOutput)
        assertEquals(0, b.rejected)
        assertEquals(
            listOf(
                "event: coffee @ 10:30",
                "dose: long-acting 19u @ 10:30",
                "dose: short-acting 4u @ 10:30",
                "event: chicken burger @ 17:30",
                "dose: short-acting 6u @ 17:30",
            ),
            b.entries.map { it.noteText() },
        )
    }

    @Test fun `prose around the json and a bare array are tolerated`() {
        assertEquals(1, parseBreakdown("""Here you go: {"entries":[{"kind":"event","name":"walk","time":"18:00"}]} Hope that helps""").entries.size)
        assertEquals(1, parseBreakdown("""[{"kind":"event","name":"walk","time":"18:00"}]""").entries.size)
        val none = parseBreakdown("I can't help with that")
        assertTrue(none.entries.isEmpty()); assertEquals(1, none.rejected)
    }

    @Test fun `rows are validated strictly`() {
        val b = parseBreakdown(
            """{"entries":[
              {"kind":"dose","type":"rapid","units":4,"time":"10:30"},
              {"kind":"dose","type":"short-acting","units":0,"time":"10:30"},
              {"kind":"dose","type":"short-acting","units":150,"time":"10:30"},
              {"kind":"dose","type":"short-acting","units":4.5,"time":"10:30"},
              {"kind":"dose","type":"short-acting","units":"lots","time":"10:30"},
              {"kind":"event","name":"  ","time":"10:30"},
              {"kind":"meal","name":"toast","time":"10:30"},
              {"kind":"dose","type":"Short-Acting","units":"3","time":"9:05"},
              {"kind":"dose","type":"long-acting","units":20.0,"time":"10:30"}
            ]}""",
        )
        assertEquals(7, b.rejected)
        assertEquals(listOf("dose: short-acting 3u @ 09:05", "dose: long-acting 20u @ 10:30"), b.entries.map { it.noteText() })
    }

    @Test fun `missing or bad time keeps the row but asks for a time`() {
        val b = parseBreakdown(
            """{"entries":[{"kind":"event","name":"snack","time":null},{"kind":"event","name":"nap","time":"25:00"},{"kind":"event","name":"tea","time":"evening"}]}""",
        )
        assertEquals(3, b.entries.size)
        assertTrue(b.entries.all { it.time == null && it.noteText() == null })
    }

    private fun entry(id: Long, text: String) =
        JournalEntity(id = id, day = "2026-09-25", text = text, createdAtMs = id, updatedAtMs = id)

    @Test fun `routine rows already logged are matched within an hour`() {
        val existing = listOf(
            entry(1, "dose: short-acting 4u @ 10:30"),
            entry(2, "dose: long-acting 19u @ 10:30"),
            entry(3, "event: coffee @ 10:30"),
        )
        val b = parseBreakdown(exampleOutput).entries
        assertEquals(listOf(3L, 2L, 1L, null, null), b.map { matchExisting(it, existing)?.id })
        // type and window matter: short at 10:30 doesn't match the long-acting row, nor a dose 2 h away
        assertNull(matchExisting(ProposedEntry(true, "short-acting", 4, time = LocalTime.of(12, 31)), existing))
        assertEquals(1L, matchExisting(ProposedEntry(true, "short-acting", 5, time = LocalTime.of(11, 30)), existing)?.id)
        assertEquals(3L, matchExisting(ProposedEntry(false, name = "Coffee", time = LocalTime.of(10, 0)), existing)?.id)
    }

    @Test fun `a confirmed duplicate wins over a guess`() {
        val existing = listOf(entry(1, "event: pizza @ 18:00 (guess)"), entry(2, "event: pizza @ 18:15"))
        assertEquals(2L, matchExisting(ProposedEntry(false, name = "pizza", time = LocalTime.of(18, 0)), existing)?.id)
        assertEquals(1L, matchExisting(ProposedEntry(false, name = "pizza", time = LocalTime.of(18, 0)), existing.take(1))?.id)
    }

    @Test fun `prompt carries the paragraph on one line`() {
        val p = describePrompt("coffee\nthen 4u")
        assertTrue(p.endsWith("Text: coffee then 4u\nJSON:"))
    }
}
