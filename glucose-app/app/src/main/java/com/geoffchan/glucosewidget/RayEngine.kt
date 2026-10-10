package com.geoffchan.glucosewidget

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs Ray's chat-only tools ([RayTools]) on the phone: SQL on a read-only
 * connection to the app's database, JavaScript in Android's isolated
 * JavaScriptSandbox (separate process, no network or file access), and
 * report files in files/reports/. Every result is a JSON string for the
 * model; failures come back as {"error"} so Ray can fix his query or code.
 */
object RayEngine {
    private const val ANALYSIS_TIMEOUT_MS = 20_000L
    private const val HEAP_BYTES = 256L * 1024 * 1024

    /** Handles [RayTools] names; null for anything else (the shared tools). */
    suspend fun handle(context: Context, name: String, args: String, zone: ZoneId, onReport: (String) -> Unit): String? {
        if (name != RayTools.QUERY && name != RayTools.ANALYZE && name != RayTools.REPORT) return null
        return try {
            val a = JSONObject(args)
            when (name) {
                RayTools.QUERY -> query(context, a.getString("sql"))
                RayTools.ANALYZE -> analyze(context, a.getString("code"), a.getString("from_day"), a.getString("to_day"), zone)
                else -> saveReport(context, a.getString("title"), a.getString("html")).let { file ->
                    onReport(file)
                    JSONObject().put("saved", file).put("shown_as", RayTools.reportTitleOf(file)).toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    suspend fun query(context: Context, sql: String): String = withContext(Dispatchers.IO) {
        RayTools.checkQuery(sql)?.let { return@withContext JSONObject().put("error", it).toString() }
        val path = context.getDatabasePath("glucose.db").path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql.trim().trimEnd(';'), null).use { c ->
                val rows = mutableListOf<List<Any?>>()
                while (rows.size < RayTools.MAX_QUERY_ROWS && c.moveToNext()) {
                    rows += (0 until c.columnCount).map { i -> cell(c, i) }
                }
                RayTools.queryResult(c.columnNames.toList(), rows, truncated = !c.isAfterLast && c.moveToNext())
            }
        }
    }

    private fun cell(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> Math.round(c.getDouble(i) * 1000) / 1000.0
        Cursor.FIELD_TYPE_BLOB -> "(blob)"
        else -> c.getString(i)
    }

    suspend fun analyze(context: Context, code: String, fromDay: String, toDay: String, zone: ZoneId): String {
        val range = RayTools.analysisRange(fromDay, toDay)
        val dao = GlucoseDb.get(context).dao()
        val start = range.from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = range.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val script = RayTools.analysisScript(
            code, range, dao.readingsIn(start, end), dao.dayJournalBetween(range.from.toString(), range.to.toString()), zone,
        )
        if (!JavaScriptSandbox.isSupported()) return JSONObject().put("error", "the JavaScript sandbox isn't available on this phone").toString()
        val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).await()
        try {
            val params = IsolateStartupParameters()
            if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) params.maxHeapSizeBytes = HEAP_BYTES
            if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT) && script.length > 900_000) {
                return JSONObject().put("error", "too much data for this phone's sandbox; use a shorter range").toString()
            }
            val isolate = sandbox.createIsolate(params)
            try {
                val raw = withTimeout(ANALYSIS_TIMEOUT_MS) { isolate.evaluateJavaScriptAsync(script).await() }
                return cut(raw, RayTools.MAX_RESULT_CHARS)
            } finally {
                isolate.close()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return JSONObject().put("error", "stopped after ${ANALYSIS_TIMEOUT_MS / 1000} s; simplify the code").toString()
        } finally {
            sandbox.close()
        }
    }

    fun saveReport(context: Context, title: String, html: String): String {
        val name = RayTools.reportFileName(title, LocalDateTime.now())
        File(reportsDir(context), name).writeText(RayTools.reportHtml(title, html))
        return name
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try { cont.resume(get()) } catch (e: java.util.concurrent.ExecutionException) { cont.resumeWithException(e.cause ?: e) } catch (e: Exception) { cont.resumeWithException(e) }
        }, Runnable::run)
        cont.invokeOnCancellation { cancel(true) }
    }
}
