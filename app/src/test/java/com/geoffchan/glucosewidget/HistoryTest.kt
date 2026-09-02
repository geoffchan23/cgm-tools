package com.geoffchan.glucosewidget

import org.junit.Assert.assertEquals
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
