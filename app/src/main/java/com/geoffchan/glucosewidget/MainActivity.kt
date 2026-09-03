package com.geoffchan.glucosewidget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = GlucoseDb.get(this).dao()
        val zone = ZoneId.systemDefault()
        Refresh.enqueue(this) // opening the app freshens the data

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var day by remember { mutableStateOf(LocalDate.now(zone)) }
                var mode by remember { mutableStateOf(SCOPE_DAY) }
                var showPicker by remember { mutableStateOf(false) }
                var editing by remember { mutableStateOf<JournalEntity?>(null) }
                var adding by remember { mutableStateOf(false) }
                var deleting by remember { mutableStateOf<JournalEntity?>(null) }
                var dosing by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                val today = LocalDate.now(zone)
                val isWeek = mode == SCOPE_WEEK
                val firstDay = if (isWeek) weekStartOf(day) else day
                val spanDays = if (isWeek) 7 else 1
                val entryKey = firstDay.toString()

                val (startMs, endMs) = rangeBoundsMs(firstDay, spanDays, zone)
                val readings by dao.readingsBetween(startMs, endMs)
                    .collectAsState(initial = emptyList())
                val scopedEntries by dao.journalFor(mode, entryKey)
                    .collectAsState(initial = emptyList())
                // Week view also lists that week's day notes, labeled by date.
                val dayEntriesInWeek by (
                    if (isWeek) dao.dayJournalInRange(firstDay.toString(), firstDay.plusDays(6).toString())
                    else dao.journalFor("none", "none")
                    ).collectAsState(initial = emptyList())
                val entries = scopedEntries + dayEntriesInWeek

                Scaffold(
                    floatingActionButton = {
                        FloatingActionButton(onClick = { adding = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add note")
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        // ---- header ----
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { day = day.minusDays(spanDays.toLong()) }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous")
                            }
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.CANADA)
                                Text(
                                    if (isWeek) {
                                        "${firstDay.format(fmt)} – ${firstDay.plusDays(6).format(fmt)}"
                                    } else {
                                        day.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.CANADA))
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                val current = if (isWeek) weekStartOf(today) == firstDay else day == today
                                if (current)

                                    Text(
                                        if (isWeek) "This week" else "Today",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                            }
                            IconButton(
                                onClick = { day = day.plusDays(spanDays.toLong()) },
                                enabled = firstDay.plusDays(spanDays.toLong()) <= today,
                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
                            IconButton(onClick = { showPicker = true }) {
                                Icon(Icons.Filled.DateRange, "Pick date")
                            }
                            IconButton(onClick = {
                                startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                            }) { Icon(Icons.Filled.Settings, "Settings") }
                        }

                        // ---- day/week toggle ----
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = !isWeek, onClick = { mode = SCOPE_DAY }, label = { Text("Day") })
                            FilterChip(selected = isWeek, onClick = { mode = SCOPE_WEEK }, label = { Text("Week") })
                        }

                        // ---- day tags + quick dose (day mode only) ----
                        if (!isWeek) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                for (tag in DAY_TAGS) {
                                    val existing = entries.firstOrNull { it.scope == SCOPE_DAY && it.text == tag }
                                    FilterChip(
                                        selected = existing != null,
                                        onClick = {
                                            scope.launch {
                                                if (existing != null) {
                                                    dao.deleteJournal(existing)
                                                } else {
                                                    val now = System.currentTimeMillis()
                                                    dao.insertJournal(
                                                        JournalEntity(
                                                            day = entryKey, text = tag,
                                                            createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY,
                                                        ),
                                                    )
                                                }
                                            }
                                        },
                                        label = { Text(tag.removePrefix("#")) },
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { dosing = true }) { Text("+ Dose") }
                            }
                        }

                        // ---- chart ----
                        val settings = remember { runBlocking { Store.settings(this@MainActivity) } }
                        val doseMarks = entries.mapNotNull { e ->
                            if (e.scope != SCOPE_DAY) return@mapNotNull null
                            val d = parseDoseNote(e.text) ?: return@mapNotNull null
                            val dayOffset = java.time.temporal.ChronoUnit.DAYS
                                .between(firstDay, LocalDate.parse(e.day)).toInt()
                            (dayOffset * 1440f + d.minuteOfDay) to d
                        }
                        val eventMarks = entries.mapNotNull { e ->
                            if (e.scope != SCOPE_DAY) return@mapNotNull null
                            val ev = parseEventNote(e.text) ?: return@mapNotNull null
                            val dayOffset = java.time.temporal.ChronoUnit.DAYS
                                .between(firstDay, LocalDate.parse(e.day)).toInt()
                            (dayOffset * 1440f + ev.minuteOfDay) to ev.name
                        }
                        RangeChart(
                            readings = readings,
                            firstDay = firstDay,
                            days = spanDays,
                            zone = zone,
                            modifier = Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 12.dp),
                            lowMmol = settings.lowMmol,
                            highMmol = settings.highMmol,
                            doses = doseMarks,
                            events = eventMarks,
                        )
                        Text(
                            buildString {
                                append(
                                    if (readings.isEmpty()) "No readings in this range"
                                    else "${readings.size} readings",
                                )
                                if (doseMarks.isNotEmpty()) {
                                    append(" · ${doseMarks.size} dose"); if (doseMarks.size > 1) append("s"); append(" ▲")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                        )

                        Spacer(Modifier.height(8.dp))

                        // ---- journal (scoped to day or week) ----
                        LazyColumn(
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(entries.filterNot { isDerivedEntry(it.text) }, key = { it.id }) { entry ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                                        if (isWeek && entry.scope == SCOPE_DAY) {
                                            Text(
                                                LocalDate.parse(entry.day)
                                                    .format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.CANADA)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(top = 6.dp),
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                entry.text,
                                                Modifier.weight(1f).padding(vertical = 8.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            TextButton(onClick = { editing = entry }) { Text("Edit") }
                                            IconButton(onClick = { deleting = entry }) {
                                                Icon(Icons.Filled.Delete, "Delete note")
                                            }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(72.dp)) } // clear the FAB
                        }
                    }
                }

                // ---- dialogs ----
                if (showPicker) {
                    val pickerState = rememberDatePickerState(
                        initialSelectedDateMillis = day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
                    )
                    DatePickerDialog(
                        onDismissRequest = { showPicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                pickerState.selectedDateMillis?.let {
                                    day = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                                }
                                showPicker = false
                            }) { Text("OK") }
                        },
                    ) { DatePicker(state = pickerState) }
                }

                if (adding || editing != null) {
                    var text by remember(adding, editing) { mutableStateOf(editing?.text ?: "") }
                    AlertDialog(
                        onDismissRequest = { adding = false; editing = null },
                        title = {
                            Text(
                                when {
                                    editing != null -> "Edit note"
                                    isWeek -> "New note — week of ${firstDay.format(DateTimeFormatter.ofPattern("MMM d", Locale.CANADA))}"
                                    else -> "New note — ${day.format(DateTimeFormatter.ofPattern("MMM d", Locale.CANADA))}"
                                },
                            )
                        },
                        text = {
                            OutlinedTextField(
                                text, { text = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        if (isWeek) "How was the week? Routine, food themes, exercise…"
                                        else "What happened? Meals, activity, sleep…",
                                    )
                                },
                                minLines = 3,
                            )
                        },
                        confirmButton = {
                            Button(
                                enabled = text.isNotBlank(),
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    val toSave = editing?.copy(text = text.trim(), updatedAtMs = now)
                                        ?: JournalEntity(
                                            day = entryKey, text = text.trim(),
                                            createdAtMs = now, updatedAtMs = now, scope = mode,
                                        )
                                    scope.launch {
                                        if (editing != null) dao.updateJournal(toSave) else dao.insertJournal(toSave)
                                        adding = false; editing = null
                                    }
                                },
                            ) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") }
                        },
                    )
                }

                if (dosing) {
                    var insulinType by remember {
                        mutableStateOf(runBlocking { Store.lastMedication(this@MainActivity) })
                    }
                    var unitsText by remember {
                        mutableStateOf(defaultUnits(insulinType, LocalDate.now(zone).dayOfWeek).toString())
                    }
                    fun unitsOrNull() = unitsText.toIntOrNull()?.takeIf { it in 1..100 }
                    fun bump(delta: Int) {
                        unitsText = ((unitsText.toIntOrNull() ?: 0) + delta).coerceIn(1, 100).toString()
                    }
                    var doseTime by remember { mutableStateOf(java.time.LocalTime.now(zone).withSecond(0)) }
                    var showTimePicker by remember { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = { dosing = false },
                        title = { Text("Log dose") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = insulinType == "short-acting",
                                        onClick = {
                                            insulinType = "short-acting"
                                            unitsText = defaultUnits("short-acting", LocalDate.now(zone).dayOfWeek).toString()
                                        },
                                        label = { Text("Short-acting") },
                                    )
                                    FilterChip(
                                        selected = insulinType == "long-acting",
                                        onClick = {
                                            insulinType = "long-acting"
                                            unitsText = defaultUnits("long-acting", LocalDate.now(zone).dayOfWeek).toString()
                                        },
                                        label = { Text("Long-acting") },
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    androidx.compose.material3.FilledTonalIconButton(onClick = { bump(-1) }) {
                                        Text("−", style = MaterialTheme.typography.titleLarge)
                                    }
                                    OutlinedTextField(
                                        unitsText,
                                        { new -> if (new.length <= 3 && new.all(Char::isDigit)) unitsText = new },
                                        label = { Text("Units") },
                                        singleLine = true,
                                        isError = unitsOrNull() == null,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                    androidx.compose.material3.FilledTonalIconButton(onClick = { bump(+1) }) {
                                        Text("+", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(onClick = { doseTime = doseTime.minusMinutes(15) }) { Text("−15m") }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { showTimePicker = true },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(doseTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                                    }
                                    TextButton(onClick = { doseTime = doseTime.plusMinutes(15) }) { Text("+15m") }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = unitsOrNull() != null,
                                onClick = {
                                    val units = unitsOrNull() ?: return@Button
                                    val now = System.currentTimeMillis()
                                    val time = doseTime.format(DateTimeFormatter.ofPattern("HH:mm"))
                                    scope.launch {
                                        dao.insertJournal(
                                            JournalEntity(
                                                day = LocalDate.now(zone).toString(),
                                                text = doseNoteText(insulinType, "${units}u", time),
                                                createdAtMs = now, updatedAtMs = now, scope = SCOPE_DAY,
                                            ),
                                        )
                                        Store.saveLastMedication(this@MainActivity, insulinType)
                                        dosing = false
                                    }
                                },
                            ) { Text("Save") }
                        },
                        dismissButton = { TextButton(onClick = { dosing = false }) { Text("Cancel") } },
                    )

                    if (showTimePicker) {
                        val timeState = androidx.compose.material3.rememberTimePickerState(
                            initialHour = doseTime.hour,
                            initialMinute = doseTime.minute,
                            is24Hour = true,
                        )
                        AlertDialog(
                            onDismissRequest = { showTimePicker = false },
                            title = { Text("Dose time") },
                            text = { androidx.compose.material3.TimePicker(state = timeState) },
                            confirmButton = {
                                TextButton(onClick = {
                                    doseTime = java.time.LocalTime.of(timeState.hour, timeState.minute)
                                    showTimePicker = false
                                }) { Text("OK") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                            },
                        )
                    }
                }

                deleting?.let { doomed ->
                    AlertDialog(
                        onDismissRequest = { deleting = null },
                        title = { Text("Delete note?") },
                        text = { Text(doomed.text.take(120)) },
                        confirmButton = {
                            Button(onClick = {
                                scope.launch { dao.deleteJournal(doomed); deleting = null }
                            }) { Text("Delete") }
                        },
                        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}
