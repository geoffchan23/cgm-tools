package com.geoffchan.glucosewidget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Refresh.enqueue(context)
        Refresh.scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Refresh.cancel(context)
    }
}


class GlucoseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val reading = Store.reading(context)
        val settings = Store.settings(context)
        val hasCreds = Store.credentials(context) != null
        // Last dose / last food-exercise log: look back a few days so a quiet
        // morning still shows last night's entries.
        val zone = java.time.ZoneId.systemDefault()
        val recent = runCatching {
            GlucoseDb.get(context).dao().dayJournalSince(java.time.LocalDate.now(zone).minusDays(3).toString())
        }.getOrDefault(emptyList())
        val lastDose = latestDose(recent, zone)
        val lastLog = latestEvent(recent, zone)
        provideContent {
            GlanceTheme {
                Content(reading, settings, hasCreds, lastDose, lastLog)
            }
        }
    }

    @Composable
    private fun Content(
        reading: Reading?,
        settings: Settings,
        hasCreds: Boolean,
        lastDose: LastLog?,
        lastLog: LastLog?,
    ) {
        val now = System.currentTimeMillis()
        val bg = Color(0xE6000000)
        val dim = Color(0xFF757575)
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bg)
                .cornerRadius(24.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            // Row 1: big reading + arrow, with its age on the right.
            when {
                !hasCreds -> Text("Set up", style = TextStyle(color = ColorProvider(Color.Gray), fontSize = 16.sp))
                reading == null -> Text("…", style = TextStyle(color = ColorProvider(Color.Gray), fontSize = 24.sp))
                else -> {
                    val state = displayState(reading, now, settings.lowMmol, settings.highMmol)
                    val color = when (state) {
                        GlucoseState.LOW -> Color(0xFFFF5252)
                        GlucoseState.HIGH -> Color(0xFFFFB300)
                        GlucoseState.IN_RANGE -> Color.White
                        GlucoseState.STALE -> Color(0xFF9E9E9E)
                    }
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            mmolText(reading.mgdl),
                            style = TextStyle(color = ColorProvider(color), fontSize = 40.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                        Text(
                            " " + trendArrow(reading.trend),
                            style = TextStyle(color = ColorProvider(color), fontSize = 26.sp),
                            maxLines = 1,
                        )
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            ageText(reading.timestampMs, now),
                            style = TextStyle(color = ColorProvider(if (state == GlucoseState.STALE) color else dim), fontSize = 13.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
            // Rows 2 and 3: last dose, last food/exercise log — label left, age right.
            for (item in listOf(lastDose, lastLog)) {
                if (item == null) continue
                Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label,
                        style = TextStyle(color = ColorProvider(Color(0xFFDDDDDD)), fontSize = 14.sp),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text(
                        relativeAge(item.atMs, now),
                        style = TextStyle(color = ColorProvider(dim), fontSize = 13.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
