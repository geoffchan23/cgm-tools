package com.geoffchan.glucosewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    private val body =
        """[{"WT":"Date(1693526400000)","ST":"Date(1693526400000)","DT":"Date(1693526400000-0400)","Value":195,"Trend":"Flat"}]"""

    @Test fun `parses value timestamp and trend`() {
        val r = parseLatestReading(body)!!
        assertEquals(195, r.mgdl)
        assertEquals(1693526400000L, r.timestampMs)
        assertEquals("Flat", r.trend)
    }

    @Test fun `parses legacy integer trend`() {
        val legacy = """[{"WT":"Date(1693526400000)","Value":100,"Trend":4}]"""
        val r = parseLatestReading(legacy)!!
        assertEquals("Flat", r.trend)
    }

    @Test fun `empty array yields null`() {
        assertNull(parseLatestReading("[]"))
    }

    @Test fun `garbage yields null`() {
        assertNull(parseLatestReading("""{"Code":"SessionIdNotFound"}"""))
    }
}

class ConvertTest {
    @Test fun `mgdl to mmol one decimal`() {
        assertEquals("10.8", mmolText(195))
        assertEquals("3.9", mmolText(70))
        assertEquals("5.5", mmolText(99))
        assertEquals("22.2", mmolText(400))
    }
}

class TrendTest {
    @Test fun `trend strings map to arrows`() {
        assertEquals("↑↑", trendArrow("DoubleUp"))
        assertEquals("↑", trendArrow("SingleUp"))
        assertEquals("↗", trendArrow("FortyFiveUp"))
        assertEquals("→", trendArrow("Flat"))
        assertEquals("↘", trendArrow("FortyFiveDown"))
        assertEquals("↓", trendArrow("SingleDown"))
        assertEquals("↓↓", trendArrow("DoubleDown"))
    }

    @Test fun `unknown trends map to dash`() {
        assertEquals("–", trendArrow("NotComputable"))
        assertEquals("–", trendArrow("RateOutOfRange"))
        assertEquals("–", trendArrow("None"))
        assertEquals("–", trendArrow("whatever"))
    }
}

class StateTest {
    private val reading = Reading(mgdl = 195, timestampMs = 1_000_000L, trend = "Flat") // 10.8

    @Test fun `in range is normal`() {
        val s = displayState(reading, nowMs = 1_000_000L + 5 * 60_000, lowMmol = 3.9, highMmol = 11.0)
        assertEquals(GlucoseState.IN_RANGE, s)
    }

    @Test fun `above high is high`() {
        val s = displayState(reading, nowMs = 1_000_000L, lowMmol = 3.9, highMmol = 10.0)
        assertEquals(GlucoseState.HIGH, s)
    }

    @Test fun `below low is low`() {
        val low = Reading(mgdl = 65, timestampMs = 1_000_000L, trend = "SingleDown") // 3.6
        val s = displayState(low, nowMs = 1_000_000L, lowMmol = 3.9, highMmol = 10.0)
        assertEquals(GlucoseState.LOW, s)
    }

    @Test fun `stale wins over range`() {
        val s = displayState(reading, nowMs = 1_000_000L + 13 * 60_000, lowMmol = 3.9, highMmol = 10.0)
        assertEquals(GlucoseState.STALE, s)
    }

    @Test fun `boundary values are in range`() {
        val exactlyLow = Reading(mgdl = 70, timestampMs = 0L, trend = "Flat") // 3.9
        assertEquals(GlucoseState.IN_RANGE, displayState(exactlyLow, 0L, 3.9, 10.0))
    }

    @Test fun `age text clamps negative and formats minutes`() {
        assertEquals("0m", ageText(readingMs = 2_000L, nowMs = 1_000L))
        assertEquals("3m", ageText(readingMs = 0L, nowMs = 3 * 60_000L + 5_000L))
        assertTrue(ageText(readingMs = 0L, nowMs = 90 * 60_000L).endsWith("m"))
    }
}
