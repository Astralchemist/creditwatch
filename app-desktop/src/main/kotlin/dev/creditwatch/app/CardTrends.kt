package dev.creditwatch.app

import dev.creditwatch.domain.MonitoringSample
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

data class TrendPoint(val time: Instant, val value: BigDecimal)

data class CardTrend(
    val points: List<TrendPoint> = emptyList(),
    val interpolation: Interpolation = Interpolation.LINEAR,
) {
    val changePercent: BigDecimal? get() {
        val first = points.firstOrNull()?.value ?: return null
        val last = points.lastOrNull()?.value ?: return null
        if (points.size < 2 || first.signum() == 0) return null
        return last.subtract(first).multiply(BigDecimal(100)).divide(first.abs(), 1, RoundingMode.HALF_UP)
    }

    val minutes: Long get() = if (points.size < 2) 0 else
        Duration.between(points.first().time, points.last().time).toMinutes().coerceAtLeast(1)
}

data class CardTrends(
    val balance: CardTrend = CardTrend(),
    val burn: CardTrend = CardTrend(),
)

/** Long enough to show the shape of a runway measured in hours, not just the last few readings. */
val TREND_WINDOW: Duration = Duration.ofHours(6)

/** Charts use only stored readings from the same account, within [TREND_WINDOW]. */
fun buildCardTrends(samples: List<MonitoringSample>): CardTrends {
    val latest = samples.maxByOrNull { it.observedAt } ?: return CardTrends()
    val ordered = samples.asSequence()
        .filter { it.accountId == latest.accountId && it.balance.currency == latest.balance.currency }
        .filter { it.observedAt >= latest.observedAt.minus(TREND_WINDOW) && it.observedAt <= latest.observedAt }
        .distinctBy { it.observedAt }
        .sortedBy { it.observedAt }
        .toList()
    return CardTrends(
        balance = CardTrend(ordered.filter {
            !it.balanceObservedAt.isAfter(it.observedAt) &&
                Duration.between(it.balanceObservedAt, it.observedAt) <= Duration.ofMinutes(2)
        }.map { TrendPoint(it.observedAt, it.balance.amount) }),
        burn = CardTrend(ordered.filter { it.hasRequiredRates }
            .map { TrendPoint(it.observedAt, it.knownRate.amountPerHour) },
            interpolation = Interpolation.STEP),
    )
}
