package com.geoffchan.glucosewidget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.launch
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

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var day by remember { mutableStateOf(LocalDate.now(zone)) }
                var showPicker by remember { mutableStateOf(false) }
                var editing by remember { mutableStateOf<JournalEntity?>(null) }
                var adding by remember { mutableStateOf(false) }
                var deleting by remember { mutableStateOf<JournalEntity?>(null) }
                val scope = rememberCoroutineScope()

                val (startMs, endMs) = dayBoundsMs(day, zone)
                val readings by dao.readingsBetween(startMs, endMs)
                    .collectAsState(initial = emptyList())
                val entries by dao.journalForDay(day.toString())
                    .collectAsState(initial = emptyList())
                val today = LocalDate.now(zone)

                Scaffold(
                    floatingActionButton = {
                        FloatingActionButton(onClick = { adding = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add note")
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        // ---- date header ----
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { day = day.minusDays(1) }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day")
                            }
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    day.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.CANADA)),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (day == today) Text("Today", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { day = day.plusDays(1) }, enabled = day < today) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
                            }
                            IconButton(onClick = { showPicker = true }) {
                                Icon(Icons.Filled.DateRange, "Pick date")
                            }
                            IconButton(onClick = {
                                startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                            }) { Icon(Icons.Filled.Settings, "Settings") }
                        }

                        // ---- chart ----
                        val settings = remember { kotlinx.coroutines.runBlocking { Store.settings(this@MainActivity) } }
                        DayChart(
                            readings = readings,
                            day = day,
                            zone = zone,
                            modifier = Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 12.dp),
                            lowMmol = settings.lowMmol,
                            highMmol = settings.highMmol,
                        )
                        Text(
                            if (readings.isEmpty()) "No readings for this day"
                            else "${readings.size} readings",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                        )

                        Spacer(Modifier.height(8.dp))

                        // ---- journal ----
                        LazyColumn(
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(entries, key = { it.id }) { entry ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
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
                        title = { Text(if (adding) "New note" else "Edit note") },
                        text = {
                            OutlinedTextField(
                                text, { text = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("What happened? Meals, activity, sleep…") },
                                minLines = 3,
                            )
                        },
                        confirmButton = {
                            Button(
                                enabled = text.isNotBlank(),
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    val toSave = editing?.copy(text = text.trim(), updatedAtMs = now)
                                        ?: JournalEntity(day = day.toString(), text = text.trim(), createdAtMs = now, updatedAtMs = now)
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
