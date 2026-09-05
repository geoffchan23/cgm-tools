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

/** Legacy one-tap day tags (chips UI removed 2026-09-02); still hidden from lists. */
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

data class DoseNote(val isShort: Boolean, val units: Int, val minuteOfDay: Int) {
    /** Canonical type name, as the dose dialog's chips and [doseNoteText] use it. */
    val insulinType: String get() = if (isShort) "short-acting" else "long-acting"
    val time: java.time.LocalTime get() = java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
}

data class EventNote(val name: String, val minuteOfDay: Int)

private val EVENT_RE = Regex("""^event: (.+) @ (\d{1,2}):(\d{2})$""")

/**
 * Parse a derived event entry (`event: coffee @ 10:30`). These are written
 * by Claude Code extraction runs, not by hand; the app plots them and
 * hides them from the notes list.
 */
fun parseEventNote(text: String): EventNote? {
    val m = EVENT_RE.find(text) ?: return null
    val (name, hh, mm) = m.destructured
    return EventNote(name.trim(), hh.toInt() * 60 + mm.toInt())
}

/** Entries the notes list hides: tags (chips) and derived events (chart). */
fun isDerivedEntry(text: String): Boolean = isTagEntry(text) || parseEventNote(text) != null

/** Chart marker inputs (minute-of-range keyed) from day-scope journal entries. */
fun markerData(
    entries: List<JournalEntity>,
    firstDay: LocalDate,
): Pair<List<Pair<Float, DoseNote>>, List<Pair<Float, String>>> {
    val doses = mutableListOf<Pair<Float, DoseNote>>()
    val events = mutableListOf<Pair<Float, String>>()
    for (e in entries) {
        if (e.scope != SCOPE_DAY) continue
        val dayOffset = java.time.temporal.ChronoUnit.DAYS.between(firstDay, LocalDate.parse(e.day)).toInt()
        parseDoseNote(e.text)?.let { doses += (dayOffset * 1440f + it.minuteOfDay) to it }
        parseEventNote(e.text)?.let { events += (dayOffset * 1440f + it.minuteOfDay) to it.name }
    }
    return doses to events
}

private val DOSE_RE = Regex("""^dose: (.+) (\d+)\S* @ (\d{1,2}):(\d{2})$""")

/** Parse a dose journal entry; legacy free-name notes count as short-acting. */
fun parseDoseNote(text: String): DoseNote? {
    val m = DOSE_RE.find(text) ?: return null
    val (name, units, hh, mm) = m.destructured
    return DoseNote(
        isShort = !name.contains("long", ignoreCase = true),
        units = units.toInt(),
        minuteOfDay = hh.toInt() * 60 + mm.toInt(),
    )
}

/** [start, end) in epoch ms of [days] consecutive local days from [firstDay]. */
fun rangeBoundsMs(firstDay: LocalDate, days: Int, zone: ZoneId): Pair<Long, Long> {
    val start = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = firstDay.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}
