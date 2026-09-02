package com.geoffchan.glucosewidget

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/**
 * One day of glucose, midnight to midnight. Dots, not a line — a line
 * would interpolate across collection gaps and invent data. Color is
 * status (low red / high amber / in-range white) with the shaded target
 * band as the redundant, non-color encoding. Single series: no legend.
 */
@Composable
fun DayChart(
    readings: List<ReadingEntity>,
    day: LocalDate,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    lowMmol: Double = Store.DEFAULT_LOW,
    highMmol: Double = Store.DEFAULT_HIGH,
) {
    val density = LocalDensity.current
    Canvas(modifier) {
        val (startMs, endMs) = dayBoundsMs(day, zone)
        val minutesInDay = (endMs - startMs) / 60_000f // DST-correct

        val labelPx = with(density) { 10.sp.toPx() }
        val padLeft = with(density) { 30.dp.toPx() }
        val padBottom = with(density) { 18.dp.toPx() }
        val padTop = with(density) { 6.dp.toPx() }
        val plot = Rect(padLeft, padTop, size.width, size.height - padBottom)

        val maxData = readings.maxOfOrNull { mmolValue(it.mgdl) } ?: 0.0
        val yMax = maxOf(14.0, ceil(maxData) + 1)
        val yMin = 2.0
        fun yOf(mmol: Double) =
            plot.bottom - ((mmol - yMin) / (yMax - yMin)).toFloat() * plot.height
        fun xOf(minute: Float) = plot.left + (minute / minutesInDay) * plot.width

        val gridInk = Color(0x22FFFFFF)
        val mutedInk = Color(0x99FFFFFF)

        // target band — the calm zone, barely-there fill
        drawRect(
            color = Color(0x14FFFFFF),
            topLeft = Offset(plot.left, yOf(highMmol)),
            size = androidx.compose.ui.geometry.Size(plot.width, yOf(lowMmol) - yOf(highMmol)),
        )

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(0x99, 0xFF, 0xFF, 0xFF)
            textSize = labelPx
            isAntiAlias = true
        }

        // horizontal gridlines + y labels at whole-number marks
        val yTicks = buildList {
            var v = 5.0
            while (v < yMax) { add(v); v += 5.0 }
        } + listOf(lowMmol, highMmol)
        for (tick in yTicks.distinct()) {
            val y = yOf(tick)
            drawLine(gridInk, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
            drawIntoCanvas {
                it.nativeCanvas.drawText(
                    if (tick == tick.toLong().toDouble()) tick.toLong().toString() else tick.toString(),
                    2f, y + labelPx / 3, textPaint,
                )
            }
        }

        // x labels every 6 h
        for (h in 0..24 step 6) {
            val x = xOf(h * 60f).coerceAtMost(plot.right)
            drawLine(gridInk, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
            drawIntoCanvas {
                it.nativeCanvas.drawText("%02d".format(h % 24), x - labelPx, plot.bottom + labelPx + 4f, textPaint)
            }
        }

        // the data — one dot per reading
        val r = with(density) { 2.dp.toPx() }
        for (reading in readings) {
            val mmol = mmolValue(reading.mgdl)
            val color = when {
                mmol < lowMmol -> Color(0xFFFF5252)
                mmol > highMmol -> Color(0xFFFFB300)
                else -> Color.White
            }
            drawCircle(
                color = color,
                radius = r,
                center = Offset(xOf(minuteOfDay(reading.timestampMs, day, zone)), yOf(mmol)),
            )
        }

        // "now" marker on today
        val now = System.currentTimeMillis()
        if (now in startMs until endMs) {
            val x = xOf((now - startMs) / 60_000f)
            drawLine(mutedInk, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 2f)
        }
    }
}
