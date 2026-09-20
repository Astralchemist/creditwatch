package dev.creditwatch.app

import dev.creditwatch.domain.MonitoringSample
import dev.creditwatch.engine.MonitoringCalculator
import dev.creditwatch.engine.RunwayResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

data class TrendPoint(val time: Instant, val value: BigDecimal)

data class CardTrend(val points: List<TrendPoint> = emptyList()) {
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
    val runway: CardTrend = CardTrend(),
)

/** Charts use only stored readings from the same account and the latest hour. */
fun buildCardTrends(samples: List<MonitoringSample>): CardTrends {
    val latest = samples.maxByOrNull { it.observedAt } ?: return CardTrends()
    val ordered = samples.asSequence()
        .filter { it.accountId == latest.accountId && it.balance.currency == latest.balance.currency }
        .filter { it.observedAt >= latest.observedAt.minus(Duration.ofHours(1)) && it.observedAt <= latest.observedAt }
        .distinctBy { it.observedAt }
        .sortedBy { it.observedAt }
        .toList()
    val calculator = MonitoringCalculator()
    return CardTrends(
        balance = CardTrend(ordered.filter {
            !it.balanceObservedAt.isAfter(it.observedAt) &&
                Duration.between(it.balanceObservedAt, it.observedAt) <= Duration.ofMinutes(2)
        }.map { TrendPoint(it.observedAt, it.balance.amount) }),
        burn = CardTrend(ordered.filter { it.hasRequiredRates }
            .map { TrendPoint(it.observedAt, it.knownRate.amountPerHour) }),
        runway = CardTrend(ordered.mapIndexedNotNull { index, sample ->
            val result = calculator.calculate(sample, ordered.subList(0, index)).safeRunway
            (result as? RunwayResult.Available)?.let {
                TrendPoint(sample.observedAt, BigDecimal.valueOf(it.duration.seconds))
            }
        }),
    )
}
