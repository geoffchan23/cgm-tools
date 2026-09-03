package com.geoffchan.glucosewidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil

/** Gridline spacing for a visible span, minutes. Coarser as you zoom out. */
fun tickStepMinutes(visibleMinutes: Float): Int = when {
    visibleMinutes <= 120f -> 15
    visibleMinutes <= 360f -> 30
    visibleMinutes <= 600f -> 60
    visibleMinutes <= 1200f -> 180
    visibleMinutes <= 2880f -> 360
    else -> 1440
}

/**
 * Glucose over [days] consecutive days starting at [firstDay]. Dots, not a
 * line — a line would interpolate across collection gaps and invent data.
 * Color is status (low red / high amber / in-range white) with the shaded
 * target band as the redundant, non-color encoding. Single series: no legend.
 *
 * Pinch zooms the time axis (glucose axis stays fixed), drag pans, both
 * clamped to the range; double-tap resets. Ticks adapt to the visible span.
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
    doses: List<Pair<Float, DoseNote>> = emptyList(), // minute-of-range to dose
    events: List<Pair<Float, String>> = emptyList(), // minute-of-range to event name
) {
    val density = LocalDensity.current
    val (startMs, endMs) = rangeBoundsMs(firstDay, days, zone)
    val totalMinutes = (endMs - startMs) / 60_000f

    var zoomX by remember(firstDay, days) { mutableFloatStateOf(1f) }
    var viewStartMin by remember(firstDay, days) { mutableFloatStateOf(0f) }
    val visibleMinutes = totalMinutes / zoomX

    val padLeftPx = with(density) { 30.dp.toPx() }

    Canvas(
        modifier
            .pointerInput(firstDay, days) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val plotW = (size.width - padLeftPx).coerceAtLeast(1f)
                    val frac = ((centroid.x - padLeftPx) / plotW).coerceIn(0f, 1f)
                    val anchorMin = viewStartMin + frac * (totalMinutes / zoomX)
                    zoomX = (zoomX * gestureZoom).coerceIn(1f, 96f)
                    val newVisible = totalMinutes / zoomX
                    viewStartMin = (anchorMin - frac * newVisible - pan.x / plotW * newVisible)
                        .coerceIn(0f, totalMinutes - newVisible)
                }
            }
            .pointerInput(firstDay, days) {
                detectTapGestures(onDoubleTap = { zoomX = 1f; viewStartMin = 0f })
            },
    ) {
        val labelPx = with(density) { 10.sp.toPx() }
        val padBottom = with(density) { 18.dp.toPx() }
        val padTop = with(density) { 6.dp.toPx() }
        val plot = Rect(padLeftPx, padTop, size.width, size.height - padBottom)
        val viewEndMin = viewStartMin + visibleMinutes

        val maxData = readings.maxOfOrNull { mmolValue(it.mgdl) } ?: 0.0
        val yMax = maxOf(14.0, ceil(maxData) + 1)
        val yMin = 2.0
        fun yOf(mmol: Double) =
            plot.bottom - ((mmol - yMin) / (yMax - yMin)).toFloat() * plot.height
        fun xOf(minute: Float) =
            plot.left + ((minute - viewStartMin) / visibleMinutes) * plot.width

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

        // x gridlines + labels, adaptive to zoom
        val step = tickStepMinutes(visibleMinutes)
        if (step >= 1440) {
            // day ticks on exact local midnights (DST-correct)
            for (d in 0 until days) {
                val dayStart = rangeBoundsMs(firstDay.plusDays(d.toLong()), 1, zone).first
                val m = (dayStart - startMs) / 60_000f
                if (m < viewStartMin - 1 || m > viewEndMin + 1) continue
                val x = xOf(m)
                drawLine(gridInk, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
                val label = firstDay.plusDays(d.toLong()).dayOfWeek
                    .getDisplayName(TextStyle.NARROW, Locale.CANADA)
                val dayWidthPx = plot.width * (1440f / visibleMinutes)
                drawIntoCanvas {
                    it.nativeCanvas.drawText(label, x + dayWidthPx / 2 - labelPx / 2, plot.bottom + labelPx + 4f, textPaint)
                }
            }
        } else {
            var m = (ceil(viewStartMin / step) * step)
            while (m <= viewEndMin) {
                val x = xOf(m)
                drawLine(gridInk, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
                val minuteOfDay = m.toInt() % 1440
                val isMidnight = minuteOfDay == 0
                val label = if (isMidnight && days > 1) {
                    firstDay.plusDays((m.toInt() / 1440).toLong()).dayOfWeek
                        .getDisplayName(TextStyle.SHORT, Locale.CANADA)
                } else {
                    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
                }
                drawIntoCanvas {
                    it.nativeCanvas.drawText(label, x - labelPx * 1.2f, plot.bottom + labelPx + 4f, textPaint)
                }
                m += step
            }
        }

        // the data — one dot per reading; a touch larger when zoomed in
        val baseR = when {
            visibleMinutes <= 720f -> 3.dp
            visibleMinutes <= 2000f -> 2.dp
            else -> 1.2.dp
        }
        val r = with(density) { baseR.toPx() }
        clipRect(plot.left, plot.top, plot.right, plot.bottom) {
            for (reading in readings) {
                val minute = (reading.timestampMs - startMs) / 60_000f
                if (minute < viewStartMin - 5 || minute > viewEndMin + 5) continue
                val mmol = mmolValue(reading.mgdl)
                val color = when {
                    mmol < lowMmol -> Color(0xFFFF5252)
                    mmol > highMmol -> Color(0xFFFFB300)
                    else -> Color.White
                }
                drawCircle(color, r, Offset(xOf(minute), yOf(mmol)))
            }

            // dose markers: triangles along the bottom edge — filled for
            // short-acting, outlined for long-acting, units labeled beside.
            // Purple, deliberately outside the red/amber/white status set.
            val dosePurple = Color(0xFFD0BCFF)
            val triH = with(density) { 9.dp.toPx() }
            val dosePaint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(0xFF, 0xD0, 0xBC, 0xFF)
                textSize = with(density) { 13.sp.toPx() }
                isFakeBoldText = true
                isAntiAlias = true
            }
            // markers that would overlap horizontally stack upward instead
            var prevX = Float.NEGATIVE_INFINITY
            var level = 0
            for ((minute, dose) in doses.sortedBy { it.first }) {
                if (minute < viewStartMin - 5 || minute > viewEndMin + 5) continue
                val x = xOf(minute)
                level = if (x - prevX < triH * 2.5f) level + 1 else 0
                prevX = x
                val baseY = plot.bottom - 2f - level * (triH + dosePaint.textSize * 0.4f)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, baseY - triH)
                    lineTo(x - triH * 0.6f, baseY)
                    lineTo(x + triH * 0.6f, baseY)
                    close()
                }
                if (dose.isShort) {
                    drawPath(path, dosePurple)
                } else {
                    drawPath(path, dosePurple, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                }
                if (visibleMinutes <= 2880f) {
                    drawIntoCanvas {
                        it.nativeCanvas.drawText("${dose.units}u", x + triH * 0.8f, baseY - 2f, dosePaint)
                    }
                }
            }

            // event markers: teal diamonds in a band just above the dose
            // triangles, so food/activity reads next to the insulin row
            val eventTeal = Color(0xFF80DEEA)
            val diaR = with(density) { 5.dp.toPx() }
            val eventPaint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(0xFF, 0x80, 0xDE, 0xEA)
                textSize = with(density) { 12.sp.toPx() }
                isAntiAlias = true
            }
            var evPrevX = Float.NEGATIVE_INFINITY
            var evLevel = 0
            for ((minute, name) in events.sortedBy { it.first }) {
                if (minute < viewStartMin - 5 || minute > viewEndMin + 5) continue
                val x = xOf(minute)
                evLevel = if (x - evPrevX < diaR * 12f && visibleMinutes <= 2880f) evLevel + 1 else 0
                evPrevX = x
                val eventBase = plot.bottom - with(density) { 30.dp.toPx() }
                val cy = eventBase - evLevel * (diaR * 2 + eventPaint.textSize)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, cy - diaR); lineTo(x + diaR, cy); lineTo(x, cy + diaR); lineTo(x - diaR, cy); close()
                }
                drawPath(path, eventTeal)
                if (visibleMinutes <= 2880f) {
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(name, x + diaR + 3f, cy + eventPaint.textSize / 3, eventPaint)
                    }
                }
            }

            // "now" marker when the visible window includes the present,
            // with the current reading readable right at the line
            val now = System.currentTimeMillis()
            if (now in startMs until endMs) {
                val nowMin = (now - startMs) / 60_000f
                val x = xOf(nowMin)
                drawLine(mutedInk, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 2f)

                val latest = readings.maxByOrNull { it.timestampMs }
                if (latest != null && nowMin >= viewStartMin && nowMin <= viewEndMin) {
                    val mmol = mmolValue(latest.mgdl)
                    val stale = now - latest.timestampMs > STALE_AFTER_MS
                    val color = when {
                        stale -> android.graphics.Color.argb(0xFF, 0x9E, 0x9E, 0x9E)
                        mmol < lowMmol -> android.graphics.Color.argb(0xFF, 0xFF, 0x52, 0x52)
                        mmol > highMmol -> android.graphics.Color.argb(0xFF, 0xFF, 0xB3, 0x00)
                        else -> android.graphics.Color.WHITE
                    }
                    // ring the newest dot so the label visibly belongs to it
                    val latestMin = (latest.timestampMs - startMs) / 60_000f
                    drawCircle(
                        Color(color), r + with(density) { 3.dp.toPx() },
                        Offset(xOf(latestMin), yOf(mmol)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                    val bigPaint = android.graphics.Paint().apply {
                        this.color = color
                        textSize = with(density) { 16.sp.toPx() }
                        isFakeBoldText = true
                        isAntiAlias = true
                    }
                    val label = mmolText(latest.mgdl) + " " + trendArrow(latest.trend)
                    val w = bigPaint.measureText(label)
                    val pad = with(density) { 6.dp.toPx() }
                    val tx = if (x + pad + w > plot.right) x - pad - w else x + pad
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(label, tx, plot.top + bigPaint.textSize, bigPaint)
                    }
                }
            }
        }
    }
}
