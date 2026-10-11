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
import androidx.glance.appwidget.updateAll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import java.time.LocalTime

/** A proposed row in the confirm list, with what it matched in the day's log. */
private data class ReviewRow(
    val entry: ProposedEntry,
    val checked: Boolean,
    val match: JournalEntity?, // already-logged duplicate, a guess this confirms, or a routine row it replaces
) {
    val status: String get() = matchStatus(entry.noteText(), match)

    /** Saving updates [match] (a guess or routine row) instead of inserting. */
    val confirmsGuess: Boolean get() = status == WatchProtocol.STATUS_CONFIRM || status == WatchProtocol.STATUS_REPLACE
}

/** What was saved from the dialog, for [MainActivity] to write and the interaction log to label. */
data class ReviewedSave(
    val inserts: List<Pair<String?, String>>, // (text as proposed, text to save) — differ if she edited it
    val confirms: List<Triple<JournalEntity, String?, String>>, // (guess, as proposed, text to save)
    val unticked: List<String>, // proposals she left unchecked
    val interactionId: String?,
    val changes: List<ChangeOp> = emptyList(), // ticked edits/deletes/adds on other days
)


private fun ProposedEntry.logText(): String = noteText() ?: "${name ?: "${units}u $insulinType"} (no time)"

private fun reviewRow(entry: ProposedEntry, existing: List<JournalEntity>): ReviewRow {
    val match = matchExisting(entry, existing)
    // A plain duplicate starts unchecked; matching a guess or an auto-logged
    // routine row starts checked, and saving updates that row to these values.
    return ReviewRow(entry, checked = matchStatus(entry.noteText(), match) != WatchProtocol.STATUS_ALREADY, match = match)
}

/**
 * "Ask / log by text": type a question or a paragraph. The OpenAI assistant
 * ([Assistant], when set up and online) answers and/or breaks it into doses
 * and food/exercise logs; otherwise on-device Gemini Nano does the breakdown.
 * The user checks each row, then Save. [existing] is [day]'s journal, for
 * dedupe. [onSave] gets new texts to insert, guess rows to replace
 * (confirmed) and changes to past entries/other days. If the assistant asks
 * a question, Reply sends her answer with the conversation so far. Each
 * Send is recorded in the interaction log, and what she does next (save /
 * back / reply / cancel) becomes its outcome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescribeDialog(
    title: String,
    day: java.time.LocalDate,
    existing: List<JournalEntity>,
    onDismiss: () -> Unit,
    onSave: (ReviewedSave) -> Unit,
    initialText: String = "", // e.g. what she said into the Ask bar's mic
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val useAssistant = remember { Assistant.configured(context) }
    var answer by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    val model = remember { Generation.getClient() }
    DisposableEffect(Unit) { onDispose { model.close() } }
    val scope = rememberCoroutineScope()

    var paragraph by remember { mutableStateOf(initialText) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>("Checking on-device model…") }
    var working by remember { mutableStateOf(false) }
    val rows = remember { mutableStateListOf<ReviewRow>() }
    val originals = remember { mutableStateListOf<String>() } // each row's text as proposed, before edits
    var interactionId by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    val changes = remember { mutableStateListOf<CheckedChange>() }
    var awaiting by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<String?>(null) } // the record whose answer she's replying to
    var replyingTo by remember { mutableStateOf<String?>(null) } // its answer, shown above the box

    /** Leaving without saving: the open record gets [kind] (answered if there was only an answer). */
    fun leave(kind: String) {
        val k = if (kind == OUTCOME_CANCELLED && rows.isEmpty() && !answer.isNullOrBlank()) OUTCOME_ANSWERED else kind
        InteractionLog.setOutcomeLater(context, interactionId, Outcome(k, System.currentTimeMillis()))
        interactionId = null
    }
    fun close() { if (reviewing) leave(OUTCOME_CANCELLED); onDismiss() }

    LaunchedEffect(Unit) {
        if (useAssistant) { ready = true; status = null } // Nano still loads below, as the fallback
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> { ready = true; status = null }
                FeatureStatus.UNAVAILABLE -> if (!useAssistant) status = "Gemini Nano isn't available on this phone, so this can't run. Use Log dose / Log food instead."
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

    fun showRows(entries: List<ProposedEntry>, skipped: Int, proposedChanges: List<ChangeOp> = emptyList()) {
        rows.clear()
        rows += entries.map { reviewRow(it, existing) }
        originals.clear()
        originals += rows.map { it.entry.logText() }
        changes.clear()
        changes += proposedChanges.map { CheckedChange(it, true) }
        status = when {
            entries.isEmpty() && proposedChanges.isEmpty() && answer.isNullOrBlank() -> "Couldn't find any doses or food in that. Try saying it differently."
            skipped > 0 -> "Skipped $skipped item(s) that didn't make sense."
            else -> null
        }
        reviewing = entries.isNotEmpty() || proposedChanges.isNotEmpty() || !answer.isNullOrBlank()
    }

    fun breakDown() {
        working = true
        status = null
        answer = null; detail = null; awaiting = false
        val said = paragraph
        val replying = replyTo
        var history = emptyList<Turn>()
        val attempts = mutableListOf<ParseAttempt>()
        val trace = org.json.JSONArray()
        var aiError: String? = null
        /** One record per Send: what she typed, what handled it, what was shown. */
        suspend fun log(parser: String, ai: AssistantResult? = null, rejected: Int = 0, extra: org.json.JSONObject.() -> Unit = {}) {
            val id = newUid()
            interactionId = id
            InteractionLog.record(
                context, id, SOURCE_PHONE, said, parser,
                interactionData(
                    InteractionLog.context(context, day, userTurn = ai?.userTurn), attempts,
                    rows.map { ProposalLog(it.entry.logText(), it.status) },
                    answer.orEmpty(), detail, rejected, ai, trace.takeIf { it.length() > 0 }, aiError, history,
                ).apply { replying?.let { put("replyTo", it) } }.apply(extra),
            )
        }
        scope.launch {
            history = InteractionLog.history(context, replying)
            if (useAssistant && !Assistant.online(context)) attempts += ParseAttempt("openai", false, "offline")
            if (useAssistant && Assistant.online(context)) {
                val ta = System.currentTimeMillis()
                try {
                    val r = Assistant.ask(context, said, day, fromWatch = false, timeoutMs = 45_000, trace = trace, history = history)
                    attempts += ParseAttempt("openai", true, null, System.currentTimeMillis() - ta)
                    answer = r.answer.takeIf { it.isNotBlank() }
                    detail = r.detail
                    awaiting = r.awaitingAnswer
                    showRows(r.entries, r.rejected, r.changes)
                    log("openai", r, r.rejected)
                    working = false
                    return@launch
                } catch (e: Exception) {
                    android.util.Log.w("Assistant", "ask failed: $e")
                    aiError = if (e is kotlinx.coroutines.TimeoutCancellationException) "timeout after 45000 ms" else e.message ?: e.javaClass.simpleName
                    attempts += ParseAttempt("openai", false, aiError, System.currentTimeMillis() - ta)
                    if (!ready || model.checkStatus() != FeatureStatus.AVAILABLE) {
                        status = "Ray couldn't answer (${e.message}). Try again in a moment."
                        working = false
                        log("none")
                        return@launch
                    }
                    status = "Ray couldn't answer; using the on-device model."
                }
            }
            val tn = System.currentTimeMillis()
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
                showRows(breakdown.entries, breakdown.rejected)
                attempts += ParseAttempt("nano", breakdown.entries.isNotEmpty(), null, System.currentTimeMillis() - tn)
                log("nano", rejected = breakdown.rejected) { put("raw", raw) }
            } catch (e: Exception) {
                status = "Couldn't break that down: ${e.message}"
                attempts += ParseAttempt("nano", false, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - tn)
                log("none")
            } finally {
                working = false
            }
        }
    }

    val toSave = rows.filter { it.checked }
    val changesToSave = changes.filter { it.checked }
    val saveCount = toSave.size + changesToSave.size
    val canSave = saveCount > 0 && toSave.all { it.entry.time != null }

    fun reply() {
        replyTo = interactionId
        replyingTo = answer
        leave(OUTCOME_REPLIED)
        paragraph = ""
        reviewing = false
        status = null
    }

    SheetDialog(
        onDismissRequest = { close() },
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!reviewing) {
                    replyingTo?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(250) // let the sheet open first
                        runCatching { focus.requestFocus() }
                    }
                    OutlinedTextField(
                        paragraph, { paragraph = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).focusRequester(focus),
                        placeholder = {
                            Text(
                                if (useAssistant) "Log something (\"sourdough with 15 g cheddar and took 3\") or ask (\"why did I go low last night?\")…"
                                else "Coffee for breakfast with 19 long and 4 short, then chicken burger for dinner and 6…",
                            )
                        },
                        minLines = 5,
                    )
                } else {
                    answer?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (rows.isNotEmpty()) Text("Check what to save:", style = MaterialTheme.typography.labelLarge)
                    rows.forEachIndexed { i, row ->
                        // an edited time or name can start or stop matching a logged entry
                        ReviewRowItem(row) { rows[i] = it.copy(match = matchExisting(it.entry, existing)) }
                    }
                    if (changes.isNotEmpty()) ChangeList(changes)
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                if (working) Text(if (useAssistant) "Thinking…" else "Thinking (on this phone)…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (!reviewing) {
                Button(enabled = ready && !working && paragraph.isNotBlank(), onClick = { breakDown() }) { Text(if (useAssistant) "Send" else "Break down") }
            } else if (rows.isEmpty() && changes.isEmpty()) {
                if (awaiting) Button(onClick = { reply() }) { Text("Reply") } else Button(onClick = { close() }) { Text("Done") }
            } else {
                Button(
                    enabled = canSave,
                    onClick = {
                        // each guess is confirmed at most once; any further match is a new row
                        fun proposedOf(r: ReviewRow) = originals.getOrNull(rows.indexOf(r))
                        val (confirming, inserting) = toSave.partition { it.confirmsGuess }
                        val firstPerGuess = confirming.distinctBy { it.match!!.id }
                        val confirms = firstPerGuess.map { Triple(it.match!!, proposedOf(it), it.entry.noteText()!!) }
                        val inserts = (inserting + (confirming - firstPerGuess.toSet())).map { proposedOf(it) to it.entry.noteText()!! }
                        val unticked = rows.filter { !it.checked }.map { proposedOf(it) ?: it.entry.logText() } +
                            changes.filter { !it.checked }.map { changeLogText(it.op) }
                        onSave(ReviewedSave(inserts, confirms, unticked, interactionId, changesToSave.map { it.op }))
                        interactionId = null
                    },
                ) { Text("Save $saveCount") }
            }
        },
        dismissButton = {
            Row {
                // Reply keeps the conversation (the assistant asked or said something); Back starts over
                if (reviewing && !answer.isNullOrBlank() && !(awaiting && rows.isEmpty() && changes.isEmpty())) TextButton(onClick = { reply() }) { Text("Reply") }
                if (reviewing) TextButton(onClick = { leave(OUTCOME_ASK_AGAIN); replyTo = null; replyingTo = null; reviewing = false; status = null }) { Text("Back") }
                TextButton(onClick = { close() }) { Text("Cancel") }
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
                    row.status == WatchProtocol.STATUS_CONFIRM -> "confirms Claude's guess"
                    row.status == WatchProtocol.STATUS_REPLACE -> "replaces auto-logged ${entryLabel(row.match!!.text)}"
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

/** A proposed change and whether she's keeping it ticked. */
data class CheckedChange(val op: ChangeOp, val checked: Boolean)

/** Changes to past entries / other days, grouped by day, each with a tick box. */
@Composable
fun ChangeList(changes: androidx.compose.runtime.snapshots.SnapshotStateList<CheckedChange>) {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.CANADA)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Changes to your log:", style = MaterialTheme.typography.labelLarge)
        changes.withIndex().groupBy { it.value.op.day }.forEach { (day, items) ->
            Text(
                runCatching { java.time.LocalDate.parse(day).format(fmt) }.getOrDefault(day),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
            )
            items.forEach { (i, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = c.checked, onCheckedChange = { changes[i] = c.copy(checked = it) })
                    Text(changeLabel(c.op), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Review of changes proposed from her watch (opened from the "review on
 * your phone" notification): the record [interactionId]'s changes, ticked;
 * Save applies them and labels the record.
 */
@Composable
fun ChangesDialog(interactionId: String, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val changes = remember { mutableStateListOf<CheckedChange>() }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(interactionId) {
        changes.clear()
        changes += InteractionLog.proposedChanges(context, interactionId).map { CheckedChange(it, true) }
        loaded = true
    }
    val picked = changes.filter { it.checked }
    SheetDialog(
        onDismissRequest = onDone,
        title = { Text("Changes from your watch") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    !loaded -> Text("Loading…")
                    changes.isEmpty() -> Text("Nothing to review.")
                    else -> ChangeList(changes)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = picked.isNotEmpty() && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val items = Journal.applyChanges(context, picked.map { it.op })
                        GlucoseWidget().updateAll(context)
                        InteractionLog.setOutcome(
                            context, interactionId,
                            Outcome(OUTCOME_SAVED, System.currentTimeMillis(), items, changes.filter { !it.checked }.map { changeLogText(it.op) }),
                        )
                        onDone()
                    }
                },
            ) { Text("Save ${picked.size}") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (loaded && changes.isNotEmpty()) InteractionLog.setOutcomeLater(context, interactionId, Outcome(OUTCOME_CANCELLED, System.currentTimeMillis(), note = "phone review"))
                onDone()
            }) { Text("Cancel") }
        },
    )
}
