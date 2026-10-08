package com.geoffchan.glucosewidget.wear

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Speak → the phone breaks it down → tick what's right → Save. Opens straight
 * into voice input (from the launcher or the "Log" tile). The phone (hers,
 * the main phone) does all parsing and saving; the watch only shows rows.
 */
class LogActivity : ComponentActivity() {
    private sealed interface Ui {
        data object Listening : Ui
        data class Thinking(val transcript: String, val saving: Boolean = false) : Ui
        data class Confirm(val transcript: String, val rows: List<Row>, val answer: String = "") : Ui
        data class Answer(val transcript: String, val answer: String) : Ui
        data class Done(val summary: String) : Ui
        data class Queued(val transcript: String) : Ui
        data class Error(val message: String) : Ui
    }

    private var ui by mutableStateOf<Ui>(Ui.Listening)
    /** The phone's interaction-log record for what's on screen; cleared once an outcome is sent. */
    private var interactionId: String? = null
    private val checked = mutableStateListOf<Boolean>()
    private val link by lazy { PhoneLink(this) }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = heardText(result.data)
        when {
            result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank() -> { tellOutcome(Protocol.OUTCOME_ASK_AGAIN); parse(heard) }
            ui is Ui.Listening -> finish() // backed out of the first prompt: nothing to keep
            // backed out of "Say again" / "Ask again": stay on whatever was showing
        }
    }

    /**
     * Speech comes back in EXTRA_RESULTS; if she switches the input screen to
     * the keyboard, Wear returns the typed text as a RemoteInput result instead.
     */
    private fun heardText(data: Intent?): String? {
        data ?: return null
        // The keyboard path returns CharSequences (or an array) under the same key.
        @Suppress("DEPRECATION")
        when (val r = data.extras?.get(RecognizerIntent.EXTRA_RESULTS)) {
            is List<*> -> r.firstOrNull()
            is Array<*> -> r.firstOrNull()
            else -> r
        }?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        android.app.RemoteInput.getResultsFromIntent(data)?.let { b ->
            b.keySet().firstNotNullOfOrNull { k -> b.getCharSequence(k)?.toString()?.takeIf { it.isNotBlank() } }?.let { return it }
        }
        @Suppress("DEPRECATION")
        android.util.Log.i("WatchLog", "no text in result; extras=${data.extras?.keySet()?.associateWith { data.extras?.get(it)?.javaClass?.simpleName }}")
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Scaffold(timeText = { TimeText() }) { Screen() } } }
        if (savedInstanceState == null) listen()
        // Anything held from when the phone was away goes out now, quietly.
        if (WatchQueue.all(this).isNotEmpty()) lifecycleScope.launch { runCatching { WatchQueue.flush(applicationContext) } }
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Log something or ask")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            ui = Ui.Error("Voice input isn't available on this watch.")
        }
    }

    /**
     * Tells the phone's interaction log what she did with the record on
     * screen (cancel, ask again, read an answer). Fire-and-forget, outside the
     * activity's scope so it survives finish().
     */
    private fun tellOutcome(kind: String) {
        val id = interactionId ?: return
        interactionId = null
        val app = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            PhoneLink(app).send(Protocol.PATH_OUTCOME, encodeOutcome(id, kind))
        }
    }

    private fun parse(transcript: String) {
        ui = Ui.Thinking(transcript)
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        lifecycleScope.launch {
            ui = try {
                // the assistant may look things up before answering: give it longer
                val p = decodeProposal(link.request(Protocol.PATH_PARSE, encodeParse(id, transcript), Protocol.PATH_PROPOSAL, timeoutMs = 30_000))
                interactionId = p.id ?: id
                when {
                    !p.ok -> Ui.Error(p.error ?: READ_FAILED)
                    p.rows.isEmpty() && p.answer.isNotBlank() -> Ui.Answer(transcript, p.answer)
                    p.rows.isEmpty() -> Ui.Error("Didn't catch any doses or food — try again.\n\n“$transcript”")
                    else -> {
                        checked.clear()
                        checked.addAll(p.rows.map { it.status != Protocol.STATUS_ALREADY })
                        Ui.Confirm(transcript, p.rows, p.answer)
                    }
                }
            } catch (e: PhoneUnreachable) {
                // Don't lose it: hold it here; the phone saves it as a guess when it's back.
                WatchQueue.add(applicationContext, transcript, parseId = id)
                Ui.Queued(transcript)
            } catch (e: Exception) {
                Ui.Error(READ_FAILED)
            }
        }
    }

    private fun save(c: Ui.Confirm) {
        val texts = c.rows.filterIndexed { i, _ -> checked.getOrElse(i) { false } }.map { it.text }
        val unticked = c.rows.filterIndexed { i, _ -> !checked.getOrElse(i) { false } }.map { it.text }
        val id = interactionId
        ui = Ui.Thinking(c.transcript, saving = true)
        lifecycleScope.launch {
            try {
                val s = decodeSaved(link.request(Protocol.PATH_SAVE, encodeSave(texts, id, unticked), Protocol.PATH_SAVED))
                interactionId = null // the save is the outcome
                if (!s.ok) { ui = Ui.Error(s.error ?: SAVE_FAILED); return@launch }
                ui = Ui.Done(savedSummary(s))
                buzz()
                delay(1_500)
                finish()
            } catch (e: PhoneUnreachable) {
                ui = Ui.Error("${e.message} Your entry wasn't saved — try again.")
            } catch (e: Exception) {
                ui = Ui.Error(SAVE_FAILED)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    @Composable
    private fun Screen() {
        when (val s = ui) {
            Ui.Listening -> Centered { Text("Listening…") }
            is Ui.Thinking -> Centered {
                CircularProgressIndicator()
                Text(
                    if (s.saving) "Saving…" else "Thinking…\n“${s.transcript}”",
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            is Ui.Confirm -> ConfirmList(s)
            is Ui.Answer -> ScalingLazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Text(
                        "“${s.transcript}”", textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant,
                    )
                }
                item { Text(s.answer, textAlign = TextAlign.Center, style = MaterialTheme.typography.body1, modifier = Modifier.padding(vertical = 6.dp)) }
                item { Chip(onClick = { tellOutcome(Protocol.OUTCOME_ANSWERED); finish() }, label = { Text("Done") }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth()) }
                item { Chip(onClick = { listen() }, label = { Text("Ask again") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
            }
            is Ui.Done -> Centered {
                Text("✓", fontSize = 48.sp, color = MaterialTheme.colors.primary)
                Text(s.summary, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2)
            }
            is Ui.Queued -> ScalingLazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 32.dp, start = 10.dp, end = 10.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Text(
                        "Saved on your watch — I'll send it when your phone is back.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.body2,
                    )
                }
                item {
                    Text(
                        "“${s.transcript}”", textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                item { Chip(onClick = { finish() }, label = { Text("OK") }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth()) }
            }
            is Ui.Error -> ScalingLazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 32.dp, start = 10.dp, end = 10.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { Text(s.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2) }
                item { Chip(onClick = { listen() }, label = { Text("Try again") }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth()) }
                item { Chip(onClick = { finish() }, label = { Text("Close") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
            }
        }
    }

    @Composable
    private fun ConfirmList(s: Ui.Confirm) {
        ScalingLazyColumn(
            Modifier.fillMaxSize(),
            state = rememberScalingLazyListState(),
            contentPadding = PaddingValues(top = 28.dp, start = 8.dp, end = 8.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    "“${s.transcript}”", textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant,
                )
            }
            if (s.answer.isNotBlank()) {
                item { Text(s.answer, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            itemsIndexed(s.rows) { i, row ->
                val on = checked.getOrElse(i) { false }
                ToggleChip(
                    checked = on,
                    onCheckedChange = { checked[i] = it },
                    label = { Text(row.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        Text(
                            when (row.status) {
                                Protocol.STATUS_ALREADY -> "${row.time} · already logged"
                                Protocol.STATUS_CONFIRM -> "${row.time} · confirms guess"
                                else -> row.time
                            },
                        )
                    },
                    toggleControl = { Checkbox(checked = on) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = { save(s) },
                    enabled = checked.any { it },
                    label = { Text("Save") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Chip(onClick = { listen() }, label = { Text("Say again") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
            item { Chip(onClick = { tellOutcome(Protocol.OUTCOME_CANCELLED); finish() }, label = { Text("Cancel") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
        }
    }

    private companion object {
        const val READ_FAILED = "Your phone couldn't read that — try again."
        const val SAVE_FAILED = "Your phone couldn't save that — try again."
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
        }
    }
}
