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
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil

/**
 * Glucose over [days] consecutive days starting at [firstDay]. Dots, not a
 * line — a line would interpolate across collection gaps and invent data.
 * Color is status (low red / high amber / in-range white) with the shaded
 * target band as the redundant, non-color encoding. Single series: no legend.
 *
 * days == 1: hour labels every 6 h. days > 1: a gridline per midnight,
 * weekday initial under each day.
 */
@Composable
fun RangeChart(
    readings: List<ReadingEntity>,
    firstDay: LocalDate,
    days: Int,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    lowMmol: Double = Store.DEFAULT_LOW,
    highMmol: Double = Store.DEFAULT_HIGH,
) {
    val density = LocalDensity.current
    Canvas(modifier) {
        val (startMs, endMs) = rangeBoundsMs(firstDay, days, zone)
        val minutesInRange = (endMs - startMs) / 60_000f

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
        fun xOf(minute: Float) = plot.left + (minute / minutesInRange) * plot.width

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

        // horizontal gridlines + y labels
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

        // x gridlines + labels
        if (days == 1) {
            for (h in 0..24 step 6) {
                val x = xOf(h * 60f).coerceAtMost(plot.right)
                drawLine(gridInk, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
                drawIntoCanvas {
                    it.nativeCanvas.drawText("%02d".format(h % 24), x - labelPx, plot.bottom + labelPx + 4f, textPaint)
                }
            }
        } else {
            for (d in 0 until days) {
                val dayStart = rangeBoundsMs(firstDay.plusDays(d.toLong()), 1, zone).first
                val x = xOf((dayStart - startMs) / 60_000f)
                drawLine(gridInk, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
                val label = firstDay.plusDays(d.toLong()).dayOfWeek
                    .getDisplayName(TextStyle.NARROW, Locale.CANADA)
                val dayWidth = plot.width / days
                drawIntoCanvas {
                    it.nativeCanvas.drawText(label, x + dayWidth / 2 - labelPx / 2, plot.bottom + labelPx + 4f, textPaint)
                }
            }
            drawLine(gridInk, Offset(plot.right, plot.top), Offset(plot.right, plot.bottom), strokeWidth = 1f)
        }

        // the data — one dot per reading (smaller when the span is wide)
        val r = with(density) { if (days == 1) 2.dp.toPx() else 1.2.dp.toPx() }
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
                center = Offset(xOf((reading.timestampMs - startMs) / 60_000f), yOf(mmol)),
            )
        }

        // "now" marker when the range includes the present
        val now = System.currentTimeMillis()
        if (now in startMs until endMs) {
            val x = xOf((now - startMs) / 60_000f)
            drawLine(mutedInk, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 2f)
        }
    }
}
