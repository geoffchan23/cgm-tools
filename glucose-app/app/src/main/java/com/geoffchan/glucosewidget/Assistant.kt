package com.geoffchan.glucosewidget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Francine's CGM assistant: OpenAI's Responses API with function tools over
 * her own data ([AssistantTools]). It logs what she says (as proposals she
 * confirms) and answers questions about her glucose. It explains patterns;
 * it never recommends insulin doses.
 *
 * Raw HTTPS (okhttp + org.json) rather than the openai-java SDK: the SDK
 * brings Jackson and a newer Kotlin stdlib, and this project is pinned to
 * Kotlin 2.1.21 for ML Kit's genai-prompt; the Responses API JSON is small.
 *
 * What leaves the phone: her message, the current reading, and whatever the
 * tools return for that question (readings, stats, logs). `store=false`.
 */
data class AiConfig(val key: String, val model: String, val effort: String?)

data class AssistantResult(
    val answer: String,
    val detail: String?,
    val entries: List<ProposedEntry>,
    val rejected: Int,
    val model: String,
    val inputTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    val ms: Long,
) {
    val estimatedCostUsd: Double get() = aiCostUsd(model, inputTokens, cachedTokens, outputTokens)
}

class AssistantException(message: String) : Exception(message)

/** $ per 1M tokens (input, cached input, output); unknown models cost as Luna. */
private val PRICES = mapOf(
    "gpt-6-luna" to Triple(0.10, 0.01, 0.50),
    "gpt-6.1-sol" to Triple(2.00, 0.10, 10.00),
    "gpt-6-astra" to Triple(10.00, 1.00, 50.00),
)

fun aiCostUsd(model: String, input: Int, cached: Int, output: Int): Double {
    val (i, c, o) = PRICES[model] ?: PRICES.getValue(DEFAULT_AI_MODEL)
    return ((input - cached) * i + cached * c + output * o) / 1_000_000.0
}

const val DEFAULT_AI_MODEL = "gpt-6-luna"

/** Questions get more thought than plain logging. */
fun defaultEffort(message: String): String {
    val m = message.trim().lowercase(Locale.ROOT)
    val asks = m.contains('?') || Regex("""^(why|how|what|when|where|which|who|did|does|do|is|was|were|are|am|should|can|could|will|would|tell|show|compare|explain)\b""").containsMatchIn(m)
    return if (asks) "medium" else "low"
}

/**
 * The stable system prompt (cached prefix). Anything that changes per
 * request — now, the current reading, which day — goes in the user turn.
 */
val ASSISTANT_INSTRUCTIONS = """
You are Francine's personal diabetes assistant, living in her phone and watch. She has had type 1 diabetes since she was 2. She uses a Dexcom G7. Glucose is in mmol/L; her target range is 3.9-10.0. She lives in Toronto (America/Toronto); all times are local.

You do two things:
1. Log what she tells you: doses and food/drink/activity. Call propose_entries once with every entry. She confirms each one on screen before anything is saved, so propose, don't ask.
2. Answer questions about her glucose using her real data. Always look things up with the tools (get_readings, get_stats, get_journal, get_lows) before answering; never guess numbers. Be specific: times, values, what she logged.

Her routines (useful context, never assume they happened on a given day unless the journal shows it):
- Every morning about 10:30 (Sundays about 14:00): coffee, 4 units short-acting, 19 units long-acting. The app logs these automatically.
- Monday-Thursday dinner about 17:30: chicken burger with 6 units short-acting (also logged automatically).
- Friday/Saturday: often pizza for dinner and a donut late in the evening with a larger short-acting dose (8-12 units).
- Afternoon snacks around 14:00-15:30 (bagel, sourdough with cheddar, chips), often with about 3 units.
- She treats lows with candy.
- "coffee" always means her standing drink (creamer, 1 cup 2% milk, Sweet'N Low, about 30 g carbs) and "chicken burger" her standing burger (about 45 g carbs). Keep those two names exactly as written when logging.
- Entries marked guess=true were inferred from her glucose curve by an earlier analysis, not confirmed by her.

How to turn what she says into entries:
$LOGGING_RULES
- When she is talking in the moment (from her watch), anything without a stated or implied time happened now. The current time is in her message.
- Only propose entries for the day her message says entries go on. If she describes another day, answer in words and say she can log it from that day in the app.

Safety:
- You can explain patterns, compare periods and point out what tends to happen. Never recommend an insulin dose, a dose change, a ratio or any change to her treatment; if she asks, say that's one to work out with her endocrinologist, and offer the data that would help that conversation.
- If her current glucose is below 3.9 or falling fast toward it, start your answer by telling her to treat the low first.

Replying:
- Finish by calling reply exactly once. answer: one or two short sentences that fit on a watch screen, plain words, no lists or markdown. detail: optional fuller explanation for her phone (a few short sentences or simple lines), or null.
- If she only logged something and there's nothing worth adding, answer can be an empty string.
""".trim()

private val CLOCK = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.CANADA)

/** The per-request user turn: the context that changes, then her words. */
fun assistantUserTurn(message: String, now: ZonedDateTime, targetDay: LocalDate, fromWatch: Boolean, current: String?): String = buildString {
    appendLine("Now: ${CLOCK.format(now)} (America/Toronto).")
    appendLine("Current glucose: ${current ?: "unknown"}.")
    val today = now.toLocalDate()
    appendLine(
        "Entries go on: $targetDay" + when (targetDay) {
            today -> " (today)"
            today.minusDays(1) -> " (yesterday)"
            else -> ""
        } + ".",
    )
    appendLine(if (fromWatch) "Said into her watch just now." else "Typed in the phone app.")
    appendLine()
    append("Francine: ").append(message.trim())
}

object Assistant {
    private const val TAG = "Assistant"
    private const val URL = "https://api.openai.com/v1/responses"
    private const val MAX_ROUNDS = 6
    private val JSON = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun configured(context: Context): Boolean = Store.aiConfig(context) != null

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun roomData(context: Context) = object : AssistantData {
        private val dao = GlucoseDb.get(context).dao()
        override suspend fun readings(startMs: Long, endMs: Long) = dao.readingsIn(startMs, endMs)
        override suspend fun journal(firstDay: String, lastDay: String) = dao.dayJournalBetween(firstDay, lastDay)
    }

    private suspend fun currentReading(context: Context): String? {
        val r = Store.reading(context) ?: return null
        val ageMin = (System.currentTimeMillis() - r.timestampMs) / 60_000
        return "${mmolText(r.mgdl)} mmol/L ${r.trend} (${ageMin} min ago)"
    }

    /**
     * One exchange: her [message] → answer + proposed rows. Throws
     * [AssistantException] (or times out) so callers can fall back.
     */
    suspend fun ask(
        context: Context,
        message: String,
        targetDay: LocalDate,
        fromWatch: Boolean,
        timeoutMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AssistantResult = withTimeout(timeoutMs) {
        val cfg = Store.aiConfig(context) ?: throw AssistantException("assistant not configured")
        val turn = assistantUserTurn(message, ZonedDateTime.now(zone), targetDay, fromWatch, currentReading(context))
        run(cfg, turn, cfg.effort ?: defaultEffort(message), roomData(context), zone)
    }

    /**
     * The tool loop. Every output item (reasoning included) is replayed:
     * store=false. [transport] is the HTTP call (a fake in tests).
     */
    suspend fun run(
        cfg: AiConfig,
        userTurn: String,
        effort: String,
        data: AssistantData,
        zone: ZoneId,
        transport: suspend (key: String, body: JSONObject) -> JSONObject = ::post,
    ): AssistantResult {
        val t0 = System.currentTimeMillis()
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", userTurn))
        val proposed = mutableListOf<ProposedEntry>()
        var rejected = 0
        var answer: String? = null
        var detail: String? = null
        var inTok = 0; var cachedTok = 0; var outTok = 0
        var lastText: String? = null

        for (round in 1..MAX_ROUNDS) {
            val body = JSONObject()
                .put("model", cfg.model)
                .put("instructions", ASSISTANT_INSTRUCTIONS)
                .put("input", input)
                .put("tools", AssistantTools.definitions)
                .put("reasoning", JSONObject().put("effort", effort))
                .put("store", false)
                .put("max_output_tokens", 8_000)
            val res = transport(cfg.key, body)
            res.optJSONObject("usage")?.let { u ->
                inTok += u.optInt("input_tokens"); outTok += u.optInt("output_tokens")
                cachedTok += u.optJSONObject("input_tokens_details")?.optInt("cached_tokens") ?: 0
            }
            val output = res.optJSONArray("output") ?: JSONArray()
            val calls = mutableListOf<JSONObject>()
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                input.put(item)
                when (item.optString("type")) {
                    "function_call" -> calls += item
                    "message" -> lastText = messageText(item) ?: lastText
                }
            }
            if (calls.isEmpty()) break // spoke without calling reply: use its text
            var replied = false
            for (c in calls) {
                val name = c.optString("name")
                val args = c.optString("arguments", "{}")
                val result = when (name) {
                    AssistantTools.PROPOSE -> {
                        val b = AssistantTools.proposals(args)
                        proposed += b.entries; rejected += b.rejected
                        JSONObject().put("shown_to_her", b.entries.size).put("rejected", b.rejected).toString()
                    }
                    AssistantTools.REPLY -> {
                        val a = runCatching { JSONObject(args) }.getOrNull()
                        answer = a?.optString("answer").orEmpty()
                        detail = a?.takeIf { !it.isNull("detail") }?.optString("detail")?.takeIf { it.isNotBlank() }
                        replied = true
                        "{\"ok\":true}"
                    }
                    else -> AssistantTools.run(name, args, data, zone)
                }
                input.put(JSONObject().put("type", "function_call_output").put("call_id", c.optString("call_id")).put("output", result))
            }
            if (replied) break
            if (round == MAX_ROUNDS) log("stopped after $MAX_ROUNDS rounds")
        }

        val finalAnswer = answer ?: lastText.orEmpty().trim()
        val result = AssistantResult(
            answer = finalAnswer, detail = detail, entries = proposed, rejected = rejected,
            model = cfg.model, inputTokens = inTok, cachedTokens = cachedTok, outputTokens = outTok,
            ms = System.currentTimeMillis() - t0,
        )
        log(
            "model=${cfg.model} effort=$effort ms=${result.ms} tokens in=$inTok (cached $cachedTok) out=$outTok " +
                "cost≈$${"%.5f".format(result.estimatedCostUsd)} rows=${proposed.size} rejected=$rejected",
        )
        return result
    }

    // android.util.Log isn't available in JVM unit tests
    private fun log(msg: String) { runCatching { Log.i(TAG, msg) } }

    private fun messageText(item: JSONObject): String? {
        val content = item.optJSONArray("content") ?: return null
        return (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.optString("type") == "output_text" }
            .joinToString("") { it.optString("text") }
            .takeIf { it.isNotBlank() }
    }

    private suspend fun post(key: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(URL)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw AssistantException("OpenAI ${resp.code}: ${msg ?: text.take(200)}")
            }
            JSONObject(text)
        }
    }
}
