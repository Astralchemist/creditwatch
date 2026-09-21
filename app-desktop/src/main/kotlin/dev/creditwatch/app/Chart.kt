package dev.creditwatch.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path as GraphicsPath
import androidx.compose.ui.unit.dp
import androidx.compose.material.Text
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant

/**
 * How a reading relates to the one before it.
 *
 * A balance falls continuously, so [LINEAR] is honest. A rate does not: it holds until an
 * instance starts or stops and then jumps, so drawing a diagonal between two readings shows a
 * gradual change that never happened. Telemetry will want both — utilisation is continuous,
 * a power cap or an instance count is stepped.
 */
enum class Interpolation { LINEAR, STEP }

/**
 * One sparkline, filled to the baseline. Kept free of any particular metric so the telemetry
 * series can render through the same path: pass [bounds] for a series whose scale is fixed and
 * meaningful, such as a percentage that should sit against 0..100 rather than against its own
 * min and max.
 *
 * The fill covers measured readings only. [projection] stays an unfilled dashed line from the
 * last real reading, so the eye can tell a reading from an estimate at a glance — a balance
 * falling to zero at the current burn is a forecast, not a measurement.
 */
@Composable
fun Sparkline(
    trend: CardTrend,
    color: Color,
    modifier: Modifier = Modifier,
    projection: TrendPoint? = null,
    bounds: ClosedFloatingPointRange<Float>? = null,
    emptyLabel: String = "—",
    emptyColor: Color = color,
) {
    val points = trend.points.mapNotNull { point ->
        point.value.toFloat().takeIf(Float::isFinite)?.let { point.time to it }
    }
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.CenterStart) {
            Text(emptyLabel, color = emptyColor, fontSize = 12.sp)
        }
        return
    }
    val forecast = projection?.value?.toFloat()?.takeIf(Float::isFinite)?.let { projection.time to it }

    Canvas(modifier) {
        val startMs = points.first().first.toEpochMilli()
        val endMs = maxOf(points.last().first.toEpochMilli(), forecast?.first?.toEpochMilli() ?: Long.MIN_VALUE)
        val span = (endMs - startMs).coerceAtLeast(1).toFloat()

        val values = points.map { it.second } + listOfNotNull(forecast?.second)
        val low = bounds?.start ?: values.min()
        val high = bounds?.endInclusive ?: values.max()
        val inset = 3.dp.toPx()
        val usable = (size.height - inset * 2).coerceAtLeast(1f)
        // A series that never moves has no spread to scale against. Pinning it to the floor
        // leaves a filled area of nothing; holding it mid-height draws a band that reads as
        // "steady" instead of as "empty".
        val flat = high - low <= 0f
        val spread = if (flat) 1f else high - low

        fun x(time: Instant) = (time.toEpochMilli() - startMs) / span * size.width
        fun y(value: Float) =
            if (flat) size.height / 2f else size.height - inset - ((value - low) / spread * usable)

        // A gap in the readings is missing data, not a flat line through it, so each run of
        // adjacent readings is its own shape. Filling one path across a gap would invent area.
        val segments = mutableListOf<MutableList<Pair<Instant, Float>>>()
        points.forEach { current ->
            val previous = segments.lastOrNull()?.last()
            if (previous == null || Duration.between(previous.first, current.first).seconds > 180) {
                segments += mutableListOf(current)
            } else {
                segments.last() += current
            }
        }

        val fill = Brush.verticalGradient(
            listOf(color.copy(alpha = .30f), color.copy(alpha = .03f)),
            startY = 0f, endY = size.height,
        )
        segments.forEach { segment ->
            val line = GraphicsPath()
            segment.forEachIndexed { index, current ->
                val previous = segment.getOrNull(index - 1)
                when {
                    previous == null -> line.moveTo(x(current.first), y(current.second))
                    trend.interpolation == Interpolation.STEP -> {
                        // Hold the old rate across the interval, then jump: the change was an event.
                        line.lineTo(x(current.first), y(previous.second))
                        line.lineTo(x(current.first), y(current.second))
                    }
                    else -> line.lineTo(x(current.first), y(current.second))
                }
            }
            if (segment.size > 1) {
                val area = GraphicsPath().apply {
                    addPath(line)
                    lineTo(x(segment.last().first), size.height)
                    lineTo(x(segment.first().first), size.height)
                    close()
                }
                drawPath(area, fill, style = Fill)
            }
            drawPath(line, color, style = Stroke(width = 1.5.dp.toPx()))
        }

        val last = points.last()
        if (forecast != null && forecast.first.isAfter(last.first)) {
            val dashed = GraphicsPath().apply {
                moveTo(x(last.first), y(last.second))
                lineTo(x(forecast.first), y(forecast.second))
            }
            drawPath(dashed, color.copy(alpha = .55f), style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            ))
        }
        drawCircle(color, radius = 2.dp.toPx(), center =
            androidx.compose.ui.geometry.Offset(x(last.first), y(last.second)))
    }
}
