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

/** The Monday of the ISO week containing [date]. Week keys use this. */
fun weekStartOf(date: LocalDate): LocalDate =
    date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

/** One-tap day tags. Stored as journal entries whose whole text is the tag. */
val DAY_TAGS = listOf("#sick", "#stress", "#travel", "#cycle")

fun isTagEntry(text: String): Boolean = text.startsWith("#") && !text.contains(" ")

/**
 * Geoff's dosing routine: short-acting is 4u; long-acting is 19u on
 * weekdays and 25u Friday through Sunday. Prefills the dose dialog.
 */
fun defaultUnits(type: String, dayOfWeek: java.time.DayOfWeek): Int = when {
    type == "long-acting" && dayOfWeek in listOf(
        java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY,
    ) -> 25
    type == "long-acting" -> 19
    else -> 4
}

/** Canonical dose-note format; the analysis parser relies on it. */
fun doseNoteText(name: String, amount: String, time: String): String =
    "dose: ${name.trim()} ${amount.trim()} @ $time"

/** [start, end) in epoch ms of [days] consecutive local days from [firstDay]. */
fun rangeBoundsMs(firstDay: LocalDate, days: Int, zone: ZoneId): Pair<Long, Long> {
    val start = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = firstDay.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}
