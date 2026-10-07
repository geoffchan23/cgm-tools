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
        data class Confirm(val transcript: String, val rows: List<Row>) : Ui
        data class Done(val summary: String) : Ui
        data class Error(val message: String) : Ui
    }

    private var ui by mutableStateOf<Ui>(Ui.Listening)
    private val checked = mutableStateListOf<Boolean>()
    private val link by lazy { PhoneLink(this) }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        when {
            result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank() -> parse(heard)
            ui is Ui.Listening -> finish() // backed out of the first prompt: nothing to keep
            // backed out of "Say again": stay on whatever was showing
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Scaffold(timeText = { TimeText() }) { Screen() } } }
        if (savedInstanceState == null) listen()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dose, food or activity")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            ui = Ui.Error("Voice input isn't available on this watch.")
        }
    }

    private fun parse(transcript: String) {
        ui = Ui.Thinking(transcript)
        lifecycleScope.launch {
            ui = try {
                val p = decodeProposal(link.request(Protocol.PATH_PARSE, transcript, Protocol.PATH_PROPOSAL))
                when {
                    !p.ok -> Ui.Error(p.error ?: "The phone couldn't read that.")
                    p.rows.isEmpty() -> Ui.Error("Didn't catch any doses or food — try again.\n\n“$transcript”")
                    else -> {
                        checked.clear()
                        checked.addAll(p.rows.map { it.status != Protocol.STATUS_ALREADY })
                        Ui.Confirm(transcript, p.rows)
                    }
                }
            } catch (e: PhoneUnreachable) {
                Ui.Error(e.message.orEmpty())
            } catch (e: Exception) {
                Ui.Error("Something went wrong: ${e.message}")
            }
        }
    }

    private fun save(c: Ui.Confirm) {
        val texts = c.rows.filterIndexed { i, _ -> checked.getOrElse(i) { false } }.map { it.text }
        ui = Ui.Thinking(c.transcript, saving = true)
        lifecycleScope.launch {
            try {
                val s = decodeSaved(link.request(Protocol.PATH_SAVE, encodeSave(texts), Protocol.PATH_SAVED))
                if (!s.ok) { ui = Ui.Error(s.error ?: "The phone couldn't save that."); return@launch }
                ui = Ui.Done(savedSummary(s))
                buzz()
                delay(1_500)
                finish()
            } catch (e: PhoneUnreachable) {
                ui = Ui.Error(e.message.orEmpty())
            } catch (e: Exception) {
                ui = Ui.Error("Something went wrong: ${e.message}")
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
                    if (s.saving) "Saving…" else "“${s.transcript}”",
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            is Ui.Confirm -> ConfirmList(s)
            is Ui.Done -> Centered {
                Text("✓", fontSize = 48.sp, color = MaterialTheme.colors.primary)
                Text(s.summary, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2)
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
            item { Chip(onClick = { finish() }, label = { Text("Cancel") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
        }
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
        }
    }
}
