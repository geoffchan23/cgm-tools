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

data class EventNote(val name: String, val minuteOfDay: Int) {
    val time: java.time.LocalTime get() = java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
}

/** Canonical food/exercise log format; same shape the chart plots as diamonds. */
fun eventNoteText(name: String, time: String): String = "event: ${name.trim()} @ $time"

private val TIME_12H = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)

/** "12:21 PM" — what the user sees; storage stays 24-hour HH:mm. */
fun time12(t: java.time.LocalTime): String = t.format(TIME_12H)

/** Notes-list rendering: timed entries read "12:40 PM · lunch"; anything else is shown raw. */
fun entryLabel(text: String): String {
    parseDoseNote(text)?.let { return "${time12(it.time)} · ${it.units}u ${it.insulinType}" }
    parseEventNote(text)?.let { return "${time12(it.time)} · ${it.name}" }
    return text
}

/** Minute-of-day for timed entries (doses, logs); null for free text. */
fun entryMinute(text: String): Int? =
    parseDoseNote(text)?.minuteOfDay ?: parseEventNote(text)?.minuteOfDay

/** Notes-list order: by day, then timed entries by time, then untimed by creation. */
fun sortForList(entries: List<JournalEntity>): List<JournalEntity> =
    entries.sortedWith(
        compareBy<JournalEntity> { it.day }
            .thenBy { entryMinute(it.text) == null }
            .thenBy { entryMinute(it.text) ?: 0 }
            .thenBy { it.createdAtMs },
    )

/** A marker for the widget: short label plus the instant it happened. */
data class LastLog(val label: String, val atMs: Long)

private fun instantOf(day: String, minuteOfDay: Int, zone: ZoneId): Long =
    LocalDate.parse(day).atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()

fun latestDose(entries: List<JournalEntity>, zone: ZoneId): LastLog? =
    entries.asSequence()
        .filter { it.scope == SCOPE_DAY }
        .mapNotNull { e -> parseDoseNote(e.text)?.let { d -> LastLog("${if (d.isShort) "▲" else "△"} ${d.units}u", instantOf(e.day, d.minuteOfDay, zone)) } }
        .maxByOrNull { it.atMs }

fun latestEvent(entries: List<JournalEntity>, zone: ZoneId): LastLog? =
    entries.asSequence()
        .filter { it.scope == SCOPE_DAY }
        .mapNotNull { e -> parseEventNote(e.text)?.let { ev -> LastLog("◆ ${ev.name}", instantOf(e.day, ev.minuteOfDay, zone)) } }
        .maxByOrNull { it.atMs }

/** "now", "45m ago", "2h ago", "3d ago"; future instants clamp to "now". */
fun relativeAge(atMs: Long, nowMs: Long): String {
    val m = (nowMs - atMs) / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m ago"
        m < 24 * 60 -> "${m / 60}h ago"
        else -> "${m / (24 * 60)}d ago"
    }
}

private val EVENT_RE = Regex("""^event: (.+) @ (\d{1,2}):(\d{2})$""")

/**
 * Parse a food/exercise log (`event: coffee @ 10:30`). Logged from the app's
 * "Log food/exercise" dialog (older ones were written by Claude Code from
 * free-text notes); plotted as diamonds on the chart.
 */
fun parseEventNote(text: String): EventNote? {
    val m = EVENT_RE.find(text) ?: return null
    val (name, hh, mm) = m.destructured
    return EventNote(name.trim(), hh.toInt() * 60 + mm.toInt())
}

/** Entries the notes list hides: legacy tag chips only. Logs and doses are shown. */
fun isDerivedEntry(text: String): Boolean = isTagEntry(text)

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
