package com.geoffchan.glucosewidget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.launch
import java.time.LocalTime

/** A proposed row in the confirm list, with what it matched in the day's log. */
private data class ReviewRow(
    val entry: ProposedEntry,
    val checked: Boolean,
    val match: JournalEntity?, // already-logged duplicate, or a guess this confirms
) {
    val confirmsGuess: Boolean get() = match != null && isGuessEntry(match.text)
}

private fun reviewRow(entry: ProposedEntry, existing: List<JournalEntity>): ReviewRow {
    val match = matchExisting(entry, existing)
    // A plain duplicate starts unchecked; matching one of Claude's guesses
    // starts checked, and saving confirms the guess with these values.
    return ReviewRow(entry, checked = match == null || isGuessEntry(match.text), match = match)
}

/**
 * "Describe in words": type a paragraph, on-device Gemini Nano breaks it into
 * doses and food/exercise logs, the user checks each row, then Save. Nothing
 * leaves the phone. [existing] is the target day's journal, for dedupe.
 * [onSave] gets new texts to insert and guess rows to replace (confirmed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescribeDialog(
    title: String,
    existing: List<JournalEntity>,
    onDismiss: () -> Unit,
    onSave: (inserts: List<String>, confirms: List<Pair<JournalEntity, String>>) -> Unit,
) {
    val model = remember { Generation.getClient() }
    DisposableEffect(Unit) { onDispose { model.close() } }
    val scope = rememberCoroutineScope()

    var paragraph by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>("Checking on-device model…") }
    var working by remember { mutableStateOf(false) }
    val rows = remember { mutableStateListOf<ReviewRow>() }
    var reviewing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> { ready = true; status = null }
                FeatureStatus.UNAVAILABLE -> status = "Gemini Nano isn't available on this phone, so this can't run. Use Log dose / Log food instead."
                else -> { // DOWNLOADABLE or DOWNLOADING: ask AICore to fetch it, show progress
                    status = "Downloading the on-device model…"
                    model.download().collect { s ->
                        when (s) {
                            is DownloadStatus.DownloadStarted -> status = "Downloading the on-device model (${s.bytesToDownload / 1_000_000} MB)…"
                            is DownloadStatus.DownloadProgress -> status = "Downloading the on-device model… ${s.totalBytesDownloaded / 1_000_000} MB"
                            is DownloadStatus.DownloadFailed -> status = "Model download failed: ${s.e.message}. Try again on Wi-Fi."
                            DownloadStatus.DownloadCompleted -> { ready = true; status = null }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            status = "On-device model error: ${e.message}"
        }
    }

    fun breakDown() {
        working = true
        status = null
        scope.launch {
            try {
                val response = model.generateContent(
                    generateContentRequest(TextPart(describePrompt(paragraph))) {
                        temperature = 0f
                        topK = 1
                        maxOutputTokens = 256 // API maximum; five rows of JSON need ~150
                    },
                )
                val raw = response.candidates.firstOrNull()?.text.orEmpty()
                android.util.Log.d("Describe", "model output: $raw")
                val breakdown = parseBreakdown(raw)
                rows.clear()
                rows += breakdown.entries.map { reviewRow(it, existing) }
                status = when {
                    breakdown.entries.isEmpty() -> "Couldn't find any doses or food in that. Try saying it differently."
                    breakdown.rejected > 0 -> "Skipped ${breakdown.rejected} item(s) that didn't make sense."
                    else -> null
                }
                reviewing = breakdown.entries.isNotEmpty()
            } catch (e: Exception) {
                status = "Couldn't break that down: ${e.message}"
            } finally {
                working = false
            }
        }
    }

    val toSave = rows.filter { it.checked }
    val canSave = toSave.isNotEmpty() && toSave.all { it.entry.time != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!reviewing) {
                    OutlinedTextField(
                        paragraph, { paragraph = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        placeholder = { Text("Coffee for breakfast with 19 long and 4 short, then chicken burger for dinner and 6…") },
                        minLines = 5,
                    )
                } else {
                    Text("Check what to save:", style = MaterialTheme.typography.labelLarge)
                    rows.forEachIndexed { i, row ->
                        // an edited time or name can start or stop matching a logged entry
                        ReviewRowItem(row) { rows[i] = it.copy(match = matchExisting(it.entry, existing)) }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                if (working) Text("Thinking (on this phone)…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (!reviewing) {
                Button(enabled = ready && !working && paragraph.isNotBlank(), onClick = { breakDown() }) { Text("Break down") }
            } else {
                Button(
                    enabled = canSave,
                    onClick = {
                        // each guess is confirmed at most once; any further match is a new row
                        val (confirming, inserting) = toSave.partition { it.confirmsGuess }
                        val firstPerGuess = confirming.distinctBy { it.match!!.id }
                        val confirms = firstPerGuess.map { it.match!! to it.entry.noteText()!! }
                        val inserts = (inserting + (confirming - firstPerGuess.toSet())).map { it.entry.noteText()!! }
                        onSave(inserts, confirms)
                    },
                ) { Text("Save ${toSave.size}") }
            }
        },
        dismissButton = {
            Row {
                if (reviewing) TextButton(onClick = { reviewing = false; status = null }) { Text("Back") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** One checkable row: what it is, its time (tap to change), and why it's unchecked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewRowItem(row: ReviewRow, onChange: (ReviewRow) -> Unit) {
    val e = row.entry
    var pickTime by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.checked, onCheckedChange = { onChange(row.copy(checked = it)) })
            Column(Modifier.weight(1f)) {
                if (e.isDose) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.units}u ${e.insulinType}", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onChange(row.copy(entry = e.copy(units = (e.units!! - 1).coerceAtLeast(1)))) }) { Text("−") }
                        TextButton(onClick = { onChange(row.copy(entry = e.copy(units = (e.units!! + 1).coerceAtMost(100)))) }) { Text("+") }
                    }
                } else {
                    OutlinedTextField(
                        e.name!!, { new -> if (new.isNotBlank()) onChange(row.copy(entry = e.copy(name = new))) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val note = when {
                    row.confirmsGuess -> "confirms Claude's guess"
                    row.match != null -> "already logged"
                    e.time == null -> "set a time"
                    else -> null
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = if (e.time == null && row.match == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
            }
            OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.width(104.dp)) {
                Text(e.time?.let { time12(it) } ?: "Time?")
            }
        }
    }
    if (pickTime) {
        val state = androidx.compose.material3.rememberTimePickerState(
            initialHour = e.time?.hour ?: 12,
            initialMinute = e.time?.minute ?: 0,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Time") },
            text = { androidx.compose.material3.TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(row.copy(entry = e.copy(time = LocalTime.of(state.hour, state.minute))))
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}
