package com.geoffchan.glucosewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ParseAllTest {
    @Test fun `parses every reading in the array`() {
        val body = """[
            {"WT":"Date(1693526400000)","Value":195,"Trend":"Flat"},
            {"WT":"Date(1693526100000)","Value":190,"Trend":"FortyFiveUp"},
            {"WT":"Date(1693525800000)","Value":186,"Trend":4}
        ]"""
        val rs = parseReadings(body)
        assertEquals(3, rs.size)
        assertEquals(195, rs[0].mgdl)
        assertEquals("FortyFiveUp", rs[1].trend)
        assertEquals("Flat", rs[2].trend) // legacy int 4
    }

    @Test fun `skips malformed elements, keeps the rest`() {
        val body = """[{"bogus":true},{"WT":"Date(1693526400000)","Value":100,"Trend":"Flat"}]"""
        val rs = parseReadings(body)
        assertEquals(1, rs.size)
        assertEquals(100, rs[0].mgdl)
    }

    @Test fun `empty and garbage yield empty list`() {
        assertTrue(parseReadings("[]").isEmpty())
        assertTrue(parseReadings("""{"Code":"SessionIdNotFound"}""").isEmpty())
    }
}

class DayMathTest {
    private val toronto = ZoneId.of("America/Toronto")

    @Test fun `day bounds cover exactly the local day`() {
        val day = LocalDate.of(2026, 9, 2)
        val (start, end) = dayBoundsMs(day, toronto)
        // 2026-09-02 00:00 EDT = 04:00 UTC
        assertEquals(1788321600000L, start)
        assertEquals(start + 24 * 3600_000, end)
    }

    @Test fun `DST spring-forward day is 23 hours`() {
        val day = LocalDate.of(2026, 3, 8)
        val (start, end) = dayBoundsMs(day, toronto)
        assertEquals(23 * 3600_000L, end - start)
    }

    @Test fun `dayKey formats local date of a timestamp`() {
        // 2026-09-03 01:30 UTC is still Sep 2 in Toronto (EDT, UTC-4)
        val ms = 1788312600000L + 24 * 3600_000 // 2026-09-03T01:30:00Z
        assertEquals("2026-09-02", dayKey(ms, toronto))
    }

    @Test fun `minute of day maps into chart x domain`() {
        val day = LocalDate.of(2026, 9, 2)
        val (start, _) = dayBoundsMs(day, toronto)
        assertEquals(0f, minuteOfDay(start, day, toronto), 0.01f)
        assertEquals(90f, minuteOfDay(start + 90 * 60_000, day, toronto), 0.01f)
    }
}

class WeekMathTest {
    @Test fun `weekStartOf returns the Monday of the week`() {
        // 2026-09-02 is a Wednesday
        assertEquals(LocalDate.of(2026, 8, 31), weekStartOf(LocalDate.of(2026, 9, 2)))
        // a Monday maps to itself
        assertEquals(LocalDate.of(2026, 8, 31), weekStartOf(LocalDate.of(2026, 8, 31)))
        // a Sunday belongs to the week that started 6 days earlier
        assertEquals(LocalDate.of(2026, 8, 31), weekStartOf(LocalDate.of(2026, 9, 6)))
    }

    @Test fun `rangeBoundsMs spans the requested number of days`() {
        val zone = ZoneId.of("America/Toronto")
        val (start, end) = rangeBoundsMs(LocalDate.of(2026, 8, 31), 7, zone)
        assertEquals(7 * 24 * 3600_000L, end - start)
        assertEquals(dayBoundsMs(LocalDate.of(2026, 8, 31), zone).first, start)
    }
}

class TickTest {
    @Test fun `tick step adapts to visible span`() {
        assertEquals(15, tickStepMinutes(90f))       // <=1.5h visible: 15-min ticks
        assertEquals(30, tickStepMinutes(180f))      // 3h: 30-min
        assertEquals(60, tickStepMinutes(480f))      // 8h: hourly
        assertEquals(180, tickStepMinutes(720f))     // 12h: 3-hourly
        assertEquals(360, tickStepMinutes(1440f))    // full day: 6-hourly (matches old look)
        assertEquals(1440, tickStepMinutes(10080f))  // full week: daily
    }
}

class ConventionTest {
    @Test fun `tag entries are recognized`() {
        assertTrue(isTagEntry("#sick"))
        assertTrue(isTagEntry("#travel"))
        org.junit.Assert.assertFalse(isTagEntry("had #pasta for lunch"))
        org.junit.Assert.assertFalse(isTagEntry("regular note"))
    }

    @Test fun `dose note format is stable for the parser`() {
        assertEquals("dose: insulin 4u @ 13:05", doseNoteText("insulin", "4u", "13:05"))
        assertEquals("dose: metformin 500mg @ 08:30", doseNoteText(" metformin ", " 500mg ", "08:30"))
    }
}

class DoseDefaultTest {
    @Test fun `short acting defaults to 4 every day`() {
        for (d in java.time.DayOfWeek.entries) assertEquals(4, defaultUnits("short-acting", d))
    }

    @Test fun `long acting is 19 on weekdays and 25 Fri through Sun`() {
        assertEquals(19, defaultUnits("long-acting", java.time.DayOfWeek.MONDAY))
        assertEquals(19, defaultUnits("long-acting", java.time.DayOfWeek.THURSDAY))
        assertEquals(25, defaultUnits("long-acting", java.time.DayOfWeek.FRIDAY))
        assertEquals(25, defaultUnits("long-acting", java.time.DayOfWeek.SATURDAY))
        assertEquals(25, defaultUnits("long-acting", java.time.DayOfWeek.SUNDAY))
    }
}

class DoseParseTest {
    @Test fun `parses canonical dose notes`() {
        val d = parseDoseNote("dose: short-acting 4u @ 13:05")!!
        assertEquals(true, d.isShort)
        assertEquals(4, d.units)
        assertEquals(13 * 60 + 5, d.minuteOfDay)
        val l = parseDoseNote("dose: long-acting 25u @ 21:30")!!
        assertEquals(false, l.isShort)
        assertEquals(25, l.units)
    }

    @Test fun `parses legacy free-name form, defaults to short unless named long`() {
        val d = parseDoseNote("dose: insulin 4u @ 08:00")!!
        assertEquals(true, d.isShort)
        assertEquals(4, d.units)
    }

    @Test fun `non-dose notes return null`() {
        assertNull(parseDoseNote("had pasta at noon"))
        assertNull(parseDoseNote("#sick"))
        assertNull(parseDoseNote("dose: gibberish"))
    }

    @Test fun `parsed dose prefills the edit dialog and round-trips`() {
        val d = parseDoseNote("dose: long-acting 25u @ 21:30")!!
        assertEquals("long-acting", d.insulinType)
        assertEquals(java.time.LocalTime.of(21, 30), d.time)
        assertEquals("dose: long-acting 25u @ 21:30", doseNoteText(d.insulinType, "${d.units}u", d.time.toString()))
    }

    @Test fun `legacy free-name dose normalises to canonical on edit`() {
        val d = parseDoseNote("dose: insulin 4u @ 08:00")!!
        assertEquals("short-acting", d.insulinType)
        assertEquals("dose: short-acting 4u @ 08:00", doseNoteText(d.insulinType, "${d.units}u", d.time.toString()))
    }
}

class EventParseTest {
    @Test fun `parses event notes`() {
        val e = parseEventNote("event: coffee @ 10:30")!!
        assertEquals("coffee", e.name)
        assertEquals(10 * 60 + 30, e.minuteOfDay)
        assertEquals("ice cream", parseEventNote("event: ice cream @ 19:30")!!.name)
    }

    @Test fun `non-events return null`() {
        assertNull(parseEventNote("dose: short-acting 4u @ 11:00"))
        assertNull(parseEventNote("regular note"))
    }
}

class LogEntryTest {
    private fun entry(id: Long, day: String, text: String, created: Long = id) =
        JournalEntity(id = id, day = day, text = text, createdAtMs = created, updatedAtMs = created)

    @Test fun `event text round-trips through the log dialog`() {
        val e = parseEventNote(eventNoteText("  lunch ", "12:40"))!!
        assertEquals("lunch", e.name)
        assertEquals(java.time.LocalTime.of(12, 40), e.time)
    }

    @Test fun `times display in 12-hour form with AM PM`() {
        assertEquals("12:21 PM", time12(java.time.LocalTime.of(12, 21)))
        assertEquals("12:05 AM", time12(java.time.LocalTime.of(0, 5)))
        assertEquals("5:30 PM", time12(java.time.LocalTime.of(17, 30)))
    }

    @Test fun `list labels are friendly for doses and logs, raw otherwise`() {
        assertEquals("12:21 PM · 1u short-acting", entryLabel("dose: short-acting 1u @ 12:21"))
        assertEquals("9:00 PM · 25u long-acting", entryLabel("dose: long-acting 25u @ 21:00"))
        assertEquals("12:40 PM · lunch", entryLabel("event: lunch @ 12:40"))
        assertEquals("had pasta at noon", entryLabel("had pasta at noon"))
    }

    @Test fun `list order is by time, untimed notes last by creation`() {
        val sorted = sortForList(
            listOf(
                entry(1, "2026-09-05", "free text", created = 50),
                entry(2, "2026-09-05", "event: dinner @ 17:30"),
                entry(3, "2026-09-05", "dose: short-acting 4u @ 11:00"),
                entry(4, "2026-09-04", "event: coffee @ 23:00"),
                entry(5, "2026-09-05", "older free text", created = 10),
            ),
        )
        assertEquals(listOf(4L, 3L, 2L, 5L, 1L), sorted.map { it.id })
    }

    @Test fun `only tag entries are hidden from the list now`() {
        assertTrue(isDerivedEntry("#sick"))
        assertEquals(false, isDerivedEntry("event: lunch @ 12:40"))
        assertEquals(false, isDerivedEntry("dose: short-acting 4u @ 11:00"))
    }
}

class LatestMarkerTest {
    private val zone = ZoneId.of("America/Toronto")
    private fun entry(id: Long, day: String, text: String) =
        JournalEntity(id = id, day = day, text = text, createdAtMs = id, updatedAtMs = id)

    @Test fun `latest dose and log are chosen by day then time`() {
        val entries = listOf(
            entry(1, "2026-09-05", "dose: short-acting 4u @ 11:00"),
            entry(2, "2026-09-04", "dose: long-acting 25u @ 22:00"),
            entry(3, "2026-09-05", "event: lunch @ 12:40"),
            entry(4, "2026-09-05", "event: coffee @ 09:00"),
            entry(5, "2026-09-05", "free text"),
        )
        val dose = latestDose(entries, zone)!!
        assertEquals("▲ 4u", dose.label)
        assertEquals(LocalDate.of(2026, 9, 5).atTime(11, 0).atZone(zone).toInstant().toEpochMilli(), dose.atMs)
        val log = latestEvent(entries, zone)!!
        assertEquals("◆ lunch", log.label)
        assertEquals(LocalDate.of(2026, 9, 5).atTime(12, 40).atZone(zone).toInstant().toEpochMilli(), log.atMs)
    }

    @Test fun `long-acting shows an outline triangle and nothing gives null`() {
        assertEquals("△ 19u", latestDose(listOf(entry(1, "2026-09-05", "dose: long-acting 19u @ 11:00")), zone)!!.label)
        assertNull(latestDose(emptyList(), zone))
        assertNull(latestEvent(listOf(entry(1, "2026-09-05", "just a note")), zone))
    }

    @Test fun `widget time is clock time today, weekday-prefixed otherwise`() {
        val now = LocalDate.of(2026, 9, 5).atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        val today = LocalDate.of(2026, 9, 5).atTime(10, 30).atZone(zone).toInstant().toEpochMilli()
        val thu = LocalDate.of(2026, 9, 3).atTime(22, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("10:30 AM", whenText(today, now, zone))
        assertEquals("Thu 10:30 PM", whenText(thu, now, zone))
    }

    @Test fun `relative age reads in minutes, hours, then days`() {
        val now = 1_000_000_000_000L
        assertEquals("now", relativeAge(now - 30_000, now))
        assertEquals("45m ago", relativeAge(now - 45 * 60_000, now))
        assertEquals("2h ago", relativeAge(now - 150 * 60_000, now))
        assertEquals("3d ago", relativeAge(now - 3 * 24 * 3600_000L - 5 * 3600_000L, now))
        assertEquals("now", relativeAge(now + 60_000, now)) // future-dated log: clamp
    }
}
