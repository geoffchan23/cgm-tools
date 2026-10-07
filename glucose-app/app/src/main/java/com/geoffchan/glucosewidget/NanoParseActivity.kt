package com.geoffchan.glucosewidget

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Gemini Nano only runs for the top foreground app (AICore answers
 * BACKGROUND_USE_BLOCKED otherwise, even from a foreground service). So when
 * her watch sends a sentence, [WatchParse] posts a full-screen-intent
 * notification that opens this invisible activity over the lock screen —
 * the screen lights for a few seconds and the phone stays locked — which
 * runs the prompt and hands the raw answer back through [NanoBridge].
 */
class NanoParseActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val id = intent.getStringExtra(EXTRA_ID)
        val prompt = intent.getStringExtra(EXTRA_PROMPT)
        if (id == null || prompt == null || !NanoBridge.isWaiting(id)) { finish(); return }
        scope.launch {
            val model = Generation.getClient()
            val raw = try {
                model.generateContent(
                    generateContentRequest(TextPart(prompt)) { temperature = 0f; topK = 1; maxOutputTokens = 256 },
                ).candidates.firstOrNull()?.text
            } catch (e: Exception) {
                Log.i(TAG, "Nano failed on the lock screen: ${e.message}")
                null
            } finally {
                model.close()
            }
            NanoBridge.complete(id, raw)
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_PROMPT = "prompt"
        private const val TAG = "WatchLog"
    }
}

/** In-process hand-off from [NanoParseActivity] to whoever is waiting for its answer. */
object NanoBridge {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    fun register(id: String): CompletableDeferred<String?> = CompletableDeferred<String?>().also { waiting[id] = it }
    fun isWaiting(id: String): Boolean = waiting.containsKey(id)
    fun complete(id: String, raw: String?) { waiting.remove(id)?.complete(raw) }
    fun cancel(id: String) { waiting.remove(id)?.cancel() }
}
