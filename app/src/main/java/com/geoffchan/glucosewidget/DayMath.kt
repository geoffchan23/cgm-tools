package com.geoffchan.glucosewidget

import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Parse every valid reading in a Share response; malformed items are skipped. */
fun parseReadings(body: String): List<Reading> = runCatching {
    val arr = JSONArray(body)
    (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val one = JSONArray().put(arr.getJSONObject(i)).toString()
            parseLatestReading(one)
        }.getOrNull()
    }
}.getOrDefault(emptyList())

/** [start, end) of a local calendar day in epoch ms. DST-correct: not always 24 h. */
fun dayBoundsMs(day: LocalDate, zone: ZoneId): Pair<Long, Long> {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

/** "YYYY-MM-DD" of the local day a timestamp falls in. */
fun dayKey(timestampMs: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate().toString()

/** Minutes since local midnight of [day] — the chart's x coordinate. */
fun minuteOfDay(timestampMs: Long, day: LocalDate, zone: ZoneId): Float {
    val (start, _) = dayBoundsMs(day, zone)
    return (timestampMs - start) / 60_000f
}
