package com.geoffchan.glucosewidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/** Landscape fullscreen chart; opened by the expand button on the day view. */
class ChartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val zone = ZoneId.systemDefault()
        val firstDay = LocalDate.ofEpochDay(intent.getLongExtra("epochDay", LocalDate.now(zone).toEpochDay()))
        val days = intent.getIntExtra("days", 1)
        val dao = GlucoseDb.get(this).dao()
        val (startMs, endMs) = rangeBoundsMs(firstDay, days, zone)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val readings by dao.readingsBetween(startMs, endMs)
                    .collectAsState(initial = emptyList())
                val dayEntries by dao.dayJournalInRange(
                    firstDay.toString(), firstDay.plusDays(days - 1L).toString(),
                ).collectAsState(initial = emptyList())
                val settings = remember { runBlocking { Store.settings(this@ChartActivity) } }
                val (doseMarks, eventMarks) = markerData(dayEntries, firstDay)

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    RangeChart(
                        readings = readings,
                        firstDay = firstDay,
                        days = days,
                        zone = zone,
                        modifier = Modifier.fillMaxSize().padding(
                            start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp,
                        ),
                        lowMmol = settings.lowMmol,
                        highMmol = settings.highMmol,
                        doses = doseMarks,
                        events = eventMarks,
                    )
                    IconButton(
                        onClick = { finish() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close fullscreen",
                            tint = Color.White, // outside a Surface the default tint is black
                        )
                    }
                }
            }
        }
    }
}
