package com.geoffchan.glucosewidget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Chat with Ray (see [RayChat]): shared threads between her phone and
 * Geoff's. Ray's messages can carry a card of proposed entries/changes
 * (ticked; untick, then Save), a saved report, and what he ran to get
 * there (tap to see the SQL or code).
 */
class ChatActivity : ComponentActivity() {
    companion object { const val EXTRA_THREAD = "thread" }

    private var thread by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_THREAD)?.let { thread = it }
    }

    override fun onResume() {
        super.onResume()
        ChatNotification.openThread = thread
        Sync.enqueue(this) // pull anything the other phone said
    }

    override fun onPause() {
        super.onPause()
        ChatNotification.openThread = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = GlucoseDb.get(this).dao()
        val me = runBlocking { Store.chatAuthor(this@ChatActivity) }
        thread = intent.getStringExtra(EXTRA_THREAD) ?: runBlocking {
            // carry on the latest conversation if it's from the last day, else start fresh
            chatThreads(dao.allChatNow()).firstOrNull { System.currentTimeMillis() - it.lastAtMs < 24 * 3600_000L }?.thread
        } ?: newUid()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var listing by remember { mutableStateOf(false) }
                BackHandler(enabled = listing) { listing = false }
                val all by dao.allChat().collectAsState(initial = emptyList())
                val current = thread ?: return@MaterialTheme
                LaunchedEffect(current) { ChatNotification.openThread = current }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { if (listing) listing = false else finish() }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(if (listing) "Chats" else "Ray", style = MaterialTheme.typography.titleLarge)
                                if (!listing) Text("You're ${authorName(me)} on this phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            if (!listing) IconButton(onClick = { listing = true }) { Icon(Icons.AutoMirrored.Filled.List, "All chats") }
                            IconButton(onClick = { thread = newUid(); listing = false }) { Icon(Icons.Filled.Add, "New chat") }
                        }
                        if (listing) {
                            ThreadList(chatThreads(all)) { thread = it; listing = false }
                        } else {
                            Conversation(current, all.filter { it.thread == current }, me)
                        }
                    }
                }
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.CANADA)
private val DAY_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.CANADA)

private fun whenLabel(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return if (t.toLocalDate() == LocalDate.now()) t.format(TIME) else t.format(DAY_TIME)
}

@Composable
private fun ThreadList(threads: List<ChatThreadSummary>, onOpen: (String) -> Unit) {
    if (threads.isEmpty()) {
        Text("No chats yet.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(threads, key = { it.thread }) { t ->
            Card(onClick = { onOpen(t.thread) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(t.title, style = MaterialTheme.typography.bodyLarge)
                    Text(t.lastLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2)
                    Text("${whenLabel(t.lastAtMs)} · ${t.count} messages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

private val STARTERS = listOf(
    "How was this past week?",
    "Why did I go low last night?",
    "Compare my mornings this month with last month",
    "Make a report of my lows for my endocrinologist",
)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Conversation(thread: String, messages: List<ChatMessageEntity>, me: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val busy by RayChat.busy.collectAsState()
    val thinking = thread in busy
    var draft by remember(thread) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, thinking) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }

    fun send(text: String) {
        if (text.isBlank() || thinking) return
        RayChat.send(context, thread, me, text)
        draft = ""
    }

    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thinking) item("thinking") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Ray is looking into it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        items(messages.asReversed(), key = { it.uid }) { m -> Bubble(m, me) }
        if (messages.isEmpty()) item("empty") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    "Ask Ray anything about Francine's glucose, or tell him what to log. He can look through all of her data, run his own analysis and make reports. Geoff and Francine both see this chat.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                STARTERS.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            draft, { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message Ray") },
            maxLines = 6,
        )
        IconButton(onClick = { send(draft) }, enabled = draft.isNotBlank() && !thinking) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send")
        }
    }
}

@Composable
private fun Bubble(m: ChatMessageEntity, me: String) {
    val mine = m.author == me
    val ray = m.author == AUTHOR_RAY
    val meta = remember(m.meta) { m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 340.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    mine -> MaterialTheme.colorScheme.primaryContainer
                    ray -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${authorName(m.author)} · ${whenLabel(m.createdAtMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
                decodeCard(m.card)?.let { CardView(m.uid, it, me) }
                meta?.optString("report")?.takeIf { it.isNotBlank() }?.let { ReportLink(it) }
                meta?.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { ToolsFooter(it) }
            }
        }
    }
}

@Composable
private fun ReportLink(file: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val here = remember(file) { File(reportsDir(context), file).isFile }
    if (here) {
        OutlinedButton(onClick = {
            context.startActivity(Intent(context, ReportsActivity::class.java).putExtra(ReportsActivity.EXTRA_OPEN, file))
        }) { Text("Open report: ${RayTools.reportTitleOf(file) ?: file}") }
    } else {
        Text("Report saved on the other phone: ${RayTools.reportTitleOf(file) ?: file}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

/** "Used: query ×2, analysis" — tap for the SQL and code he ran. */
@Composable
private fun ToolsFooter(tools: org.json.JSONArray) {
    var open by remember { mutableStateOf(false) }
    val list = (0 until tools.length()).map { tools.getJSONObject(it) }
    val names = list.groupingBy { toolLabel(it.optString("name")) }.eachCount().entries.joinToString(", ") { (n, c) -> if (c > 1) "$n ×$c" else n }
    Text(
        (if (open) "▾ " else "▸ ") + "Looked at: $names",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.clickable { open = !open },
    )
    if (open) list.forEach { t ->
        val detail = listOfNotNull(
            t.optString("sql").takeIf { it.isNotBlank() },
            t.optString("code").takeIf { it.isNotBlank() },
            listOf("from_day", "to_day", "from", "to").mapNotNull { k -> t.optString(k).takeIf { it.isNotBlank() } }.takeIf { it.isNotEmpty() }?.joinToString(" → "),
            t.optString("error").takeIf { it.isNotBlank() }?.let { "error: $it" },
        )
        Text(toolLabel(t.optString("name")), style = MaterialTheme.typography.labelMedium)
        detail.forEach { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
    }
}

private fun toolLabel(name: String) = when (name) {
    AssistantTools.READINGS -> "readings"
    AssistantTools.STATS -> "stats"
    AssistantTools.JOURNAL -> "log"
    AssistantTools.LOWS -> "lows"
    AssistantTools.PROPOSE -> "new entries"
    AssistantTools.PROPOSE_CHANGES -> "changes"
    RayTools.QUERY -> "query"
    RayTools.ANALYZE -> "analysis"
    RayTools.REPORT -> "report"
    else -> name
}

/** Ray's proposals: everything starts ticked; untick what's wrong, then Save. */
@Composable
private fun CardView(uid: String, card: ChatCard, me: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val entryTicks = remember(uid) { mutableStateListOf(*Array(card.entries.size) { true }) }
    val changeTicks = remember(uid) { mutableStateListOf(*Array(card.changes.size) { true }) }
    var saving by remember { mutableStateOf(false) }
    val existing by produceState(emptyList<JournalEntity>(), card.day, card.state) {
        value = GlucoseDb.get(context).dao().dayJournalBetween(card.day, card.day)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val open = card.state == CARD_OPEN
        if (card.entries.isNotEmpty()) {
            val dayLabel = runCatching { LocalDate.parse(card.day).format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA)) }.getOrDefault(card.day)
            Text("Log on $dayLabel:", style = MaterialTheme.typography.labelLarge)
            card.entries.forEachIndexed { i, text ->
                val status = proposedFromText(text)?.let { matchStatus(text, matchExisting(it, existing)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (open) Checkbox(checked = entryTicks.getOrElse(i) { true }, onCheckedChange = { entryTicks[i] = it })
                    Column {
                        Text(entryLabel(text), style = MaterialTheme.typography.bodyMedium)
                        when (status.takeIf { open }) {
                            WatchProtocol.STATUS_ALREADY -> "already logged"
                            WatchProtocol.STATUS_CONFIRM -> "confirms a guess"
                            WatchProtocol.STATUS_REPLACE -> "replaces the auto-logged entry"
                            else -> null
                        }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
        }
        if (card.changes.isNotEmpty()) {
            Text("Changes:", style = MaterialTheme.typography.labelLarge)
            card.changes.forEachIndexed { i, op ->
                val d = runCatching { LocalDate.parse(op.day).format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA)) }.getOrDefault(op.day)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (open) Checkbox(checked = changeTicks.getOrElse(i) { true }, onCheckedChange = { changeTicks[i] = it })
                    Text("$d: ${changeLabel(op)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (open) {
            val picked = entryTicks.count { it } + changeTicks.count { it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = picked > 0 && !saving, onClick = {
                    saving = true
                    scope.launch {
                        ChatStore.saveCard(
                            context, uid,
                            card.entries.filterIndexed { i, _ -> entryTicks.getOrElse(i) { true } },
                            card.changes.filterIndexed { i, _ -> changeTicks.getOrElse(i) { true } },
                            me,
                        )
                        saving = false
                    }
                }) { Text("Save $picked") }
                TextButton(enabled = !saving, onClick = { scope.launch { ChatStore.dismissCard(context, uid, me) } }) { Text("Dismiss") }
            }
        } else {
            Spacer(Modifier.size(2.dp))
            Text(
                when (card.state) {
                    CARD_SAVED -> "${card.result ?: "Saved"} by ${authorName(card.by ?: "")}"
                    else -> "Dismissed by ${authorName(card.by ?: "")}"
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
